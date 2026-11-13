package com.campuslife.merchant.cache;

import com.campuslife.merchant.config.RatingCacheProperties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class RatingCache {
    private final boolean enabled;
    private final RedisRatingStore store;
    private final ConcurrentHashMap<RatingCacheKey, CompletableFuture<RatingStats>> flights = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final long waitMillis;
    private final LongAdder databaseLoads = new LongAdder(), coalesced = new LongAdder(), waitTimeouts = new LongAdder();

    @Autowired
    public RatingCache(RatingCacheProperties props, ObjectProvider<RedisRatingStore> stores) {
        this(props.enabled(), stores.getIfAvailable(), 256, 20);
    }
    RatingCache(boolean enabled, RedisRatingStore store, int capacity, long waitMillis) {
        this.enabled = enabled; this.store = store; this.slots = new Semaphore(capacity); this.waitMillis = waitMillis;
        if (enabled && store == null) { throw new IllegalArgumentException("Redis cache store missing"); }
    }
    public boolean enabled() { return enabled; }
    public Load request() { return new Load(); }
    public record Metrics(long databaseLoads, long coalesced, long waitTimeouts, int inFlight) {}
    public Metrics metrics() { return new Metrics(databaseLoads.sum(), coalesced.sum(), waitTimeouts.sum(), flights.size()); }

    /** A single detail read owns at most one flight; commit() must be called after the RR method returns. */
    public final class Load implements AutoCloseable {
        private RatingCacheKey key;
        private CompletableFuture<RatingStats> owned;
        private RatingStats value;
        private boolean fill, finished;
        public RatingStats resolve(RatingCacheKey requested, Supplier<RatingStats> loader) {
            if (key != null) { throw new IllegalStateException("Only one rating key per detail request"); }
            key = requested;
            if (enabled) {
                var cached = store.read(key);
                if (cached != null) { return cached; }
                var other = flights.get(key);
                if (other == null && slots.tryAcquire()) {
                    var candidate = new CompletableFuture<RatingStats>();
                    other = flights.putIfAbsent(key, candidate);
                    if (other == null) { owned = candidate; }
                    else { slots.release(); }
                }
                if (other != null) {
                    try { var result = other.get(waitMillis, TimeUnit.MILLISECONDS); coalesced.increment(); return result; }
                    catch (TimeoutException ex) { waitTimeouts.increment(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    catch (ExecutionException ex) { /* Owner failed; load from this request's RR snapshot. */ }
                }
            }
            databaseLoads.increment(); value = loader.get(); fill = enabled; return value;
        }
        public void commit() {
            if (finished) { throw new IllegalStateException("Rating load already finished"); }
            try {
                if (owned != null) { owned.complete(value); }
                if (fill) { store.put(key, value); }
            } finally { release(); finished = true; }
        }
        private void release() {
            if (owned != null && flights.remove(key, owned)) { slots.release(); }
        }
        @Override public void close() {
            if (!finished) {
                if (owned != null) { owned.completeExceptionally(new IllegalStateException("Rating read did not commit")); }
                release(); finished = true;
            }
        }
    }
}
