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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="campuslife.files.cleanup-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileDownloadMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-23T01:00:00Z");
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<Long> owners=new ArrayList<>();
    private String hash;private byte[] pdf;
    @Autowired AccountMapper accounts;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate jdbc;
    @Autowired Environment env;@Autowired ObjectMapper json;@Autowired FileCleanup cleanup;@Autowired FileDownloadService downloads;
    @MockitoSpyBean FileAssetMapper files;@MockitoSpyBean LocalFileStorage storage;@MockitoSpyBean Clock clock;
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("Download_Test123!");pdf=PdfSamples.pdf(2);}
    @BeforeEach void freeze(){fixed(NOW);}
    @AfterEach void cleanupOwned()throws Exception {
        reset(files,storage,clock);
        for(long id:owners){
            for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=?",id)){storage.delete(storage.storageId(),row.get("storage_key").toString());}
            jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);
            jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);
            jdbc.update("DELETE FROM account WHERE id=?",id);
        }
        owners.clear();
    }

    @ParameterizedTest @ValueSource(strings={"NONE","RANGE","ETAG","MODIFIED"})
    void actualHttpReturnsOriginalPdfAndSafeHeadersWithoutConditionalOrRangeResponses(String kind)throws Exception {
        var token=login(user("STUDENT"));
        var view=data(upload(token,"校园 100%练习.pdf","application/pdf",pdf));
        var id=view.path("id").asString();var before=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id);
        var builder=request("/api/v1/files/"+id+"/content",token).header("Accept","application/pdf");
        if(kind.equals("RANGE")){builder.header("Range","bytes=0-9");}
        if(kind.equals("ETAG")){builder.header("If-None-Match","*");}
        if(kind.equals("MODIFIED")){builder.header("If-Modified-Since","Wed, 01 Jan 2031 00:00:00 GMT");}
        var response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(pdf);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(pdf.length);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Accept-Ranges")).isEmpty();
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).isEqualTo("attachment; filename=\"campuslife-file.pdf\"; filename*=UTF-8''%E6%A0%A1%E5%9B%AD%20100%25%E7%BB%83%E4%B9%A0.pdf");
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id)).isEqualTo(before);
    }
    @Test void quotedUnicodeFilenameCannotBreakAttachmentHeader()throws Exception {
        var token=login(user("STUDENT"));var view=data(upload(token,"test.pdf","application/pdf",pdf));
        jdbc.update("UPDATE file_asset SET original_name=? WHERE id=?","中文\";name=evil%.pdf",view.path("id").asString());
        var response=download(view.path("id").asString(),token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("%22%3Bname%3Devil%25.pdf").doesNotContain(";name=evil");
    }
    @ParameterizedTest @ValueSource(strings={"MERCHANT","ADMIN","ANONYMOUS","INVALID","DISABLED","REVOKED","EXPIRED"})
    void downloadRequiresCurrentIdentityOrMerchantGrantWithoutOpeningUnauthorizedStorage(String kind)throws Exception {
        String token=null;int status=403;String code="ROLE_FORBIDDEN";
        if(kind.equals("ANONYMOUS")){status=401;code="AUTH_REQUIRED";}
        else if(kind.equals("INVALID")){token="invalid";status=401;code="TOKEN_INVALID";}
        else {
            var a=user(Set.of("MERCHANT","ADMIN").contains(kind)?kind:"STUDENT");token=login(a);
            if(kind.equals("MERCHANT")){status=404;code="RESOURCE_NOT_FOUND";}
            if(Set.of("DISABLED","REVOKED","EXPIRED").contains(kind)){invalidate(a,kind);}
            if(kind.equals("DISABLED")){code="ACCOUNT_DISABLED";}
            if(kind.equals("REVOKED")){status=401;code="SESSION_REVOKED";}
            if(kind.equals("EXPIRED")){status=401;code="SESSION_EXPIRED";}
        }
        clearInvocations(storage,files);expectDownload(download("1",token),status,code);verifyNoInteractions(storage,files);
    }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","%2B1","1.0","%201","9223372036854775808"})
    void downloadValidatesCanonicalId(String id)throws Exception{expectDownload(download(id,login(user("STUDENT"))),400,"VALIDATION_FAILED");}
    @ParameterizedTest @ValueSource(strings={"AVAILABLE","UPLOADING","DELETING","DELETED","EXPIRED"})
    void ownershipPrecedesLifecycleAndDeniedDownloadNeverOpensObjects(String state)throws Exception {
        var token=login(user("STUDENT"));var other=login(user("STUDENT"));var view=data(upload(token,"test.pdf","application/pdf",pdf));var id=view.path("id").asString();
        if(state.equals("EXPIRED")){jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),id);}
        else if(state.equals("DELETED")){jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),id);}
        else{jdbc.update("UPDATE file_asset SET status=? WHERE id=?",state,id);}
        clearInvocations(storage);
        expectDownload(download(id,other),404,"RESOURCE_NOT_FOUND");
        expectDownload(download("9223372036854775807",token),404,"RESOURCE_NOT_FOUND");
        if(!state.equals("AVAILABLE")){expectDownload(download(id,token),state.equals("UPLOADING")?404:410,state.equals("UPLOADING")?"RESOURCE_NOT_FOUND":"FILE_EXPIRED");}
        verify(storage,never()).open(anyString(),anyString(),anyLong());
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void expiryBoundaryIsInclusiveAtMillisecondPrecision(int relativeMillis)throws Exception {
        var token=login(user("STUDENT"));var view=data(upload(token,"test.pdf","application/pdf",pdf));var id=view.path("id").asString();
        jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW.plusMillis(relativeMillis)),id);
        var response=download(id,token);
        if(relativeMillis>0){assertThat(response.body()).isEqualTo(pdf);}else{expectDownload(response,410,"FILE_EXPIRED");}
    }
    @Test void futureProtectedNullExpiryIsReadableWithoutAddingReferences()throws Exception {
        var token=login(user("STUDENT"));var view=data(upload(token,"test.pdf","application/pdf",pdf));
        jdbc.update("UPDATE file_asset SET expires_at=NULL WHERE id=?",view.path("id").asString());
        assertThat(download(view.path("id").asString(),token).body()).isEqualTo(pdf);
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","LENGTH","NAMESPACE","DIRECTORY","OPEN","INITIAL_READ","BUFFERED_READ"})
    void storageFailuresBeforeCommitReturnJsonAndPreserveMetadata(String fault)throws Exception {
        var token=login(user("STUDENT"));var view=data(upload(token,"test.pdf","application/pdf",pdf));var row=row(view);var id=row.getId().toString();var path=object(row);
        if(fault.equals("MISSING")){Files.delete(path);}
        if(fault.equals("LENGTH")){Files.write(path,new byte[]{1});}
        if(fault.equals("NAMESPACE")){jdbc.update("UPDATE file_asset SET storage_id=? WHERE id=?",UUID.randomUUID().toString(),id);}
        if(fault.equals("DIRECTORY")){Files.delete(path);Files.createDirectory(path);}
        if(fault.equals("OPEN")){doThrow(new IOException("private path must not leak")).when(storage).open(anyString(),anyString(),anyLong());}
        if(Set.of("INITIAL_READ","BUFFERED_READ").contains(fault)) {
            doAnswer(i->new FilterInputStream((InputStream)i.callRealMethod()) {
                int reads;
                @Override public int read(byte[] b,int off,int len)throws IOException {
                    if(reads++==(fault.equals("INITIAL_READ")?0:1)){throw new IOException("private path must not leak");}
                    return super.read(b,off,Math.min(len,1));
                }
            }).when(storage).open(anyString(),anyString(),anyLong());
        }
        var before=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id);
        try {
            var response=download(id,token);expectDownload(response,503,"FILE_STORAGE_UNAVAILABLE");
            assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
            assertThat(new String(response.body(),StandardCharsets.UTF_8)).doesNotContain("private path",storage.root().toString(),row.getStorageKey());
            assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id)).isEqualTo(before);
        }finally{if(fault.equals("DIRECTORY")){Files.delete(path);}}
    }
    @ParameterizedTest @ValueSource(strings={"DISABLED","REVOKED","EXPIRED","ROLE_CHANGED","SESSION_REMOVED","FILE_EXPIRED","DELETING"})
    void identityAndFileAreRecheckedAfterWaitingForGuard(String change)throws Exception {
        var a=user("STUDENT");var token=login(a);var row=row(data(upload(token,"test.pdf","application/pdf",pdf)));
        var guard=storage.lock(row.getStorageKey());var waiting=new CountDownLatch(1);
        doAnswer(i->{waiting.countDown();return guard;}).when(storage).lock(row.getStorageKey());
        guard.lock();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=pool.submit(()->download(row.getId().toString(),token));await(waiting);
            try {
                switch(change) {
                    case "ROLE_CHANGED"->jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",a.id());
                    case "SESSION_REMOVED"->jdbc.update("DELETE FROM auth_session WHERE account_id=?",a.id());
                    case "FILE_EXPIRED"->jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),row.getId());
                    case "DELETING"->jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",row.getId());
                    default->invalidate(a,change);
                }
            }finally{guard.unlock();}
            int status=Set.of("DISABLED","ROLE_CHANGED").contains(change)?403:Set.of("FILE_EXPIRED","DELETING").contains(change)?410:401;
            String code=switch(change){case "DISABLED"->"ACCOUNT_DISABLED";case "ROLE_CHANGED"->"ROLE_FORBIDDEN";case "REVOKED"->"SESSION_REVOKED";case "SESSION_REMOVED"->"TOKEN_INVALID";case "EXPIRED"->"SESSION_EXPIRED";default->"FILE_EXPIRED";};
            expectDownload(future.get(20,TimeUnit.SECONDS),status,code);verify(storage,never()).open(anyString(),anyString(),anyLong());
        }finally{if(guard.isHeldByCurrentThread()){guard.unlock();}}
    }
    @ParameterizedTest @ValueSource(strings={"REVOKED","DISABLED","FILE_EXPIRED"})
    void authorizedStreamFinishesAndCleanupSkipsItWithoutHoldingDatabaseTransaction(String change)throws Exception {
        var a=user("STUDENT");var token=login(a);byte[] large=PdfSamples.sized(32768);
        var row=row(data(upload(token,"stream.pdf","application/pdf",large)));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new FilterInputStream((InputStream)i.callRealMethod()) {
                int reads;
                @Override public int read(byte[] b,int off,int len)throws IOException {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if(reads++==1){entered.countDown();await(release);}
                    return super.read(b,off,len);
                }
            };
        }).when(storage).open(anyString(),anyString(),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=pool.submit(()->download(row.getId().toString(),token));await(entered);
            try {
                pool.submit(()->{
                    if(change.equals("FILE_EXPIRED")){jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),row.getId());}
                    else{invalidate(a,change);jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),row.getId());}
                }).get(5,TimeUnit.SECONDS);
                cleanup.runOnce();assertThat(files.selectById(row.getId()).getStatus()).isEqualTo("AVAILABLE");assertThat(object(row)).exists();
                expectDownload(download(row.getId().toString(),token),change.equals("FILE_EXPIRED")?410:change.equals("REVOKED")?401:403,change.equals("FILE_EXPIRED")?"FILE_EXPIRED":change.equals("REVOKED")?"SESSION_REVOKED":"ACCOUNT_DISABLED");
            }finally{release.countDown();}
            var response=future.get(20,TimeUnit.SECONDS);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(large);
            cleanup.runOnce();assertThat(files.selectById(row.getId()).getStatus()).isEqualTo("DELETED");assertThat(object(row)).doesNotExist();
        }finally{release.countDown();}
    }
    @Test void cleanupThatCommitsFirstPreventsContentRead()throws Exception {
        var token=login(user("STUDENT"));var row=row(data(upload(token,"test.pdf","application/pdf",pdf)));
        jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),row.getId());cleanup.runOnce();
        clearInvocations(storage);expectDownload(download(row.getId().toString(),token),410,"FILE_EXPIRED");verify(storage,never()).open(anyString(),anyString(),anyLong());
    }
    @Test void simulatedClientWriteFailureClosesInputAndReleasesGuard()throws Exception {
        var a=user("STUDENT");var token=login(a);byte[] large=PdfSamples.sized(32768);var row=row(data(upload(token,"test.pdf","application/pdf",large)));
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(i->new FilterInputStream((InputStream)i.callRealMethod()) {
            @Override public void close()throws IOException{closed.set(true);super.close();}
        }).when(storage).open(anyString(),anyString(),anyLong());
        var base=new org.springframework.mock.web.MockHttpServletResponse();
        var output=new jakarta.servlet.ServletOutputStream() {
            int writes;
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(jakarta.servlet.WriteListener listener){}
            @Override public void write(int b)throws IOException{throw new IOException("client disconnected");}
            @Override public void write(byte[] b,int off,int len)throws IOException {
                if(writes++>0){throw new IOException("client disconnected");}
                base.getOutputStream().write(b,off,len);base.flushBuffer();
            }
        };
        var response=new jakarta.servlet.http.HttpServletResponseWrapper(base) {
            @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return output;}
        };
        var principal=new com.campuslife.auth.security.AuthPrincipal(a.id(),jdbc.queryForObject("SELECT jti FROM auth_session WHERE account_id=?",String.class,a.id()),new com.campuslife.auth.vo.AccountView(Long.toString(a.id()),a.name(),"测试同学","STUDENT","ACTIVE","0"));
        assertThatThrownBy(()->downloads.download(principal,row.getId().toString(),response)).isInstanceOf(IOException.class).hasMessage("PDF transfer interrupted");
        assertThat(base.isCommitted()).isTrue();assertThat(closed).isTrue();
        assertThat(base.getContentAsByteArray()).isEqualTo(Arrays.copyOf(large,8192));
        jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),row.getId());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){pool.submit(cleanup::runOnce).get(5,TimeUnit.SECONDS);}
        assertThat(files.selectById(row.getId()).getStatus()).isEqualTo("DELETED");
    }
    @Test void committedReadFailureSendsOnlyPdfPrefixAndReleasesFileGuard()throws Exception {
        var token=login(user("STUDENT"));byte[] large=PdfSamples.sized(32768);var row=row(data(upload(token,"test.pdf","application/pdf",large)));
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(i->new FilterInputStream((InputStream)i.callRealMethod()) {
            int reads;
            @Override public int read(byte[] b,int off,int len)throws IOException{if(reads++==2){throw new IOException("injected after commit");}return super.read(b,off,len);}
            @Override public void close()throws IOException{closed.set(true);super.close();}
        }).when(storage).open(anyString(),anyString(),anyLong());
        long started=System.nanoTime();
        var response=http.send(request("/api/v1/files/"+row.getId()+"/content",token).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);var received=new ByteArrayOutputStream();
        try(var input=response.body()){byte[] buffer=new byte[4096];int n;try{while((n=input.read(buffer))!=-1){received.write(buffer,0,n);}}catch(IOException expected){}}
        assertThat(received.size()).isBetween(1,large.length-1);
        assertThat(received.toByteArray()).isEqualTo(Arrays.copyOf(large,received.size()));assertThat(closed).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));
        jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),row.getId());cleanup.runOnce();
        assertThat(files.selectById(row.getId()).getStatus()).isEqualTo("DELETED");
    }
    private HttpResponse<byte[]> download(String id,String token)throws Exception {
        return http.send(request("/api/v1/files/"+id+"/content",token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private void expectDownload(HttpResponse<byte[]> response,int status,String code) {
        var body=new String(response.body(),StandardCharsets.UTF_8);assertThat(response.statusCode()).as(body).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(json.readTree(body).path("code").asString()).isEqualTo(code);
    }
    private record User(long id,String name){}
    private User user(String role){var a=new AccountEntity();a.setUsername("download_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("虚构文件同学");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);owners.add(a.getId());return new User(a.getId(),a.getUsername());}
    private void invalidate(User a,String kind){if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());}else if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",FileRules.utc(NOW),a.id());}else{fixed(NOW.plusSeconds(7200));}}
    private void fixed(Instant now){doReturn(now).when(clock).instant();}
    private String login(User a)throws Exception{return data(call("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",a.name(),"password","Download_Test123!")),null)).path("accessToken").asString();}
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
