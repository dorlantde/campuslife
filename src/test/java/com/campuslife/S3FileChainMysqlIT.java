package com.campuslife;

import com.campuslife.file.PdfSamples;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.*;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.files.cleanup-enabled=false","campuslife.messaging.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_S3_BUSINESS_IT",matches="owned-isolated-fixture")
class S3FileChainMysqlIT extends S3Fixture {
    @Test void realPdfUploadAndStudentDownloadRetainBytesHeadersAndMetadata() throws Exception {
        var c=setup("PRINT");var row=asset(c);assertThat(row.getStorageKind()).isEqualTo("S3");
        assertThat(row.getStorageId()).isEqualTo(fixture("NAMESPACE"));assertThat(disk.root().resolve("objects").resolve(row.getStorageKey())).doesNotExist();
        var response=download(row,c.token());assertThat(response.statusCode()).isEqualTo(200);
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()))).isEqualTo(row.getSha256());
        assertThat(response.body()).isEqualTo(admin.getObjectAsBytes(b->b.bucket(fixture("BUCKET")).key(object(row))).asByteArray());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment;");
        var before=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",row.getId());
        data(call("GET","/api/v1/files/"+row.getId(),null,c.token(),null));assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",row.getId())).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","OTHER_STUDENT","ADMIN","OTHER_MERCHANT"})
    void authorizationFailsBeforeRemoteIo(String role) throws Exception {
        var c=setup("PRINT");var row=asset(c);String token=role.equals("ANONYMOUS")?null:login(user(role.equals("OTHER_STUDENT")?"STUDENT":role.equals("ADMIN")?"ADMIN":"MERCHANT"));
        clearInvocations(remote);assertThat(download(row,token).statusCode()).isEqualTo(role.equals("ANONYMOUS")?401:role.equals("ADMIN")?403:404);verifyNoInteractions(remote);
    }
    @Test void completedPrintKeepsSevenDaysAndRemovesMerchantGrantWhileStudentCanDownload() throws Exception {
        var c=setup("PRINT");var row=asset(c);String id=id(create(c,"complete"));data(action(c,id,"payments/mock"));String merchant=login(c.f().owner());
        data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));assertThat(download(row,merchant).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",c.f().shop());
        data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,merchant,null));assertThat(download(row,merchant).statusCode()).isEqualTo(200);
        data(call("POST","/api/v1/merchant/orders/"+id+"/ready-for-pickup",null,merchant,null));assertThat(download(row,merchant).statusCode()).isEqualTo(200);
        clearInvocations(remote);data(action(c,id,"pickup"));verifyNoInteractions(remote);expires(row,NOW.plus(Duration.ofDays(7)));
        assertThat(download(row,merchant).statusCode()).isEqualTo(404);assertThat(download(row,c.token()).statusCode()).isEqualTo(200);
        var facts=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",row.getId());data(action(c,id,"pickup"));assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",row.getId())).isEqualTo(facts);
        fixed(NOW.plus(Duration.ofDays(7)));drain();assertThat(fileRows.selectById(row.getId()).getStatus()).isEqualTo("DELETED");missing(row);
    }
    @ParameterizedTest @ValueSource(strings={"UNPAID_CANCEL","STUDENT_REFUND","REJECT","MERCHANT_CANCEL","ADMIN_PRINTING","ADMIN_READY"})
    void terminalPathsKeepOriginalCouponMoneyAndFileReferenceRules(String mode) throws Exception {
        var c=setup("PRINT");var row=asset(c);String id=id(create(c,"close"));
        if(!mode.equals("UNPAID_CANCEL")){data(action(c,id,"payments/mock"));}
        String merchant=login(c.f().owner());
        if(mode.equals("MERCHANT_CANCEL")||mode.startsWith("ADMIN")){data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));}
        if(mode.startsWith("ADMIN")){data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,merchant,null));}
        if(mode.equals("ADMIN_READY")){data(call("POST","/api/v1/merchant/orders/"+id+"/ready-for-pickup",null,merchant,null));}
        clearInvocations(remote);
        switch(mode) {
            case "UNPAID_CANCEL","STUDENT_REFUND" -> data(action(c,id,"cancel"));
            case "REJECT" -> data(call("POST","/api/v1/merchant/orders/"+id+"/reject",Map.of("reason","模拟故障"),merchant,null));
            case "MERCHANT_CANCEL" -> data(call("POST","/api/v1/merchant/orders/"+id+"/cancel",Map.of("reason","模拟故障"),merchant,null));
            default -> {String adminToken=login(user("ADMIN"));long version=jdbc.queryForObject("SELECT version FROM service_order WHERE id=?",Long.class,id);
                data(call("POST","/api/v1/admin/orders/"+id+"/close-exception",Map.of("reason","已核实打印故障","expectedVersion",Long.toString(version)),adminToken,null));}
        }
        verifyNoInteractions(remote);expires(row,NOW.plus(Duration.ofDays(7)));
        assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=?",Integer.class,id)).isEqualTo(mode.equals("UNPAID_CANCEL")?0:1);
        assertThat(state(c.coupon())).isEqualTo(mode.equals("UNPAID_CANCEL")?"AVAILABLE":"USED");assertThat(download(row,merchant).statusCode()).isEqualTo(404);
    }
    @Test void allReferencesProtectSharedS3FileAndReplaysNeedNoStorage() throws Exception {
        var c=setup("PRINT");var row=asset(c);String first=id(create(c,"first"));
        var body=c.body().deepCopy();body.remove("userCouponId");body.put("expectedPayableAmount",3000);String second=id(call("POST",c.path(),body,c.token(),"second"));
        data(action(c,first,"cancel"));expires(row,null);clearInvocations(remote);
        marker("wrong-identity");data(call("POST",c.path(),body,c.token(),"second"));data(action(c,second,"payments/mock"));verifyNoInteractions(remote);
        data(action(c,second,"cancel"));verifyNoInteractions(remote);expires(row,NOW.plus(Duration.ofDays(7)));
    }
    @ParameterizedTest @ValueSource(strings={"PUT_BEFORE","PUT_AFTER","CORRUPT_ROUNDTRIP","SESSION_REVOKED"})
    void failedUploadsNeverPublishAndRecoverByPersistentLocation(String failure) throws Exception {
        var student=user("STUDENT");login(student);byte[] pdf=PdfSamples.pdf(2);
        doAnswer(i->{
            if(failure.equals("PUT_BEFORE")){throw new IOException("injected unavailable");}
            i.callRealMethod();var location=(FileLocation)i.getArgument(0);
            if(failure.equals("PUT_AFTER")){throw new IOException("injected lost PUT reply");}
            if(failure.equals("CORRUPT_ROUNDTRIP")){admin.putObject(b->b.bucket(fixture("BUCKET")).key(location.namespace()+"/objects/"+location.key()),RequestBody.fromBytes(new byte[pdf.length]));}
            if(failure.equals("SESSION_REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",FileRules.utc(NOW),student.id());}
            return null;
        }).when(remote).put(any(),any());
        var principal=principal(student);
        assertThatThrownBy(()->uploads.upload(principal,new MockMultipartFile("file","test.pdf","application/pdf",pdf))).isInstanceOf(com.campuslife.common.exception.BusinessException.class);
        var row=fileRows.selectById(jdbc.queryForObject("SELECT MAX(id) FROM file_asset WHERE owner_id=?",Long.class,student.id()));
        assertThat(row.getStatus()).isEqualTo("DELETED");missing(row);
        try(var paths=Files.list(disk.root().resolve("temporary"))){assertThat(paths.count()).isZero();}
    }
    @Test void lostPublishCommitResponseCannotCompensateAnAvailableObject() throws Exception {
        var student=user("STUDENT");login(student);var mapper=sqlSession.getMapper(FileAssetMapper.class);
        doAnswer(i->{int n=mapper.publish(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){throw new IllegalStateException("injected committed response loss");}});return n;
        }).when(fileRows).publish(anyLong(),anyLong(),any(),any());
        byte[] pdf=PdfSamples.pdf(2);
        assertThatThrownBy(()->uploads.upload(principal(student),new MockMultipartFile("file","test.pdf","application/pdf",pdf))).isInstanceOf(IllegalStateException.class);
        var row=fileRows.selectById(jdbc.queryForObject("SELECT MAX(id) FROM file_asset WHERE owner_id=?",Long.class,student.id()));
        assertThat(row.getStatus()).isEqualTo("AVAILABLE");try(var stream=content.open(row)){assertThat(stream.readAllBytes()).isEqualTo(pdf);}
        verify(remote,never()).delete(any());
    }
    @Test void durableTombstoneReclaimsSyntheticLateWriteButNeverAvailable() throws Exception {
        var c=setup("PRINT");var row=asset(c);var student=c.student();fixed(NOW.plusSeconds(86400));drain();
        var deleted=fileRows.selectById(row.getId());assertThat(deleted.getStatus()).isEqualTo("DELETED");missing(row);
        put(row,PdfSamples.pdf(5));drain();missing(row);assertThat(fileRows.selectById(row.getId()).getVersion()).isEqualTo(deleted.getVersion());
        fixed(NOW);byte[] pdf=PdfSamples.pdf(1);var keep=uploads.upload(principal(student),new MockMultipartFile("file","keep.pdf","application/pdf",pdf));var keepRow=fileRows.selectById(Long.parseLong(keep.id()));
        drain();try(var stream=content.open(keepRow)){assertThat(stream.readAllBytes()).isEqualTo(pdf);}
        assertThat(fileTransactions.tombstone(deleted.getId(),deleted.getStorageId(),deleted.getVersion()+1)).isNull();
    }
    @Test void markerMismatchFailsClosedWhileHistoricalLocalFilesRemainAvailable() throws Exception {
        var c=setup("PRINT");var row=asset(c);var local=new FileAssetEntity();local.setStorageKind("LOCAL");local.setStorageId(disk.storageId());local.setStorageKey("a".repeat(32)+".pdf");local.setByteSize(3L);
        var received=disk.receive(new ByteArrayInputStream(new byte[]{1,2,3}));disk.move(received,local.getStorageKey());disk.releaseTemporary(received.path());
        try {
            marker("wrong-storage-identity");assertThat(download(row,c.token()).statusCode()).isEqualTo(503);
            try(var input=content.open(local)){assertThat(input.readAllBytes()).containsExactly((byte)1,(byte)2,(byte)3);}
            assertThat(fileRows.selectById(row.getId()).getStatus()).isEqualTo("AVAILABLE");
        } finally {disk.delete(local.getStorageId(),local.getStorageKey());}
    }
    @Test void firstCreateFailsWithoutStorageButQuoteAndReplayStayMetadataOnly() throws Exception {
        var c=setup("PRINT");var row=asset(c);String id=id(create(c,"original"));
        marker("wrong-storage-identity");clearInvocations(remote);data(create(c,"original"));
        var coupon=c.body().remove("userCouponId");quote(c);c.body().set("userCouponId",coupon);verifyNoInteractions(remote);
        expect(create(c,"new"),503,"FILE_STORAGE_UNAVAILABLE");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE student_id=?",Integer.class,c.student().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("PENDING_PAYMENT");expires(row,null);
    }
    @Test void downloadWaitsOutsideTransactionAndRechecksSessionAfterSharedGuard() throws Exception {
        var c=setup("PRINT");var row=asset(c);var guard=content.lock(row.getStorageKey());guard.lock();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var task=pool.submit(()->download(row,c.token()));Thread.sleep(250);
            jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",FileRules.utc(NOW),c.student().id());guard.unlock();
            assertThat(task.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        } finally {if(guard.isHeldByCurrentThread()){guard.unlock();}}
    }
    @Test void s3IoDoesNotHoldMysqlFileRowOrSharedScheduler() throws Exception {
        var c=setup("PRINT");var row=asset(c);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();await(release);return i.callRealMethod();}).when(remote).open(any(),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var download=pool.submit(()->download(row,c.token()));await(entered);
            pool.submit(()->new TransactionTemplate(manager).execute(s->{assertThat(fileRows.lock(row.getId())).isNotNull();return null;})).get(5,TimeUnit.SECONDS);
            release.countDown();assertThat(download.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {release.countDown();}
    }
    @Test void sameFileConcurrentCreatesKeepOneIdempotentOrderAndProtectedContent() throws Exception {
        var c=setup("PRINT");var row=asset(c);var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->{await(start);return create(c,"concurrent");});var second=pool.submit(()->{await(start);return create(c,"concurrent");});start.countDown();
            assertThat(id(first.get(20,TimeUnit.SECONDS))).isEqualTo(id(second.get(20,TimeUnit.SECONDS)));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE student_id=?",Integer.class,c.student().id())).isEqualTo(1);expires(row,null);
        }
    }
    @Test void merchantCancellationDoesNotWaitForAuthorizedDownloadAndNextDownloadIsDenied() throws Exception {
        var c=setup("PRINT");var row=asset(c);String id=id(create(c,"download-cancel"));data(action(c,id,"payments/mock"));String token=login(c.f().owner());
        data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,token,null));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{entered.countDown();await(release);return i.callRealMethod();}).when(remote).open(any(),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var transfer=pool.submit(()->download(row,token));await(entered);
            data(pool.submit(()->call("POST","/api/v1/merchant/orders/"+id+"/cancel",Map.of("reason","无法履约"),token,null)).get(5,TimeUnit.SECONDS));
            release.countDown();assertThat(transfer.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(download(row,token).statusCode()).isEqualTo(404);
        } finally {release.countDown();}
    }
    @Test void twentyMiBActualPdfPassesLocalParsingAndFullRemotePublicationVerification() throws Exception {
        var student=user("STUDENT");login(student);byte[] pdf=PdfSamples.sized(20971520);
        var view=uploads.upload(principal(student),new MockMultipartFile("file","large.pdf","application/pdf",pdf));
        assertThat(view.byteSize()).isEqualTo(20971520L);var row=fileRows.selectById(Long.parseLong(view.id()));
        try(var stream=content.open(row)){assertThat(stream.readAllBytes()).isEqualTo(pdf);}
    }
    @Test void switchingNewUploadTargetToLocalDoesNotRelocateExistingS3File() throws Exception {
        var c=setup("PRINT");var previous=asset(c);var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("remote",remote);
        var localTarget=new FileStorage(disk,beans.getBeanProvider(S3FileStorage.class),"LOCAL");
        var uploader=new FileUploadService(disk,localTarget,new PdfInspector(),fileTransactions,cleanupJob);
        byte[] pdf=PdfSamples.pdf(1);clearInvocations(remote);
        var next=uploader.upload(principal(c.student()),new MockMultipartFile("file","local.pdf","application/pdf",pdf));verifyNoInteractions(remote);
        var localRow=fileRows.selectById(Long.parseLong(next.id()));assertThat(localRow.getStorageKind()).isEqualTo("LOCAL");
        assertThat(fileRows.selectById(previous.getId()).getStorageKind()).isEqualTo("S3");
        try(var input=localTarget.open(previous)){assertThat(input.readAllBytes()).hasSize(previous.getByteSize().intValue());}
        try(var input=content.open(localRow)){assertThat(input.readAllBytes()).isEqualTo(pdf);}
        assertThat(download(localRow,c.token()).statusCode()).isEqualTo(200);
    }
    @Test void configuredButUnreachableS3DoesNotPreventMysqlJarStartup() throws Exception {
        int port;try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var log=Path.of(".cache/s3-startup-"+UUID.randomUUID()+".log").toAbsolutePath();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",Path.of("target/campuslife-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),
                "--server.address=127.0.0.1","--server.port="+port,"--campuslife.files.root="+disk.root(),"--campuslife.files.cleanup-enabled=false",
                "--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=false","--campuslife.coupon.expiry-enabled=false");
        var environment=builder.environment();environment.put("FILE_S3_ENABLED","true");environment.put("FILE_UPLOAD_TARGET","LOCAL");
        environment.put("FILE_S3_ENDPOINT","http://127.0.0.1:1");environment.put("FILE_S3_BUCKET",fixture("BUCKET"));environment.put("FILE_S3_NAMESPACE",fixture("NAMESPACE"));
        environment.put("FILE_S3_ACCESS_KEY",fixture("APP_KEY"));environment.put("FILE_S3_SECRET_KEY",fixture("APP_SECRET"));
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);boolean healthy=false;
            while(System.nanoTime()<end&&process.isAlive()) {
                try {var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/system/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding());healthy=response.statusCode()==200;if(healthy){break;}}
                catch(IOException ex){}Thread.sleep(100);
            }
            assertThat(healthy).as("S3 unreachability must not block ordinary startup; diagnostics %s",log).isTrue();
        } finally {process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
    }
}
