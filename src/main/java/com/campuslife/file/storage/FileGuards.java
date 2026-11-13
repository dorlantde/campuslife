package com.campuslife.file.storage;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** One instance-wide guard set for LOCAL and S3. Collisions only serialize unrelated files. */
@Component
@Profile("mysql")
public class FileGuards {
    private final ReentrantLock[] locks = new ReentrantLock[64];
    public FileGuards() { for (int i=0;i<locks.length;i++) { locks[i]=new ReentrantLock(); } }
    public ReentrantLock lock(String key) { return locks[(key.hashCode()&Integer.MAX_VALUE)%locks.length]; }
}
