package com.campuslife.messaging.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long ROUND_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long CLOSE_NANOS = TimeUnit.SECONDS.toNanos(8);
    private final OutboxTransactions transactions;
    private final ObjectProvider<RabbitOutboxTransport> transport;
    private final boolean enabled;
    private final boolean scheduling;
    private final int batchSize;
    private final LongSupplier roundTicker;
    private final ReentrantLock running = new ReentrantLock();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean roundRunning = new AtomicBoolean();
    private final ThreadPoolExecutor worker;

    @Autowired
    public OutboxPublisher(OutboxTransactions transactions, ObjectProvider<RabbitOutboxTransport> transport,
            @Value("${campuslife.messaging.enabled:false}") boolean enabled,
            @Value("${campuslife.messaging.scheduling-enabled:true}") boolean scheduling,
            @Value("${campuslife.messaging.publisher-batch-size:32}") int batchSize) {
        this(transactions, transport, enabled, scheduling, batchSize, System::nanoTime);
    }

    // Controls round boundaries in tests, never business dates or the real close budget.
    OutboxPublisher(OutboxTransactions transactions, ObjectProvider<RabbitOutboxTransport> transport,
            boolean enabled, boolean scheduling, int batchSize, LongSupplier roundTicker) {
        if (batchSize < 1 || batchSize > 32) {
            throw new IllegalArgumentException("Publisher batch size must be between 1 and 32");
        }
        this.transactions = transactions;
        this.transport = transport;
        this.enabled = enabled;
        this.scheduling = scheduling;
        this.batchSize = batchSize;
        this.roundTicker = roundTicker;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon(true).name("outbox-publisher-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void scheduled() {
        if (!enabled || !scheduling || stopping.get() || !roundRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    long started = roundTicker.getAsLong();
                    for (int attempt = 0; attempt < batchSize; attempt++) {
                        if (stopping.get() || Thread.currentThread().isInterrupted()
                                || roundTicker.getAsLong() - started >= ROUND_NANOS) {
                            break;
                        }
                        if (attemptOne() != Attempt.ACKED) {
                            break;
                        }
                    }
                } catch (RuntimeException ex) {
                    // Unknown commit/result cannot authorize clearing or recycling a durable lease.
                    log.warn("Outbox round deferred: {}", ex.getClass().getSimpleName());
                } finally {
                    roundRunning.set(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            // Busy handoff or close: no caller-thread IO and no queued task. Durable rows remain due.
            roundRunning.set(false);
        }
    }

    /** Synchronous single attempt; true means attempted, not necessarily SENT or consumed. */
    public boolean publishOne() {
        return attemptOne() != Attempt.NONE;
    }

    private enum Attempt { NONE, ACKED, ENDED }

    private Attempt attemptOne() {
        if (!enabled || stopping.get() || !running.tryLock()) {
            return Attempt.NONE;
        }
        try {
            if (stopping.get()) {
                return Attempt.NONE;
            }
            var sender = transport.getIfAvailable();
            if (sender == null) {
                return Attempt.NONE;
            }
            // Already entering claim is the in-flight attempt, even if close arrives during SQL.
            var row = transactions.claim();
            if (row == null) {
                return Attempt.NONE;
            }
            var outcome = sender.publish(row);
            boolean saved = transactions.finish(row, outcome);
            log.info("Outbox message={} attempt={} result={} leaseUpdated={}",
                    row.messageId(), row.attempts(), outcome, saved);
            return outcome == OutboxTransactions.Outcome.ACK && saved ? Attempt.ACKED : Attempt.ENDED;
        } finally {
            running.unlock();
        }
    }

    @PreDestroy
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + CLOSE_NANOS;
        worker.shutdown();
        try {
            boolean idle = running.tryLock(remaining(deadline), TimeUnit.NANOSECONDS);
            if (idle) {
                running.unlock();
            }
            boolean ended = worker.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!idle || !ended) {
                log.warn("Outbox shutdown reached shared bounded wait; lease remains recoverable");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            if (!worker.isTerminated()) {
                worker.shutdownNow();
            }
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }
}
