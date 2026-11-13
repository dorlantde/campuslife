package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.file.PdfSamples;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.LocalFileStorage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="campuslife.files.cleanup-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileUploadMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-23T01:00:00Z");
    private final HttpClient http=HttpClient.newHttpClient();private final List<Long> owners=new ArrayList<>();private final List<Path> extras=new ArrayList<>();
    private String hash;private byte[] pdf;
    @Autowired AccountMapper accounts;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired ObjectMapper json;
    @Autowired FileCleanup cleanup;@Autowired FileTransactions transactions;@Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean FileAssetMapper files;@MockitoSpyBean LocalFileStorage storage;@MockitoSpyBean PdfInspector inspector;@MockitoSpyBean Clock clock;
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("File_Test123!");pdf=PdfSamples.pdf(2);}
    @BeforeEach void freeze(){doReturn(NOW).when(clock).instant();}
    @AfterEach void cleanupOwned()throws Exception {
        reset(files,storage,inspector,clock);
        for(long id:owners){for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=?",id)){storage.delete(storage.storageId(),row.get("storage_key").toString());}jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id);}owners.clear();
        for(Path path:extras){Files.deleteIfExists(path);}extras.clear();
        try(var paths=Files.newDirectoryStream(storage.root().resolve("temporary"))){for(Path path:paths){if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){Files.delete(path);}}}
    }
    @ParameterizedTest @ValueSource(strings={"application/pdf","application/octet-stream","MISSING"})
    void uploadAndOwnMetadataReflectActualBytesAndPages(String mime)throws Exception {
        var user=user("STUDENT");var token=login(user);var response=upload(token,"C:\\fakepath\\校园练习.PDF",mime.equals("MISSING")?null:mime,pdf);var value=data(response);assertThat(response.statusCode()).isEqualTo(201);
        assertThat(value.propertyNames()).containsExactlyInAnyOrder("id","originalName","byteSize","mediaType","pageCount","status","expiresAt");assertThat(value.path("id").isString()).isTrue();assertThat(value.path("originalName").asString()).isEqualTo("校园练习.PDF");assertThat(value.path("byteSize").asLong()).isEqualTo(pdf.length);assertThat(value.path("pageCount").asInt()).isEqualTo(2);assertThat(value.path("status").asString()).isEqualTo("AVAILABLE");assertThat(Instant.parse(value.path("expiresAt").asString())).isEqualTo(NOW.plusSeconds(86400));assertThat(data(get(value.path("id").asString(),token))).isEqualTo(value);
        var row=row(value);assertThat(Files.readAllBytes(object(row))).isEqualTo(pdf);assertThat(row.getSha256()).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf)));assertThat(row.getOwnerId()).isEqualTo(user.id());assertThat(row.getVersion()).isEqualTo(1);
        var again=data(upload(token,"same.pdf","application/pdf",pdf));assertThat(again.path("id").asString()).isNotEqualTo(value.path("id").asString());assertThat(row(again).getSha256()).isEqualTo(row.getSha256());
    }
    @ParameterizedTest @CsvSource({"TEXT,PDF_INVALID","MAGIC,PDF_INVALID","ENCRYPTED,PDF_ENCRYPTED","EMPTY_PASSWORD,PDF_ENCRYPTED","ZERO,PDF_INVALID","TOO_MANY,PDF_PAGE_LIMIT_EXCEEDED"})
    void invalidPdfDoesNotCreateMetadataOrObjects(String kind,String code)throws Exception {
        byte[] input=switch(kind){case "TEXT"->"not pdf".getBytes(StandardCharsets.UTF_8);case "MAGIC"->"%PDF-1.7\ninvalid".getBytes(StandardCharsets.UTF_8);case "ENCRYPTED"->PdfSamples.encrypted(1,"secret");case "EMPTY_PASSWORD"->PdfSamples.encrypted(1,"");case "ZERO"->PdfSamples.pdf(0);default->PdfSamples.pdf(501);};
        expect(upload(login(user("STUDENT")),"test.pdf","application/pdf",input),422,code);assertThat(rows()).isZero();assertNoOwnedObjects();
    }
    @ParameterizedTest @ValueSource(ints={1,500})
    void acceptedPageBoundaries(int pages)throws Exception{var view=data(upload(login(user("STUDENT")),"test.pdf","application/pdf",PdfSamples.pdf(pages)));assertThat(view.path("pageCount").asInt()).isEqualTo(pages);}
    @ParameterizedTest @ValueSource(ints={20971520,20971521})
    void exactTwentyMiBBoundaryUsesRealHttpBody(int size)throws Exception {
        var result=upload(login(user("STUDENT")),"large.pdf","application/pdf",PdfSamples.sized(size));if(size==20971520){assertThat(data(result).path("byteSize").asInt()).isEqualTo(size);}else{expect(result,413,"FILE_TOO_LARGE");assertThat(rows()).isZero();}
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","EMPTY","DUPLICATE","EXTRA_FIELD","EXTRA_BINARY","WRONG_FIELD","REPEATED_TEXT","JSON"})
    void uploadFormRejectsUnknownOrDuplicateParts(String kind)throws Exception {
        var token=login(user("STUDENT"));var part=new Part("file","test.pdf","application/pdf",pdf);HttpResponse<String> response;
        response=switch(kind){case "MISSING"->multipart(token,List.of());case "EMPTY"->multipart(token,List.of(new Part("file","test.pdf","application/pdf",new byte[0])));case "DUPLICATE"->multipart(token,List.of(part,part));case "EXTRA_FIELD"->multipart(token,List.of(part,new Part("ownerId",null,null,"999".getBytes())));case "EXTRA_BINARY"->multipart(token,List.of(part,new Part("unknown","test.pdf","application/pdf",pdf)));case "WRONG_FIELD"->multipart(token,List.of(new Part("document","test.pdf","application/pdf",pdf)));case "REPEATED_TEXT"->multipart(token,List.of(part,new Part("file",null,null,"fake".getBytes())));default->call("POST","/api/v1/files","{}",token);};
        expect(response,kind.equals("JSON")?415:400,kind.equals("JSON")?"UNSUPPORTED_MEDIA_TYPE":"VALIDATION_FAILED");assertThat(rows()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"MERCHANT","ADMIN","ANONYMOUS","INVALID","DISABLED","REVOKED","EXPIRED"})
    void uploadAndMetadataRequireActiveStudentSession(String kind)throws Exception {
        String token=null;int status=403;String code="ROLE_FORBIDDEN";
        if(kind.equals("ANONYMOUS")){status=401;code="AUTH_REQUIRED";}else if(kind.equals("INVALID")){token="invalid";status=401;code="TOKEN_INVALID";}
        else{var user=user(Set.of("MERCHANT","ADMIN").contains(kind)?kind:"STUDENT");token=login(user);if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",user.id());code="ACCOUNT_DISABLED";}if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",FileRules.utc(NOW),user.id());status=401;code="SESSION_REVOKED";}if(kind.equals("EXPIRED")){fixed(NOW.plusSeconds(7200));status=401;code="SESSION_EXPIRED";}}
        clearInvocations(files,storage,inspector);expect(upload(token,"test.pdf","application/pdf",pdf),status,code);expect(get("1",token),status,code);verifyNoInteractions(files,storage,inspector);
    }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","%2B1","1.0","%201","9223372036854775808"})
    void metadataIdValidation(String id)throws Exception{expect(get(id,login(user("STUDENT"))),400,"VALIDATION_FAILED");}
    @Test void ownershipIsCheckedBeforeExpiryOrDeletionAndGetDoesNotTouchDisk()throws Exception {
        var a=user("STUDENT");var token=login(a);var view=data(upload(token,"test.pdf","application/pdf",pdf));var id=view.path("id").asString();var row=row(view);var b=login(user("STUDENT"));expect(get(id,b),404,"RESOURCE_NOT_FOUND");expect(get("9223372036854775807",token),404,"RESOURCE_NOT_FOUND");
        var before=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id);clearInvocations(storage,inspector);assertThat(data(get(id,token))).isEqualTo(view);assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id)).isEqualTo(before);verifyNoInteractions(storage,inspector);
        fixed(NOW.plusSeconds(86400));token=login(a);b=login(user("STUDENT"));expect(get(id,b),404,"RESOURCE_NOT_FOUND");expect(get(id,token),410,"FILE_EXPIRED");assertThat(object(row)).exists();cleanup.runOnce();assertThat(object(row)).doesNotExist();expect(get(id,token),410,"FILE_EXPIRED");expect(get(id,b),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"DISABLED","REVOKED","EXPIRED"})
    void identityChangedDuringParsingCannotReserveAvailableFile(String kind)throws Exception {
        var a=user("STUDENT");var token=login(a);var real=new PdfInspector();doAnswer(i->{int pages=real.pages(i.getArgument(0));invalidate(a,kind);return pages;}).when(inspector).pages(any());
        expect(upload(token,"test.pdf","application/pdf",pdf),kind.equals("DISABLED")?403:401,kind.equals("DISABLED")?"ACCOUNT_DISABLED":kind.equals("REVOKED")?"SESSION_REVOKED":"SESSION_EXPIRED");assertThat(rows()).isZero();assertNoOwnedObjects();
    }
    @ParameterizedTest @ValueSource(strings={"DISABLED","REVOKED","EXPIRED"})
    void identityChangedAfterMoveRollsBackPublicationAndCompensates(String kind)throws Exception {
        var a=user("STUDENT");var token=login(a);doAnswer(i->{i.callRealMethod();invalidate(a,kind);return null;}).when(storage).move(any(),anyString());
        expect(upload(token,"test.pdf","application/pdf",pdf),kind.equals("DISABLED")?403:401,kind.equals("DISABLED")?"ACCOUNT_DISABLED":kind.equals("REVOKED")?"SESSION_REVOKED":"SESSION_EXPIRED");assertThat(statuses()).containsExactly("DELETED");assertNoOwnedObjects();
    }
    @ParameterizedTest @ValueSource(strings={"RECEIVE","MOVE_BEFORE","MOVE_AFTER","RESERVE","PUBLISH"})
    void injectedFailureDoesNotLeaveHalfPublishedFile(String phase)throws Exception {
        var token=login(user("STUDENT"));var real=sqlSession.getMapper(FileAssetMapper.class);
        if(phase.equals("RECEIVE")){doThrow(new IOException("injected")).when(storage).receive(any());}
        if(phase.equals("MOVE_BEFORE")){doThrow(new IOException("injected")).when(storage).move(any(),anyString());}
        if(phase.equals("MOVE_AFTER")){doAnswer(i->{i.callRealMethod();throw new IOException("injected");}).when(storage).move(any(),anyString());}
        if(phase.equals("RESERVE")){doAnswer(i->{real.insert((FileAssetEntity)i.getArgument(0));throw new TransientDataAccessResourceException("injected");}).when(files).insert(any(FileAssetEntity.class));}
        if(phase.equals("PUBLISH")){doAnswer(i->{real.publish(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));throw new TransientDataAccessResourceException("injected");}).when(files).publish(anyLong(),anyLong(),any(),any());}
        expect(upload(token,"test.pdf","application/pdf",pdf),503,Set.of("RESERVE","PUBLISH").contains(phase)?"SERVICE_TEMPORARILY_UNAVAILABLE":"FILE_STORAGE_UNAVAILABLE");assertThat(countState("AVAILABLE")).isZero();assertNoOwnedObjects();
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","FINALIZE"})
    void deletionFailureIsDurableAndRetryIsIdempotent(String phase)throws Exception {
        var a=user("STUDENT");var view=data(upload(login(a),"test.pdf","application/pdf",pdf));var row=row(view);fixed(NOW.plusSeconds(86400));
        if(phase.equals("DELETE")){doThrow(new IOException("injected")).when(storage).delete(anyString(),anyString());}else{doThrow(new TransientDataAccessResourceException("injected")).when(files).deleted(anyLong(),anyLong(),any());}
        cleanup.runOnce();assertThat(files.selectById(row.getId()).getStatus()).isEqualTo("DELETING");assertThat(Files.exists(object(row))).isEqualTo(phase.equals("DELETE"));reset(storage,files);cleanup.runOnce();assertThat(files.selectById(row.getId()).getStatus()).isEqualTo("DELETED");assertThat(object(row)).doesNotExist();cleanup.runOnce();assertThat(files.selectById(row.getId()).getVersion()).isEqualTo(4);
    }
    @Test void failedUploadCompensationCanBeRecoveredByNextScan()throws Exception {
        var token=login(user("STUDENT"));doAnswer(i->{i.callRealMethod();throw new IOException("injected");}).when(storage).move(any(),anyString());doThrow(new IOException("injected")).when(storage).delete(anyString(),anyString());
        expect(upload(token,"test.pdf","application/pdf",pdf),503,"FILE_STORAGE_UNAVAILABLE");assertThat(statuses()).containsExactly("DELETING");reset(storage);cleanup.runOnce();assertThat(statuses()).containsExactly("DELETED");assertNoOwnedObjects();
    }
    @ParameterizedTest @ValueSource(ints={86399,86400,86401})
    void expiryBoundaryAndInternalUploadingVisibility(int seconds)throws Exception {
        var a=user("STUDENT");var view=data(upload(login(a),"test.pdf","application/pdf",pdf));var id=view.path("id").asString();fixed(NOW.plusSeconds(seconds));var token=login(a);cleanup.runOnce();if(seconds<86400){assertThat(data(get(id,token))).isEqualTo(view);}else{expect(get(id,token),410,"FILE_EXPIRED");assertThat(row(view).getStatus()).isEqualTo("DELETED");}
    }
    @ParameterizedTest @ValueSource(strings={"STAGING_OBJECT","STAGING_EMPTY","DELETING_EMPTY","OTHER_NAMESPACE","PROTECTED_NULL"})
    void recoveryCandidatesRespectStateNamespaceAndProtection(String kind)throws Exception {
        var a=user("STUDENT");var token=login(a);var view=data(upload(token,"test.pdf","application/pdf",pdf));var row=row(view);var past=FileRules.utc(NOW.minusSeconds(1));
        if(kind.startsWith("STAGING")){jdbc.update("UPDATE file_asset SET status='UPLOADING',expires_at=? WHERE id=?",past,row.getId());expect(get(row.getId().toString(),token),404,"RESOURCE_NOT_FOUND");}
        if(kind.equals("DELETING_EMPTY")){jdbc.update("UPDATE file_asset SET status='DELETING',expires_at=? WHERE id=?",past,row.getId());}
        if(kind.endsWith("EMPTY")){storage.delete(storage.storageId(),row.getStorageKey());}
        if(kind.equals("OTHER_NAMESPACE")){jdbc.update("UPDATE file_asset SET storage_id=?,expires_at=? WHERE id=?",UUID.randomUUID().toString(),past,row.getId());}
        if(kind.equals("PROTECTED_NULL")){jdbc.update("UPDATE file_asset SET expires_at=NULL WHERE id=?",row.getId());}
        cleanup.runOnce();var after=files.selectById(row.getId());assertThat(after.getStatus()).isEqualTo(Set.of("OTHER_NAMESPACE","PROTECTED_NULL").contains(kind)?"AVAILABLE":"DELETED");
    }
    @Test void cleanupBatchIsBoundedAndOtherRowsAreNotStarved()throws Exception {
        var a=user("STUDENT");var original=row(data(upload(login(a),"test.pdf","application/pdf",pdf)));storage.delete(storage.storageId(),original.getStorageKey());jdbc.update("DELETE FROM file_asset WHERE id=?",original.getId());
        for(int i=0;i<101;i++){original.setId(null);original.setStorageKey(UUID.randomUUID().toString().replace("-","")+".pdf");original.setExpiresAt(FileRules.utc(NOW));files.insert(original);}cleanup.runOnce();assertThat(countState("DELETED")).isEqualTo(100);cleanup.runOnce();assertThat(countState("DELETED")).isEqualTo(101);
    }
    @Test void twoProcessingSlotsRejectThirdAndIdenticalUploadsRemainSeparate()throws Exception {
        var token=login(user("STUDENT"));var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var real=new PdfInspector();doAnswer(i->{entered.countDown();await(release);return real.pages(i.getArgument(0));}).when(inspector).pages(any());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->upload(token,"one.pdf","application/pdf",pdf));var second=pool.submit(()->upload(token,"two.pdf","application/pdf",pdf));await(entered);
            try{expect(upload(token,"third.pdf","application/pdf",pdf),429,"FILE_PROCESSING_BUSY");}finally{release.countDown();}
            var a=data(first.get(20,TimeUnit.SECONDS));var b=data(second.get(20,TimeUnit.SECONDS));assertThat(a.path("id").asString()).isNotEqualTo(b.path("id").asString());
        }finally{release.countDown();}
    }
    @Test void cleanupCannotInterleaveWithGuardedPublication()throws Exception {
        var a=user("STUDENT");var token=login(a);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(i->{i.callRealMethod();entered.countDown();await(release);return null;}).when(storage).move(any(),anyString());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var upload=pool.submit(()->upload(token,"test.pdf","application/pdf",pdf));await(entered);fixed(NOW.plusSeconds(1800));
            try{cleanup.runOnce();assertThat(statuses()).containsExactly("UPLOADING");}finally{release.countDown();}
            expect(upload.get(20,TimeUnit.SECONDS),503,"FILE_STORAGE_UNAVAILABLE");assertThat(statuses()).containsExactly("DELETED");
        }finally{release.countDown();}
    }
    @Test void concurrentCleanupOnlyDeletesOnce()throws Exception {
        var view=data(upload(login(user("STUDENT")),"test.pdf","application/pdf",pdf));fixed(NOW.plusSeconds(86400));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(i->{entered.countDown();await(release);return i.callRealMethod();}).when(storage).delete(anyString(),anyString());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var first=pool.submit(cleanup::runOnce);await(entered);try{pool.submit(cleanup::runOnce).get(10,TimeUnit.SECONDS);}finally{release.countDown();}first.get(10,TimeUnit.SECONDS);
        }finally{release.countDown();}
        verify(storage,times(1)).delete(anyString(),anyString());assertThat(row(view).getStatus()).isEqualTo("DELETED");
    }
    @Test void actualDatabaseConstraintsRejectCorruptMetadata()throws Exception {
        var view=data(upload(login(user("STUDENT")),"test.pdf","application/pdf",pdf));var id=view.path("id").asString();for(String sql:List.of("byte_size=0","byte_size=20971521","pdf_page_count=0","pdf_page_count=501","sha256='bad'","storage_key='../outside'","status='UNKNOWN'","version=-1","owner_id=9223372036854775807")){assertThatThrownBy(()->jdbc.update("UPDATE file_asset SET "+sql+" WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    }
    @Test void actualNewJarProcessRecoversDurableInterruptedRowsAndTemporaryFiles()throws Exception {
        var a=user("STUDENT");var token=login(a);var one=row(data(upload(token,"one.pdf","application/pdf",pdf)));var two=row(data(upload(token,"two.pdf","application/pdf",pdf)));jdbc.update("UPDATE file_asset SET status='UPLOADING',expires_at=? WHERE id=?",FileRules.utc(NOW.minusSeconds(1)),one.getId());jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",two.getId());
        var orphan=storage.root().resolve("temporary/"+"b".repeat(32)+".part");Files.writeString(orphan,"owned stale temp");Files.setLastModifiedTime(orphan,FileTime.from(NOW.minusSeconds(3600)));extras.add(orphan);var container=storage.root().resolve("multipart/upload_owned_restart_001.tmp");Files.writeString(container,"old container temp");extras.add(container);var keep=storage.root().resolve("temporary/keep.txt");Files.writeString(keep,"not owned");extras.add(keep);
        int port;try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        Path output=Path.of(".cache/file-recovery-"+UUID.randomUUID()+".log").toAbsolutePath();var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",Path.of("target/campuslife-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"--server.address=127.0.0.1","--server.port="+port,"--campuslife.files.root="+storage.root(),"--campuslife.files.cleanup-enabled=true","--campuslife.booking.expiry-enabled=false").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);boolean ready=false;
            while(System.nanoTime()<deadline&&process.isAlive()){
                try{var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/system/health")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString());ready=response.statusCode()==200&&"DELETED".equals(files.selectById(one.getId()).getStatus())&&"DELETED".equals(files.selectById(two.getId()).getStatus());if(ready){break;}}catch(IOException ignored){}Thread.sleep(150);
            }
            assertThat(ready).as("Recovery process log: %s",output).isTrue();assertThat(object(one)).doesNotExist();assertThat(object(two)).doesNotExist();assertThat(orphan).doesNotExist();assertThat(container).doesNotExist();assertThat(keep).exists();
        }finally{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    private record User(long id,String name){}
    private User user(String role){var a=new AccountEntity();a.setUsername("file_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("虚构文件同学");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);owners.add(a.getId());return new User(a.getId(),a.getUsername());}
    private void invalidate(User a,String kind){if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());}else if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",FileRules.utc(NOW),a.id());}else{fixed(NOW.plusSeconds(7200));}}
    private void fixed(Instant now){doReturn(now).when(clock).instant();}
    private String login(User a)throws Exception{return data(call("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",a.name(),"password","File_Test123!")),null)).path("accessToken").asString();}
    private FileAssetEntity row(JsonNode view){return files.selectById(Long.parseLong(view.path("id").asString()));}
    private Path object(FileAssetEntity row){return storage.root().resolve("objects").resolve(row.getStorageKey());}
    private int rows(){return jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE storage_id=?",Integer.class,storage.storageId());}
    private List<String> statuses(){return jdbc.queryForList("SELECT status FROM file_asset WHERE storage_id=? ORDER BY id",String.class,storage.storageId());}
    private int countState(String state){return jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE storage_id=? AND status=?",Integer.class,storage.storageId(),state);}
    private void assertNoOwnedObjects()throws Exception{try(var stream=Files.list(storage.root().resolve("objects"))){assertThat(stream.count()).isZero();}try(var stream=Files.list(storage.root().resolve("temporary"))){assertThat(stream.count()).isZero();}}
    private HttpResponse<String> get(String id,String token)throws Exception{return call("GET","/api/v1/files/"+id,null,token);}
    private HttpResponse<String> call(String method,String path,String body,String token)throws Exception{var builder=request(path,token);if(body!=null){builder.header("Content-Type","application/json");}return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private HttpRequest.Builder request(String path,String token){var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(60));if(token!=null){builder.header("Authorization","Bearer "+token);}return builder;}
    private record Part(String name,String filename,String type,byte[] bytes){}
    private HttpResponse<String> upload(String token,String name,String type,byte[] bytes)throws Exception{return multipart(token,List.of(new Part("file",name,type,bytes)));}
    private HttpResponse<String> multipart(String token,List<Part> parts)throws Exception {
        String boundary="CampusLife"+UUID.randomUUID().toString().replace("-","");var output=new ByteArrayOutputStream();for(var part:parts){output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+part.name()+"\""+(part.filename()==null?"":"; filename=\""+part.filename()+"\"")+"\r\n"+(part.type()==null?"":"Content-Type: "+part.type()+"\r\n")+"\r\n").getBytes(StandardCharsets.UTF_8));output.write(part.bytes());output.write("\r\n".getBytes(StandardCharsets.US_ASCII));}output.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));return http.send(request("/api/v1/files",token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private JsonNode data(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isIn(200,201);return json.readTree(response.body()).path("data");}
    private void expect(HttpResponse<String> response,int status,String code){assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);}
    private static void await(CountDownLatch latch){try{if(!latch.await(20,TimeUnit.SECONDS)){throw new IllegalStateException("gate timeout");}}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
}
