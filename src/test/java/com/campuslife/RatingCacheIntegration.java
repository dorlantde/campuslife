package com.campuslife;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.merchant.cache.*;
import com.campuslife.merchant.config.RatingCacheProperties;
import com.campuslife.merchant.mapper.*;
import com.campuslife.order.service.OrderReviewTransactions;
import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in: owns the entire empty native fixture verified by PublicShopQueryFixture, never a user database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.rating-cache.enabled=true",
        "campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@Import(PublicShopQueryBaseline.CaptureConfiguration.class)
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_CACHE_IT",matches="isolated-fixture")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RatingCacheIntegration {
    private static final long SHOP=PublicShopQueryFixture.BASE+6;
    private static final Instant NOW=Instant.parse("2026-10-28T01:00:00Z");
    @Autowired JdbcTemplate jdbc; @Autowired Environment env; @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json; @Autowired AccountMapper accounts; @Autowired OrderReviewTransactions transactions;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("ratingRedisTemplate") StringRedisTemplate redis; @Autowired RatingCacheProperties props; @Autowired RatingCache cache;
    @Autowired PublicShopQueryBaseline.Capture capture; @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean Clock clock; @MockitoSpyBean PublicShopMapper shops; @MockitoSpyBean RatingRevisionMapper revisions;
    @MockitoSpyBean RedisRatingStore store;
    private final HttpClient http=HttpClient.newHttpClient();

    @BeforeAll void seed()throws Exception{
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);fixture.initialize();fixture.grow(1,10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_rating_revision",Integer.class)).isEqualTo(5);
        // Warm the test client, not cache data. Production never requires Redis for startup.
        for(int n=0;;n++){try{redis.hasKey("campuslife:"+props.namespace()+":test-probe");break;}catch(org.springframework.data.redis.RedisConnectionFailureException|org.springframework.dao.QueryTimeoutException ex){if(n==4){throw ex;}Thread.sleep(100);}}
    }
    @BeforeEach void prepare(){
        reset(clock,shops,revisions,store);doReturn(NOW).when(clock).instant();
        jdbc.update("UPDATE shop SET approval_status='APPROVED',business_enabled=TRUE WHERE id=?",SHOP);
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",SHOP);
        jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE shop_id=?",SHOP);
        revisions.advance(SHOP); // New fixture generation; never reuses a version cached by an earlier test.
    }
    @AfterEach void resetSpies(){capture.end();reset(shops,revisions,store,clock);assertThat(cache.metrics().inFlight()).isZero();}
    private long revision(long shop){return jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM shop_rating_revision WHERE shop_id=?",Long.class,shop);}
    private RatingCacheKey key(){return new RatingCacheKey(SHOP,revision(SHOP));}
    private HttpResponse<String> get(long shop)throws Exception{
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/shops/"+shop)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode detail(long shop)throws Exception{var r=get(shop);assertThat(r.statusCode()).isEqualTo(200);return json.readTree(r.body()).path("data");}
    private AuthPrincipal principal(long account){
        var row=accounts.selectById(account);String jti=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(account_id,jti,created_at,expires_at) VALUES(?,?,?,?)",account,jti,LocalDateTime.ofInstant(NOW.minusSeconds(60),ZoneOffset.UTC),LocalDateTime.ofInstant(NOW.plusSeconds(7200),ZoneOffset.UTC));
        return new AuthPrincipal(account,jti,new AccountView(Long.toString(account),row.getUsername(),row.getNickname(),row.getRole(),row.getStatus(),Long.toString(row.getVersion())));
    }
    private long visibleReview(long shop){return jdbc.queryForObject("SELECT id FROM order_review WHERE shop_id=? AND visibility='VISIBLE' ORDER BY id LIMIT 1",Long.class,shop);}
    private long unreviewed(int n){
        long id=SHOP*10000+n;jdbc.update("DELETE FROM order_review WHERE order_id=?",id);revisions.advance(SHOP);return id;
    }
    private AuthPrincipal student(long order){return principal(jdbc.queryForObject("SELECT student_id FROM service_order WHERE id=?",Long.class,order));}
    private void hide(long review){transactions.hide(principal(PublicShopQueryFixture.ADMIN),review,"隔离缓存验证");}

    @Test void fullHttpHitMissUseTwoOrThreeOrdinaryRrQueriesAndPreserveData()throws Exception{
        capture.begin();JsonNode first;
        try{first=detail(SHOP);}finally{capture.end();}
        assertThat(capture.snapshot()).hasSize(3);
        var missSql=capture.snapshot();
        capture.begin();JsonNode second;
        try{second=detail(SHOP);}finally{capture.end();}
        assertThat(second).isEqualTo(first);assertThat(capture.snapshot()).hasSize(2);
        assertThat(capture.snapshot()).allSatisfy(s->{assertThat(s.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);assertThat(s.readOnly()).isTrue();assertThat(s.sql()).doesNotContain("FOR UPDATE","FOR SHARE");});
        assertThat(capture.snapshot()).noneSatisfy(s->assertThat(s.sql()).contains("SUM(rating)"));
        assertThat(redis.getExpire(key().redisKey(props.namespace()))).isBetween(299L,360L);
        var payload=json.readTree(redis.opsForValue().get(key().redisKey(props.namespace())));assertThat(payload.size()).isEqualTo(5);
        System.out.println("CACHE_SQL_EVIDENCE "+json.writeValueAsString(Map.of("miss",missSql,"hit",capture.snapshot(),"dataEqual",true)));
    }
    @Test void redisFillOccursAfterTheReadTransactionReleasesItsConnection()throws Exception{
        doAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return inv.callRealMethod();}).when(store).put(any(),any());
        detail(SHOP);verify(store).put(any(),any());
    }
    @Test void zeroStatisticsAreCachedAndExpireWithoutInventingAVisibleShop()throws Exception{
        long empty=PublicShopQueryFixture.BASE+5;var result=detail(empty);assertThat(result.path("reviewCount").asLong()).isZero();assertThat(result.path("rating").isNull()).isTrue();
        var name=new RatingCacheKey(empty,0).redisKey(props.namespace());assertThat(redis.getExpire(name)).isBetween(29L,45L);
        capture.begin();try{assertThat(detail(empty)).isEqualTo(result);}finally{capture.end();}assertThat(capture.snapshot()).hasSize(2);
        redis.expire(name,Duration.ofMillis(1));Thread.sleep(10);
        capture.begin();try{assertThat(detail(empty)).isEqualTo(result);}finally{capture.end();}assertThat(capture.snapshot()).hasSize(3);
        assertThat(get(Long.MAX_VALUE).statusCode()).isEqualTo(404);verify(store,never()).read(new RatingCacheKey(Long.MAX_VALUE,0));
    }
    @ParameterizedTest @ValueSource(strings={"broken","wrongVersion","unknownSchema"})
    void invalidCacheCannotChangeTheResponseAndGetsRebuilt(String kind)throws Exception{
        var before=detail(SHOP);String name=key().redisKey(props.namespace());String bad="broken";
        if(!kind.equals("broken")){var node=(tools.jackson.databind.node.ObjectNode)json.readTree(redis.opsForValue().get(name));if(kind.equals("wrongVersion")){node.put("revision","0");}else{node.put("schema",99);}bad=node.toString();}
        redis.opsForValue().set(name,bad,Duration.ofSeconds(60));capture.begin();
        try{assertThat(detail(SHOP)).isEqualTo(before);}finally{capture.end();}
        assertThat(capture.snapshot()).hasSize(3);new RatingCacheCodec(json).decode(key(),redis.opsForValue().get(name));
    }
    @ParameterizedTest @ValueSource(strings={"SHOP","ACCOUNT","OWNER"})
    void cachedStatisticsCannotBypassNewSnapshotVisibility(String kind)throws Exception{
        detail(SHOP);switch(kind){case "SHOP"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",SHOP);case "ACCOUNT"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",SHOP);default->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",SHOP);}
        clearInvocations(store);assertThat(get(SHOP).statusCode()).isEqualTo(404);verify(store,never()).read(any());
    }
    @Test void operatingSwitchAndHoursRemainLiveWhileRatingHits()throws Exception{
        var before=detail(SHOP);assertThat(before.path("openNow").asBoolean()).isTrue();
        jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?",SHOP);
        var after=detail(SHOP);assertThat(after.path("openNow").asBoolean()).isFalse();assertThat(after.path("rating")).isEqualTo(before.path("rating"));
    }
    @Test void firstReviewCreatesRevisionAndSubmissionAndHideAdvanceExactlyOnce()throws Exception{
        long shop=PublicShopQueryFixture.BASE+2;jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?",shop);
        jdbc.update("DELETE FROM order_review WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_rating_revision WHERE shop_id=?",shop);
        assertThat(detail(shop).path("reviewCount").asLong()).isZero();long order=shop*10000+1;
        var user=student(order);var review=transactions.create(user,order,4,"首次评价");assertThat(revision(shop)).isEqualTo(1);
        assertThat(detail(shop).path("rating").asDouble()).isEqualTo(4);
        assertThatThrownBy(()->transactions.create(user,order,5,"重复")).isInstanceOf(RuntimeException.class);assertThat(revision(shop)).isEqualTo(1);
        var admin=principal(PublicShopQueryFixture.ADMIN);transactions.hide(admin,Long.parseLong(review.id()),"相同原因");assertThat(revision(shop)).isEqualTo(2);
        transactions.hide(admin,Long.parseLong(review.id()),"相同原因");assertThat(revision(shop)).isEqualTo(2);
        assertThat(detail(shop).path("reviewCount").asLong()).isZero();
    }
    @Test void revisionFailureRollsBackReviewCreationAndHiding()throws Exception{
        long order=unreviewed(3), before=revision(SHOP);var user=student(order);
        doThrow(new CannotAcquireLockException("injected revision failure")).when(revisions).advance(SHOP);
        assertThatThrownBy(()->transactions.create(user,order,5,"回滚")).isInstanceOf(CannotAcquireLockException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_review WHERE order_id=?",Long.class,order)).isZero();assertThat(revision(SHOP)).isEqualTo(before);
        long review=visibleReview(SHOP);var admin=principal(PublicShopQueryFixture.ADMIN);
        assertThatThrownBy(()->transactions.hide(admin,review,"回滚隐藏")).isInstanceOf(CannotAcquireLockException.class);
        assertThat(jdbc.queryForObject("SELECT visibility FROM order_review WHERE id=?",String.class,review)).isEqualTo("VISIBLE");assertThat(revision(SHOP)).isEqualTo(before);
    }
    @Test void concurrentDifferentOrdersSerializeOnlyAtTheFinalRevisionRow()throws Exception{
        long a=unreviewed(11),b=unreviewed(12),before=revision(SHOP);var pa=student(a);var pb=student(b);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->{go.await();return transactions.create(pa,a,3,"并发甲");});
            var two=pool.submit(()->{go.await();return transactions.create(pb,b,5,"并发乙");});go.countDown();
            assertThat(one.get(20,TimeUnit.SECONDS)).isNotNull();assertThat(two.get(20,TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(revision(SHOP)).isEqualTo(before+2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_review WHERE order_id IN (?,?)",Long.class,a,b)).isEqualTo(2);
        detail(SHOP);
    }
    @Test void oldRrSnapshotStillMatchesItsRevisionAfterConcurrentHide()throws Exception{
        var before=detail(SHOP);long old=revision(SHOP),review=visibleReview(SHOP);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        var real=sqlSession.getMapper(PublicShopMapper.class);
        doAnswer(inv->{var row=real.visibleWithRatingRevision(SHOP);reached.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();sqlSession.clearCache();assertThat(real.visibleWithRatingRevision(SHOP).ratingRevision()).isEqualTo(old);return row;}).when(shops).visibleWithRatingRevision(SHOP);
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->detail(SHOP));try{assertThat(reached.await(15,TimeUnit.SECONDS)).isTrue();hide(review);}finally{release.countDown();}
            assertThat(future.get(20,TimeUnit.SECONDS)).isEqualTo(before);
        }
        reset(shops);assertThat(detail(SHOP).path("reviewCount").asLong()).isEqualTo(before.path("reviewCount").asLong()-1);
    }
    @Test void lateOldFillCannotOverwriteTheNewVersionKey()throws Exception{
        long old=revision(SHOP),review=visibleReview(SHOP);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(inv->{RatingCacheKey k=inv.getArgument(0);if(k.revision()==old){reached.countDown();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();}return inv.callRealMethod();}).when(store).put(any(),any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->detail(SHOP));JsonNode current;
            try{assertThat(reached.await(15,TimeUnit.SECONDS)).isTrue();hide(review);current=detail(SHOP);}finally{release.countDown();}
            var previous=future.get(20,TimeUnit.SECONDS);assertThat(previous.path("reviewCount").asLong()).isEqualTo(current.path("reviewCount").asLong()+1);
            assertThat(detail(SHOP)).isEqualTo(current);
            var codec=new RatingCacheCodec(json);assertThat(codec.decode(key(),redis.opsForValue().get(key().redisKey(props.namespace()))).count()).isEqualTo(current.path("reviewCount").asLong());
        }
    }
    @Test void concurrentHttpReadCanReuseCommittedFlightBeforeRedisFill()throws Exception{
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);long before=cache.metrics().coalesced();
        doAnswer(inv->{reached.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return inv.callRealMethod();}).when(store).put(any(),any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var owner=pool.submit(()->detail(SHOP));JsonNode follower;
            try{assertThat(reached.await(15,TimeUnit.SECONDS)).isTrue();capture.begin();try{follower=detail(SHOP);}finally{capture.end();}assertThat(capture.snapshot()).hasSize(2);}
            finally{release.countDown();}
            assertThat(owner.get(20,TimeUnit.SECONDS)).isEqualTo(follower);
        }
        assertThat(cache.metrics().coalesced()).isEqualTo(before+1);
    }
    @Test void mysqlFailureCannotBeServedFromAnExistingCache()throws Exception{
        detail(SHOP);doThrow(new CannotAcquireLockException("injected DB failure")).when(shops).visibleWithRatingRevision(SHOP);
        assertThat(get(SHOP).statusCode()).isGreaterThanOrEqualTo(500);
    }
    @Test void revisionOverflowRollsBackTheReviewChange(){
        long shop=PublicShopQueryFixture.BASE+8;long review=visibleReview(shop);
        jdbc.update("UPDATE shop_rating_revision SET revision=? WHERE shop_id=?",Long.MAX_VALUE,shop);
        assertThatThrownBy(()->hide(review)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(revision(shop)).isEqualTo(Long.MAX_VALUE);assertThat(jdbc.queryForObject("SELECT visibility FROM order_review WHERE id=?",String.class,review)).isEqualTo("VISIBLE");
    }
    private String docker(String... arguments)throws Exception{
        String id=env.getRequiredProperty("CAMPUSLIFE_CACHE_CONTAINER_ID");assertThat(id).matches("[a-f0-9]{64}");
        var command=new ArrayList<String>();command.add("docker");command.addAll(List.of(arguments));command.add(id);
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
        String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertThat(process.exitValue()).as(result).isZero();return result.trim();
    }
    @Test @Order(Integer.MAX_VALUE) void actualRedisStopAllowsFallbackAndRecoveryWithoutTouchingReviewFacts()throws Exception{
        String labels=docker("inspect","--format","{{json .Config.Labels}}");
        assertThat(json.readTree(labels).path("campuslife.fixture").asString()).isEqualTo(env.getRequiredProperty("CAMPUSLIFE_CACHE_FIXTURE_ID"));
        var before=detail(SHOP);long errors=store.metrics().errors(),bypasses=store.metrics().bypasses();docker("stop","--time","1");
        try{
            assertThat(detail(SHOP)).isEqualTo(before);assertThat(detail(SHOP)).isEqualTo(before);
            assertThat(store.metrics().errors()).isGreaterThan(errors);assertThat(store.metrics().bypasses()).isGreaterThan(bypasses);
            // A fresh application also starts with the enabled cache offline, then serves via MySQL.
            try(var offline=new org.springframework.boot.builder.SpringApplicationBuilder(CampusLifeApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("mysql")
                    .run("--campuslife.rating-cache.enabled=true","--campuslife.rating-cache.namespace="+props.namespace(),
                            "--campuslife.rating-cache.port="+props.port())){
                assertThat(offline.getBean(RatingCache.class).enabled()).isTrue();
                assertThat(offline.getBean(com.campuslife.merchant.service.PublicShopCatalogService.class).detail(Long.toString(SHOP)).reviewCount())
                        .isEqualTo(before.path("reviewCount").asLong());
                assertThat(offline.getBean(RedisRatingStore.class).metrics().errors()).isGreaterThan(0);
            }
            hide(visibleReview(SHOP));assertThat(detail(SHOP).path("reviewCount").asLong()).isEqualTo(before.path("reviewCount").asLong()-1);
        }finally{docker("start");}
        long deadline=System.nanoTime()+Duration.ofSeconds(16).toNanos();
        while(System.nanoTime()<deadline){detail(SHOP);try{if(Boolean.TRUE.equals(redis.hasKey(key().redisKey(props.namespace())))){break;}}catch(org.springframework.dao.DataAccessException ex){/* reconnecting: includes rejected commands on a disconnected shared connection */}Thread.sleep(250);}
        assertThat(redis.hasKey(key().redisKey(props.namespace()))).isTrue();assertThat(detail(SHOP).path("reviewCount").asLong()).isEqualTo(before.path("reviewCount").asLong()-1);
        System.out.println("CACHE_EVIDENCE "+json.writeValueAsString(Map.of("store",store.metrics(),"loads",cache.metrics(),"mysql","real isolated MySQL","redis","8.2.4")));
    }
}
