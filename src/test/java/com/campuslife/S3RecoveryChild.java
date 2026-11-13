package com.campuslife;

import com.campuslife.file.service.*;
import com.campuslife.file.storage.S3FileStorage;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Test classpath only. Parent owns and forcibly kills this process; no fault HTTP endpoint exists. */
public final class S3RecoveryChild {
    static String mode(){return System.getenv("S3_RECOVERY_MODE");}
    static Path marker(){return Path.of(System.getenv("S3_RECOVERY_MARKER")).toAbsolutePath().normalize();}
    public static void main(String[] args)throws Exception {
        if(!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_S3_BUSINESS_IT"))||!"campuslife_it".equals(System.getenv("DB_NAME"))
                ||!marker().startsWith(Path.of("target/s3-recovery").toAbsolutePath().normalize())){throw new IllegalStateException("Owned S3 recovery fixture required");}
        try(var context=new SpringApplicationBuilder(CampusLifeApplication.class,Hooks.class).profiles("mysql","s3-recovery-child").run(
                "--server.address=127.0.0.1","--server.port=0","--campuslife.files.cleanup-enabled=false",
                "--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=false","--campuslife.coupon.expiry-enabled=false",
                "--campuslife.messaging.enabled=false","--campuslife.coupon-redis.enabled=false","--campuslife.rating-cache.enabled=false")) {
            Files.writeString(Path.of(marker()+".ready"),context.getEnvironment().getRequiredProperty("local.server.port"));
            if(mode().equals("AFTER_DELETE")||mode().equals("RECOVER")) {
                for(int n=0;n<4;n++){context.getBean(FileCleanup.class).runOnce();}
                Files.writeString(Path.of(marker()+".done"),"completed");return;
            }
            for(;;){Thread.sleep(1000);}
        }
    }
    static void pause()throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive()){throw new IllegalStateException("Crash boundary must be after commit/outside SQL");}
        Files.writeString(marker(),new ObjectMapper().writeValueAsString(Map.of("mode",mode(),"pid",ProcessHandle.current().pid(),"at",Instant.now().toString())));
        for(;;){Thread.sleep(1000);}
    }
    @Configuration(proxyBeanMethods=false) @Profile("s3-recovery-child")
    public static class Hooks {
        @Bean @Primary Clock recoveryClock(){return Clock.fixed(Instant.parse(System.getenv("S3_RECOVERY_NOW")),ZoneOffset.UTC);}
        @Bean static BeanPostProcessor recoverySeams(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(!(bean instanceof FileTransactions||bean instanceof S3FileStorage)){return bean;}
                var proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
                proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->{
                    var result=call.proceed();String method=call.getMethod().getName();
                    if(mode().equals("AFTER_RESERVE")&&method.equals("reserve")||mode().equals("AFTER_PUT")&&method.equals("put")
                            ||mode().equals("AFTER_AVAILABLE")&&method.equals("publish")||mode().equals("AFTER_DELETE")&&method.equals("delete")){pause();}
                    return result;
                });return proxy.getProxy();
            }
        };}
    }
}
