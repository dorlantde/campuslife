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

/** Shared owned-fixture support; no tests or production hooks. */
abstract class S3Fixture extends OutboxOrderFixture {
    @Autowired FileTransactions fileTransactions;
    @Autowired FileCleanup cleanupJob;
    @Autowired FileStorage content;
    @MockitoSpyBean S3FileStorage remote;
    @MockitoSpyBean FileAssetMapper fileRows;
    S3Client admin;
    static String fixture(String name) { return Objects.requireNonNull(System.getenv("S3_PROBE_"+name)); }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("campuslife.files.s3.enabled",()->"true");r.add("campuslife.files.upload-target",()->"S3");
        r.add("campuslife.files.s3.endpoint",()->fixture("ENDPOINT"));r.add("campuslife.files.s3.region",()->"us-east-1");
        r.add("campuslife.files.s3.bucket",()->fixture("BUCKET"));r.add("campuslife.files.s3.namespace",()->fixture("NAMESPACE"));
        r.add("campuslife.files.s3.access-key",()->fixture("APP_KEY"));r.add("campuslife.files.s3.secret-key",()->fixture("APP_SECRET"));
    }
    @BeforeAll void initializeOwnedBucket() throws Exception {
        String identity=fixture("IDENTITY"),name=fixture("NAME");
        assertThat(identity).matches("[a-f0-9]{32}");assertThat(name).isEqualTo("campuslife-s3-test-"+identity);
        var command=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",name).redirectErrorStream(true).start();
        assertThat(command.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(command.exitValue()).isZero();
        var labels=json.readTree(new String(command.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        assertThat(labels.path("campuslife.s3.identity").asString()).isEqualTo(identity);assertThat(labels.path("campuslife.s3.kind").asString()).isEqualTo("test");
        admin=adminClient(fixture("ENDPOINT"));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);boolean ready=false;
        while(System.nanoTime()<end) { try { admin.listBuckets();ready=true;break; } catch(Exception ex) { Thread.sleep(200); } }
        assertThat(ready).isTrue();admin.createBucket(b->b.bucket(fixture("BUCKET")));marker(fixture("NAMESPACE"));
        String bucket=fixture("BUCKET"),ns=fixture("NAMESPACE");
        String policy=json.writeValueAsString(Map.of("Version","2012-10-17","Statement",List.of(
                Map.of("Effect","Deny","Principal","*","Action","s3:DeleteObject","Resource",List.of("arn:aws:s3:::"+bucket+"/"+ns+"/objects/","arn:aws:s3:::"+bucket+"/"+ns+"/objects","arn:aws:s3:::"+bucket+"/"+ns+"/")),
                Map.of("Effect","Deny","Principal","*","Action","s3:GetObject","Resource","arn:aws:s3:::"+bucket+"/_storage-id?*"))));
        admin.putBucketPolicy(b->b.bucket(bucket).policy(policy));
    }
    S3Client adminClient(String endpoint) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(fixture("ADMIN_KEY"),fixture("ADMIN_SECRET"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .httpClientBuilder(Apache5HttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5))
                        .proxyConfiguration(software.amazon.awssdk.http.apache5.ProxyConfiguration.builder().useEnvironmentVariableValues(false).useSystemPropertyValues(false).build()))
                .overrideConfiguration(b->b.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
    }
    @BeforeEach void assertIoOutsideSql() throws Exception {
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(remote).put(any(),any());
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(remote).open(any(),anyLong());
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(remote).delete(any());
    }
    @AfterEach void removeOwnedObjects() throws Exception {
        reset(remote,fileRows);marker(fixture("NAMESPACE"));
        for(long account:accountIds) { for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=? AND storage_kind='S3' AND storage_id=?",account,fixture("NAMESPACE"))) {
            admin.deleteObject(b->b.bucket(fixture("BUCKET")).key(fixture("NAMESPACE")+"/objects/"+row.get("storage_key")));
        } }
    }
    @AfterAll void closeAdmin() { if(admin!=null){admin.close();} }
    void marker(String value) { admin.putObject(b->b.bucket(fixture("BUCKET")).key("_storage-id"),RequestBody.fromString(value)); }
    FileAssetEntity asset(Case c) { return fileRows.selectById(Long.parseLong(c.body().path("fileId").asString())); }
    String object(FileAssetEntity row) { return fixture("NAMESPACE")+"/objects/"+row.getStorageKey(); }
    void put(FileAssetEntity row,byte[] bytes) { admin.putObject(b->b.bucket(fixture("BUCKET")).key(object(row)),RequestBody.fromBytes(bytes)); }
    void missing(FileAssetEntity row) { assertThatThrownBy(()->admin.headObject(b->b.bucket(fixture("BUCKET")).key(object(row))))
            .isInstanceOfSatisfying(S3Exception.class,e->assertThat(e.statusCode()).isEqualTo(404)); }
    HttpResponse<byte[]> download(FileAssetEntity row,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/files/"+row.getId()+"/content")).timeout(Duration.ofSeconds(40));
        if(token!=null){b.header("Authorization","Bearer "+token);}return http.send(b.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    void expires(FileAssetEntity row,Instant expected) { assertThat(fileRows.selectById(row.getId()).getExpiresAt()).isEqualTo(expected==null?null:FileRules.utc(expected)); }
    void drain() { for(int i=0;i<4;i++){cleanupJob.runOnce();} }

}
