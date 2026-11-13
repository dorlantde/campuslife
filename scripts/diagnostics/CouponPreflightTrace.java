package diagnostic;

import com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap;
import com.campuslife.coupon.config.CouponRedisProperties;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.CouponMaintenance;
import com.campuslife.coupon.service.CouponMaintenanceTransactions;
import com.campuslife.coupon.service.CouponRedisRecovery;
import io.lettuce.core.event.command.*;
import io.lettuce.core.resource.DefaultClientResources;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only observation of the original packaged beans. Never emit messages, arguments or URIs. */
public class CouponPreflightTrace {
    static final long START = System.nanoTime();
    static final AtomicInteger READS = new AtomicInteger();
    static final Map<Object,Long> COMMANDS = new ConcurrentHashMap<>();
    static long ms() { return (System.nanoTime()-START)/1_000_000; }
    static void emit(String value) { System.out.println("DIAG atMs="+ms()+" "+value); }
    static String stage() {
        for (var frame:Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().startsWith("com.campuslife.coupon.service.CouponRedisRecovery")
                    && frame.getMethodName().equals("preflightMaintenanceConnection")) return "PREFLIGHT";
        }
        return "RECOVERY";
    }
    static String types(Throwable failure) {
        var text = new StringBuilder();
        for (int n=0; failure!=null && n<16; n++, failure=failure.getCause()) {
            if (!text.isEmpty()) text.append("->");
            text.append(failure.getClass().getName());
        }
        return text.toString();
    }
    static void frames(Throwable failure, int read) {
        for (int depth=0; failure!=null && depth<16; depth++, failure=failure.getCause()) {
            int shown=0;
            for (var frame:failure.getStackTrace()) {
                String name=frame.getClassName();
                if (!(name.startsWith("io.lettuce.") || name.startsWith("io.netty.")
                        || name.startsWith("org.springframework.data.redis."))) continue;
                emit("read="+read+" causeDepth="+depth+" frame="+name+"."+frame.getMethodName());
                if (++shown==5) break;
            }
        }
    }
    static String command(CommandBaseEvent event) {
        String name=String.valueOf(event.getCommand().getType());
        return name.matches("[A-Z_]{1,32}") ? name : "OTHER";
    }
    static void finish(CommandBaseEvent event, Throwable failure) {
        Long start=COMMANDS.remove(event.getCommand());
        emit("command="+command(event)+" outcome="+(failure==null?"OK":"FAILED")
                +" elapsedMs="+(start==null?-1:(System.nanoTime()-start)/1_000_000)
                +(failure==null?"":" causes="+types(failure)));
    }
    static void gate(JdbcTemplate jdbc,long id,String stage) {
        var row=jdbc.queryForMap("SELECT mode,phase,generation,control_version,rebuild_token IS NOT NULL AS ticket_present,"
                +"lease_until IS NOT NULL AS lease_present FROM coupon_dispatch_gate WHERE campaign_id=?",id);
        emit("gate="+stage+" mode="+row.get("mode")+" phase="+row.get("phase")+" generation="+row.get("generation")
                +" version="+row.get("control_version")+" ticketPresent="+row.get("ticket_present")+" leasePresent="+row.get("lease_present"));
    }
    static void jvm(String stage) {
        long collections=ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum();
        emit("jvm="+stage+" uptimeMs="+ManagementFactory.getRuntimeMXBean().getUptime()
                +" loadedClasses="+ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount()+" gcCollections="+collections);
    }
    public static void main(String[] args) {
        var listener=new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                COMMANDS.put(event.getCommand(),System.nanoTime()); emit("command="+command(event)+" outcome=STARTED");
            }
            @Override public void commandSucceeded(CommandSucceededEvent event) { finish(event,null); }
            @Override public void commandFailed(CommandFailedEvent event) { finish(event,event.getCause()); }
        };
        try (var ctx=new SpringApplicationBuilder(CouponMaintenanceBootstrap.class)
                .web(WebApplicationType.NONE).profiles("mysql","coupon-maintenance")
                .properties(Map.of("spring.main.web-application-type","none"))
                .initializers(context->context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override public Object postProcessAfterInitialization(Object bean,String name) {
                        if (!(bean instanceof CouponRedisStore)) return bean;
                        var proxy=new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                        proxy.addAdvice((MethodInterceptor) invocation->{
                            if (!invocation.getMethod().getName().equals("runId")) return invocation.proceed();
                            int read=READS.incrementAndGet(); String stage=stage(); long at=System.nanoTime();
                            emit("read="+read+" stage="+stage+" outcome=STARTED interrupted="+Thread.currentThread().isInterrupted());
                            try {
                                var result=invocation.proceed();
                                emit("read="+read+" stage="+stage+" outcome=OK elapsedMs="+(System.nanoTime()-at)/1_000_000
                                        +" interrupted="+Thread.currentThread().isInterrupted());
                                return result;
                            } catch(Throwable failure) {
                                emit("read="+read+" stage="+stage+" outcome=FAILED elapsedMs="+(System.nanoTime()-at)/1_000_000
                                        +" interrupted="+Thread.currentThread().isInterrupted()+" causes="+types(failure));
                                frames(failure,read); throw failure;
                            }
                        }); return proxy.getProxy();
                    }
                })).run()) {
            boolean packaged=CouponMaintenance.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm().contains("app.jar")
                    && CouponRedisRecovery.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm().contains("app.jar");
            emit("packagedProductionClasses="+packaged);
            if (!packaged) throw new IllegalStateException();
            jvm("context-ready-before-listeners");
            ctx.getBean("couponRedisConnectionFactory",LettuceConnectionFactory.class).getNativeClient().addListener(listener);
            ctx.getBean("couponRedisResources",DefaultClientResources.class).eventBus().get().subscribe(event->{
                if (event.getClass().getPackageName().startsWith("io.lettuce.core.event.connection"))
                    emit("connectionEvent="+event.getClass().getSimpleName());
            });
            emit("commandAndConnectionListenersAttached=true");
            var properties=ctx.getBean(CouponRedisProperties.class);
            emit("configuration passwordMatchesMountedSecret="+properties.password().equals(Files.readString(Path.of("/run/secrets/COUPON_REDIS_PASSWORD")))
                    +" expectedUser="+properties.username().equals("coupon_app")+" expectedHost="+properties.host().equals("coupon-redis")+" expectedPort="+(properties.port()==6379));
            long id=Long.parseLong(ctx.getEnvironment().getRequiredProperty("COUPON_CAMPAIGN_ID"));
            long version=Long.parseLong(ctx.getEnvironment().getRequiredProperty("COUPON_CONTROL_VERSION"));
            var jdbc=ctx.getBean(JdbcTemplate.class);
            jvm("context-ready"); gate(jdbc,id,"before");
            try {
                var state=ctx.getBean(CouponMaintenance.class).run("Resume",id,version,true);
                emit("outcome=OK phase="+state.phase()+" generation="+state.generation()+" version="+state.controlVersion()+" externalRunIdCalls="+READS.get());
            } catch(Throwable failure) {
                String code=failure instanceof CouponMaintenanceTransactions.Refused ? failure.getMessage() : "NONE";
                emit("outcome=FAILED code="+(code!=null && code.matches("[A-Z0-9_]{1,80}")?code:"REDACTED")
                        +" causes="+types(failure)+" externalRunIdCalls="+READS.get()+" interrupted="+Thread.currentThread().isInterrupted());
            }
            gate(jdbc,id,"after"); jvm("operation-finished");
        } catch(Throwable failure) { emit("contextFailureTypes="+types(failure)); }
    }
}
