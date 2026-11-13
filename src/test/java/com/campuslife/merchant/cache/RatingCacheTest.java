package com.campuslife.merchant.cache;

import java.math.BigInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RatingCacheTest {
    private final RatingCacheKey key = new RatingCacheKey(42, 7);
    private final RatingStats stats = new RatingStats(BigInteger.valueOf(9), 2);
    private final RatingCacheCodec codec = new RatingCacheCodec(new ObjectMapper());

    @Test void exactStatisticsRoundTripAndOriginalDecimalRounding() {
        assertThat(codec.decode(key, codec.encode(key, stats))).isEqualTo(stats);
        assertThat(new RatingStats(BigInteger.valueOf(601), 200).average(42)).isEqualTo(3.01);
        var empty = new RatingStats(BigInteger.ZERO, 0);
        assertThat(codec.decode(key, codec.encode(key, empty)).average(42)).isNull();
        assertThat(new RatingStats(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(5)), Long.MAX_VALUE).average(42)).isEqualTo(5);
    }
    @ParameterizedTest @ValueSource(strings = {
        "{}", "null", "[]", "broken", "{\"schema\":2,\"shopId\":\"42\",\"revision\":\"7\",\"ratingSum\":\"9\",\"reviewCount\":\"2\"}",
        "{\"schema\":1,\"shopId\":\"43\",\"revision\":\"7\",\"ratingSum\":\"9\",\"reviewCount\":\"2\"}",
        "{\"schema\":1,\"shopId\":\"42\",\"revision\":\"8\",\"ratingSum\":\"9\",\"reviewCount\":\"2\"}",
        "{\"schema\":1,\"shopId\":\"42\",\"revision\":\"7\",\"ratingSum\":\"11\",\"reviewCount\":\"2\"}",
        "{\"schema\":1,\"shopId\":\"42\",\"revision\":\"7\",\"ratingSum\":\"0\",\"reviewCount\":\"1\"}",
        "{\"schema\":1,\"shopId\":\"42\",\"revision\":\"7\",\"ratingSum\":\"01\",\"reviewCount\":\"1\"}",
        "{\"schema\":1,\"shopId\":42,\"revision\":\"7\",\"ratingSum\":\"9\",\"reviewCount\":\"2\"}",
        "{\"schema\":1,\"shopId\":\"42\",\"revision\":\"7\",\"ratingSum\":\"9\",\"reviewCount\":\"9223372036854775808\"}"
    })
    void rejectsMalformedOrMismatchedPayload(String payload) {
        assertThatThrownBy(() -> codec.decode(key, payload)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsOversizeAndUnknownFields() {
        assertThatThrownBy(() -> codec.decode(key, " ".repeat(2049))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(key, codec.encode(key, stats).replace("}", ",\"extra\":1}"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void hitAndDisabledCacheNeverCreateFlights() {
        var store = mock(RedisRatingStore.class); when(store.read(key)).thenReturn(stats);
        var cache = new RatingCache(true, store, 1, 20);
        try (var load = cache.request()) { assertThat(load.resolve(key, () -> {throw new AssertionError();})).isEqualTo(stats); load.commit(); }
        verify(store, never()).put(any(), any()); assertThat(cache.metrics().inFlight()).isZero();
        var disabled = new RatingCache(false, null, 1, 20);
        try (var load = disabled.request()) { assertThat(load.resolve(key, () -> stats)).isEqualTo(stats); load.commit(); }
    }
    @Test void uncommittedOwnerNeverPublishesAndAlwaysReleasesCapacity() {
        var store = mock(RedisRatingStore.class); var cache = new RatingCache(true, store, 1, 20);
        try (var load = cache.request()) { load.resolve(key, () -> stats); assertThat(cache.metrics().inFlight()).isEqualTo(1); }
        assertThat(cache.metrics().inFlight()).isZero(); verify(store, never()).put(any(), any());
        try (var load = cache.request()) { assertThatThrownBy(() -> load.resolve(key, () -> {throw new IllegalStateException("DB failed");})).isInstanceOf(IllegalStateException.class); }
        assertThat(cache.metrics().inFlight()).isZero();
        try (var load = cache.request()) { load.resolve(key, () -> stats); load.commit(); }
        assertThat(cache.metrics().inFlight()).isZero(); verify(store).put(key, stats);
    }
    @Test void sameVersionWaiterReceivesOnlyCommittedResult() throws Exception {
        var store = mock(RedisRatingStore.class); var entered = new CountDownLatch(1); var waiter = new CountDownLatch(1);
        var cache = new RatingCache(true, store, 1, 1000); var calls = new AtomicInteger();
        var waiterThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(store.read(key)).thenAnswer(inv -> { if (calls.incrementAndGet() == 2) {waiter.countDown();} return null; });
        try (var pool = Executors.newSingleThreadExecutor(); var owner = cache.request()) {
            owner.resolve(key, () -> stats);
            var future = pool.submit(() -> { try (var load = cache.request()) {
                waiterThread.set(Thread.currentThread()); entered.countDown(); var result = load.resolve(key, () -> {throw new AssertionError("duplicate DB load");}); load.commit(); return result;
            }});
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); assertThat(waiter.await(2, TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
            while(waiterThread.get().getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline){Thread.sleep(1);}
            assertThat(waiterThread.get().getState()).isEqualTo(Thread.State.TIMED_WAITING);
            assertThat(future.isDone()).isFalse(); owner.commit(); assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(stats);
        }
        assertThat(cache.metrics().coalesced()).isEqualTo(1); assertThat(cache.metrics().inFlight()).isZero();
    }
    @Test void timeoutAndCapacityFallbackStayBounded() {
        var cache = new RatingCache(true, mock(RedisRatingStore.class), 1, 1);
        try (var owner = cache.request(); var waiter = cache.request(); var overflow = cache.request()) {
            owner.resolve(key, () -> stats);
            assertThat(waiter.resolve(key, () -> stats)).isEqualTo(stats); waiter.commit();
            assertThat(overflow.resolve(new RatingCacheKey(43, 1), () -> stats)).isEqualTo(stats); overflow.commit();
            assertThat(cache.metrics().inFlight()).isEqualTo(1); assertThat(cache.metrics().waitTimeouts()).isEqualTo(1);
        }
        assertThat(cache.metrics().inFlight()).isZero();
    }
}
