package com.campuslife.file.service;

import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.storage.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class FileCleanup {
    private static final Logger log=LoggerFactory.getLogger(FileCleanup.class);
    private final FileAssetMapper files;
    private final FileTransactions transactions;
    private final LocalFileStorage staging;
    private final FileStorage storage;
    private final Clock clock;
    private final boolean enabled;
    private final ReentrantLock running=new ReentrantLock();
    private final AtomicBoolean stopping=new AtomicBoolean(),submitted=new AtomicBoolean();
    private final Map<String,Long> cursors=new HashMap<>();
    private int turn;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),Thread.ofPlatform().daemon(true).name("file-cleanup-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public FileCleanup(FileAssetMapper files,FileTransactions transactions,LocalFileStorage staging,FileStorage storage,Clock clock,
            @Value("${campuslife.files.cleanup-enabled:true}") boolean enabled) {
        this.files=files;this.transactions=transactions;this.staging=staging;this.storage=storage;this.clock=clock;this.enabled=enabled;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { wake(); }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void scheduled() { wake(); }
    private void wake() {
        if(!enabled||stopping.get()||!submitted.compareAndSet(false,true)) { return; }
        try { worker.execute(()->{try{runOnce();}finally{submitted.set(false);}}); }
        catch(RejectedExecutionException ex) { submitted.set(false); }
    }
    private record Scan(FileStorage.Namespace namespace,boolean tombstones) {}
    /** Explicit synchronous maintenance hook; scheduler never performs storage IO itself. */
    public void runOnce() {
        if(stopping.get()||!running.tryLock()) { return; }
        long began=System.nanoTime();
        try {
            var scans=new ArrayList<Scan>();
            for(var namespace:storage.namespaces()) { scans.add(new Scan(namespace,false));if(namespace.kind().equals("S3")){scans.add(new Scan(namespace,true));} }
            int start=Math.floorMod(turn++,scans.size()),due=0,tombstones=0;
            for(int i=0;i<scans.size();i++) {
                if(ended(began)) { break; }
                var scan=scans.get((start+i)%scans.size());
                try {
                    if(scan.tombstones()) { tombstones+=grave(scan.namespace(),began,100-tombstones); }
                    else { due+=expired(scan.namespace(),began,100-due); }
                } catch(RuntimeException ex) { log.warn("File cleanup namespace scan will retry"); }
            }
            try { staging.cleanTemporary(); } catch(IOException|RuntimeException ex) { log.warn("Temporary file cleanup will retry"); }
        } catch(RuntimeException ex) { log.warn("File cleanup scan will retry"); }
        finally { running.unlock(); }
    }
    private boolean ended(long start) { return stopping.get()||Thread.currentThread().isInterrupted()||System.nanoTime()-start>=TimeUnit.SECONDS.toNanos(1); }
    private int expired(FileStorage.Namespace namespace,long start,int remaining) {
        String cursorKey="due:"+namespace.id();long cursor=cursors.getOrDefault(cursorKey,0L);
        var candidates=files.due(namespace.kind(),namespace.id(),FileRules.utc(clock.instant()),cursor);
        if(candidates.isEmpty()&&cursor!=0) { cursors.put(cursorKey,0L);candidates=files.due(namespace.kind(),namespace.id(),FileRules.utc(clock.instant()),0); }
        int count=0;
        for(var candidate:candidates) {
            if(count>=remaining||ended(start)) { break; }
            // Advance on IO failure/busy guard too, so one bad object cannot starve later rows.
            cursors.put(cursorKey,candidate.id());count++;
            var lock=storage.lock(candidate.storageKey());if(!lock.tryLock()) { continue; }
            try { remove(candidate.id(),false); } catch(RuntimeException ex) { log.warn("File cleanup will retry id={}",candidate.id()); }
            finally { lock.unlock(); }
        }
        return count;
    }
    private int grave(FileStorage.Namespace namespace,long start,int remaining) {
        String cursorKey="grave:"+namespace.id();long cursor=cursors.getOrDefault(cursorKey,0L);
        var candidates=files.tombstones(namespace.id(),cursor);
        if(candidates.isEmpty()&&cursor!=0) { cursors.put(cursorKey,0L);candidates=files.tombstones(namespace.id(),0); }
        int count=0;
        for(var candidate:candidates) {
            if(count>=remaining||ended(start)) { break; }
            cursors.put(cursorKey,candidate.id());count++;
            var lock=storage.lock(candidate.storageKey());if(!lock.tryLock()) { continue; }
            try {
                var row=transactions.tombstone(candidate.id(),namespace.id(),candidate.version());
                if(row!=null&&candidate.storageKey().equals(row.getStorageKey())) { storage.delete(row); }
            } catch(IOException|RuntimeException ex) { log.warn("S3 tombstone cleanup will retry id={}",candidate.id()); }
            finally { lock.unlock(); }
        }
        return count;
    }
    // Caller holds the publication guard; current DB facts protect even an unknown publish commit.
    public void remove(long id,boolean failedUpload) {
        var located=files.selectById(id);if(located==null) { return; }
        if(storage.namespaces().stream().noneMatch(n->n.kind().equals(located.getStorageKind())&&n.id().equals(located.getStorageId()))) { return; }
        var row=transactions.markDeleting(id,located.getStorageKind(),located.getStorageId(),failedUpload);if(row==null) { return; }
        try { storage.delete(row); } catch(IOException ex) { throw FileRules.unavailable(); }
        transactions.finishDeleting(row);
    }
    @PreDestroy
    public void stop() {
        if(!stopping.compareAndSet(false,true)) { return; }
        worker.shutdown();
        try { if(!worker.awaitTermination(8,TimeUnit.SECONDS)) { worker.shutdownNow(); } }
        catch(InterruptedException ex) { worker.shutdownNow();Thread.currentThread().interrupt(); }
    }
}
