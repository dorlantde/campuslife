package diagnostic;

import com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap;
import com.campuslife.coupon.mapper.DispatchGateMapper;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.CouponMaintenance;
import com.campuslife.coupon.service.CouponRedisRecovery;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Targeted source-classpath verification, not a deployed replacement JAR acceptance. */
public class CouponPreflightVerification {
    static String types(Throwable failure) {
        var text = new StringBuilder();
        for (int n = 0; failure != null && n < 12; n++, failure = failure.getCause()) {
            if (!text.isEmpty()) text.append("->");
            text.append(failure.getClass().getName());
        }
        return text.toString();
    }
    static void require(boolean value) {
        if (!value) throw new AssertionError("Targeted maintenance assertion failed");
    }
    public static void main(String[] args) {
        var calls = new AtomicInteger();
        long started = System.nanoTime();
        // The mounted classes must precede the original release classes on the explicit classpath.
        require(CouponMaintenance.class.getProtectionDomain().getCodeSource().getLocation().getPath().equals("/run/diagnostic/"));
        require(CouponRedisRecovery.class.getProtectionDomain().getCodeSource().getLocation().getPath().equals("/run/diagnostic/"));
        System.out.println("DIAG updatedProductionClassesLoaded=true");
        try (var ctx = new SpringApplicationBuilder(CouponMaintenanceBootstrap.class)
                .web(WebApplicationType.NONE).profiles("mysql", "coupon-maintenance")
                .properties(Map.of("spring.main.web-application-type", "none"))
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!(bean instanceof CouponRedisStore)) return bean;
                        var proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                        proxy.addAdvice((MethodInterceptor) invocation -> {
                            if (!invocation.getMethod().getName().equals("runId")) return invocation.proceed();
                            int call = calls.incrementAndGet(); long at = System.nanoTime();
                            long campaign = Long.parseLong(context.getEnvironment().getRequiredProperty("COUPON_CAMPAIGN_ID"));
                            var gate = context.getBean(DispatchGateMapper.class).find(campaign);
                            System.out.printf("DIAG read=%d beforePhase=%s ticketPresent=%s%n", call, gate.phase(), gate.rebuildToken()!=null);
                            try {
                                var value = invocation.proceed();
                                System.out.printf("DIAG read=%d outcome=OK elapsedMs=%d%n", call, (System.nanoTime()-at)/1_000_000);
                                return value;
                            } catch (Throwable failure) {
                                System.out.printf("DIAG read=%d outcome=FAILED elapsedMs=%d causes=%s%n", call, (System.nanoTime()-at)/1_000_000, types(failure));
                                throw failure;
                            }
                        }); return proxy.getProxy();
                    }
                })).run()) {
            long id = Long.parseLong(ctx.getEnvironment().getRequiredProperty("COUPON_CAMPAIGN_ID"));
            long version = Long.parseLong(ctx.getEnvironment().getRequiredProperty("COUPON_CONTROL_VERSION"));
            String action = ctx.getEnvironment().getRequiredProperty("DIAGNOSTIC_ACTION");
            require(action.equals("Resume") || action.equals("Sync"));
            var maintenance = ctx.getBean(CouponMaintenance.class);
            var gates = ctx.getBean(DispatchGateMapper.class);
            var before = gates.find(id);
            require(before.controlVersion() == version && before.mode().equals("ASYNC"));
            if (action.equals("Sync")) {
                var paused = maintenance.run("Pause", id, version, true);
                require(calls.get() == 0);
                version = paused.controlVersion();
            }
            var result = maintenance.run(action, id, version, true);
            var after = gates.find(id);
            require(after.rebuildToken() == null && after.leaseUntil() == null);
            if (action.equals("Resume")) {
                require(result.mode().equals("ASYNC") && result.phase().equals("OPEN"));
            } else {
                require(result.mode().equals("SYNC") && result.phase().equals("PAUSED"));
                int at = calls.get();
                result = maintenance.run("Resume", id, result.controlVersion(), true);
                require(result.mode().equals("SYNC") && result.phase().equals("OPEN") && calls.get() == at);
                System.out.println("DIAG syncResumeAddedRedisReads=0");
            }
            System.out.printf("DIAG action=%s outcome=OK mode=%s phase=%s generation=%d controlVersion=%d redisReads=%d elapsedMs=%d%n",
                    action, result.mode(), result.phase(), result.generation(), result.controlVersion(), calls.get(), (System.nanoTime()-started)/1_000_000);
        } catch (Throwable failure) {
            System.out.println("DIAG outcome=FAILED causes=" + types(failure));
        }
    }
}
