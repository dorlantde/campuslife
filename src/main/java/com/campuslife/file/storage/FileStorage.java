package com.campuslife.file.storage;

import com.campuslife.file.entity.FileAssetEntity;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Routing by persisted location, never fallback. Local storage also remains the safe upload staging area. */
@Component
@Profile("mysql")
public class FileStorage {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FileStorage.class);
    private final LocalFileStorage local;
    private final S3FileStorage s3;
    private final String uploadKind;
    public FileStorage(LocalFileStorage local,ObjectProvider<S3FileStorage> remote,
            @Value("${campuslife.files.upload-target:LOCAL}") String uploadKind) {
        this.local=local;this.s3=remote.getIfAvailable();this.uploadKind=uploadKind;
        if (!("LOCAL".equals(uploadKind)||"S3".equals(uploadKind)) || "S3".equals(uploadKind)&&s3==null) {
            throw new IllegalArgumentException("Upload target requires an enabled backend");
        }
        if (s3!=null&&s3.namespace().equals(local.storageId())) { throw new IllegalArgumentException("Storage namespaces must differ"); }
    }
    public FileLocation uploadLocation(String key) {
        return new FileLocation(uploadKind,"LOCAL".equals(uploadKind)?local.storageId():s3.namespace(),key);
    }
    public record Namespace(String kind,String id) {}
    public List<Namespace> namespaces() {
        var values=new ArrayList<Namespace>();values.add(new Namespace("LOCAL",local.storageId()));
        if(s3!=null) { values.add(new Namespace("S3",s3.namespace())); } return List.copyOf(values);
    }
    public ReentrantLock lock(String key) { return local.lock(key); }
    public void write(FileAssetEntity row,LocalFileStorage.Received received) throws IOException {
        var location=FileLocation.of(row);check(location);
        if ("LOCAL".equals(location.kind())) { local.move(received,location.key()); }
        else {
            s3.put(location,received);
            // Full bytes, not ETag/HEAD or self-supplied metadata, establish publication integrity.
            try (var in=open(row)) {
                MessageDigest digest;
                try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
                byte[] buffer=new byte[8192];long bytes=0;int n;
                while((n=in.read(buffer))!=-1) { bytes+=n;if(bytes>received.bytes()) { throw new IOException("S3 object grew"); }digest.update(buffer,0,n); }
                if(bytes!=received.bytes()||!received.sha256().equals(HexFormat.of().formatHex(digest.digest()))) { throw new IOException("S3 content verification failed"); }
            }
        }
    }
    public InputStream open(FileAssetEntity row) throws IOException {
        var location=FileLocation.of(row);check(location);
        return "LOCAL".equals(location.kind())?local.open(location.namespace(),location.key(),row.getByteSize()):s3.open(location,row.getByteSize());
    }
    public void delete(FileAssetEntity row) throws IOException {
        var location=FileLocation.of(row);check(location);
        if("LOCAL".equals(location.kind())) { local.delete(location.namespace(),location.key()); } else { s3.delete(location); }
    }
    private void check(FileLocation location) throws IOException {
        if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IOException("File IO inside SQL transaction rejected"); }
        if("LOCAL".equals(location.kind())) { if(!local.storageId().equals(location.namespace())) { log.warn("Unmanaged LOCAL file namespace rejected");throw new IOException("Unmanaged LOCAL location"); } }
        else if(s3==null||!s3.namespace().equals(location.namespace())) { log.warn("Unconfigured S3 file namespace rejected");throw new IOException("S3 backend not configured"); }
    }
}
