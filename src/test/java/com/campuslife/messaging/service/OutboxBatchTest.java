package com.campuslife.messaging.service;

import com.campuslife.messaging.model.OutboxMessage;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxBatchTest {
    static final class Harness implements AutoCloseable {
        final OutboxTransactions tx = mock(OutboxTransactions.class);
        final RabbitOutboxTransport sender = mock(RabbitOutboxTransport.class);
        final ObjectProvider<RabbitOutboxTransport> provider = mock(ObjectProvider.class);
        final OutboxMessage row = mock(OutboxMessage.class);
        final AtomicInteger claims = new AtomicInteger(), finishes = new AtomicInteger();
        final OutboxPublisher publisher;
        Harness(int size, boolean enabled, boolean scheduling, LongSupplier ticker) {
            when(provider.getIfAvailable()).thenReturn(sender);
            when(tx.claim()).thenAnswer(i -> { claims.incrementAndGet(); return row; });
            when(sender.publish(row)).thenReturn(OutboxTransactions.Outcome.ACK);
            when(tx.finish(any(), any())).thenAnswer(i -> { finishes.incrementAndGet(); return true; });
            publisher = new OutboxPublisher(tx, provider, enabled, scheduling, size, ticker);
        }
        public void close() { publisher.stop(); }
    }

    static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (condition.call()) { return; }
            Thread.sleep(5);
        }
        throw new AssertionError("Publisher did not reach boundary");
    }
    static void idle(Harness h) throws Exception {
        await(() -> !((AtomicBoolean)ReflectionTestUtils.getField(h.publisher, "roundRunning")).get());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 32})
    void aRoundCannotClaimBeyondItsQuota(int size) throws Exception {
        try (var h = new Harness(size, true, true, () -> 0L)) {
            h.publisher.scheduled(); idle(h);
            assertThat(h.claims.get()).isEqualTo(size);
            verify(h.sender, times(size)).publish(h.row);
        }
    }

    @Test void nextTickDrainsRemainingRowsAndEmptyScanEndsTheRound() throws Exception {
        try (var h = new Harness(32, true, true, () -> 0L)) {
            doAnswer(i -> h.claims.incrementAndGet() <= 40 ? h.row : null).when(h.tx).claim();
            h.publisher.scheduled(); idle(h); assertThat(h.finishes.get()).isEqualTo(32);
            await(() -> { if (h.claims.get() > 32) { return true; } h.publisher.scheduled(); return h.claims.get() > 32; });
            idle(h);
            assertThat(h.finishes.get()).isEqualTo(40);
            assertThat(h.claims.get()).isEqualTo(41);
        }
    }

    @ParameterizedTest @ValueSource(longs = {1_000_000_000L, 2_000_000_000L})
    void elapsedRoundBudgetStopsTheNextClaimButFinishesTheCurrentOne(long elapsed) throws Exception {
        var ticker = new AtomicLong();
        try (var h = new Harness(32, true, true, ticker::get)) {
            doAnswer(i -> { ticker.set(elapsed); return OutboxTransactions.Outcome.ACK; }).when(h.sender).publish(h.row);
            h.publisher.scheduled(); idle(h); assertThat(h.finishes.get()).isEqualTo(1);
            assertThat(h.claims.get()).isEqualTo(1);
            verify(h.tx).finish(h.row, OutboxTransactions.Outcome.ACK);
        }
    }

    @Test void shutdownAndBusinessClocksDoNotDependOnTheRoundTicker() throws Exception {
        var ticker = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        try (var h = new Harness(32, true, true, ticker::get)) {
            doAnswer(i -> { ticker.addAndGet(1_000_000_000L); return OutboxTransactions.Outcome.ACK; }).when(h.sender).publish(h.row);
            h.publisher.scheduled(); idle(h); assertThat(h.finishes.get()).isEqualTo(1);
            assertThat(h.claims.get()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"RETURNED", "NACK", "TIMEOUT", "CONNECTION_FAILED", "SHUTDOWN"})
    void aFailedTransportResultEndsTheWaveWithoutTryingAnotherMessage(String outcome) throws Exception {
        try (var h = new Harness(32, true, true, () -> 0L)) {
            var result = OutboxTransactions.Outcome.valueOf(outcome);
            when(h.sender.publish(h.row)).thenReturn(result);
            h.publisher.scheduled(); idle(h);
            assertThat(h.claims.get()).isEqualTo(1); verify(h.tx).finish(h.row, result);
        }
    }

    @Test void lostLeaseEndsTheWaveEvenAfterAck() throws Exception {
        try (var h = new Harness(32, true, true, () -> 0L)) {
            var written = new CountDownLatch(1);
            doAnswer(i -> { written.countDown(); return false; }).when(h.tx).finish(h.row, OutboxTransactions.Outcome.ACK);
            h.publisher.scheduled(); assertThat(written.await(3, TimeUnit.SECONDS)).isTrue(); idle(h);
            assertThat(h.claims.get()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"claim", "send", "finish"})
    void unknownFailuresDoNotRecycleTheLeaseOrStartAnotherAttempt(String stage) throws Exception {
        try (var h = new Harness(32, true, true, () -> 0L)) {
            var failed = new CountDownLatch(1);
            if (stage.equals("claim")) { doAnswer(i -> { h.claims.incrementAndGet(); failed.countDown(); throw new IllegalStateException("unknown claim commit"); }).when(h.tx).claim(); }
            if (stage.equals("send")) { doAnswer(i -> { failed.countDown(); throw new IllegalStateException("unknown send result"); }).when(h.sender).publish(h.row); }
            if (stage.equals("finish")) { doAnswer(i -> { failed.countDown(); throw new IllegalStateException("unknown finish commit"); }).when(h.tx).finish(any(), any()); }
            h.publisher.scheduled(); assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue(); idle(h);
            assertThat(h.claims.get()).isEqualTo(1);
            if (!stage.equals("finish")) { verify(h.tx, never()).finish(any(), any()); }
        }
    }

    @Test void emptyOrMissingTransportDoesNotSpin() throws Exception {
        try (var h = new Harness(32, true, true, () -> 0L)) {
            doAnswer(i -> { h.claims.incrementAndGet(); return null; }).when(h.tx).claim();
            h.publisher.scheduled(); idle(h);
            verifyNoInteractions(h.sender); assertThat(h.claims.get()).isEqualTo(1);
        }
        try (var h = new Harness(32, true, false, () -> 0L)) {
            when(h.provider.getIfAvailable()).thenReturn(null);
            assertThat(h.publisher.publishOne()).isFalse(); verifyNoInteractions(h.tx, h.sender);
        }
    }

    @Test void disabledScheduleAllowsOnlyTheExplicitSingleAttempt() {
        try (var h = new Harness(32, true, false, () -> 0L)) {
            h.publisher.scheduled(); verifyNoInteractions(h.tx, h.sender);
            assertThat(h.publisher.publishOne()).isTrue(); assertThat(h.claims.get()).isEqualTo(1);
        }
        try (var h = new Harness(32, false, true, () -> 0L)) {
            h.publisher.scheduled(); assertThat(h.publisher.publishOne()).isFalse();
            verifyNoInteractions(h.tx, h.sender, h.provider);
        }
    }

    @Test void aSingleCallStillReturnsTrueForAnAttemptThatWasNotSent() {
        try (var h = new Harness(32, true, false, () -> 0L)) {
            when(h.sender.publish(h.row)).thenReturn(OutboxTransactions.Outcome.NACK);
            assertThat(h.publisher.publishOne()).isTrue(); assertThat(h.claims.get()).isEqualTo(1);
        }
    }

    @Test void busyWorkerDoesNotQueueTicksOrExecuteIoOnTheSchedulerThread() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var thread = new AtomicReference<Thread>();
        try (var h = new Harness(32, true, true, () -> 0L)) {
            doAnswer(i -> h.claims.incrementAndGet() == 1 ? h.row : null).when(h.tx).claim();
            doAnswer(i -> { thread.set(Thread.currentThread()); entered.countDown(); release.await(10, TimeUnit.SECONDS); return OutboxTransactions.Outcome.ACK; }).when(h.sender).publish(h.row);
            try {
                long start = System.nanoTime(); h.publisher.scheduled(); assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                for (int i=0;i<100;i++) { h.publisher.scheduled(); }
                assertThat(h.publisher.publishOne()).isFalse(); assertThat(h.claims.get()).isEqualTo(1);
                assertThat(thread.get()).isNotSameAs(Thread.currentThread()); assertThat(thread.get().isDaemon()).isTrue();
                release.countDown(); await(() -> h.claims.get() == 2); h.publisher.stop();
                assertThat(h.claims.get()).isEqualTo(2);
            } finally { release.countDown(); }
        }
    }

    @Test void closeUsesOneEightSecondBudgetAndDoesNotExtendOnSecondStop() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1); var interrupted = new AtomicBoolean();
        try (var h = new Harness(32, true, true, () -> 0L)) {
            doAnswer(i -> {
                entered.countDown();
                while (release.getCount()>0) { try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException ex) { interrupted.set(true); } }
                return OutboxTransactions.Outcome.ACK;
            }).when(h.sender).publish(h.row);
            doAnswer(i -> { finished.countDown(); return true; }).when(h.tx).finish(any(), any());
            try {
                h.publisher.scheduled(); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime(); h.publisher.stop();
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofMillis(7800),Duration.ofSeconds(11));
                start=System.nanoTime(); h.publisher.stop(); assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
                h.publisher.scheduled(); assertThat(h.publisher.publishOne()).isFalse(); assertThat(h.claims.get()).isEqualTo(1);
                await(interrupted::get); release.countDown(); assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(h.claims.get()).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @Test void aClaimEnteredBeforeCloseMayCommitAndFinishButNoNextClaimStarts() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var h = new Harness(32, true, true, () -> 0L); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            doAnswer(i -> { h.claims.incrementAndGet(); entered.countDown(); release.await(5,TimeUnit.SECONDS); return h.row; }).when(h.tx).claim();
            h.publisher.scheduled(); assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var closing=pool.submit(h.publisher::stop);
            await(()->((AtomicBoolean)ReflectionTestUtils.getField(h.publisher,"stopping")).get());
            release.countDown(); closing.get(3,TimeUnit.SECONDS); assertThat(h.claims.get()).isEqualTo(1);
            verify(h.tx).finish(h.row, OutboxTransactions.Outcome.ACK);
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "33", "word", "1.5"})
    void invalidBatchConfigurationFailsStartup(String value) {
        context().withPropertyValues("campuslife.messaging.publisher-batch-size="+value).run(c -> {
            assertThat(c).hasFailed(); assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }
    @Test void defaultBatchConfigurationBindsWithoutStartingThreadsOrIo() {
        context().run(c -> { assertThat(c).hasNotFailed(); assertThat(c.getBean(OutboxPublisher.class).publishOne()).isFalse(); });
    }
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withInitializer(c -> c.getEnvironment().setActiveProfiles("mysql", "outbox-batch-unit"))
                .withUserConfiguration(Wiring.class, OutboxPublisher.class)
                .withPropertyValues("campuslife.messaging.enabled=false");
    }
    @TestConfiguration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("outbox-batch-unit")
    static class Wiring {
        @Bean OutboxTransactions transactions() { return mock(OutboxTransactions.class); }
    }
}
