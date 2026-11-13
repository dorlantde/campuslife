package com.campuslife;

import com.campuslife.file.PdfSamples;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.service.FileRules;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.files.cleanup-enabled=false","campuslife.messaging.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_S3_BUSINESS_IT",matches="owned-isolated-fixture")
class S3RecoveryMysqlIT extends S3Fixture {
    final Path output=Path.of("target/s3-recovery",UUID.randomUUID().toString()).toAbsolutePath();
    final List<Child> children=new ArrayList<>();final List<Map<String,Object>> evidence=new ArrayList<>();
    @RegisterExtension final TestWatcher results=new TestWatcher(){
        @Override public void testSuccessful(ExtensionContext c){evidence.add(Map.of("case",c.getDisplayName(),"outcome","passed"));}
        @Override public void testFailed(ExtensionContext c,Throwable ex){evidence.add(Map.of("case",c.getDisplayName(),"outcome","failed","failure",ex.getClass().getSimpleName()));}
    };
    record Child(Process process,Path marker,int port,String mode){}
    @AfterEach void stopChildren()throws Exception {for(var c:children){stop(c);}children.clear();}
    @AfterAll void saveEvidence()throws Exception {Files.createDirectories(Path.of(System.getenv("S3_RECOVERY_OUTPUT")));Files.writeString(Path.of(System.getenv("S3_RECOVERY_OUTPUT"),"fault-checks.json"),json.writeValueAsString(evidence));}
    Child child(String mode,String endpoint,Instant at,String secret)throws Exception {
        Files.createDirectories(output);Path mark=output.resolve(UUID.randomUUID()+".marker");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",System.getProperty("java.class.path"),S3RecoveryChild.class.getName());
        var e=builder.environment();e.put("S3_RECOVERY_MODE",mode);e.put("S3_RECOVERY_MARKER",mark.toString());e.put("S3_RECOVERY_NOW",at.toString());
        e.put("FILE_S3_ENABLED","true");e.put("FILE_UPLOAD_TARGET","S3");e.put("FILE_S3_ENDPOINT",endpoint);e.put("FILE_S3_REGION","us-east-1");e.put("FILE_S3_BUCKET",fixture("BUCKET"));e.put("FILE_S3_NAMESPACE",fixture("NAMESPACE"));e.put("FILE_S3_ACCESS_KEY",fixture("APP_KEY"));e.put("FILE_S3_SECRET_KEY",secret);
        var process=builder.redirectErrorStream(true).redirectOutput(Path.of(mark+".log").toFile()).start();
        var pending=new Child(process,mark,0,mode);children.add(pending);
        waitFor(()->Files.exists(Path.of(mark+".ready")),process,75,"child startup "+mark.getFileName());
        var result=new Child(process,mark,Integer.parseInt(Files.readString(Path.of(mark+".ready"))),mode);children.set(children.size()-1,result);return result;
    }
    Child child(String mode,String endpoint,Instant at)throws Exception {return child(mode,endpoint,at,fixture("APP_SECRET"));}
    void stop(Child child)throws Exception {if(child.process().isAlive()){child.process().destroyForcibly();assertThat(child.process().waitFor(15,TimeUnit.SECONDS)).isTrue();}}
    void recover(Instant at,String endpoint)throws Exception {var c=child("RECOVER",endpoint,at);assertThat(c.process().waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(c.process().exitValue()).isZero();assertThat(Path.of(c.marker()+".done")).exists();}
    interface Checked {boolean get()throws Exception;}
    void waitFor(Checked test,Process process,int seconds,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline){if(test.get()){return;}if(process!=null&&!process.isAlive()){break;}Thread.sleep(100);}throw new AssertionError(message);
    }
    HttpResponse<String> upload(Child child,String token,byte[] pdf)throws Exception {
        String boundary="S3Recovery"+UUID.randomUUID();var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recovery.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(pdf);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+child.port()+"/api/v1/files")).timeout(Duration.ofSeconds(70))
                .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    FileAssetEntity last(User user){return fileRows.selectById(jdbc.queryForObject("SELECT MAX(id) FROM file_asset WHERE owner_id=?",Long.class,user.id()));}
    HttpResponse<byte[]> get(Child child,FileAssetEntity row,String token)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+child.port()+"/api/v1/files/"+row.getId()+"/content")).timeout(Duration.ofSeconds(40)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @ParameterizedTest @ValueSource(strings={"AFTER_RESERVE","AFTER_PUT","AFTER_AVAILABLE","AFTER_DELETE"})
    void realProcessCrashAndFreshProcessRecovery(String mode)throws Exception {
        var u=user("STUDENT");String token=login(u);byte[] pdf=PdfSamples.pdf(2);FileAssetEntity existing=null;
        if(mode.equals("AFTER_DELETE")){var view=uploads.upload(principal(u),new org.springframework.mock.web.MockMultipartFile("file","test.pdf","application/pdf",pdf));existing=fileRows.selectById(Long.parseLong(view.id()));jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW.minusSeconds(1)),existing.getId());}
        var c=child(mode,fixture("ENDPOINT"),NOW);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request=mode.equals("AFTER_DELETE")?null:pool.submit(()->{try{return upload(c,token,pdf);}catch(IOException ex){return null;}});
            waitFor(()->Files.exists(c.marker()),c.process(),40,"real crash boundary "+mode);
            var row=existing==null?last(u):fileRows.selectById(existing.getId());
            assertThat(row.getStatus()).isEqualTo(mode.equals("AFTER_AVAILABLE")?"AVAILABLE":mode.equals("AFTER_DELETE")?"DELETING":"UPLOADING");
            if(mode.equals("AFTER_PUT")||mode.equals("AFTER_AVAILABLE")){assertThat(admin.getObjectAsBytes(b->b.bucket(fixture("BUCKET")).key(object(row))).asByteArray()).isEqualTo(pdf);}else{missing(row);}
            if(request!=null){assertThat(request.isDone()).isFalse();}stop(c);if(request!=null){request.get(10,TimeUnit.SECONDS);}
            recover(NOW.plusSeconds(1801),fixture("ENDPOINT"));
            var after=fileRows.selectById(row.getId());
            if(mode.equals("AFTER_AVAILABLE")){assertThat(after.getStatus()).isEqualTo("AVAILABLE");assertThat(download(after,token).statusCode()).isEqualTo(200);assertThat(after.getVersion()).isEqualTo(row.getVersion());}
            else{assertThat(after.getStatus()).isEqualTo("DELETED");missing(after);}
        }
        evidence.add(Map.of("window",mode,"actualForceKill",true,"freshRecoveryProcess",true));
    }
    @Test void timedOutRealPutsArriveAfterTombstoneDeleteAndFreshProcessReclaimsThem()throws Exception {
        var keep=setup("PRINT");var safe=asset(keep);var u=user("STUDENT");String token=login(u);byte[] pdf=PdfSamples.pdf(2);
        try(var proxy=new S3FaultProxy(fixture("ENDPOINT"))) {
            proxy.fault=S3FaultProxy.Fault.HOLD_PUT;var c=child("NORMAL",proxy.endpoint(),NOW);
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var request=pool.submit(()->upload(c,token,pdf));assertThat(proxy.held.await(15,TimeUnit.SECONDS)).isTrue();
                var result=request.get(55,TimeUnit.SECONDS);expect(result,503,"FILE_STORAGE_UNAVAILABLE");
                var row=last(u);assertThat(row.getStatus()).isEqualTo("DELETED");missing(row);assertThat(proxy.deletes.get()).isGreaterThan(0);stop(c);
                recover(NOW.plusSeconds(1801),proxy.endpoint());int before=proxy.deletes.get();assertThat(before).isGreaterThan(1);
                assertThat(proxy.heldRequests.get()).isBetween(1,2);proxy.release.countDown();
                waitFor(()->proxy.forwardedPuts.get()==proxy.heldRequests.get(),null,15,"late signed PUT forwarded after successful deletes");
                assertThat(admin.getObjectAsBytes(b->b.bucket(fixture("BUCKET")).key(object(row))).asByteArray()).isEqualTo(pdf);
                recover(NOW.plusSeconds(1801),proxy.endpoint());missing(row);assertThat(proxy.deletes.get()).isGreaterThan(before);
                assertThat(fileRows.selectById(safe.getId()).getStatus()).isEqualTo("AVAILABLE");assertThat(download(safe,keep.token()).statusCode()).isEqualTo(200);
                evidence.add(Map.of("latePut",true,"heldRealRequests",proxy.heldRequests.get(),"successfulDeletes",proxy.deletes.get(),"events",proxy.events));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","SAME_LENGTH_WRONG_CONTENT","AUTH_FAILURE","TRUNCATED","RESTART"})
    void storageFaultsKeepMetadataAndDocumentTransferBoundary(String fault)throws Exception {
        var u=user("STUDENT");String token=login(u);byte[] pdf=PdfSamples.sized(131072);
        var view=uploads.upload(principal(u),new org.springframework.mock.web.MockMultipartFile("file","sample.pdf","application/pdf",pdf));var row=fileRows.selectById(Long.parseLong(view.id()));
        var before=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",row.getId());
        if(fault.equals("MISSING")){admin.deleteObject(b->b.bucket(fixture("BUCKET")).key(object(row)));assertThat(download(row,token).statusCode()).isEqualTo(503);put(row,pdf);}
        if(fault.equals("SAME_LENGTH_WRONG_CONTENT")){var wrong=pdf.clone();wrong[0]^=1;put(row,wrong);var response=download(row,token);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(wrong);assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()))).isNotEqualTo(row.getSha256());put(row,pdf);}
        if(fault.equals("AUTH_FAILURE")){var c=child("NORMAL",fixture("ENDPOINT"),NOW,"intentionally-invalid-fixture-secret");assertThat(get(c,row,token).statusCode()).isEqualTo(503);stop(c);}
        if(fault.equals("TRUNCATED")) {
            try(var proxy=new S3FaultProxy(fixture("ENDPOINT"))) {
                proxy.fault=S3FaultProxy.Fault.TRUNCATE_GET;var c=child("NORMAL",proxy.endpoint(),NOW);
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+c.port()+"/api/v1/files/"+row.getId()+"/content")).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);var bytes=new ByteArrayOutputStream();
                try(var in=response.body()){assertThatThrownBy(()->{byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){bytes.write(b,0,n);}}).isInstanceOf(IOException.class);}
                assertThat(bytes.size()).isBetween(1,pdf.length-1);assertThat(new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1)).doesNotContain("\"traceId\"","FILE_STORAGE_UNAVAILABLE");stop(c);
            }
        }
        if(fault.equals("RESTART")){docker("stop");try{assertThat(download(row,token).statusCode()).isEqualTo(503);}finally{docker("start");}
            admin.close();admin=adminClient(fixture("ENDPOINT"));
            waitFor(()->{try{marker(fixture("NAMESPACE"));return true;}catch(Exception ex){return false;}},null,60,"S3 restart write readiness");assertThat(download(row,token).body()).isEqualTo(pdf);}
        assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",row.getId())).isEqualTo(before);
    }
    void docker(String action)throws Exception {
        var p=new ProcessBuilder("docker",action,fixture("NAME")).redirectErrorStream(true).start();assertThat(p.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
    }
    @Test void sameLengthCorruptedReadbackCannotPublishEvenWithoutChecksumHeader()throws Exception {
        var u=user("STUDENT");String token=login(u);
        try(var proxy=new S3FaultProxy(fixture("ENDPOINT"))) {
            proxy.fault=S3FaultProxy.Fault.CORRUPT_GET;var c=child("NORMAL",proxy.endpoint(),NOW);
            expect(upload(c,token,PdfSamples.pdf(2)),503,"FILE_STORAGE_UNAVAILABLE");var row=last(u);assertThat(row.getStatus()).isEqualTo("DELETED");missing(row);stop(c);
        }
    }
}
