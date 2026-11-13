package com.campuslife.file;

import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FileCleanupWorkerTest {
    @Test void scheduledCallbacksDoNotWaitOrQueueWhileStorageIsBlocked() throws Exception {
        var rows=mock(FileAssetMapper.class);var tx=mock(FileTransactions.class);var local=mock(LocalFileStorage.class);var storage=mock(FileStorage.class);
        String ns="12345678-1234-1234-1234-123456789abc",key="a".repeat(32)+".pdf";
        when(storage.namespaces()).thenReturn(List.of(new FileStorage.Namespace("S3",ns)));
        when(rows.due(eq("S3"),eq(ns),any(),anyLong())).thenReturn(List.of(new FileAssetMapper.Candidate(1,key)));
        when(storage.lock(key)).thenReturn(new ReentrantLock());var row=new FileAssetEntity();row.setId(1L);row.setStorageKind("S3");row.setStorageId(ns);row.setStorageKey(key);
        when(rows.selectById(1L)).thenReturn(row);when(tx.markDeleting(1,"S3",ns,false)).thenReturn(row);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return null;}).when(storage).delete(row);
        var job=new FileCleanup(rows,tx,local,storage,Clock.systemUTC(),true);
        try {
            job.startup();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();long start=System.nanoTime();
            for(int n=0;n<50;n++){job.scheduled();}job.runOnce();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(500));
            verify(rows,times(1)).due(eq("S3"),eq(ns),any(),anyLong());verify(storage,times(1)).delete(row);
            release.countDown();
        } finally {release.countDown();job.stop();}
        verify(tx,times(1)).finishDeleting(row);
    }
}
