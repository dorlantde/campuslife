package diagnostic;

import com.campuslife.coupon.config.CouponRedisConfiguration;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.merchant.config.RatingCacheProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Isolated test-only entry; uses original production Redis beans, no SQL or business changes. */
public class CouponRedisColdProbe {
    public static void main(String[] args) throws Exception {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("mysql");
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe", Map.of(
                "campuslife.coupon-redis.enabled", "true",
                "campuslife.coupon-redis.namespace", System.getenv("COUPON_REDIS_NAMESPACE"),
                "campuslife.coupon-redis.host", "coupon-redis",
                "campuslife.coupon-redis.port", "6379",
                "campuslife.coupon-redis.username", "coupon_app",
                "campuslife.coupon-redis.password", Files.readString(Path.of("/run/secrets/COUPON_REDIS_PASSWORD")).trim())));
            ctx.registerBean(RatingCacheProperties.class, () -> new RatingCacheProperties(false, "", "127.0.0.1", 6379));
            ctx.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            ctx.register(CouponRedisConfiguration.class);
            ctx.refresh();
            var store = ctx.getBean(CouponRedisStore.class);
            for (int call = 1; call <= 3; call++) {
                long started = System.nanoTime();
                try {
                    store.runId();
                    System.out.printf("DIAG call=%d outcome=OK elapsedMs=%d%n", call, (System.nanoTime()-started)/1_000_000);
                } catch (Exception failure) {
                    var kinds = new StringBuilder();
                    Throwable next = failure;
                    for (int depth=0; next!=null && depth<12; depth++, next=next.getCause()) {
                        if (!kinds.isEmpty()) kinds.append("->");
                        kinds.append(next.getClass().getName());
                    }
                    // Class names only: never emit messages, URIs, credentials or exception payloads.
                    System.out.printf("DIAG call=%d outcome=FAILED elapsedMs=%d causes=%s%n", call,
                        (System.nanoTime()-started)/1_000_000, kinds);
                }
            }
        }
    }
}
