package com.campuslife.file.storage;

import com.campuslife.file.config.S3StorageProperties;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.ChecksumMode;

/** Small synchronous S3 subset. Construction performs no network requests or bucket initialization. */
public class S3FileStorage implements AutoCloseable {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(S3FileStorage.class);
    private final S3StorageProperties settings;
    private final S3Client client;
    public S3FileStorage(S3StorageProperties settings) {
        this.settings=settings;
        client=S3Client.builder().endpointOverride(URI.create(settings.endpoint())).region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(),settings.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(4).connectionTimeout(Duration.ofSeconds(2))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5))
                        .proxyConfiguration(software.amazon.awssdk.http.apache5.ProxyConfiguration.builder()
                                .useSystemPropertyValues(false).useEnvironmentVariableValues(false).build()))
                .overrideConfiguration(b->b.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build())).build();
    }
    public String namespace() { return settings.namespace(); }
    private String key(FileLocation location) throws IOException {
        outsideTransaction();
        if (!"S3".equals(location.kind())||!namespace().equals(location.namespace())) { throw new IOException("Unmanaged S3 location"); }
        return namespace()+"/objects/"+location.key();
    }
    private static void outsideTransaction() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IOException("Storage IO inside SQL transaction rejected"); }
    }
    private void identity() throws IOException {
        outsideTransaction();
        // Recheck each operation: restoring or accidentally redirecting a backend must fail closed.
        try {
            var stream=client.getObject(b->b.bucket(settings.bucket()).key("_storage-id"));
            try {
                var bytes=stream.readNBytes(37);
                if (!namespace().equals(new String(bytes,StandardCharsets.US_ASCII))) { log.warn("S3 storage identity mismatch; operation rejected");throw new IOException("S3 identity mismatch"); }
            } finally { stream.abort(); } // Never drain an unexpectedly large marker while rejecting its identity.
        } catch (RuntimeException ex) { log.warn("S3 storage identity unavailable; operation rejected");throw new IOException("S3 identity unavailable"); }
    }
    public void put(FileLocation location,LocalFileStorage.Received received) throws IOException {
        String target=key(location);identity();
        try { client.putObject(b->b.bucket(settings.bucket()).key(target).contentType("application/pdf"),RequestBody.fromFile(received.path())); }
        catch (RuntimeException ex) { throw new IOException("S3 write unavailable"); }
    }
    public InputStream open(FileLocation location,long expectedBytes) throws IOException {
        String target=key(location);identity();
        try {
            var stream=client.getObject(b->b.bucket(settings.bucket()).key(target).checksumMode(ChecksumMode.ENABLED));
            if (stream.response().contentLength()==null || stream.response().contentLength()!=expectedBytes) {
                stream.abort();throw new IOException("S3 length mismatch");
            }
            // SDK exceptions may also happen during stream consumption. Preserve the servlet's IOException boundary.
            return new FilterInputStream(stream) {
                @Override public int read() throws IOException { try { return super.read(); } catch(RuntimeException ex) { throw new IOException("S3 stream interrupted"); } }
                @Override public int read(byte[] b,int o,int n) throws IOException { try { return super.read(b,o,n); } catch(RuntimeException ex) { throw new IOException("S3 stream interrupted"); } }
                @Override public void close() throws IOException { stream.abort(); }
            };
        } catch (RuntimeException ex) { throw new IOException("S3 read unavailable"); }
    }
    public void delete(FileLocation location) throws IOException {
        String target=key(location);identity();
        try { client.deleteObject(b->b.bucket(settings.bucket()).key(target)); }
        catch (RuntimeException ex) { throw new IOException("S3 delete unavailable"); }
    }
    @Override public void close() { client.close(); }
}
