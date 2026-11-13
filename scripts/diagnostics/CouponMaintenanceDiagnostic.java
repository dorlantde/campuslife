package diagnostic;

import com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.CouponMaintenance;
import com.campuslife.coupon.service.CouponMaintenanceTransactions;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Test-only loader main; the original JAR and maintenance beans remain unchanged. */
public class CouponMaintenanceDiagnostic {
    public static void main(String[] args) {
        try (var ctx = new SpringApplicationBuilder(CouponMaintenanceBootstrap.class)
                .web(WebApplicationType.NONE).profiles("mysql", "coupon-maintenance")
                .properties(Map.of("spring.main.web-application-type", "none")).run()) {
            var env = ctx.getEnvironment();
            var state = ctx.getBean(CouponMaintenance.class).run("Resume",
                Long.parseLong(env.getRequiredProperty("COUPON_CAMPAIGN_ID")),
                Long.parseLong(env.getRequiredProperty("COUPON_CONTROL_VERSION")), true);
            System.out.printf("DIAG outcome=OK generation=%d controlVersion=%d%n", state.generation(), state.controlVersion());
        } catch (Throwable failure) {
            var kinds = new StringBuilder();
            Throwable next = failure;
            for (int depth=0; next!=null && depth<12; depth++, next=next.getCause()) {
                if (!kinds.isEmpty()) kinds.append("->");
                kinds.append(next.getClass().getName());
                String code = next instanceof CouponRedisStore.Unavailable unavailable ? unavailable.code()
                    : next instanceof CouponMaintenanceTransactions.Refused ? next.getMessage() : null;
                if (code!=null && code.matches("[A-Z0-9_]{1,64}")) kinds.append('[').append(code).append(']');
                if (next instanceof java.sql.SQLException sql) kinds.append("[sqlState=").append(sql.getSQLState())
                    .append(",vendorCode=").append(sql.getErrorCode()).append(']');
            }
            System.out.println("DIAG outcome=FAILED causes="+kinds);
            System.exit(1);
        }
    }
}
