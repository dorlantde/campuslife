package com.campuslife.merchant.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** Best-effort cache access; all database failures stay outside this boundary. */
public class RedisRatingStore {
    private static final Logger LOG = LoggerFactory.getLogger(RedisRatingStore.class);
    private static final long COOLDOWN = Duration.ofSeconds(5).toNanos();
    private final StringRedisTemplate redis;
    private final String namespace;
    private final RatingCacheCodec codec;
    private final AtomicLong retryAt = new AtomicLong();
    private final AtomicBoolean probing = new AtomicBoolean();
    private final LongAdder hits = new LongAdder(), misses = new LongAdder(), bypasses = new LongAdder(),
            errors = new LongAdder(), invalid = new LongAdder(), writes = new LongAdder();

    public RedisRatingStore(StringRedisTemplate redis, String namespace, ObjectMapper json) {
        this.redis = redis; this.namespace = namespace; this.codec = new RatingCacheCodec(json);
    }
    public RatingStats read(RatingCacheKey key) {
        return attempt(() -> {
            String payload = redis.opsForValue().get(key.redisKey(namespace));
            if (payload == null) { misses.increment(); return null; }
            RatingStats value;
            try { value = codec.decode(key, payload); }
            catch (RuntimeException ex) {
                invalid.increment(); misses.increment();
                redis.delete(key.redisKey(namespace)); // Only the exact invalid cache key; no dataset scan.
                return null;
            }
            hits.increment(); return value;
        });
    }
    public void put(RatingCacheKey key, RatingStats stats) {
        attempt(() -> {
            int seconds = stats.count() == 0 ? ThreadLocalRandom.current().nextInt(30, 46)
                    : ThreadLocalRandom.current().nextInt(300, 361);
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key.redisKey(namespace), codec.encode(key, stats), Duration.ofSeconds(seconds)))) {
                writes.increment();
            }
            return Boolean.TRUE;
        });
    }
    private <T> T attempt(Supplier<T> action) {
        long until = retryAt.get(); boolean probe = until != 0;
        if (probe && (System.nanoTime() - until < 0 || !probing.compareAndSet(false, true))) {
            bypasses.increment(); return null;
        }
        try {
            T result = action.get();
            if (probe) { retryAt.compareAndSet(until, 0); }
            return result;
        } catch (RuntimeException ex) {
            errors.increment();
            long prior = retryAt.getAndSet(System.nanoTime() + COOLDOWN);
            if (prior == 0 || probe) { LOG.warn("Rating cache unavailable; bypassing for 5 seconds ({})", ex.getClass().getSimpleName()); }
            return null;
        } finally { if (probe) { probing.set(false); } }
    }
    public record Metrics(long hits, long misses, long bypasses, long errors, long invalid, long writes) {}
    public Metrics metrics() { return new Metrics(hits.sum(), misses.sum(), bypasses.sum(), errors.sum(), invalid.sum(), writes.sum()); }
}
