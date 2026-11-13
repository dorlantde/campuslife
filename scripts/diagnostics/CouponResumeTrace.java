package diagnostic;

import com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap;
import com.campuslife.coupon.mapper.DispatchGateMapper;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.CouponMaintenance;
import com.campuslife.coupon.service.CouponMaintenanceTransactions;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Test-only observer: original SQL/Redis beans, transaction boundaries and budgets remain intact. */
public class CouponResumeTrace {
    private static final Set<String> METHODS = Set.of("runId", "prepare", "initialize", "seed", "activate", "inspect");

    private static String types(Throwable failure) {
        var out = new StringBuilder();
        for (int n=0; failure!=null && n<12; n++, failure=failure.getCause()) {
            if (!out.isEmpty()) out.append("->");
            out.append(failure.getClass().getName());
            String code = failure instanceof CouponRedisStore.Unavailable u ? u.code()
                : failure instanceof CouponMaintenanceTransactions.Refused ? failure.getMessage() : null;
            if (code!=null && code.matches("[A-Z0-9_]{1,64}")) out.append('[').append(code).append(']');
            if (failure instanceof java.sql.SQLException sql) {
                String state=sql.getSQLState();
                out.append("[sqlState=").append(state!=null && state.matches("[A-Z0-9]{5}") ? state : "REDACTED")
                    .append(",vendorCode=").append(sql.getErrorCode()).append(']');
            }
        }
        return out.toString();
    }

    private static void gate(ConfigurableApplicationContext ctx, long id, String phase) {
        try {
            var g=ctx.getBean(DispatchGateMapper.class).find(id);
            var now=LocalDateTime.now(ZoneOffset.UTC);
            if (g==null) { System.out.println("DIAG snapshot="+phase+" missing=true"); return; }
            System.out.printf("DIAG snapshot=%s mode=%s phase=%s generation=%d controlVersion=%d resume=%s leaseUntilUtc=%s nowUtc=%s tokenPresent=%s runId=%s%n",
                phase,g.mode(),g.phase(),g.generation(),g.controlVersion(),g.resumePhase(),g.leaseUntil(),now,
                g.rebuildToken()!=null,g.redisRunId());
        } catch (Throwable failure) {
            System.out.println("DIAG snapshot="+phase+" failedTypes="+types(failure));
        }
    }

    public static void main(String[] args) {
        long started=System.nanoTime();
        try (var ctx = new SpringApplicationBuilder(CouponMaintenanceBootstrap.class)
                .web(WebApplicationType.NONE).profiles("mysql", "coupon-maintenance")
                .properties(Map.of("spring.main.web-application-type", "none"))
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override public Object postProcessAfterInitialization(Object bean,String name) {
                        if (!(bean instanceof CouponRedisStore)) return bean;
                        var proxy=new ProxyFactory(bean);
                        proxy.setProxyTargetClass(true);
                        proxy.addAdvice((MethodInterceptor) invocation -> {
                            String method=invocation.getMethod().getName();
                            if (!METHODS.contains(method)) return invocation.proceed();
                            long call=System.nanoTime();
                            try {
                                Object value=invocation.proceed();
                                System.out.printf("DIAG step=redis.%s outcome=OK elapsedMs=%d%n",method,(System.nanoTime()-call)/1_000_000);
                                return value;
                            } catch (Throwable failure) {
                                System.out.printf("DIAG step=redis.%s outcome=FAILED elapsedMs=%d causes=%s%n",method,(System.nanoTime()-call)/1_000_000,types(failure));
                                throw failure;
                            }
                        });
                        return proxy.getProxy();
                    }
                })).run()) {
            var env=ctx.getEnvironment();
            long id=Long.parseLong(env.getRequiredProperty("COUPON_CAMPAIGN_ID"));
            long version=Long.parseLong(env.getRequiredProperty("COUPON_CONTROL_VERSION"));
            gate(ctx,id,"before");
            try {
                var s=ctx.getBean(CouponMaintenance.class).run("Resume",id,version,true);
                System.out.printf("DIAG outcome=OK generation=%d controlVersion=%d elapsedMs=%d%n",s.generation(),s.controlVersion(),(System.nanoTime()-started)/1_000_000);
            } catch (Throwable failure) {
                System.out.printf("DIAG outcome=FAILED causes=%s elapsedMs=%d%n",types(failure),(System.nanoTime()-started)/1_000_000);
            }
            gate(ctx,id,"after");
        } catch (Throwable failure) {
            System.out.println("DIAG contextFailedTypes="+types(failure));
        }
    }
}
