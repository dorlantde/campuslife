package com.campuslife;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;

/** A real application JVM, with no mocks, agents, fault hooks, or additional HTTP endpoints. */
public final class CouponMeasurementChild {
 public static void main(String[] args)throws Exception{
  Path folder=Path.of(System.getenv("COUPON_BENCH_FOLDER")).toAbsolutePath().normalize();
  if(!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_COUPON_BENCH"))||!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_MQ_IT"))||!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_COUPON_REDIS_IT"))||!"campuslife_it".equals(System.getenv("DB_NAME"))||!folder.startsWith(Path.of("target").toAbsolutePath().normalize())){throw new IllegalStateException("Owned benchmark fixture required");}
  var identity=PublisherComparisonClasspath.childIdentity();
  var app=new SpringApplicationBuilder(CampusLifeApplication.class).profiles("mysql").run(
   "--spring.config.import=","--server.port=0","--server.address=127.0.0.1",
   "--campuslife.messaging.enabled=true","--campuslife.messaging.consumer-enabled=true","--campuslife.messaging.scheduling-enabled=true",
   "--campuslife.coupon-redis.scheduling-enabled=true","--campuslife.coupon.expiry-enabled=true",
   "--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=false","--campuslife.files.cleanup-enabled=false");
  var json=new ObjectMapper();
  Files.writeString(folder.resolve("ready.json"),json.writeValueAsString(Map.of("port",Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port")),"pid",ProcessHandle.current().pid(),"java",System.getProperty("java.version"),"processors",Runtime.getRuntime().availableProcessors(),"maxHeap",Runtime.getRuntime().maxMemory(),"databasePoolMax",5,"production",identity)));
  var metrics=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("benchmark-metrics").factory());
  metrics.scheduleWithFixedDelay(()->{try{
   var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
   Path temporary=folder.resolve("process.tmp");Files.writeString(temporary,json.writeValueAsString(Map.of("at",System.currentTimeMillis(),"cpuNanos",os.getProcessCpuTime(),"heapUsed",heap.getUsed(),"heapCommitted",heap.getCommitted(),"threads",ManagementFactory.getThreadMXBean().getThreadCount())));
   Files.move(temporary,folder.resolve("process.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(Exception ex){System.err.println("Benchmark process sample unavailable: "+ex.getClass().getSimpleName());}},0,1,TimeUnit.SECONDS);
  app.addApplicationListener((org.springframework.context.event.ContextClosedEvent e)->metrics.shutdownNow());
 }
}
