package diagnostic;

import com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap;
import com.campuslife.coupon.mapper.DispatchGateMapper;
import com.campuslife.coupon.redis.CouponRedisStore;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Test-only: two INFO calls in the original maintenance context; never obtain a rebuild ticket. */
public class CouponConnectionProbe {
    private static String types(Throwable failure) {
        var out = new StringBuilder();
        for (int n = 0; failure != null && n < 12; n++, failure = failure.getCause()) {
            if (!out.isEmpty()) out.append("->");
            out.append(failure.getClass().getName());
        }
        return out.toString();
    }

    public static void main(String[] args) {
        long started = System.nanoTime();
        try (var ctx = new SpringApplicationBuilder(CouponMaintenanceBootstrap.class)
                .web(WebApplicationType.NONE).profiles("mysql", "coupon-maintenance")
                .properties(Map.of("spring.main.web-application-type", "none")).run()) {
            long id = Long.parseLong(ctx.getEnvironment().getRequiredProperty("COUPON_CAMPAIGN_ID"));
            var mapper = ctx.getBean(DispatchGateMapper.class);
            var before = mapper.find(id);
            if (before == null || !"ASYNC".equals(before.mode()) || !"PAUSED".equals(before.phase())
                    || before.rebuildToken() != null || before.leaseUntil() != null) {
                System.out.println("DIAG precondition=FAILED");
                return;
            }
            System.out.printf("DIAG before phase=%s generation=%d controlVersion=%d ticketPresent=false%n",
                    before.phase(), before.generation(), before.controlVersion());
            var store = ctx.getBean(CouponRedisStore.class);
            for (int call = 1; call <= 2; call++) {
                long callStarted = System.nanoTime();
                try {
                    String run = store.runId();
                    System.out.printf("DIAG call=%d outcome=OK elapsedMs=%d runIdMatches=%s%n", call,
                            (System.nanoTime() - callStarted) / 1_000_000,
                            Objects.equals(before.redisRunId(), run));
                } catch (Exception failure) {
                    // Deliberately omit exception messages, command payloads, URIs and credentials.
                    System.out.printf("DIAG call=%d outcome=FAILED elapsedMs=%d causes=%s%n", call,
                            (System.nanoTime() - callStarted) / 1_000_000, types(failure));
                }
                var after = mapper.find(id);
                System.out.printf("DIAG afterCall=%d gateUnchanged=%s ticketPresent=%s%n", call,
                        before.equals(after), after != null && after.rebuildToken() != null);
            }
            System.out.printf("DIAG completed calls=2 elapsedMs=%d%n", (System.nanoTime() - started) / 1_000_000);
        } catch (Exception failure) {
            System.out.println("DIAG contextFailedTypes=" + types(failure));
        }
    }
}
