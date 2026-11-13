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
class CouponClaimsMysqlIT {
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
    @Autowired com.campuslife.coupon.service.CouponClaimService claimService;
    @MockitoSpyBean com.campuslife.coupon.mapper.ClaimRequestMapper requests;
    @MockitoSpyBean com.campuslife.coupon.mapper.OwnedCouponMapper owned;
    @BeforeAll void password(){hash=passwords.encode(PASSWORD);}
    @BeforeEach void time(){base=Instant.now().truncatedTo(ChronoUnit.MILLIS);}
    @AfterEach void cleanup(){
        reset(campaigns,shops,clock,requests,owned);
        for(long shop:shopIds){jdbc.update("DELETE FROM coupon_claim_request WHERE campaign_id IN (SELECT id FROM coupon_campaign WHERE shop_id=?)",shop);jdbc.update("DELETE FROM user_coupon WHERE shop_id=?",shop);jdbc.update("DELETE FROM coupon_campaign WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);}
        for(long id:accountIds){jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);}
        for(long id:accountIds){jdbc.update("DELETE FROM account WHERE id=?",id);}
        shopIds.clear();accountIds.clear();
    }

    @Test void claimReplayCatalogAndHistoryKeepOneIssuance() throws Exception {
        var merchant=merchant("PRINT");String mt=login(merchant),id=published(mt,2);var student=account("STUDENT");String st=login(student);
        var listed=data(call("GET","/api/v1/shops/"+merchant.shop()+"/coupon-campaigns",null,null),200);assertThat(listed.path("total").asLong()).isEqualTo(1);
        var first=data(claim(st,id,"same"),201);String claimId=first.path("id").asString();assertThat(first.path("coupon").path("state").asString()).isEqualTo("AVAILABLE");
        assertThat(data(claim(st,id,"same"),200)).isEqualTo(first);expect(claim(st,id,"different"),409,"COUPON_ALREADY_CLAIMED");
        assertThat(snapshot(id).get("issued_count")).isEqualTo(1);assertThat(snapshot(id).get("version")).isEqualTo(1L);
        data(action(id,"publish","0",mt),200);data(action(id,"stop","1",mt),200);jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",merchant.shop());
        assertThat(data(claim(st,id,"same"),200)).isEqualTo(first);data(call("GET","/api/v1/coupon-claims/"+claimId,null,st),200);
        assertThat(data(call("GET","/api/v1/me/coupons",null,st),200).path("total").asLong()).isEqualTo(1);
        expect(call("GET","/api/v1/coupon-claims/"+claimId,null,login(account("STUDENT"))),404,"RESOURCE_NOT_FOUND");
        doReturn(base.plusSeconds(1800)).when(clock).instant();assertThat(data(claim(st,id,"same"),200).path("coupon").path("state").asString()).isEqualTo("EXPIRED");
        assertThat(data(call("GET","/api/v1/me/coupons?state=AVAILABLE",null,st),200).path("total").asLong()).isZero();assertThat(data(call("GET","/api/v1/me/coupons?state=EXPIRED",null,st),200).path("total").asLong()).isEqualTo(1);
    }
    @Test void distinctStudentsCompeteForExactStockWithoutOverselling() throws Exception {
        var m=merchant("HAIRCUT");String id=published(login(m),4);var tokens=new ArrayList<String>();for(int i=0;i<12;i++){tokens.add(login(account("STUDENT")));}
        try(var pool=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<HttpResponse<String>>>();for(int i=0;i<tokens.size();i++){String t=tokens.get(i);jobs.add(pool.submit(()->claim(t,id,"competition")));}
            int success=0;for(var job:jobs){var res=job.get(45,TimeUnit.SECONDS);if(res.statusCode()==201){success++;}else{expect(res,409,"COUPON_SOLD_OUT");}}assertThat(success).isEqualTo(4);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=?",Integer.class,id)).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=?",Integer.class,id)).isEqualTo(4);assertThat(snapshot(id).get("issued_count")).isEqualTo(4);
        var page=data(call("GET","/api/v1/shops/"+m.shop()+"/coupon-campaigns",null,null),200);assertThat(page.path("items").get(0).path("remainingQuantity").asInt()).isZero();
    }
    @Test void sameStudentDifferentKeysAndSameKeyHaveOneEffect() throws Exception {
        var m=merchant("HAIRCUT");String id=published(login(m),10),st=login(account("STUDENT"));
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->claim(st,id,"original"));var b=pool.submit(()->claim(st,id,"original"));assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(201,200);
        }
        expect(claim(st,id,"another"),409,"COUPON_ALREADY_CLAIMED");assertThat(snapshot(id).get("issued_count")).isEqualTo(1);
        String other=published(login(m),10);expect(claim(st,other,"original"),409,"IDEMPOTENCY_KEY_CONFLICT");assertThat(snapshot(other).get("issued_count")).isEqualTo(0);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->claim(st,other,"left"));var b=pool.submit(()->claim(st,other,"right"));assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(snapshot(other).get("issued_count")).isEqualTo(1);
    }
    @Test void failuresAndFinalSessionExpiryRollbackAllThreeFacts() throws Exception {
        var m=merchant("PRINT");String id=published(login(m),2);var student=account("STUDENT");String st=login(student);var real=sql.getMapper(com.campuslife.coupon.mapper.ClaimRequestMapper.class);
        doAnswer(inv->{real.insert((com.campuslife.coupon.entity.ClaimRequestEntity)inv.getArgument(0));throw new IllegalStateException("isolated failure after claim write");}).when(requests).insert(any(com.campuslife.coupon.entity.ClaimRequestEntity.class));
        expect(claim(st,id,"retry"),500,"INTERNAL_ERROR");assertThat(snapshot(id).get("issued_count")).isEqualTo(0);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon",Integer.class)).isZero();
        reset(requests);Instant expiry=jdbc.queryForObject("SELECT expires_at FROM auth_session WHERE account_id=?",java.sql.Timestamp.class,student.id()).toInstant();
        doAnswer(inv->{int n=real.insert((com.campuslife.coupon.entity.ClaimRequestEntity)inv.getArgument(0));doReturn(expiry).when(clock).instant();return n;}).when(requests).insert(any(com.campuslife.coupon.entity.ClaimRequestEntity.class));
        expect(claim(st,id,"retry"),401,"SESSION_EXPIRED");assertThat(snapshot(id).get("issued_count")).isEqualTo(0);
    }
    @Test void lockWaitUsesDeadlineAfterAcquisition() throws Exception {
        var m=merchant("PRINT");String id=published(login(m),2),st=login(account("STUDENT"));var held=new CountDownLatch(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sql.getMapper(CampaignMapper.class);
        doAnswer(inv->{entered.countDown();return real.lockOwn(Long.parseLong(id),m.shop());}).when(campaigns).lockOwn(Long.parseLong(id),m.shop());
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM coupon_campaign WHERE id=? FOR UPDATE",Long.class,id);held.countDown();await(release);}));
            try{assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();var pending=pool.submit(()->claim(st,id,"deadline"));assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();doReturn(base.plusSeconds(900)).when(clock).instant();release.countDown();holder.get();expect(pending.get(),409,"COUPON_NOT_CLAIMABLE");}finally{release.countDown();}
        }
        assertThat(snapshot(id).get("issued_count")).isEqualTo(0);
    }
    @Test void invalidFieldsRolesAndPaginationCannotIssue() throws Exception {
        var m=merchant("PRINT");String mt=login(m),id=published(mt,2),st=login(account("STUDENT"));
        expect(claim(mt,id,"a"),403,"ROLE_FORBIDDEN");expect(claim(null,id,"a"),401,"AUTH_REQUIRED");expect(claim(st,id,null),400,"IDEMPOTENCY_KEY_REQUIRED");expect(claim(st,id," "),400,"IDEMPOTENCY_KEY_REQUIRED");
        for(Object v:Arrays.asList(null,1,"01","0","9223372036854775808")){var body=new HashMap<String,Object>();body.put("campaignId",v);expect(claimBody(st,body,"a"),400,"VALIDATION_FAILED");}
        expect(claimBody(st,Map.of("campaignId",id,"studentId","1"),"a"),400,"VALIDATION_FAILED");
        for(String query:List.of("size=101","page=0","state=available","shopId=0")){expect(call("GET","/api/v1/me/coupons?"+query,null,st),400,"VALIDATION_FAILED");}
        var page=data(call("GET","/api/v1/me/coupons?page=2147483647&size=100&state=LOCKED",null,st),200);assertThat(page.path("items").isEmpty()).isTrue();
        expect(call("GET","/api/v1/shops/"+m.shop()+"/coupon-campaigns",null,"bad"),401,"TOKEN_INVALID");assertThat(snapshot(id).get("issued_count")).isEqualTo(0);
    }
    @Test void inactiveShopAndCampaignCannotIssueButHistoryRemainsReadable() throws Exception {
        var m=merchant("PRINT");String mt=login(m),id=published(mt,10),st=login(account("STUDENT"));data(claim(st,id,"held"),201);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",m.id());
        expect(call("GET","/api/v1/shops/"+m.shop()+"/coupon-campaigns",null,null),404,"RESOURCE_NOT_FOUND");
        expect(claim(login(account("STUDENT")),id,"fresh"),404,"RESOURCE_NOT_FOUND");data(claim(st,id,"held"),200);
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",m.id());
        data(action(id,"stop","1",mt),200);expect(claim(login(account("STUDENT")),id,"stopped"),409,"COUPON_NOT_CLAIMABLE");
        assertThat(snapshot(id).get("issued_count")).isEqualTo(1);
    }
    @Test void ownCountAndPageShareSnapshotWhileAnotherClaimCommits() throws Exception {
        var m=merchant("PRINT");String mt=login(m),id=published(mt,10),other=published(mt,10);var student=account("STUDENT");String st=login(student);data(claim(st,id,"first"),201);
        var counted=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sql.getMapper(com.campuslife.coupon.mapper.OwnedCouponMapper.class);
        doAnswer(inv->{long n=real.countOwn(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3));counted.countDown();await(release);return n;}).when(owned).countOwn(eq(student.id()),any(),any(),any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->call("GET","/api/v1/me/coupons",null,st));
            try{assertThat(counted.await(15,TimeUnit.SECONDS)).isTrue();data(claim(st,other,"second"),201);release.countDown();var page=data(pending.get(),200);assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("items").size()).isEqualTo(1);}finally{release.countDown();}
        }
        reset(owned);assertThat(data(call("GET","/api/v1/me/coupons",null,st),200).path("total").asLong()).isEqualTo(2);
    }
    private String published(String token,int quantity)throws Exception{var b=body();b.put("totalQuantity",quantity);String id=data(call("POST",PATH,b,token),201).path("id").asString();data(action(id,"publish","0",token),200);return id;}
    private HttpResponse<String> claim(String token,String id,String key)throws Exception{return claimBody(token,Map.of("campaignId",id),key);}
    private HttpResponse<String> claimBody(String token,Object body,String key)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/coupon-claims")).timeout(Duration.ofSeconds(45)).header("Content-Type","application/json");if(token!=null){b.header("Authorization","Bearer "+token);}if(key!=null){b.header("Idempotency-Key",key);}return client.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
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
