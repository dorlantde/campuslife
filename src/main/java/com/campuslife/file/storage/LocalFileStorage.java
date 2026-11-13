package com.campuslife.file.storage;

import com.campuslife.file.service.FileRules;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class LocalFileStorage {
    public record Received(Path path,long bytes,String sha256) {}
    private final Path root,objects,temporary,multipart;
    private final Clock clock;
    private final Set<Path> activeTemporary=new HashSet<>();
    private final FileGuards guards;
    private String storageId;
    public LocalFileStorage(@Value("${campuslife.files.root}") String directory,Clock clock) {
        this(directory,clock,new FileGuards());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LocalFileStorage(@Value("${campuslife.files.root}") String directory,Clock clock,FileGuards guards) {
        this.root=Path.of(directory).toAbsolutePath().normalize();this.clock=clock;
        this.guards=guards;
        objects=root.resolve("objects");temporary=root.resolve("temporary");multipart=root.resolve("multipart");
    }
    public synchronized String storageId() { initialize();return storageId; }
    public Path root() { return root; }
    public ReentrantLock lock(String key) { return guards.lock(key); }
    public synchronized Path multipartDirectory() { initialize();return multipart; }
    private void initialize() {
        if(storageId!=null){return;}
        try {
            ensureDirectory(root);ensureDirectory(objects);ensureDirectory(temporary);ensureDirectory(multipart);
            Path marker=root.resolve(".storage-id");
            if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) {
                try { Files.writeString(marker,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
                catch(FileAlreadyExistsException ignored) { }
            }
            safe(marker);var id=Files.readString(marker).strip();
            if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")){throw new IOException("Invalid storage marker");}
            // Called before this context opens its HTTP listener. Only container-generated leftovers are removed.
            try(var entries=Files.newDirectoryStream(multipart)) {
                for(Path path:entries){if(path.getFileName().toString().matches("upload_[A-Za-z0-9_-]+[.]tmp")){safe(path);if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){Files.deleteIfExists(path);}}}
            }
            try(var entries=Files.newDirectoryStream(temporary)) {
                for(Path path:entries){if(path.getFileName().toString().matches("PDFBox[0-9]+[.]tmp")){safe(path);if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){Files.deleteIfExists(path);}}}
            }
            storageId=id;
        }catch(IOException ex){throw FileRules.unavailable();}
    }
    private static void ensureDirectory(Path path)throws IOException {
        safe(path);Files.createDirectories(path);safe(path);
    }
    // NOFOLLOW attributes also reject Windows junction/reparse directory entries (isOther).
    private static void safe(Path path)throws IOException {
        Path absolute=path.toAbsolutePath().normalize(),cursor=absolute.getRoot();
        for(Path part:absolute){
            cursor=cursor.resolve(part);
            if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isSymbolicLink()||a.isOther()){throw new IOException("Linked storage path rejected");}
            }
        }
    }
    private Path objectPath(String key)throws IOException {
        initialize();if(!key.matches("[a-f0-9]{32}[.]pdf")){throw new IOException("Invalid storage key");}
        Path path=objects.resolve(key).normalize();if(!path.getParent().equals(objects)){throw new IOException("Invalid storage parent");}
        safe(path);return path;
    }
    public Received receive(InputStream input)throws IOException {
        Path path;
        synchronized(this){initialize();safe(temporary);path=temporary.resolve(UUID.randomUUID().toString().replace("-","")+".part");safe(path);Files.createFile(path);activeTemporary.add(path);}
        try {
            MessageDigest hash;
            try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
            long bytes=0;
            try(var output=Files.newOutputStream(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){bytes+=n;if(bytes>FileRules.MAX_BYTES){throw FileRules.tooLarge();}output.write(buffer,0,n);hash.update(buffer,0,n);}
            }
            if(bytes==0){throw FileRules.invalid();}
            return new Received(path,bytes,HexFormat.of().formatHex(hash.digest()));
        }catch(IOException|RuntimeException ex){try{releaseTemporary(path);}catch(IOException cleanup){ex.addSuppressed(cleanup);}throw ex;}
    }
    public void move(Received received,String key)throws IOException {
        Path source=received.path().toAbsolutePath().normalize();
        synchronized(this){if(!activeTemporary.contains(source)){throw new IOException("Inactive upload");}}
        if(!source.getParent().equals(temporary)){throw new IOException("Invalid temporary parent");}
        safe(source);Path target=objectPath(key);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){throw new IOException("Object already exists");}
        try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ex){Files.move(source,target);}
    }
    public void delete(String namespace,String key)throws IOException {
        if(!storageId().equals(namespace)){throw new IOException("Different storage namespace");}
        Path path=objectPath(key);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){throw new IOException("Not a regular object");}
        Files.deleteIfExists(path);
    }
    /** Caller holds the file guard until this stream is closed. */
    public InputStream open(String namespace,String key,long expectedBytes)throws IOException {
        if(!storageId().equals(namespace)){throw new IOException("Different storage namespace");}
        Path path=objectPath(key);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){throw new IOException("Not a regular object");}
        var channel=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
        try {
            if(channel.size()!=expectedBytes){throw new IOException("Object length mismatch");}
            return java.nio.channels.Channels.newInputStream(channel);
        }catch(IOException|RuntimeException ex){channel.close();throw ex;}
    }
    public synchronized void releaseTemporary(Path path)throws IOException {
        try {
            if(!path.toAbsolutePath().normalize().getParent().equals(temporary)){throw new IOException("Invalid temporary parent");}
            safe(path);Files.deleteIfExists(path);
        }finally{activeTemporary.remove(path);}
    }
    public synchronized void cleanTemporary()throws IOException {
        initialize();safe(temporary);int count=0;
        try(var entries=Files.newDirectoryStream(temporary)) {
            for(Path path:entries) {
                if(count>=100){break;}
                if(!path.getFileName().toString().matches("[a-f0-9]{32}[.]part")||activeTemporary.contains(path)){continue;}
                safe(path);
                if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(clock.instant().minusSeconds(1800))){Files.deleteIfExists(path);count++;}
            }
        }
    }
}
