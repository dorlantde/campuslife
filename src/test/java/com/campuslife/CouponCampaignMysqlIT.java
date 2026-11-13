package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.dto.*;
import com.campuslife.coupon.mapper.CampaignMapper;
import com.campuslife.coupon.service.MerchantCampaignService;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.ShopMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponCampaignMysqlIT {
    private static final String PATH="/api/v1/merchant/coupon-campaigns",PASSWORD="Coupon_test_2026!";
    private final HttpClient client=HttpClient.newHttpClient();
    private final List<Long> accountIds=new ArrayList<>(),shopIds=new ArrayList<>();
    private String hash;private Instant base;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment env;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountMapper accounts;
    @Autowired MerchantCampaignService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @MockitoSpyBean CampaignMapper campaigns;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean Clock clock;
    @BeforeAll void password(){hash=passwords.encode(PASSWORD);}
    @BeforeEach void time(){base=Instant.now().truncatedTo(ChronoUnit.MILLIS);}
    @AfterEach void cleanup(){
        reset(campaigns,shops,clock);
        for(long shop:shopIds){jdbc.update("DELETE FROM coupon_campaign WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);}
        for(long id:accountIds){jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);}
        for(long id:accountIds){jdbc.update("DELETE FROM account WHERE id=?",id);}
        shopIds.clear();accountIds.clear();
    }
    @Test void lifecycleFreezesRulesAndReplaysOnlyOriginalTransitions() throws Exception {
        var u=merchant("HAIRCUT");var token=login(u);var unchanged=identity(u);
        var body=body();body.put("name","  "+"😀".repeat(80)+"  ");
        var created=data(call("POST",PATH,body,token),201);String id=created.path("id").asString();
        assertThat(created.path("name").asString()).isEqualTo("😀".repeat(80));assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("issuedCount").asInt()).isZero();assertThat(created.path("version").asString()).isEqualTo("0");
        var snapshot=snapshot(id);body.put("expectedVersion","0");
        assertThat(data(call("PATCH",PATH+"/"+id,body,token),200)).isEqualTo(created);assertThat(snapshot(id)).isEqualTo(snapshot);
        body.put("name","新活动");var modified=data(call("PATCH",PATH+"/"+id,body,token),200);assertThat(modified.path("version").asString()).isEqualTo("1");
        expect(call("PATCH",PATH+"/"+id,body,token),409,"CONFIG_VERSION_CONFLICT");
        var published=data(action(id,"publish","1",token),200);assertThat(published.path("version").asString()).isEqualTo("2");
        assertThat(published.path("publishedBy").asString()).isEqualTo(Long.toString(u.id()));assertThat(published.path("publishExpectedVersion").asString()).isEqualTo("1");
        snapshot=snapshot(id);assertThat(data(action(id,"publish","1",token),200)).isEqualTo(published);assertThat(snapshot(id)).isEqualTo(snapshot);
        body.put("expectedVersion","2");expect(call("PATCH",PATH+"/"+id,body,token),409,"COUPON_CAMPAIGN_STATE_CONFLICT");
        var stopped=data(action(id,"stop","2",token),200);assertThat(stopped.path("status").asString()).isEqualTo("STOPPED");assertThat(stopped.path("version").asString()).isEqualTo("3");
        snapshot=snapshot(id);assertThat(data(action(id,"stop","2",token),200)).isEqualTo(stopped);assertThat(snapshot(id)).isEqualTo(snapshot);
        expect(action(id,"publish","1",token),409,"CONFIG_VERSION_CONFLICT");expect(action(id,"publish","3",token),409,"COUPON_CAMPAIGN_STATE_CONFLICT");
        expect(action(id,"stop","3",token),409,"COUPON_CAMPAIGN_STATE_CONFLICT");expect(call("DELETE",PATH+"/"+id,null,token),405,"METHOD_NOT_ALLOWED");
        assertThat(data(call("GET",PATH+"/"+id,null,token),200)).isEqualTo(stopped);assertThat(identity(u)).isEqualTo(unchanged);
    }
    @Test void bothCategoriesAmountAndOffsetBoundariesAreExact() throws Exception {
        var u=merchant("PRINT");var token=login(u);var b=body();b.put("thresholdAmount",5000000000L);b.put("reductionAmount",4999999999L);b.put("totalQuantity",100000);
        b.put("claimStartAt",base.minusSeconds(60).atOffset(ZoneOffset.ofHours(8)).toString());
        var row=data(call("POST",PATH,b,token),201);assertThat(row.path("thresholdAmount").asLong()).isEqualTo(5000000000L);
        assertThat(Instant.parse(row.path("claimStartAt").asString())).isEqualTo(base.minusSeconds(60));
        b=body();b.put("thresholdAmount",2);b.put("reductionAmount",1);b.put("totalQuantity",1);data(call("POST",PATH,b,token),201);
    }
    @ParameterizedTest @MethodSource("badFields")
    void strictFieldsCannotCreateOrModify(String field,Object value,boolean remove) throws Exception {
        var u=merchant("HAIRCUT");var token=login(u);String id=create(token);var before=snapshot(id);var b=body();
        if(remove){b.remove(field);}else{b.put(field,value);}
        expect(call("POST",PATH,b,token),400,"VALIDATION_FAILED");
        b.put("expectedVersion","0");expect(call("PATCH",PATH+"/"+id,b,token),400,"VALIDATION_FAILED");
        assertThat(snapshot(id)).isEqualTo(before);assertThat(count(u)).isEqualTo(1);
    }
    static Stream<Arguments> badFields(){
        var cases=new ArrayList<Arguments>();
        for(String field:List.of("name","thresholdAmount","reductionAmount","totalQuantity","claimStartAt","claimEndAt","useStartAt","useEndAt")){
            cases.add(Arguments.of(field,null,true));cases.add(Arguments.of(field,null,false));
        }
        for(Object value:List.of(" ","中".repeat(81),"\uD800",true,12))cases.add(Arguments.of("name",value,false));
        for(Object value:List.of(0,-1,1,5000000001L,"3000",3000.0,true))cases.add(Arguments.of("thresholdAmount",value,false));
        for(Object value:List.of(0,-1,3000,3001,5000000000L,"500",500.0))cases.add(Arguments.of("reductionAmount",value,false));
        for(Object value:List.of(0,-1,100001,"100",100.0,true))cases.add(Arguments.of("totalQuantity",value,false));
        for(Object value:List.of("2026-11-01T00:00:00","2026-02-30T00:00:00Z","2026-11-01T00:00:00.000001Z","0999-01-01T00:00:00Z","10000-01-01T00:00:00Z",true,12))cases.add(Arguments.of("claimStartAt",value,false));
        cases.add(Arguments.of("claimEndAt","1000-01-01T00:00:00Z",false));cases.add(Arguments.of("useStartAt","9999-01-01T00:00:00Z",false));
        cases.add(Arguments.of("useEndAt","1000-01-01T00:00:00Z",false));
        for(String field:List.of("shopId","status","issuedCount","version","publishedBy","couponId"))cases.add(Arguments.of(field,"1",false));
        return cases.stream();
    }
    @Test void invalidVersionsPaginationIdsAndTransitionBodiesDoNotWrite() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);var before=snapshot(id);
        for(Object v:Arrays.asList(null,0,0.0,"00","-1"," 0","9223372036854775808")){
            var b=new LinkedHashMap<String,Object>();b.put("expectedVersion",v);expect(call("POST",PATH+"/"+id+"/publish",b,token),400,"VALIDATION_FAILED");
        }
        expect(call("POST",PATH+"/"+id+"/publish",Map.of(),token),400,"VALIDATION_FAILED");
        expect(call("POST",PATH+"/"+id+"/publish",Map.of("expectedVersion","0","name","x"),token),400,"VALIDATION_FAILED");
        expect(call("PATCH",PATH+"/"+id,Map.of("expectedVersion","0"),token),400,"VALIDATION_FAILED");
        for(String value:List.of("0","01","-1","abc","9223372036854775808")){expect(call("GET",PATH+"/"+value,null,token),400,"VALIDATION_FAILED");}
        for(String query:List.of("page=0","page=-1","page=2147483648","page=","size=0","size=101","size=1.0")){expect(call("GET",PATH+"?"+query,null,token),400,"VALIDATION_FAILED");}
        expect(action(id,"stop","0",token),409,"COUPON_CAMPAIGN_STATE_CONFLICT");assertThat(snapshot(id)).isEqualTo(before);
    }
    @Test void listIsScopedSortedAndLongOffsetDoesNotOverflow() throws Exception {
        var a=merchant("HAIRCUT");var b=merchant("PRINT");String token=login(a);String first=create(token),second=create(token);create(login(b));
        var page=data(call("GET",PATH+"?size=1",null,token),200);assertThat(page.path("total").asLong()).isEqualTo(2);assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(second);
        var next=data(call("GET",PATH+"?size=1&page=2",null,token),200);assertThat(next.path("items").get(0).path("id").asString()).isEqualTo(first);
        var end=data(call("GET",PATH+"?page=2147483647&size=100",null,token),200);assertThat(end.path("total").asLong()).isEqualTo(2);assertThat(end.path("items").isEmpty()).isTrue();
        expect(call("GET",PATH+"/"+second,null,login(b)),404,"RESOURCE_NOT_FOUND");expect(action(second,"stop","0",login(b)),404,"RESOURCE_NOT_FOUND");
        expect(call("GET",PATH+"/9223372036854775807",null,token),404,"RESOURCE_NOT_FOUND");
    }
    @Test void stopAndReadRemainAllowedAfterSuspensionButOtherWritesDoNot() throws Exception {
        var u=merchant("PRINT");String token=login(u),id=create(token),draft=create(token);data(action(id,"publish","0",token),200);
        jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",u.shop());
        data(call("GET",PATH,null,token),200);data(call("GET",PATH+"/"+id,null,token),200);data(action(id,"stop","1",token),200);
        expect(call("POST",PATH,body(),token),403,"SHOP_NOT_APPROVED");expect(action(draft,"publish","0",token),403,"SHOP_NOT_APPROVED");
        var patch=body();patch.put("expectedVersion","0");expect(call("PATCH",PATH+"/"+draft,patch,token),403,"SHOP_NOT_APPROVED");
        jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",u.shop());expect(call("GET",PATH,null,token),403,"ROLE_FORBIDDEN");expect(action(id,"stop","1",token),403,"ROLE_FORBIDDEN");
    }
    @Test void authenticationAndStalePrincipalCannotBypassCurrentAccount() throws Exception {
        expect(call("GET",PATH,null,null),401,"AUTH_REQUIRED");
        for(String role:List.of("STUDENT","ADMIN")){var u=account(role);expect(call("GET",PATH,null,login(u)),403,"ROLE_FORBIDDEN");}
        var unowned=account("MERCHANT");expect(call("GET",PATH,null,login(unowned)),404,"RESOURCE_NOT_FOUND");
        var u=merchant("HAIRCUT");String token=login(u);String jti=jdbc.queryForObject("SELECT jti FROM auth_session WHERE account_id=?",String.class,u.id());
        var principal=new AuthPrincipal(u.id(),jti,new AccountView(Long.toString(u.id()),u.name(),"测试","MERCHANT","ACTIVE","0"));
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",u.id());
        assertBusiness(()->service.create(principal,json.readValue(json.writeValueAsString(body()),CampaignCreate.class)),"ACCOUNT_DISABLED");
        assertBusiness(()->service.list(principal,null,null),"ACCOUNT_DISABLED");expect(call("GET",PATH,null,token),403,"ACCOUNT_DISABLED");assertThat(count(u)).isZero();
    }
    @Test void firstPublishChecksRightOpenWindowButRetryKeepsOriginalFact() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),published=create(token),unpublished=create(token);data(action(published,"publish","0",token),200);var before=snapshot(published);
        doReturn(base.plusSeconds(900)).when(clock).instant();
        expect(action(unpublished,"publish","0",token),409,"COUPON_CLAIM_WINDOW_ENDED");data(action(published,"publish","0",token),200);assertThat(snapshot(published)).isEqualTo(before);
        data(action(published,"stop","1",token),200);
    }
    @Test void historicalDraftCanBeSavedButNotPublishedAndTimeRollbackCannotWrite() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u);var b=body();b.put("claimStartAt",base.minusSeconds(120).toString());b.put("claimEndAt",base.minusSeconds(60).toString());
        String expired=data(call("POST",PATH,b,token),201).path("id").asString();expect(action(expired,"publish","0",token),409,"COUPON_CLAIM_WINDOW_ENDED");
        String id=create(token);
        // Preserve a valid JWT time range, but put the persisted business fact ahead of the decision clock.
        jdbc.update("UPDATE coupon_campaign SET updated_at=? WHERE id=?",java.sql.Timestamp.from(base.plusSeconds(120)),id);
        var before=snapshot(id);doReturn(base.plusSeconds(60)).when(clock).instant();
        expect(action(id,"publish","0",token),409,"COUPON_TIME_INVALID");assertThat(snapshot(id)).isEqualTo(before);
    }
    @Test void maxVersionAllowsNoopButRejectsActualMutation() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);jdbc.update("UPDATE coupon_campaign SET version=? WHERE id=?",Long.MAX_VALUE,id);
        var b=body();b.put("expectedVersion",Long.toString(Long.MAX_VALUE));var before=snapshot(id);
        data(call("PATCH",PATH+"/"+id,b,token),200);assertThat(snapshot(id)).isEqualTo(before);
        b.put("name","变化");expect(call("PATCH",PATH+"/"+id,b,token),409,"CONFIG_VERSION_CONFLICT");expect(action(id,"publish",Long.toString(Long.MAX_VALUE),token),409,"CONFIG_VERSION_CONFLICT");
    }
    @Test void ownerChangeCannotReplayAnotherActorsTransition() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);data(action(id,"publish","0",token),200);var other=account("MERCHANT");
        jdbc.update("UPDATE shop_member SET account_id=? WHERE shop_id=?",other.id(),u.shop());String changed=login(other);
        expect(action(id,"publish","0",changed),409,"CONFIG_VERSION_CONFLICT");data(action(id,"stop","1",changed),200);
        assertThat(snapshot(id).get("published_by")).isEqualTo(u.id());assertThat(snapshot(id).get("stopped_by")).isEqualTo(other.id());
    }
    @Test void concurrentDifferentDraftChangesHaveOneWinner() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);var a=body();a.put("expectedVersion","0");a.put("name","甲");var b=new LinkedHashMap<>(a);b.put("name","乙");
        try(var pool=Executors.newFixedThreadPool(2)){
            var left=pool.submit(()->call("PATCH",PATH+"/"+id,a,token));var right=pool.submit(()->call("PATCH",PATH+"/"+id,b,token));
            assertThat(List.of(left.get().statusCode(),right.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(snapshot(id).get("version")).isEqualTo(1L);assertThat(snapshot(id).get("name")).isIn("甲","乙");
    }
    @Test void campaignLockWaitUsesFinalTimeAndCannotPublishAtDeadline() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);var held=new CountDownLatch(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sql.getMapper(CampaignMapper.class);
        doAnswer(inv->{entered.countDown();return real.lockOwn(Long.parseLong(id),u.shop());}).when(campaigns).lockOwn(Long.parseLong(id),u.shop());
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM coupon_campaign WHERE id=? FOR UPDATE",Long.class,id);held.countDown();await(release);}));
            try{
                assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();var request=pool.submit(()->action(id,"publish","0",token));
                assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->request.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                doReturn(base.plusSeconds(900)).when(clock).instant();release.countDown();holder.get(20,TimeUnit.SECONDS);expect(request.get(20,TimeUnit.SECONDS),409,"COUPON_CLAIM_WINDOW_ENDED");
            }finally{release.countDown();}
        }
        assertThat(snapshot(id).get("status")).isEqualTo("DRAFT");
    }
    @Test void shopLockWaitRechecksApprovalBeforePublishing() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);var held=new CountDownLatch(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sql.getMapper(ShopMapper.class);
        doAnswer(inv->{entered.countDown();return real.share(u.shop());}).when(shops).share(u.shop());
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE",Long.class,u.shop());held.countDown();await(release);jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",u.shop());}));
            try{
                assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();var request=pool.submit(()->action(id,"publish","0",token));assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->request.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();holder.get(20,TimeUnit.SECONDS);expect(request.get(20,TimeUnit.SECONDS),403,"SHOP_NOT_APPROVED");
            }finally{release.countDown();}
        }
        assertThat(snapshot(id).get("status")).isEqualTo("DRAFT");
    }
    @Test void readSnapshotKeepsQualificationTotalAndPageTogether() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);var counted=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sql.getMapper(CampaignMapper.class);
        doAnswer(inv->{long n=real.countOwn(u.shop());counted.countDown();await(release);return n;}).when(campaigns).countOwn(u.shop());
        try(var pool=Executors.newSingleThreadExecutor()){
            var request=pool.submit(()->call("GET",PATH,null,token));
            try{
                assertThat(counted.await(15,TimeUnit.SECONDS)).isTrue();create(token);jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",u.shop());release.countDown();
                var page=data(request.get(20,TimeUnit.SECONDS),200);assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("items").size()).isEqualTo(1);assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(id);
            }finally{release.countDown();}
        }
        expect(call("GET",PATH,null,token),403,"ROLE_FORBIDDEN");
    }
    @Test void finalSessionExpiryRollsBackAlreadyExecutedPublish() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);var before=snapshot(id);var real=sql.getMapper(CampaignMapper.class);
        Instant expiry=jdbc.queryForObject("SELECT expires_at FROM auth_session WHERE account_id=?",java.sql.Timestamp.class,u.id()).toInstant();
        doAnswer(inv->{int n=real.publish(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3));doReturn(expiry).when(clock).instant();return n;}).when(campaigns).publish(anyLong(),anyLong(),anyLong(),any());
        expect(action(id,"publish","0",token),401,"SESSION_EXPIRED");assertThat(snapshot(id)).isEqualTo(before);
    }
    @Test void failureAfterStateWriteRollsBackAllTransitionFields() throws Exception {
        var u=merchant("HAIRCUT");String token=login(u),id=create(token);data(action(id,"publish","0",token),200);var before=snapshot(id);var real=sql.getMapper(CampaignMapper.class);
        doAnswer(inv->{real.stop(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3));throw new IllegalStateException("isolated rollback injection");}).when(campaigns).stop(anyLong(),anyLong(),anyLong(),any());
        expect(action(id,"stop","1",token),500,"INTERNAL_ERROR");assertThat(snapshot(id)).isEqualTo(before);
    }
    @Test void databaseEnforcesAmountsWindowStockStatesAndForeignKeys() throws Exception {
        var u=merchant("HAIRCUT");String id=create(login(u));var before=snapshot(id);
        for(String clause:List.of("reduction_amount=threshold_amount","reduction_amount=0","threshold_amount=5000000001","total_quantity=0","total_quantity=100001","issued_count=1","issued_count=-1","claim_end_at=claim_start_at","use_start_at=claim_end_at","use_end_at=claim_start_at","status='published'","version=-1","published_at=created_at")){
            assertSql(()->jdbc.update("UPDATE coupon_campaign SET "+clause+" WHERE id=?",id),3819);assertThat(snapshot(id)).isEqualTo(before);
        }
        assertSql(()->jdbc.update("UPDATE coupon_campaign SET shop_id=? WHERE id=?",Long.MAX_VALUE,id),1216,1452);
    }

    private record User(long id,String name,long shop){}
    private User account(String role){
        var a=new AccountEntity();a.setUsername("cp_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("虚构活动测试账号");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());return new User(a.getId(),a.getUsername(),0);
    }
    private User merchant(String category){
        var a=account("MERCHANT");var shop=new ShopEntity();shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?",Long.class,category));shop.setName("虚构优惠券店");shop.setCampusCode("XINGHAI_MAIN");shop.setLocationText("虚构位置");shop.setDescription("");shop.setApprovalStatus("APPROVED");shop.setBusinessEnabled(false);shop.setVersion(1L);shops.insert(shop);shopIds.add(shop.getId());jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop.getId(),a.id());return new User(a.id(),a.name(),shop.getId());
    }
    private Map<String,Object> body(){
        var b=new LinkedHashMap<String,Object>();b.put("name","满30减5");b.put("thresholdAmount",3000);b.put("reductionAmount",500);b.put("totalQuantity",100);
        b.put("claimStartAt",base.minusSeconds(60).toString());b.put("claimEndAt",base.plusSeconds(900).toString());b.put("useStartAt",base.minusSeconds(120).toString());b.put("useEndAt",base.plusSeconds(1800).toString());return b;
    }
    private String create(String token)throws Exception{return data(call("POST",PATH,body(),token),201).path("id").asString();}
    private int count(User u){return jdbc.queryForObject("SELECT COUNT(*) FROM coupon_campaign WHERE shop_id=?",Integer.class,u.shop());}
    private Map<String,Object> snapshot(String id){return jdbc.queryForMap("SELECT * FROM coupon_campaign WHERE id=?",id);}
    private List<?> identity(User u){return List.of(jdbc.queryForMap("SELECT * FROM account WHERE id=?",u.id()),jdbc.queryForMap("SELECT * FROM shop WHERE id=?",u.shop()),jdbc.queryForMap("SELECT * FROM shop_member WHERE shop_id=?",u.shop()));}
    private String login(User u)throws Exception{return data(call("POST","/api/v1/auth/login",Map.of("username",u.name(),"password",PASSWORD),null),200).path("accessToken").asString();}
    private HttpResponse<String> action(String id,String action,String version,String token)throws Exception{return call("POST",PATH+"/"+id+"/"+action,Map.of("expectedVersion",version),token);}
    private JsonNode data(HttpResponse<String> response,int status)throws Exception{return expect(response,status,"OK").path("data");}
    private JsonNode expect(HttpResponse<String> r,int status,String code)throws Exception{assertThat(r.statusCode()).as(r.body()).isEqualTo(status);var node=json.readTree(r.body());assertThat(node.path("code").asString()).isEqualTo(code);return node;}
    private HttpResponse<String> call(String method,String path,Object body,String token)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(30));if(token!=null){r.header("Authorization","Bearer "+token);}if(body!=null){r.header("Content-Type","application/json");}
        r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body).replace("\uD800","\\uD800"),StandardCharsets.UTF_8));return client.send(r.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS)){throw new IllegalStateException("Test gate timeout");}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code){assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo(code));}
    private static void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int... codes){assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(e->assertThat(codes).contains(((SQLException)((DataAccessException)e).getMostSpecificCause()).getErrorCode()));}
}
