package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.BusinessHoursRequest;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.BusinessHourMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.service.BusinessHoursService;
import com.campuslife.merchant.vo.BusinessHourView;
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
class BusinessHoursMysqlIT {
    private static final String PASSWORD = " Hours_密码1! ";
    private static final String PATH = "/api/v1/merchant/shop/business-hours";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> shopIds = new ArrayList<>();
    private String hash;
    private User admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountMapper accounts;
    @Autowired BusinessHoursService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean BusinessHourMapper hours;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean java.time.Clock clock;

    @BeforeAll void prepareSyntheticPasswordHash() { hash = passwords.encode(PASSWORD); }
    @AfterEach void cleanup() {
        reset(hours, shops, clock);
        for (long id : shopIds) {
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", id);
            jdbc.update("DELETE FROM shop_review WHERE shop_id=?", id);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?", id);
            jdbc.update("DELETE FROM shop WHERE id=?", id);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); }
        for (long id : accountIds) { jdbc.update("DELETE FROM account WHERE id=?", id); }
        shopIds.clear(); accountIds.clear(); admin = null;
    }

    @Test void fullWeekReplacementMergesSortsAndReturnsRealHoursEverywhere() throws Exception {
        var merchant = merchant(); String token = login(merchant); var identity = identity(merchant);
        var input = List.of(hour(2,"14:00","18:00"), hour(1,"14:00","18:00"), hour(2,"09:00","14:00"), hour(1,"09:00","12:00"));
        var expected = List.of(hour(1,"09:00","12:00"), hour(1,"14:00","18:00"), hour(2,"09:00","18:00"));
        var saved = expect(put("1", input, token),200,"OK").path("data");
        assertThat(saved.path("businessHours")).isEqualTo(json.valueToTree(expected));
        assertThat(saved.path("version").asString()).isEqualTo("2");
        assertThat(saved.path("businessEnabled").asBoolean()).isFalse();
        assertThat(expect(call("GET","/api/v1/merchant/shop",null,token),200,"OK").path("data")).isEqualTo(saved);
        String adminToken = login(admin());
        assertThat(expect(call("GET","/api/v1/admin/shops/"+merchant.shop(),null,adminToken),200,"OK").path("data").path("businessHours")).isEqualTo(json.valueToTree(expected));
        var patched = expect(call("PATCH","/api/v1/merchant/shop",Map.of("expectedVersion","2","businessEnabled",true),token),200,"OK").path("data");
        assertThat(patched.path("businessHours")).isEqualTo(json.valueToTree(expected));
        var replaced = expect(put("3",List.of(hour(7,"00:00","23:59")),token),200,"OK").path("data");
        assertThat(replaced.path("businessEnabled").asBoolean()).isTrue();
        assertThat(replaced.path("businessHours")).isEqualTo(json.valueToTree(List.of(hour(7,"00:00","23:59"))));
        var cleared = expect(put("4",List.of(),token),200,"OK").path("data");
        assertThat(cleared.path("businessHours").isEmpty()).isTrue();
        assertThat(cleared.path("version").asString()).isEqualTo("5");
        assertThat(cleared.path("businessEnabled").asBoolean()).isTrue();
        assertThat(identity(merchant)).isEqualTo(identity);
    }

    @Test void canonicalNoOpPreservesRowIdsAndVersionButStillRejectsOldVersion() throws Exception {
        var merchant = merchant(); String token = login(merchant);
        expect(put("1",List.of(hour(1,"09:00","18:00"),hour(7,"10:01","11:02")),token),200,"OK");
        var before = snapshot(merchant); clearInvocations(hours);
        expect(put("2",List.of(hour(7,"10:01","11:02"),hour(1,"12:00","18:00"),hour(1,"09:00","12:00")),token),200,"OK");
        assertThat(snapshot(merchant)).isEqualTo(before);
        verify(hours,never()).delete(anyLong()); verify(hours,never()).insert(anyLong(),any());
        expect(put("1",List.of(hour(1,"09:00","18:00")),token),409,"CONFIG_VERSION_CONFLICT");
        expect(put("2",List.of(),token),200,"OK"); var empty = snapshot(merchant);
        expect(put("3",List.of(),token),200,"OK"); assertThat(snapshot(merchant)).isEqualTo(empty);
    }

    @ParameterizedTest @MethodSource("invalidIntervals")
    void invalidIntervalsCannotAlterSavedWeek(Object intervals) throws Exception {
        var merchant = merchant(); String token = login(merchant);
        expect(put("1",List.of(hour(3,"09:00","12:00")),token),200,"OK"); var before = snapshot(merchant);
        var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion","2"); body.put("intervals",intervals);
        expect(call("PUT",PATH,body,token),400,"VALIDATION_FAILED"); assertThat(snapshot(merchant)).isEqualTo(before);
    }
    static Stream<Arguments> invalidIntervals() {
        var cases = new ArrayList<Object>();
        cases.add(null); cases.add("[]"); cases.add(Map.of()); cases.add(List.of("bad")); cases.add(Arrays.asList((Object)null));
        for(Object day : Arrays.asList(null,"1",true,1.0,0,8,Long.MAX_VALUE)) {
            var h = new LinkedHashMap<String,Object>(hour(1,"09:00","12:00")); h.put("weekday",day); cases.add(List.of(h));
        }
        for(Object time : Arrays.asList(null,900,true,"9:00","09:00:00","24:00","23:60","09:00Z"," 09:00","09:00 ","\uD800")) {
            var h = new LinkedHashMap<String,Object>(hour(1,"09:00","12:00")); h.put("startTime",time); cases.add(List.of(h));
        }
        cases.add(List.of(hour(1,"12:00","09:00"))); cases.add(List.of(hour(1,"09:00","09:00")));
        cases.add(List.of(hour(1,"09:00","24:00"))); cases.add(List.of(hour(1,"23:00","01:00")));
        cases.add(List.of(hour(1,"09:00","12:00"),hour(1,"11:59","13:00")));
        cases.add(List.of(hour(1,"09:00","12:00"),hour(1,"09:00","12:00")));
        cases.add(List.of(hour(1,"09:00","18:00"),hour(1,"10:00","11:00")));
        cases.add(List.of(Map.of("weekday",1,"startTime","09:00")));
        cases.add(List.of(Map.of("weekday",1,"startTime","09:00","endTime","12:00","shopId","1")));
        cases.add(List.of(hour(1,"01:00","02:00"),hour(1,"03:00","04:00"),hour(1,"05:00","06:00"),hour(1,"07:00","08:00"),hour(1,"09:00","10:00")));
        cases.add(Collections.nCopies(29,hour(1,"09:00","12:00")));
        return cases.stream().map(value -> Arguments.of((Object)value));
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void rejectsBadVersionMissingIntervalsAndUnknownTopLevelFields(Map<String,Object> body) throws Exception {
        var merchant = merchant(); String token = login(merchant); var before = snapshot(merchant);
        expect(call("PUT",PATH,body,token),400,"VALIDATION_FAILED"); assertThat(snapshot(merchant)).isEqualTo(before);
    }
    static Stream<Arguments> invalidBodies() {
        var cases = new ArrayList<Map<String,Object>>();
        cases.add(Map.of("intervals",List.of())); cases.add(Map.of("expectedVersion","1"));
        for(Object version : Arrays.asList(null,1,"01","-1"," 1","9223372036854775808")) {
            var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion",version); body.put("intervals",List.of()); cases.add(body);
        }
        cases.add(Map.of("expectedVersion","1","intervals",List.of(),"shopId","1"));
        cases.add(Map.of("expectedVersion","1","intervals",List.of(),"businessEnabled",true));
        return cases.stream().map(Arguments::of);
    }

    @Test void acceptsFourDailyAndTwentyEightWeeklyIntervalsAndCountsBeforeMerging() throws Exception {
        var merchant = merchant(); String token = login(merchant); var full = new ArrayList<Map<String,Object>>();
        for(int day=1;day<=7;day++) { for(int i=0;i<4;i++) { full.add(hour(day,String.format("%02d:00",i*2),String.format("%02d:00",i*2+1))); } }
        assertThat(expect(put("1",full,token),200,"OK").path("data").path("businessHours").size()).isEqualTo(28);
        var consecutive = new ArrayList<Map<String,Object>>();
        for(int i=0;i<5;i++) { consecutive.add(hour(1,String.format("%02d:00",i),String.format("%02d:00",i+1))); }
        expect(put("2",consecutive,token),400,"VALIDATION_FAILED");
    }

    @Test void rolesStatusesSessionsAndOtherShopOwnershipAreProtectedEvenForEmptyWeek() throws Exception {
        var merchant = merchant(); var other = merchant(); String token = login(merchant); var otherBefore = snapshot(other);
        expect(put("1",List.of(),null),401,"AUTH_REQUIRED");
        expect(put("1",List.of(),login(admin())),403,"ROLE_FORBIDDEN");
        expect(put("1",List.of(),login(account("STUDENT"))),403,"ROLE_FORBIDDEN");
        for(String state : List.of("PENDING","REJECTED","SUSPENDED")) {
            jdbc.update("UPDATE shop SET approval_status=?,reviewed_by=NULL,reviewed_at=NULL,approval_reason=NULL WHERE id=?",state,merchant.shop());
            expect(put("1",List.of(),token),403,"SHOP_NOT_APPROVED");
        }
        jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?",merchant.shop());
        for(String state : List.of("PENDING","DISABLED")) {
            jdbc.update("UPDATE shop_member SET status=? WHERE account_id=?",state,merchant.id());
            expect(put("1",List.of(),token),403,"ROLE_FORBIDDEN");
        }
        jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE account_id=?",merchant.id());
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",merchant.id()); expect(put("1",List.of(),token),403,"ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",merchant.id());
        expect(call("POST","/api/v1/auth/logout",null,token),200,"OK"); expect(put("1",List.of(),token),401,"SESSION_REVOKED");
        expect(put("1",List.of(hour(1,"09:00","10:00")),login(merchant)),200,"OK"); assertThat(snapshot(other)).isEqualTo(otherBefore);
        jdbc.update("DELETE FROM shop_member WHERE account_id=?",merchant.id());
        try { expect(put("2",List.of(),login(merchant)),404,"RESOURCE_NOT_FOUND"); }
        finally { jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')",merchant.shop(),merchant.id()); }
    }

    @Test void versionLimitPermitsNoOpAndForbidsChangingTheWeek() throws Exception {
        var merchant = merchant(); String token = login(merchant); jdbc.update("UPDATE shop SET version=? WHERE id=?",Long.MAX_VALUE,merchant.shop()); var before=snapshot(merchant);
        expect(put(Long.toString(Long.MAX_VALUE),List.of(),token),200,"OK");
        expect(put(Long.toString(Long.MAX_VALUE),List.of(hour(1,"09:00","12:00")),token),409,"CONFIG_VERSION_CONFLICT"); assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void concurrentHoursAndProfilePatchShareOneVersion() throws Exception {
        var merchant=merchant(); String a=login(merchant), b=login(merchant); CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{start.await();return put("1",List.of(hour(1,"09:00","12:00")),a);});
            var y=pool.submit(()->{start.await();return call("PATCH","/api/v1/merchant/shop",Map.of("expectedVersion","1","name","并发新店名"),b);});
            start.countDown(); var rx=x.get(30,TimeUnit.SECONDS); var ry=y.get(30,TimeUnit.SECONDS);
            assertThat(List.of(rx.statusCode(),ry.statusCode())).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,merchant.shop())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_business_hour WHERE shop_id=?",Integer.class,merchant.shop())).isEqualTo(rx.statusCode()==200?1:0);
        }
    }

    @Test void twoConcurrentWeeksCannotMixRows() throws Exception {
        var merchant=merchant(); String a=login(merchant),b=login(merchant); CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{start.await();return put("1",List.of(hour(1,"09:00","12:00")),a);});
            var y=pool.submit(()->{start.await();return put("1",List.of(hour(2,"14:00","18:00")),b);});
            start.countDown(); var rx=x.get(30,TimeUnit.SECONDS); var ry=y.get(30,TimeUnit.SECONDS);
            assertThat(List.of(rx.statusCode(),ry.statusCode())).containsExactlyInAnyOrder(200,409);
            var result=hours.views(merchant.shop()); assertThat(result).hasSize(1);
            assertThat(result.getFirst().weekday()).isEqualTo(rx.statusCode()==200?1:2);
        }
    }

    @Test void insertFailureAfterDeletingAndInsertingRollsBackOldRowsAndVersion() throws Exception {
        var merchant=merchant(); String token=login(merchant); expect(put("1",List.of(hour(1,"09:00","12:00")),token),200,"OK"); var before=snapshot(merchant);
        var real=sqlSession.getMapper(BusinessHourMapper.class);
        doAnswer(inv->{ long shop=inv.getArgument(0); BusinessHourView hour=inv.getArgument(1);
            if(hour.weekday()==2) { throw new IllegalStateException("Injected midway through replacement"); }
            return real.insert(shop,hour);
        }).when(hours).insert(anyLong(),any());
        expect(put("2",List.of(hour(1,"14:00","18:00"),hour(2,"09:00","12:00")),token),500,"INTERNAL_ERROR"); assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void finalVersionWriteFailureRollsBackReplacement() throws Exception {
        var merchant=merchant(); String token=login(merchant); var before=snapshot(merchant);
        doReturn(0).when(shops).incrementVersion(merchant.shop(),1);
        expect(put("1",List.of(hour(1,"09:00","12:00")),token),409,"CONFIG_VERSION_CONFLICT"); assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void lockedRoleAndSessionDoNotTrustOldPrincipal() throws Exception {
        var merchant=merchant(); String token=login(merchant); var before=snapshot(merchant);
        String jti=json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8)).path("jti").asString();
        var principal=new AuthPrincipal(merchant.id(),jti,new AccountView(Long.toString(merchant.id()),merchant.name(),"店主","MERCHANT","ACTIVE","0"));
        var request=json.convertValue(Map.of("expectedVersion","1","intervals",List.of()),BusinessHoursRequest.class);
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",merchant.id());
        assertBusiness(()->service.replace(principal,request),"ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET role='MERCHANT',status='DISABLED' WHERE id=?",merchant.id()); assertBusiness(()->service.replace(principal,request),"ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",merchant.id());
        jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?",merchant.id(),jti);
        assertBusiness(()->service.replace(principal,request),"SESSION_REVOKED"); assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void expiryAfterInsertingStillRollsBackReplacement() throws Exception {
        var merchant=merchant(); String token=login(merchant); var before=snapshot(merchant); var real=sqlSession.getMapper(BusinessHourMapper.class);
        doAnswer(inv->{int result=real.insert(inv.getArgument(0),inv.getArgument(1));doReturn(java.time.Instant.now().plusSeconds(7201)).when(clock).instant();return result;})
                .when(hours).insert(anyLong(),any());
        expect(put("1",List.of(hour(1,"09:00","12:00")),token),401,"SESSION_EXPIRED"); assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void shopWaitUsesCurrentHoursAndCurrentStatus() throws Exception {
        var merchant=merchant(); String token=login(merchant); CountDownLatch held=new CountDownLatch(1),read=new CountDownLatch(1),release=new CountDownLatch(1);
        var real=sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv->{var row=real.findOwn(merchant.id());read.countDown();return row;}).when(shops).findOwn(merchant.id());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var update=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE",Long.class,merchant.shop()); held.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timeout"); } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,1,'09:00','12:00')",merchant.shop());
                jdbc.update("UPDATE shop SET version=2 WHERE id=?",merchant.shop());
            }));
            try {
                assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();
                var response=pool.submit(()->put("2",List.of(hour(1,"09:00","12:00")),token));
                try { assertThat(read.await(15,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->response.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally {release.countDown();}
                update.get(20,TimeUnit.SECONDS); expect(response.get(20,TimeUnit.SECONDS),200,"OK");
            } finally {release.countDown();}
        }
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,merchant.shop())).isEqualTo(2);
        verify(hours,never()).delete(anyLong());
    }

    @Test void ownReadAndAdminPageKeepVersionAndHoursInOneSnapshotAndBatchOnce() throws Exception {
        var a=merchant(); var b=merchant(); String owner=login(a), adminToken=login(admin());
        expect(put("1",List.of(hour(1,"09:00","12:00")),owner),200,"OK");
        expect(put("1",List.of(hour(2,"14:00","18:00")),login(b)),200,"OK");
        clearInvocations(hours);
        var page=expect(call("GET","/api/v1/admin/shops?page=1&size=100",null,adminToken),200,"OK").path("data").path("items");
        verify(hours,times(1)).batchRows(anyList()); verify(hours,never()).rows(anyLong());
        assertThat(page.toString()).contains("09:00","14:00");
        var real=sqlSession.getMapper(ShopMapper.class); CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(inv->{var row=real.findOwn(a.id());read.countDown();if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timeout");return row;})
                .when(shops).findOwn(a.id());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->call("GET","/api/v1/merchant/shop",null,owner));
            try {
                assertThat(read.await(15,TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE",Long.class,a.shop());
                    jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",a.shop());jdbc.update("UPDATE shop SET version=3 WHERE id=?",a.shop());});
            } finally {release.countDown();}
            var data=expect(response.get(20,TimeUnit.SECONDS),200,"OK").path("data");
            assertThat(data.path("version").asString()).isEqualTo("2");assertThat(data.path("businessHours").size()).isEqualTo(1);
        }
        CountDownLatch pageRead=new CountDownLatch(1), pageRelease=new CountDownLatch(1);
        var realHours=sqlSession.getMapper(BusinessHourMapper.class);
        doAnswer(inv->{List<Long> ids=inv.getArgument(0);pageRead.countDown();
            if(!pageRelease.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timeout");
            return realHours.batchRows(ids);
        }).when(hours).batchRows(anyList());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->call("GET","/api/v1/admin/shops?page=1&size=100",null,adminToken));
            try {
                assertThat(pageRead.await(15,TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE",Long.class,a.shop());
                    jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,4,'15:00','16:00')",a.shop());
                    jdbc.update("UPDATE shop SET version=4 WHERE id=?",a.shop());
                });
            } finally {pageRelease.countDown();}
            var items=expect(response.get(20,TimeUnit.SECONDS),200,"OK").path("data").path("items");
            for(JsonNode item:items) {
                if(item.path("id").asString().equals(Long.toString(a.shop()))) {
                    assertThat(item.path("version").asString()).isEqualTo("3");
                    assertThat(item.path("businessHours").isEmpty()).isTrue();
                }
            }
        }
    }

    @Test void databaseEnforcesWeekdayMinuteRangeUniqueAndForeignKey() throws Exception {
        var merchant=merchant();
        for(String values:List.of("0,'09:00','12:00'","8,'09:00','12:00'","1,'12:00','09:00'","1,'09:00:01','12:00'","1,'23:00','24:00'","1,'-01:00','01:00'")) {
            assertSqlCode(()->jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES ("+merchant.shop()+","+values+")"),3819);
        }
        jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,1,'09:00','12:00')",merchant.shop());
        assertSqlCode(()->jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,1,'09:00','13:00')",merchant.shop()),1062);
        assertSqlCode(()->jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,1,'09:00','12:00')",Long.MAX_VALUE),1216,1452);
        jdbc.update("DELETE FROM shop_review WHERE shop_id=?",merchant.shop());
        jdbc.update("DELETE FROM shop_member WHERE shop_id=?",merchant.shop());
        assertSqlCode(()->jdbc.update("DELETE FROM shop WHERE id=?",merchant.shop()),1217,1451);
    }

    private void assertSqlCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int... codes) {
        assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(e->{
            var cause=(SQLException)((DataAccessException)e).getMostSpecificCause();
            assertThat(codes).contains(cause.getErrorCode());
            if(cause.getErrorCode()!=3819) { assertThat(cause.getSQLState()).isEqualTo("23000"); }
        });
    }
    private void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code){assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo(code));}
    private record User(long id,String name,long shop) {}
    private User account(String role) {var a=new AccountEntity();a.setUsername("hrs_"+UUID.randomUUID().toString().replace("-","").substring(0,20));a.setPasswordHash(hash);a.setNickname("测试账号");a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());return new User(a.getId(),a.getUsername(),0);}
    private User admin(){if(admin==null)admin=account("ADMIN");return admin;}
    private User merchant(){var a=account("MERCHANT");var shop=new ShopEntity();shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",Long.class));shop.setName("时段测试店");shop.setLocationText("虚构校区");shop.setDescription("原介绍");shop.setCampusCode("XINGHAI_MAIN");shop.setApprovalStatus("APPROVED");shop.setBusinessEnabled(false);shop.setVersion(1L);shops.insert(shop);shopIds.add(shop.getId());jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop.getId(),a.id());long reviewer=admin().id();jdbc.update("UPDATE shop SET reviewed_by=?,reviewed_at=UTC_TIMESTAMP(3) WHERE id=?",reviewer,shop.getId());jdbc.update("INSERT INTO shop_review (shop_id,reviewed_version,decision,reviewed_by,reviewed_at) VALUES (?,0,'APPROVED',?,UTC_TIMESTAMP(3))",shop.getId(),reviewer);return new User(a.id(),a.name(),shop.getId());}
    private List<?> identity(User user){return List.of(jdbc.queryForMap("SELECT * FROM account WHERE id=?",user.id()),jdbc.queryForMap("SELECT * FROM shop_member WHERE shop_id=?",user.shop()),jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=?",user.shop()),jdbc.queryForMap("SELECT approval_status,approval_reason,reviewed_by,reviewed_at,category_id,campus_code FROM shop WHERE id=?",user.shop()));}
    private List<?> snapshot(User user){return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?",user.shop()),jdbc.queryForList("SELECT * FROM shop_business_hour WHERE shop_id=? ORDER BY id",user.shop()));}
    private static Map<String,Object> hour(int day,String start,String end){return Map.of("weekday",day,"startTime",start,"endTime",end);}
    private String login(User user)throws Exception{return expect(call("POST","/api/v1/auth/login",Map.of("username",user.name(),"password",PASSWORD),null),200,"OK").path("data").path("accessToken").asString();}
    private HttpResponse<String> put(String version,Object intervals,String token)throws Exception{return call("PUT",PATH,Map.of("expectedVersion",version,"intervals",intervals),token);}
    private JsonNode expect(HttpResponse<String> response,int status,String code)throws Exception{assertThat(response.statusCode()).as(response.body()).isEqualTo(status);var body=json.readTree(response.body());assertThat(body.path("code").asString()).isEqualTo(code);return body;}
    private HttpResponse<String> call(String method,String path,Object body,String token)throws Exception{var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(25));if(token!=null)r.header("Authorization","Bearer "+token);if(body!=null)r.header("Content-Type","application/json");r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body).replace("\uD800","\\uD800"),StandardCharsets.UTF_8));return client.send(r.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
}
