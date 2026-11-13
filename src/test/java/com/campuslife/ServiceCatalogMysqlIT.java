package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.ServiceCreateRequest;
import com.campuslife.merchant.dto.ServicePatchRequest;
import com.campuslife.merchant.entity.ServiceItemEntity;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.service.ServiceCatalogService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceCatalogMysqlIT {
    private static final String PATH = "/api/v1/merchant/services";
    private static final String PASSWORD = " Catalog_密码1! ";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>(), shopIds = new ArrayList<>();
    private String hash;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountMapper accounts;
    @Autowired ServiceCatalogService catalog;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean ServiceItemMapper services;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean java.time.Clock clock;

    @BeforeAll void prepareSyntheticPasswordHash() { hash = passwords.encode(PASSWORD); }
    @AfterEach void cleanup() {
        reset(services, shops, clock);
        for (long shop : shopIds) {
            jdbc.update("DELETE FROM service_item WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_review WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop WHERE id=?", shop);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); }
        for (long id : accountIds) { jdbc.update("DELETE FROM account WHERE id=?", id); }
        accountIds.clear(); shopIds.clear();
        jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code IN ('HAIRCUT','PRINT')");
    }

    @Test void haircutCreateListUpdateAndUnlistKeepsShopAndAccountUnchanged() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); var shop = shopIdentity(user);
        var request = createBody("HAIRCUT"); request.put("name", "  " + "😀".repeat(80) + "  ");
        request.put("description", "  " + "介".repeat(500) + "  ");
        var created = expect(call("POST", PATH, request, token), 201, "OK").path("data");
        assertThat(created.path("name").asString()).isEqualTo("😀".repeat(80));
        assertThat(created.path("description").asString()).isEqualTo("介".repeat(500));
        assertThat(created.path("enabled").asBoolean()).isFalse();
        assertThat(created.path("version").asString()).isEqualTo("0");
        assertThat(created.path("priceAmount").asLong()).isEqualTo(3000);
        assertThat(created.path("durationMinutes").asInt()).isEqualTo(30);
        String id = created.path("id").asString();
        var updated = expect(patch(id, Map.of("expectedVersion", "0", "priceAmount", 3500, "durationMinutes", 45, "enabled", true), token), 200, "OK").path("data");
        assertThat(updated.path("version").asString()).isEqualTo("1");
        assertThat(updated.path("priceAmount").asLong()).isEqualTo(3500);
        assertThat(updated.path("durationMinutes").asInt()).isEqualTo(45);
        assertThat(updated.path("name")).isEqualTo(created.path("name"));
        assertThat(expect(call("GET", PATH+"?enabled=true", null, token),200,"OK").path("data").get(0)).isEqualTo(updated);
        var disabled = expect(patch(id, Map.of("expectedVersion", "1", "enabled", false, "description", " "), token),200,"OK").path("data");
        assertThat(disabled.path("description").asString()).isEmpty();
        assertThat(disabled.path("version").asString()).isEqualTo("2");
        assertThat(expect(call("GET", PATH+"?enabled=true", null, token),200,"OK").path("data").isEmpty()).isTrue();
        assertThat(expect(call("GET", PATH, null, token),200,"OK").path("data").size()).isEqualTo(1);
        assertThat(shopIdentity(user)).isEqualTo(shop);
        expect(call("DELETE", PATH+"/"+id, null, token),405,"METHOD_NOT_ALLOWED");
    }

    @Test void printRequiresExplicitNullDurationAndItsPriceIsOnlyReference() throws Exception {
        var user=merchant("PRINT"); String token=login(user); var body=createBody("PRINT"); body.put("enabled",true);
        var item=expect(call("POST",PATH,body,token),201,"OK").path("data");
        assertThat(item.path("durationMinutes").isNull()).isTrue();
        assertThat(item.path("description").asString()).isEmpty();
        String id=item.path("id").asString();
        var noop=new LinkedHashMap<String,Object>(); noop.put("expectedVersion","0"); noop.put("durationMinutes",null);
        expect(patch(id,noop,token),200,"OK");
        var change=new LinkedHashMap<String,Object>(noop); change.put("priceAmount",1);
        var updated=expect(patch(id,change,token),200,"OK").path("data");
        assertThat(updated.path("durationMinutes").isNull()).isTrue();
        assertThat(updated.path("version").asString()).isEqualTo("1");
        expect(patch(id,Map.of("expectedVersion","1","durationMinutes",0),token),400,"VALIDATION_FAILED");
        body.remove("durationMinutes"); expect(call("POST",PATH,body,token),400,"VALIDATION_FAILED");
        body.put("durationMinutes",30); expect(call("POST",PATH,body,token),400,"VALIDATION_FAILED");
    }

    @Test void categoryBindingAndDisabledDictionaryDoNotChangeApprovedShopRights() throws Exception {
        var haircut=merchant("HAIRCUT"); var print=merchant("PRINT");
        expect(call("POST",PATH,createBody("PRINT"),login(haircut)),400,"VALIDATION_FAILED");
        expect(call("POST",PATH,createBody("HAIRCUT"),login(print)),400,"VALIDATION_FAILED");
        jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        expect(call("POST",PATH,createBody("HAIRCUT"),login(haircut)),201,"OK");
        assertThat(serviceCount(print)).isZero();
    }

    @Test void sameNamesAreAllowedAndListIsScopedSortedAndFilterable() throws Exception {
        var user=merchant("HAIRCUT"); String token=login(user); var other=merchant("HAIRCUT");
        var a=expect(call("POST",PATH,createBody("HAIRCUT"),token),201,"OK").path("data");
        var body=createBody("HAIRCUT"); body.put("enabled",true);
        var b=expect(call("POST",PATH,body,token),201,"OK").path("data");
        expect(call("POST",PATH,body,login(other)),201,"OK");
        var listed=expect(call("GET",PATH,null,token),200,"OK").path("data");
        assertThat(listed.size()).isEqualTo(2);
        assertThat(listed.get(0).path("id")).isEqualTo(b.path("id"));
        assertThat(listed.get(1).path("id")).isEqualTo(a.path("id"));
        assertThat(expect(call("GET",PATH+"?serviceType=PRINT",null,token),200,"OK").path("data").isEmpty()).isTrue();
        assertThat(expect(call("GET",PATH+"?serviceType=HAIRCUT&enabled=false",null,token),200,"OK").path("data").size()).isEqualTo(1);
        for(String query:List.of("serviceType=haircut","serviceType=BAD","enabled=1","enabled=TRUE","enabled=")) {
            expect(call("GET",PATH+"?"+query,null,token),400,"VALIDATION_FAILED");
        }
        assertThat(listed.toString()).doesNotContain("password","shopId","accountId","jti","accessToken");
    }

    @ParameterizedTest @MethodSource("badCreates")
    void invalidCreatesCannotWriteRows(String field,Object value,boolean remove) throws Exception {
        var user=merchant("HAIRCUT"); String token=login(user); var before=shopIdentity(user); var body=createBody("HAIRCUT");
        if(remove)body.remove(field);else body.put(field,value);
        expect(call("POST",PATH,body,token),400,"VALIDATION_FAILED");
        assertThat(serviceCount(user)).isZero(); assertThat(shopIdentity(user)).isEqualTo(before);
    }
    static Stream<Arguments> badCreates() {
        var values=new ArrayList<Arguments>();
        for(String field:List.of("serviceType","name","priceAmount","durationMinutes"))values.add(Arguments.of(field,null,true));
        for(Object type:Arrays.asList(null,"haircut","BAD",1,true))values.add(Arguments.of("serviceType",type,false));
        for(Object name:Arrays.asList(null,123,true," ","中".repeat(81),"\uD800",List.of("bad")))values.add(Arguments.of("name",name,false));
        for(Object text:Arrays.asList(null,1,"介".repeat(501),"\uD800"))values.add(Arguments.of("description",text,false));
        for(Object amount:Arrays.asList(null,0,-1,100001,Long.MAX_VALUE,"3000",3000.0,true))values.add(Arguments.of("priceAmount",amount,false));
        for(Object time:Arrays.asList(null,0,14,16,241,Long.MAX_VALUE,"30",30.0,true))values.add(Arguments.of("durationMinutes",time,false));
        for(Object enabled:Arrays.asList(null,1,"true",List.of(true)))values.add(Arguments.of("enabled",enabled,false));
        for(String field:List.of("shopId","accountId","role","version","expectedVersion"))values.add(Arguments.of(field,"1",false));
        return values.stream();
    }

    @ParameterizedTest @MethodSource("badPatches")
    void invalidPatchesCannotChangeService(String field,Object value,boolean remove) throws Exception {
        var user=merchant("HAIRCUT"); String token=login(user); String id=create(user,token); var before=serviceSnapshot(id);
        var body=new LinkedHashMap<String,Object>(); body.put("expectedVersion","0");
        if(remove)body.remove(field);else body.put(field,value);
        expect(patch(id,body,token),400,"VALIDATION_FAILED"); assertThat(serviceSnapshot(id)).isEqualTo(before);
    }
    static Stream<Arguments> badPatches() {
        return Stream.of(Arguments.of("expectedVersion",null,true),Arguments.of("expectedVersion",null,false),Arguments.of("expectedVersion",0,false),
                Arguments.of("expectedVersion","00",false),Arguments.of("expectedVersion","9223372036854775808",false),
                Arguments.of("name",null,false),Arguments.of("description",null,false),Arguments.of("priceAmount",null,false),
                Arguments.of("priceAmount",1.0,false),Arguments.of("priceAmount","1",false),Arguments.of("priceAmount",100001,false),
                Arguments.of("durationMinutes",null,false),Arguments.of("durationMinutes",15.0,false),Arguments.of("durationMinutes",31,false),
                Arguments.of("enabled",null,false),Arguments.of("enabled","false",false),Arguments.of("serviceType","PRINT",false),
                Arguments.of("shopId","1",false),Arguments.of("version","1",false));
    }

    @Test void amountDurationBoundariesAndNoOpPreserveIndependentVersions() throws Exception {
        var user=merchant("HAIRCUT"); String token=login(user); var body=createBody("HAIRCUT"); body.put("priceAmount",1);body.put("durationMinutes",15);
        String id=expect(call("POST",PATH,body,token),201,"OK").path("data").path("id").asString();
        var before=serviceSnapshot(id);clearInvocations(services);
        expect(patch(id,Map.of("expectedVersion","0","name","  基础服务  ","enabled",false),token),200,"OK");
        expect(patch(id,Map.of("expectedVersion","0"),token),200,"OK");
        verify(services,never()).updateOwn(anyLong(),anyLong(),anyLong(),anyString(),anyString(),anyLong(),nullable(Integer.class),anyBoolean());
        assertThat(serviceSnapshot(id)).isEqualTo(before);
        expect(patch(id,Map.of("expectedVersion","0","priceAmount",100000,"durationMinutes",240),token),200,"OK");
        expect(patch(id,Map.of("expectedVersion","0"),token),409,"CONFIG_VERSION_CONFLICT");
        expect(call("PATCH","/api/v1/merchant/shop",Map.of("expectedVersion","1","name","新店名"),token),200,"OK");
        expect(patch(id,Map.of("expectedVersion","1","enabled",true),token),200,"OK");
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,user.shop())).isEqualTo(2);
        jdbc.update("UPDATE service_item SET version=? WHERE id=?",Long.MAX_VALUE,id);
        var max=Long.toString(Long.MAX_VALUE);before=serviceSnapshot(id);
        expect(patch(id,Map.of("expectedVersion",max),token),200,"OK");
        expect(patch(id,Map.of("expectedVersion",max,"enabled",false),token),409,"CONFIG_VERSION_CONFLICT");
        assertThat(serviceSnapshot(id)).isEqualTo(before);
    }

    @Test void authStatusesSessionsAndCrossShopIdsProtectAllOperations() throws Exception {
        var user=merchant("HAIRCUT");String token=login(user);String id=create(user,token);var other=merchant("HAIRCUT");String otherToken=login(other);
        expect(call("GET",PATH,null,null),401,"AUTH_REQUIRED");
        expect(call("POST",PATH,createBody("HAIRCUT"),login(account("ADMIN"))),403,"ROLE_FORBIDDEN");
        expect(call("GET",PATH,null,login(account("STUDENT"))),403,"ROLE_FORBIDDEN");
        expect(patch(id,Map.of("expectedVersion","0"),otherToken),404,"RESOURCE_NOT_FOUND");
        expect(patch(Long.toString(Long.MAX_VALUE),Map.of("expectedVersion","0"),token),404,"RESOURCE_NOT_FOUND");
        for(String invalid:List.of("0","01","-1","abc","9223372036854775808"))expect(patch(invalid,Map.of("expectedVersion","0"),token),400,"VALIDATION_FAILED");
        for(String state:List.of("PENDING","REJECTED","SUSPENDED")) {
            jdbc.update("UPDATE shop SET approval_status=? WHERE id=?",state,user.shop());
            expect(call("GET",PATH,null,token),403,"SHOP_NOT_APPROVED");
            expect(call("POST",PATH,createBody("HAIRCUT"),token),403,"SHOP_NOT_APPROVED");
            expect(patch(id,Map.of("expectedVersion","0"),token),403,"SHOP_NOT_APPROVED");
        }
        jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?",user.shop());
        for(String state:List.of("PENDING","DISABLED")) {
            jdbc.update("UPDATE shop_member SET status=? WHERE account_id=?",state,user.id());
            expect(call("GET",PATH,null,token),403,"ROLE_FORBIDDEN");
            expect(patch(id,Map.of("expectedVersion","0"),token),403,"ROLE_FORBIDDEN");
        }
        jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE account_id=?",user.id());
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",user.id());expect(call("GET",PATH,null,token),403,"ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",user.id());
        expect(call("POST","/api/v1/auth/logout",null,token),200,"OK");expect(patch(id,Map.of("expectedVersion","0"),token),401,"SESSION_REVOKED");
        jdbc.update("DELETE FROM shop_member WHERE account_id=?",user.id());
        try {expect(call("GET",PATH,null,login(user)),404,"RESOURCE_NOT_FOUND");}
        finally {jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')",user.shop(),user.id());}
    }

    @Test void concurrentSameServiceOnlyCommitsOneAndDifferentServicesStayIndependent() throws Exception {
        var user=merchant("HAIRCUT");String a=login(user),b=login(user);String x=create(user,a),y=create(user,a);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var p=pool.submit(()->{start.await();return patch(x,Map.of("expectedVersion","0","priceAmount",4000,"enabled",true),a);});
            var q=pool.submit(()->{start.await();return patch(x,Map.of("expectedVersion","0","priceAmount",5000,"durationMinutes",60),b);});
            start.countDown();var rp=p.get(30,TimeUnit.SECONDS);var rq=q.get(30,TimeUnit.SECONDS);
            assertThat(List.of(rp.statusCode(),rq.statusCode())).containsExactlyInAnyOrder(200,409);
            var row=serviceSnapshot(x);assertThat(row.get("version").toString()).isEqualTo("1");
            assertThat(row.get("price_amount").toString()).isEqualTo(rp.statusCode()==200?"4000":"5000");
            assertThat(row.get("duration_minutes").toString()).isEqualTo(rp.statusCode()==200?"30":"60");
        }
        try(var pool=Executors.newFixedThreadPool(2)) {
            var p=pool.submit(()->patch(x,Map.of("expectedVersion","1","description","A"),a));
            var q=pool.submit(()->patch(y,Map.of("expectedVersion","0","description","B"),b));
            expect(p.get(30,TimeUnit.SECONDS),200,"OK");expect(q.get(30,TimeUnit.SECONDS),200,"OK");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,user.shop())).isEqualTo(1);
    }

    @Test void realInsertAndUpdateFailuresRollBackWithoutChangingShop() throws Exception {
        var user=merchant("HAIRCUT");String token=login(user);var before=shopIdentity(user);var real=sqlSession.getMapper(ServiceItemMapper.class);
        doAnswer(inv->{real.insert((ServiceItemEntity)inv.getArgument(0));throw new IllegalStateException("Injected after real insert");})
                .when(services).insert(any(ServiceItemEntity.class));
        expect(call("POST",PATH,createBody("HAIRCUT"),token),500,"INTERNAL_ERROR");assertThat(serviceCount(user)).isZero();reset(services);
        String id=create(user,token);var row=serviceSnapshot(id);
        doAnswer(inv->{real.updateOwn(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3),inv.getArgument(4),inv.getArgument(5),inv.getArgument(6),inv.getArgument(7));throw new IllegalStateException("Injected after real update");})
                .when(services).updateOwn(anyLong(),anyLong(),anyLong(),anyString(),anyString(),anyLong(),nullable(Integer.class),anyBoolean());
        expect(patch(id,Map.of("expectedVersion","0","priceAmount",9999,"enabled",true),token),500,"INTERNAL_ERROR");
        assertThat(serviceSnapshot(id)).isEqualTo(row);assertThat(shopIdentity(user)).isEqualTo(before);
    }

    @Test void freshRoleAndRevokedSessionOverridePrincipalSnapshot() throws Exception {
        var user=merchant("HAIRCUT");String token=login(user);String id=create(user,token);var before=serviceSnapshot(id);
        String jti=json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8)).path("jti").asString();
        var p=new AuthPrincipal(user.id(),jti,new AccountView(Long.toString(user.id()),user.name(),"店主","MERCHANT","ACTIVE","0"));
        var body=json.convertValue(Map.of("expectedVersion","0","enabled",true),ServicePatchRequest.class);
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",user.id());assertBusiness(()->catalog.patch(p,id,body),"ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET role='MERCHANT',status='DISABLED' WHERE id=?",user.id());assertBusiness(()->catalog.patch(p,id,body),"ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",user.id());jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?",user.id(),jti);
        assertBusiness(()->catalog.patch(p,id,body),"SESSION_REVOKED");assertThat(serviceSnapshot(id)).isEqualTo(before);
    }

    @Test void sessionExpiringAfterInsertCannotCommitCreate() throws Exception {
        var user=merchant("HAIRCUT");String token=login(user);var real=sqlSession.getMapper(ServiceItemMapper.class);
        doAnswer(inv->{int result=real.insert((ServiceItemEntity)inv.getArgument(0));doReturn(java.time.Instant.now().plusSeconds(7201)).when(clock).instant();return result;})
                .when(services).insert(any(ServiceItemEntity.class));
        expect(call("POST",PATH,createBody("HAIRCUT"),token),401,"SESSION_EXPIRED");assertThat(serviceCount(user)).isZero();
    }

    @Test void shopLockWaitRechecksSuspensionBeforeServiceUpdate() throws Exception {
        var user=merchant("HAIRCUT");String token=login(user);String id=create(user,token);var before=serviceSnapshot(id);
        CountDownLatch held=new CountDownLatch(1),read=new CountDownLatch(1),release=new CountDownLatch(1);var real=sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv->{var own=real.findOwn(user.id());read.countDown();return own;}).when(shops).findOwn(user.id());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var suspended=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE",Long.class,user.shop());held.countDown();await(release);
                jdbc.update("UPDATE shop SET approval_status='SUSPENDED',version=version+1 WHERE id=?",user.shop());
            }));
            try {
                assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();var response=pool.submit(()->patch(id,Map.of("expectedVersion","0","enabled",true),token));
                try {assertThat(read.await(15,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->response.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
                finally {release.countDown();}
                suspended.get(20,TimeUnit.SECONDS);expect(response.get(20,TimeUnit.SECONDS),403,"SHOP_NOT_APPROVED");
            } finally {release.countDown();}
        }
        assertThat(serviceSnapshot(id)).isEqualTo(before);
    }

    @Test void serviceLockWaitMergesCurrentValuesInsteadOfOldSnapshot() throws Exception {
        var user=merchant("HAIRCUT");String token=login(user);String id=create(user,token);long serviceId=Long.parseLong(id);
        CountDownLatch held=new CountDownLatch(1),read=new CountDownLatch(1),release=new CountDownLatch(1);var real=sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv->{var own=real.findOwn(user.id());read.countDown();return own;}).when(shops).findOwn(user.id());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var change=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM service_item WHERE id=? FOR UPDATE",Long.class,serviceId);held.countDown();await(release);
                jdbc.update("UPDATE service_item SET price_amount=4500,version=1 WHERE id=?",serviceId);
            }));
            try {
                assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();var response=pool.submit(()->patch(id,Map.of("expectedVersion","1","enabled",true),token));
                try {assertThat(read.await(15,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->response.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
                finally {release.countDown();}
                change.get(20,TimeUnit.SECONDS);var item=expect(response.get(20,TimeUnit.SECONDS),200,"OK").path("data");
                assertThat(item.path("priceAmount").asLong()).isEqualTo(4500);assertThat(item.path("version").asString()).isEqualTo("2");
            } finally {release.countDown();}
        }
    }

    @Test void databaseConstraintsRejectBadTypePriceDurationFlagsAndOrphan() {
        var user=merchant("HAIRCUT");
        for(String values:List.of("'haircut',3000,30,0,0","'HAIRCUT',0,30,0,0","'HAIRCUT',100001,30,0,0","'HAIRCUT',3000,NULL,0,0", "'HAIRCUT',3000,16,0,0","'HAIRCUT',3000,255,0,0","'PRINT',20,30,0,0","'HAIRCUT',3000,30,2,0","'HAIRCUT',3000,30,0,-1")) {
            assertSql(()->jdbc.update("INSERT INTO service_item (shop_id,name,service_type,price_amount,duration_minutes,enabled,version) VALUES (?,'测试',"+values+")",user.shop()),3819);
        }
        assertSql(()->jdbc.update("INSERT INTO service_item (shop_id,name,service_type,price_amount,duration_minutes) VALUES (?,'测试','PRINT',20,NULL)",Long.MAX_VALUE),1216,1452);
    }

    private void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int... codes) {
        assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(e->assertThat(codes).contains(((SQLException)((DataAccessException)e).getMostSpecificCause()).getErrorCode()));
    }
    private void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo(code));}
    private static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private record User(long id,String name,long shop) {}
    private User account(String role) {
        var a=new AccountEntity();a.setUsername("svc_"+UUID.randomUUID().toString().replace("-","").substring(0,20));
        a.setPasswordHash(hash);a.setNickname("服务测试账号");a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());
        return new User(a.getId(),a.getUsername(),0);
    }
    private User merchant(String category) {
        var user=account("MERCHANT");var shop=new ShopEntity();shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?",Long.class,category));
        shop.setName("服务测试店");shop.setCampusCode("XINGHAI_MAIN");shop.setLocationText("虚构校区");shop.setDescription("");
        shop.setApprovalStatus("APPROVED");shop.setBusinessEnabled(false);shop.setVersion(1L);shops.insert(shop);shopIds.add(shop.getId());
        jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop.getId(),user.id());return new User(user.id(),user.name(),shop.getId());
    }
    private static Map<String,Object> createBody(String type) {
        var body=new LinkedHashMap<String,Object>();body.put("serviceType",type);body.put("name","基础服务");body.put("priceAmount",type.equals("PRINT")?20:3000);
        body.put("durationMinutes",type.equals("PRINT")?null:30);return body;
    }
    private String create(User user,String token)throws Exception {return expect(call("POST",PATH,createBody("HAIRCUT"),token),201,"OK").path("data").path("id").asString();}
    private int serviceCount(User user) {return jdbc.queryForObject("SELECT COUNT(*) FROM service_item WHERE shop_id=?",Integer.class,user.shop());}
    private Map<String,Object> serviceSnapshot(String id) {return jdbc.queryForMap("SELECT * FROM service_item WHERE id=?",id);}
    private List<?> shopIdentity(User user) {return List.of(jdbc.queryForMap("SELECT * FROM account WHERE id=?",user.id()),jdbc.queryForMap("SELECT * FROM shop WHERE id=?",user.shop()),jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?",user.id()),jdbc.queryForList("SELECT * FROM shop_business_hour WHERE shop_id=?",user.shop()),jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=?",user.shop()));}
    private String login(User user)throws Exception {return expect(call("POST","/api/v1/auth/login",Map.of("username",user.name(),"password",PASSWORD),null),200,"OK").path("data").path("accessToken").asString();}
    private HttpResponse<String> patch(String id,Object body,String token)throws Exception {return call("PATCH",PATH+"/"+id,body,token);}
    private JsonNode expect(HttpResponse<String> response,int status,String code)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(status);var node=json.readTree(response.body());assertThat(node.path("code").asString()).isEqualTo(code);return node;}
    private HttpResponse<String> call(String method,String path,Object body,String token)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(25));
        if(token!=null)r.header("Authorization","Bearer "+token);if(body!=null)r.header("Content-Type","application/json");
        r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body).replace("\uD800","\\uD800"),StandardCharsets.UTF_8));
        return client.send(r.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
