package com.campuslife;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.merchant.cache.*;
import com.campuslife.merchant.config.RatingCacheProperties;
import com.campuslife.merchant.mapper.RatingRevisionMapper;
import com.campuslife.order.service.OrderReviewTransactions;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit, fixture-gated M02.3 experiment, deliberately outside default Test/IT scanning. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"campuslife.rating-cache.enabled=false",
        "campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_CACHE_IT",matches="isolated-fixture")
class RatingCacheAcceptance {
    static final Instant NOW=Instant.parse("2026-10-28T01:00:00Z");
    static final long HOT=PublicShopQueryFixture.BASE+6, NORMAL=PublicShopQueryFixture.BASE+8, EMPTY=PublicShopQueryFixture.BASE+5;
    @Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Random random=new Random(20261028);
    final Map<String,Object> report=new LinkedHashMap<>();
    final List<Map<String,Object>> readStages=new ArrayList<>(),faults=new ArrayList<>(),writes=new ArrayList<>();
    Path outputFolder;
    record Sample(double ms,int status,JsonNode data){}
    record Counts(long sql,long aggregates,long revisionWrites) {
        Counts minus(Counts b){return new Counts(sql-b.sql,aggregates-b.aggregates,revisionWrites-b.revisionWrites);}
    }
    static final class App implements AutoCloseable {
        final ConfigurableApplicationContext context;final boolean enabled;final int port;final SqlCounter counter;
        App(boolean enabled,Environment env){
            this.enabled=enabled;
            context=new SpringApplicationBuilder(CampusLifeApplication.class,ExperimentConfiguration.class).web(WebApplicationType.SERVLET)
                .profiles("mysql").run("--server.address=127.0.0.1","--server.port=0","--spring.main.register-shutdown-hook=false",
                    "--campuslife.rating-cache.enabled="+enabled,"--campuslife.rating-cache.namespace="+env.getRequiredProperty("RATING_CACHE_NAMESPACE"),
                    "--campuslife.rating-cache.host=127.0.0.1","--campuslife.rating-cache.port="+env.getRequiredProperty("RATING_CACHE_PORT"),
                    "--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=false","--campuslife.files.cleanup-enabled=false");
            port=context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);counter=context.getBean(SqlCounter.class);
        }
        RatingCache cache(){return context.getBean(RatingCache.class);}
        RedisRatingStore store(){return context.getBean(RedisRatingStore.class);}
        StringRedisTemplate redis(){return context.getBean("ratingRedisTemplate",StringRedisTemplate.class);}
        String namespace(){return context.getBean(RatingCacheProperties.class).namespace();}
        public void close(){context.close();}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ExperimentConfiguration {
        @Bean @Primary Clock experimentClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean SqlCounter experimentSql(){return new SqlCounter();}
    }
    @Intercepts({@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}),
            @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})})
    static final class SqlCounter implements Interceptor {
        final AtomicLong sql=new AtomicLong(),aggregates=new AtomicLong(),revisionWrites=new AtomicLong();
        final AtomicBoolean readPhase=new AtomicBoolean(true);
        final Queue<Double> revisionTimes=new ConcurrentLinkedQueue<>();
        final ThreadLocal<Boolean> omitRevision=ThreadLocal.withInitial(()->false);
        Counts snapshot(){return new Counts(sql.get(),aggregates.get(),revisionWrites.get());}
        public Object intercept(Invocation inv)throws Throwable{
            if(inv.getTarget() instanceof Executor){
                var ms=(MappedStatement)inv.getArgs()[0];
                if(ms.getId().endsWith("RatingRevisionMapper.advance")&&omitRevision.get()){
                    if(readPhase.get()){throw new IllegalStateException("Write-cost test bypass used during a read phase");}
                    return 1; // Test-only original-transaction control; never present in the application JAR.
                }
                if(ms.getId().endsWith("RatingRevisionMapper.advance")){
                    long start=System.nanoTime();try{return inv.proceed();}finally{revisionTimes.add((System.nanoTime()-start)/1e6);}
                }
            }else{
                var statement=(StatementHandler)inv.getTarget();String text=statement.getBoundSql().getSql();
                sql.incrementAndGet();if(text.contains("SUM(rating)")){aggregates.incrementAndGet();}
                if(text.contains("INSERT INTO shop_rating_revision")){revisionWrites.incrementAndGet();}
                if(readPhase.get()){
                    var c=(Connection)inv.getArgs()[0];assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);assertThat(c.isReadOnly()).isTrue();
                }
            }
            return inv.proceed();
        }
    }
    @Test void verifyPairedHttpFaultsAndWriteCost()throws Exception{
        Path root=Path.of("").toAbsolutePath().normalize();Path output=Path.of(env.getRequiredProperty("CAMPUSLIFE_CACHE_OUTPUT")).toAbsolutePath().normalize();
        assertThat(output.getParent()).isEqualTo(root.resolve(".cache/redis-it"));assertThat(output.getFileName().toString()).isEqualTo(env.getRequiredProperty("CAMPUSLIFE_CACHE_FIXTURE_ID"));
        outputFolder=output;
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);fixture.initialize();
        report.put("startedAt",Instant.now().toString());report.put("method",Map.of("clock",NOW.toString(),"rounds",3,"warmSamples",24,"concurrentSamples",48,
                "workers",List.of(1,4,16),"client","closed-loop HTTP including JSON parsing","mysqlPoolPerApp",5));
        report.put("runtime",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"cpus",Runtime.getRuntime().availableProcessors(),
                "mysql",jdbc.queryForObject("SELECT VERSION()",String.class),"bufferPoolBytes",jdbc.queryForObject("SELECT @@innodb_buffer_pool_size",Long.class)));
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(root.resolve("src/main/java/com/campuslife"))){for(var f:files.filter(p->p.toString().endsWith(".java")).toList()){
            hashes.put(root.relativize(f).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));}}
        for(String file:List.of("pom.xml","src/main/resources/application.properties","src/test/java/com/campuslife/RatingCacheAcceptance.java","src/test/java/com/campuslife/PublicShopQueryFixture.java")){
            hashes.put(file,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(file)))));}
        report.put("sourceSha256",hashes);report.put("reads",readStages);report.put("faults",faults);report.put("writes",writes);
        try(var off=new App(false,env);var on=new App(true,env)){
            verifyOwnedRedis();
            int previous=0;
            for(int scale:List.of(1_000,10_000)){
                fixture.grow(previous+1,scale);previous=scale;
                System.out.println("CACHE_ACCEPTANCE checkpoint scale="+scale);
                pairedReads(scale,off,on);
                coldReads(scale,on);
                checkpoint();
            }
            mixedUpdates(off,on);checkpoint();faultChecks(off,on);checkpoint();writeCost(off);
            assertThat(on.cache().metrics().inFlight()).isZero();assertThat(off.cache().metrics().inFlight()).isZero();
        }
        report.put("finishedAt",Instant.now().toString());report.put("status","passed");
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("acceptance.json").toFile(),report);
        System.out.println("CACHE_ACCEPTANCE PASS raw evidence saved; all response/SQL/RR/fault assertions passed");
    }
    void checkpoint(){report.put("status","running");json.writerWithDefaultPrettyPrinter().writeValue(outputFolder.resolve("progress.json").toFile(),report);}
    Sample request(App app,long shop)throws Exception{
        long start=System.nanoTime();var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port+"/api/v1/shops/"+shop)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
        var body=json.readTree(response.body());double ms=(System.nanoTime()-start)/1e6;
        assertThat(response.statusCode()).as("HTTP status").isEqualTo(200);return new Sample(ms,response.statusCode(),body.path("data"));
    }
    Map<String,Object> metrics(App app){
        var m=new LinkedHashMap<String,Object>();m.put("sql",app.counter.snapshot());m.put("loads",app.cache().metrics());if(app.enabled){m.put("redis",app.store().metrics());}return m;
    }
    RatingCacheKey key(long shop){return new RatingCacheKey(shop,jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM shop_rating_revision WHERE shop_id=?",Long.class,shop));}
    Map<String,Object> batch(App app,List<Long> shops,int workers,int count,List<JsonNode> allowed)throws Exception{
        var before=metrics(app);var begin=new CountDownLatch(1);var samples=new ArrayList<Sample>();
        long elapsed;
        try(var pool=Executors.newFixedThreadPool(workers)){
            var futures=new ArrayList<Future<Sample>>();
            for(int i=0;i<count;i++){long shop=shops.get(i%shops.size());futures.add(pool.submit(()->{begin.await();return request(app,shop);}));}
            long start=System.nanoTime();begin.countDown();
            for(var future:futures){var s=future.get(30,TimeUnit.SECONDS);assertThat(allowed).contains(s.data());samples.add(s);}
            elapsed=System.nanoTime()-start;
        }
        var result=new LinkedHashMap<String,Object>();result.put("samplesMs",samples.stream().map(Sample::ms).toList());result.put("n",count);
        result.put("wallMs",elapsed/1e6);result.put("batchCompletedPerSecond",count/(elapsed/1e9));result.put("errors",0);
        result.put("before",before);result.put("after",metrics(app));
        result.put("sqlDelta",app.counter.snapshot().minus((Counts)before.get("sql")));return result;
    }
    void pairedReads(int scale,App off,App on)throws Exception{
        for(var entry:Map.of("normal",NORMAL,"hot",HOT,"empty",EMPTY).entrySet()){
            long shop=entry.getValue();var expected=request(off,shop).data();assertThat(request(on,shop).data()).isEqualTo(expected);
            for(int i=0;i<6;i++){request(off,shop);request(on,shop);}
            for(int workers:List.of(1,4,16)){
                for(int round=1;round<=3;round++){
                    var apps=new ArrayList<>(List.of(off,on));Collections.shuffle(apps,random);
                    for(var app:apps){
                        request(app,shop);int n=workers==1?24:48;
                        var result=batch(app,List.of(shop),workers,n,List.of(expected));
                        result.put("scale",scale);result.put("scenario",entry.getKey());result.put("cache",app.enabled);result.put("workers",workers);result.put("round",round);
                        result.put("kind","warm");readStages.add(result);
                        var delta=(Counts)result.get("sqlDelta");assertThat(delta.sql()).isEqualTo(2L*n+delta.aggregates());
                        if(!app.enabled){assertThat(delta.aggregates()).isEqualTo(n);}
                    }
                }
            }
        }
    }
    void coldReads(int scale,App on)throws Exception{
        for(int workers:List.of(4,16)){
            for(long shop:List.of(NORMAL,HOT,EMPTY)){
                var expected=request(on,shop).data();on.redis().delete(key(shop).redisKey(on.namespace()));
                var result=batch(on,List.of(shop),workers,workers*2,List.of(expected));
                result.put("scale",scale);result.put("scenario",shop==HOT?"hot":shop==NORMAL?"normal":"empty");result.put("cache",true);result.put("workers",workers);result.put("kind","cold-wave");readStages.add(result);
            }
        }
        var expected=new ArrayList<JsonNode>();
        for(long shop:List.of(NORMAL,HOT,EMPTY)){expected.add(request(on,shop).data());on.redis().expire(key(shop).redisKey(on.namespace()),Duration.ofMillis(1));}
        Thread.sleep(10);var result=batch(on,List.of(NORMAL,HOT,EMPTY),16,48,expected);
        result.put("scale",scale);result.put("scenario","three-key-expiry");result.put("cache",true);result.put("workers",16);result.put("kind","expiry-wave");readStages.add(result);
    }
    AuthPrincipal principal(App app,long id){
        var row=app.context.getBean(AccountMapper.class).selectById(id);String jti=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(account_id,jti,created_at,expires_at) VALUES(?,?,?,?)",id,jti,LocalDateTime.ofInstant(NOW.minusSeconds(60),ZoneOffset.UTC),LocalDateTime.ofInstant(NOW.plusSeconds(7200),ZoneOffset.UTC));
        return new AuthPrincipal(id,jti,new AccountView(Long.toString(id),row.getUsername(),row.getNickname(),row.getRole(),row.getStatus(),Long.toString(row.getVersion())));
    }
    void mixedUpdates(App off,App on)throws Exception{
        off.counter.readPhase.set(false);var admin=principal(off,PublicShopQueryFixture.ADMIN);var tx=off.context.getBean(OrderReviewTransactions.class);
        var reviews=jdbc.queryForList("SELECT id,rating FROM order_review WHERE shop_id=? AND visibility='VISIBLE' ORDER BY id LIMIT 6",HOT);
        var sum=jdbc.queryForObject("SELECT SUM(rating) FROM order_review WHERE shop_id=? AND visibility='VISIBLE'",BigDecimal.class,HOT).toBigIntegerExact();
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM order_review WHERE shop_id=? AND visibility='VISIBLE'",Long.class,HOT);
        var before=request(on,HOT).data();var allowed=new ArrayList<JsonNode>();allowed.add(before);
        for(var row:reviews){sum=sum.subtract(BigInteger.valueOf(((Number)row.get("rating")).longValue()));count--;
            var node=(tools.jackson.databind.node.ObjectNode)before.deepCopy();node.put("reviewCount",count);node.put("rating",new BigDecimal(sum).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP).doubleValue());allowed.add(json.readTree(node.toString()));}
        var gate=new CountDownLatch(1);var samples=new ArrayList<Double>();var observed=new HashSet<JsonNode>();
        try(var writer=Executors.newSingleThreadExecutor();var readers=Executors.newFixedThreadPool(8)){
            var update=writer.submit(()->{gate.await();for(var row:reviews){tx.hide(admin,((Number)row.get("id")).longValue(),"并发读取验证");Thread.sleep(5);}return true;});
            var futures=new ArrayList<Future<Sample>>();for(int i=0;i<96;i++){futures.add(readers.submit(()->{gate.await();return request(on,HOT);}));}gate.countDown();
            for(var future:futures){var s=future.get(30,TimeUnit.SECONDS);assertThat(allowed).contains(s.data());observed.add(s.data());samples.add(s.ms());}assertThat(update.get(30,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(request(on,HOT).data()).isEqualTo(allowed.getLast());
        faults.add(new LinkedHashMap<>(Map.of("scenario","concurrent-six-hides-and-96-reads","samplesMs",samples,"validSnapshotStates",allowed.size(),"observedStates",observed.size(),"errors",0)));
    }
    String docker(String... args)throws Exception{
        String id=env.getRequiredProperty("CAMPUSLIFE_CACHE_CONTAINER_ID");assertThat(id).matches("[a-f0-9]{64}");
        var command=new ArrayList<String>(List.of("docker"));command.addAll(List.of(args));command.add(id);return process(command);
    }
    String redisCommand(String... args)throws Exception{
        String id=env.getRequiredProperty("CAMPUSLIFE_CACHE_CONTAINER_ID");assertThat(id).matches("[a-f0-9]{64}");
        var command=new ArrayList<String>(List.of("docker","exec",id,"redis-cli","--raw"));command.addAll(List.of(args));return process(command);
    }
    String process(List<String> command)throws Exception{
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertThat(process.exitValue()).as(output).isZero();return output.trim();
    }
    void verifyOwnedRedis()throws Exception{
        var labels=json.readTree(docker("inspect","--format","{{json .Config.Labels}}"));
        assertThat(labels.path("campuslife.fixture").asString()).isEqualTo(env.getRequiredProperty("CAMPUSLIFE_CACHE_FIXTURE_ID"));
    }
    void recover(App on,long shop)throws Exception{
        long hits=on.store().metrics().hits(),writes=on.store().metrics().writes();
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline){request(on,shop);try{if((on.store().metrics().hits()>hits||on.store().metrics().writes()>writes)&&Boolean.TRUE.equals(on.redis().hasKey(key(shop).redisKey(on.namespace())))){return;}}catch(org.springframework.dao.DataAccessException ex){/* client reconnecting */}Thread.sleep(250);}
        throw new AssertionError("Redis cache did not recover");
    }
    void faultChecks(App off,App on)throws Exception{
        var expected=request(off,HOT).data();recover(on,HOT);var before=metrics(on);long begin=System.nanoTime();
        docker("pause");Map<String,Object> paused;
        try{paused=batch(on,List.of(HOT),4,16,List.of(expected));}finally{docker("unpause");}
        assertThat(on.store().metrics().errors()).isGreaterThan(((RedisRatingStore.Metrics)before.get("redis")).errors());
        paused.put("scenario","redis-paused-real-command-timeout");faults.add(paused);recover(on,HOT);
        faults.add(new LinkedHashMap<>(Map.of("scenario","timeout-recovery","elapsedMs",(System.nanoTime()-begin)/1e6,"before",before,"after",metrics(on))));
        on.redis().delete(key(HOT).redisKey(on.namespace()));assertThat(redisCommand("ACL","SETUSER","default","-set")).isEqualTo("OK");
        before=metrics(on);begin=System.nanoTime();Sample denied;
        try{denied=request(on,HOT);assertThat(denied.data()).isEqualTo(expected);}finally{assertThat(redisCommand("ACL","SETUSER","default","+set")).isEqualTo("OK");}
        assertThat(on.store().metrics().errors()).isGreaterThan(((RedisRatingStore.Metrics)before.get("redis")).errors());
        faults.add(new LinkedHashMap<>(Map.of("scenario","actual-set-denied-but-http-success","samplesMs",List.of(denied.ms()),"before",before,"after",metrics(on),"errors",0)));
        recover(on,HOT);
        var admin=principal(off,PublicShopQueryFixture.ADMIN);before=metrics(on);docker("stop","--time","1");
        try{
            var first=request(on,HOT);assertThat(first.data()).isEqualTo(expected);
            long review=jdbc.queryForObject("SELECT id FROM order_review WHERE shop_id=? AND visibility='VISIBLE' ORDER BY id LIMIT 1",Long.class,HOT);
            off.context.getBean(OrderReviewTransactions.class).hide(admin,review,"Redis离线验证");
            var after=request(off,HOT).data();assertThat(request(on,HOT).data()).isEqualTo(after);
            var degraded=batch(on,List.of(HOT),4,16,List.of(after));degraded.put("scenario","redis-stopped-with-committed-hide");degraded.put("beforeStop",before);faults.add(degraded);
        }finally{docker("start");}
        begin=System.nanoTime();recover(on,HOT);
        faults.add(new LinkedHashMap<>(Map.of("scenario","restart-recovery","elapsedMs",(System.nanoTime()-begin)/1e6,"after",metrics(on))));
    }
    void writeCost(App off)throws Exception{
        off.counter.readPhase.set(false);
        var tx=off.context.getBean(OrderReviewTransactions.class);var revision=off.context.getBean(RatingRevisionMapper.class);
        var ids=jdbc.queryForList("SELECT o.id,o.student_id FROM service_order o JOIN account a ON a.id=o.student_id WHERE o.shop_id=? AND a.status='ACTIVE' AND o.status='COMPLETED' ORDER BY o.id LIMIT 480",HOT);
        assertThat(ids.size()).isEqualTo(480);
        var students=new HashMap<Long,AuthPrincipal>();for(var row:ids){long id=((Number)row.get("student_id")).longValue();if(!students.containsKey(id)){students.put(id,principal(off,id));}}
        var admins=new ArrayList<AuthPrincipal>();admins.add(principal(off,PublicShopQueryFixture.ADMIN));
        for(int n=1;n<8;n++){long id=900200+n;jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status) SELECT ?,?,password_hash,'隔离写基线管理员','ADMIN','ACTIVE' FROM account WHERE id=900000",id,"cache_write_admin_"+n);admins.add(principal(off,id));}
        int cursor=0;
        for(int workers:List.of(1,8)){
            for(int round=1;round<=3;round++){
                var modes=new ArrayList<>(List.of(false,true));Collections.shuffle(modes,random);
                for(boolean versioned:modes){
                    int n=32;var batch=ids.subList(cursor,cursor+n);cursor+=n;
                    for(var row:batch){jdbc.update("DELETE FROM order_review WHERE order_id=?",row.get("id"));}revision.advance(HOT);
                    var before=off.counter.snapshot();off.counter.revisionTimes.clear();var lockBefore=rowLockStatus();var samples=new ArrayList<Map<String,Object>>();var go=new CountDownLatch(1);long elapsed;
                    try(var pool=Executors.newFixedThreadPool(workers)){
                        var futures=new ArrayList<Future<Map<String,Object>>>();int index=0;
                        for(var row:batch){int lane=index++%8;long order=((Number)row.get("id")).longValue(),student=((Number)row.get("student_id")).longValue();
                            futures.add(pool.submit(()->{go.await();off.counter.omitRevision.set(!versioned);
                                try{long start=System.nanoTime();var review=tx.create(students.get(student),order,4,"写成本对照");double createMs=(System.nanoTime()-start)/1e6;
                                    start=System.nanoTime();tx.hide(admins.get(lane),Long.parseLong(review.id()),"写成本对照");double hideMs=(System.nanoTime()-start)/1e6;
                                    return Map.of("createMs",createMs,"hideMs",hideMs);
                                }finally{off.counter.omitRevision.remove();}
                            }));}
                        long start=System.nanoTime();go.countDown();for(var future:futures){samples.add(future.get(30,TimeUnit.SECONDS));}elapsed=System.nanoTime()-start;
                    }finally{revision.advance(HOT);} // Restore a distinct generation before any possible future reader.
                    var delta=off.counter.snapshot().minus(before);
                    assertThat(delta.revisionWrites()).isEqualTo((versioned?2L*n:0)+1);
                    var lockAfter=rowLockStatus();var lockDelta=new TreeMap<String,Long>();lockAfter.forEach((k,v)->lockDelta.put(k,v-lockBefore.get(k)));
                    writes.add(new LinkedHashMap<>(Map.of("versioned",versioned,"workers",workers,"round",round,"samples",samples,"wallMs",elapsed/1e6,"sqlDeltaIncludingFinalGenerationAdvance",delta,
                            "revisionStatementMsIncludingFinalAdvance",List.copyOf(off.counter.revisionTimes),"allInnoDbRowLockDelta",lockDelta)));
                }
            }
        }
    }
    Map<String,Long> rowLockStatus(){
        var result=new TreeMap<String,Long>();
        for(var row:jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits')")){
            result.put(row.get("Variable_name").toString(),Long.parseLong(row.get("Value").toString()));
        }
        assertThat(result).hasSize(2);return result;
    }
}
