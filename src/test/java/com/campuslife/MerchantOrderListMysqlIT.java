package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.merchant.entity.*;
import com.campuslife.merchant.mapper.*;
import com.campuslife.order.entity.*;
import com.campuslife.order.mapper.*;
import com.campuslife.order.service.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="campuslife.booking.expiry-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantOrderListMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-22T01:00:00Z");
    private static final LocalDate DATE=LocalDate.of(2026,10,23);
    private static final Instant START=Instant.parse("2026-10-23T01:30:00Z");
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<Long> accountIds=new ArrayList<>(),shopIds=new ArrayList<>();
    private String hash;
    @Autowired AccountMapper accounts;
    @MockitoSpyBean ShopMapper shops;
    @Autowired ServiceItemMapper services;
    @Autowired BarberMapper barbers;
    @Autowired BarberServiceMapper bindings;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired PasswordEncoder passwords;
    @Autowired BookingExpiryJob expiry;
    @Autowired BookingOrderTransactions transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean OrderMapper orders;
    @MockitoSpyBean OrderListMapper lists;
    @MockitoSpyBean BookingMapper bookings;
    @MockitoSpyBean OrderEventMapper events;
    @MockitoSpyBean IdempotencyMapper keys;
    @MockitoSpyBean BarberScheduleMapper schedules;
    @MockitoSpyBean MoneyMapper money;
    @MockitoSpyBean MockPaymentPolicy mockPayment;
    @BeforeAll void prepare() { hash=passwords.encode("Booking_Test123!"); }
    @BeforeEach void freeze() { fixed(NOW); }
    @AfterEach void cleanup() {
        reset(lists,clock,orders,bookings,events,keys,schedules,shops,money,mockPayment);
        for (long shop:shopIds) {
            jdbc.update("DELETE r FROM refund_record r JOIN service_order o ON o.id=r.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE p FROM payment_record p JOIN service_order o ON o.id=p.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE k FROM idempotency_record k JOIN service_order o ON o.id=k.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE e FROM order_event e JOIN service_order o ON o.id=e.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE b FROM booking_detail b JOIN service_order o ON o.id=b.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE FROM service_order WHERE shop_id=?",shop);
            jdbc.update("DELETE s FROM barber_schedule s JOIN barber b ON b.id=s.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE g FROM barber_day_guard g JOIN barber b ON b.id=g.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE x FROM barber_service x JOIN barber b ON b.id=x.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE FROM barber WHERE shop_id=?",shop);jdbc.update("DELETE FROM service_item WHERE shop_id=?",shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);
        }
        for (long id:accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id); }
        accountIds.clear();shopIds.clear();
    }







    private HttpResponse<String> list(String token,String query)throws Exception{return call("GET","/api/v1/merchant/orders"+query,null,token,null);}

    private String createId(Fixture f,String token)throws Exception{return data(create(f,token,UUID.randomUUID().toString())).path("id").asString();}
    private String prepare(Fixture f,User student,String state)throws Exception {
        fixed(NOW);var token=login(student);var id=createId(f,token);
        if(state.equals("CLOSED")){data(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null));return id;}
        if(state.equals("PENDING_PAYMENT")){return id;}
        doReturn(true).when(mockPayment).enabled();data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,token,null));
        if(state.equals("PENDING_ACCEPTANCE")){return id;}
        var merchant=login(f.owner());data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));
        if(state.equals("ACCEPTED")){return id;}
        fixed(START);merchant=login(f.owner());data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,merchant,null));
        if(state.equals("COMPLETED")){data(call("POST","/api/v1/merchant/orders/"+id+"/complete",null,merchant,null));}
        return id;
    }
    private List<String> ids(JsonNode page){var result=new ArrayList<String>();page.path("items").forEach(x->result.add(x.path("id").asString()));return result;}

    @Test void defaultsStablePaginationShowAllStudentsInOwnShopOnly()throws Exception {
        var f=fixture();var a=login(user("STUDENT"));var b=login(user("STUDENT"));
        var one=createId(f,a);var two=createId(addBarber(f),b);var three=createId(addBarber(f),a);
        jdbc.update("UPDATE service_order SET created_at=? WHERE id=?",LocalDateTime.ofInstant(NOW.minusSeconds(1),ZoneOffset.UTC),one);
        var other=fixture();var otherId=createId(other,a);var token=login(f.owner());
        var result=data(list(token,""));assertThat(ids(result)).containsExactly(three,two,one).doesNotContain(otherId);
        assertThat(result.path("page").asInt()).isEqualTo(1);assertThat(result.path("size").asInt()).isEqualTo(20);assertThat(result.path("total").asLong()).isEqualTo(3);assertThat(result.path("totalPages").asLong()).isEqualTo(1);
        var item=result.path("items").get(0);assertThat(item.propertyNames()).containsExactlyInAnyOrder("id","orderNo","orderType","status","paymentStatus","shopId","shopName","serviceName","payableAmount","currency","createdAt","bookingStartAt","bookingEndAt","printingProgress");
        assertThat(item.path("id").isString()).isTrue();assertThat(item.path("shopId").asString()).isEqualTo(Long.toString(f.shop()));assertThat(item.path("payableAmount").asLong()).isEqualTo(3000);assertThat(item.path("currency").asString()).isEqualTo("CNY");assertThat(item.path("bookingStartAt").asString()).isEqualTo(START.toString());assertThat(item.path("bookingEndAt").asString()).isEqualTo(START.plusSeconds(1800).toString());assertThat(item.path("printingProgress").isNull()).isTrue();
        var first=data(list(token,"?page=1&size=2"));var next=data(list(token,"?page=2&size=2"));var last=data(list(token,"?page=3&size=2"));assertThat(ids(first)).containsExactly(three,two);assertThat(ids(next)).containsExactly(one);assertThat(ids(last)).isEmpty();for(var p:List.of(first,next,last)){assertThat(p.path("total").asLong()).isEqualTo(3);assertThat(p.path("totalPages").asLong()).isEqualTo(2);}
        assertThat(ids(data(list(token,"?studentId=999&shopId="+other.shop()+"&sort=id%20ASC")))).containsExactly(three,two,one);
        assertThat(ids(data(list(login(other.owner()),"")))).containsExactly(otherId);
        var detail=data(call("GET","/api/v1/merchant/orders/"+three,null,token,null));assertThat(item.path("shopName")).isEqualTo(detail.path("shopName"));assertThat(item.path("serviceName")).isEqualTo(detail.path("serviceName"));
    }
    @Test void sixRealLifecycleStatesAndTypeStatusCombinationAreFilterable()throws Exception {
        var f=fixture();var byState=new LinkedHashMap<String,String>();
        for(var state:List.of("PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED")){byState.put(state,prepare(addBarber(f),user("STUDENT"),state));}
        fixed(NOW);var token=login(f.owner());assertThat(data(list(token,"")).path("total").asLong()).isEqualTo(6);
        for(var entry:byState.entrySet()){
            var result=data(list(token,"?orderType=BOOKING&status="+entry.getKey()));assertThat(ids(result)).containsExactly(entry.getValue());assertThat(result.path("total").asLong()).isEqualTo(1);assertThat(result.path("items").get(0).path("status").asString()).isEqualTo(entry.getKey());
            var print=data(list(token,"?orderType=PRINT&status="+entry.getKey()));assertThat(ids(print)).isEmpty();assertThat(print.path("totalPages").asLong()).isZero();
        }
    }
    @Test void emptyPageBoundsAndLargestOffsetRemainValid()throws Exception {
        var f=fixture();var token=login(f.owner());var empty=data(list(token,""));assertThat(ids(empty)).isEmpty();assertThat(empty.path("total").asLong()).isZero();assertThat(empty.path("totalPages").asLong()).isZero();
        createId(f,login(user("STUDENT")));var large=data(list(token,"?page=2147483647&size=100"));assertThat(ids(large)).isEmpty();assertThat(large.path("page").asInt()).isEqualTo(Integer.MAX_VALUE);assertThat(large.path("total").asLong()).isEqualTo(1);verify(lists).pageShop(f.shop(),null,214748364600L,100);
        assertThat(data(list(token,"?page=01&size=001")).path("size").asInt()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"?orderType=","?orderType=booking","?orderType=BOOKING%20","?orderType=UNKNOWN","?status=","?status=paid","?status=PAID","?status=CLOSED%20","?page=","?page=0","?page=-1","?page=%2B1","?page=1.0","?page=%201","?page=2147483648","?size=","?size=0","?size=101","?size=1.0","?size=99999999999999999999999","?orderType=PRINT&status=UNKNOWN","?orderType=PRINT&page=0","?orderType=PRINT&size=101"})
    void badFiltersAndPaginationAreNeverSilentlyCorrected(String query)throws Exception {
        expect(list(login(fixture().owner()),query),400,"VALIDATION_FAILED");verifyNoInteractions(lists);
    }
    @ParameterizedTest @ValueSource(strings={"STUDENT","ADMIN","ANONYMOUS","INVALID","DISABLED","REVOKED","EXPIRED"})
    void onlyActiveMerchantSessionCanReadList(String kind)throws Exception {
        String token=null;int status=403;String code="ROLE_FORBIDDEN";
        if(kind.equals("ANONYMOUS")){status=401;code="AUTH_REQUIRED";}
        else if(kind.equals("INVALID")){token="invalid";status=401;code="TOKEN_INVALID";}
        else{var actor=Set.of("STUDENT","ADMIN").contains(kind)?user(kind):fixture().owner();token=login(actor);
            if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",actor.id());code="ACCOUNT_DISABLED";}
            if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),actor.id());status=401;code="SESSION_REVOKED";}
            if(kind.equals("EXPIRED")){fixed(NOW.plusSeconds(7200));status=401;code="SESSION_EXPIRED";}}
        expect(list(token,""),status,code);expect(list(token,"?orderType=PRINT"),status,code);verifyNoInteractions(lists);
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","PENDING","DISABLED"})
    void membershipCannotBeBypassedByEmptyOrPrintPage(String kind)throws Exception {
        User actor;
        if(kind.equals("MISSING")){actor=user("MERCHANT");}
        else{var f=fixture();actor=f.owner();jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",kind,f.shop());}
        var token=login(actor);for(var query:List.of("","?orderType=PRINT")){expect(list(token,query),kind.equals("MISSING")?404:403,kind.equals("MISSING")?"RESOURCE_NOT_FOUND":"ROLE_FORBIDDEN");}
        verifyNoInteractions(lists);
    }
    @Test void suspendedShopDisabledStudentAndChangedConfigurationKeepFrozenHistory()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=createId(f,login(student));var token=login(f.owner());var before=data(list(token,""));
        jdbc.update("UPDATE shop SET approval_status='SUSPENDED',business_enabled=FALSE,name='已变更店名' WHERE id=?",f.shop());jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",student.id());jdbc.update("UPDATE service_item SET enabled=FALSE,name='已变更服务',price_amount=5000,duration_minutes=60 WHERE id=?",f.service());jdbc.update("UPDATE barber SET enabled=FALSE,display_name='已变更人员' WHERE id=?",f.barber());
        assertThat(data(list(token,""))).isEqualTo(before);assertThat(ids(before)).containsExactly(id);var detail=data(call("GET","/api/v1/merchant/orders/"+id,null,token,null));assertThat(before.path("items").get(0).path("shopName")).isEqualTo(detail.path("shopName"));assertThat(before.path("items").get(0).path("serviceName")).isEqualTo(detail.path("serviceName"));
    }
    @Test void refundedSummaryKeepsPayableAmountWithoutExposingNotesOrFundDetails()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=prepare(f,student,"PENDING_ACCEPTANCE");var token=login(student);data(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null));var page=data(list(login(f.owner()),"?status=CLOSED"));var item=page.path("items").get(0);
        assertThat(item.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(item.path("payableAmount").asLong()).isEqualTo(3000);assertThat(item.has("payment")).isFalse();assertThat(item.has("refund")).isFalse();assertThat(item.has("note")).isFalse();assertThat(item.has("closeNote")).isFalse();assertThat(item.has("version")).isFalse();assertThat(item.has("studentId")).isFalse();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void overdueRowsRemainActualStatesAndGetNeverWritesOrRequiresFunds(boolean paid)throws Exception {
        var f=fixture();var id=prepare(f,user("STUDENT"),paid?"PENDING_ACCEPTANCE":"PENDING_PAYMENT");fixed(NOW.plusSeconds(paid?1800:900));var token=login(f.owner());doReturn(false).when(mockPayment).enabled();
        var before=jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id);int eventCount=count("order_event"),payments=count("payment_record");clearInvocations(money,orders,bookings,events,mockPayment,lists);
        var status=paid?"PENDING_ACCEPTANCE":"PENDING_PAYMENT";var result=data(list(token,"?status="+status));assertThat(ids(result)).containsExactly(id);assertThat(jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id)).isEqualTo(before);assertThat(count("order_event")).isEqualTo(eventCount);assertThat(count("payment_record")).isEqualTo(payments);assertThat(count("refund_record")).isZero();
        verify(lists).countShop(f.shop(),status);verify(lists).pageShop(f.shop(),status,0,20);verifyNoMoreInteractions(lists);verify(orders).ownShop(f.owner().id());verify(orders).ownership(f.shop(),f.owner().id());verifyNoMoreInteractions(orders);verifyNoInteractions(money,bookings,events,mockPayment);
    }
    @Test void malformedPrintWithoutExtensionIsExcludedFromBothQueries()throws Exception {
        var f=fixture();var id=createId(f,login(user("STUDENT")));var token=login(f.owner());jdbc.update("UPDATE service_order SET order_type='PRINT' WHERE id=?",id);
        assertThat(ids(data(list(token,"")))).isEmpty();clearInvocations(lists,orders);var result=data(list(token,"?orderType=PRINT&page=2&size=100"));assertThat(ids(result)).isEmpty();assertThat(result.path("page").asInt()).isEqualTo(2);assertThat(result.path("size").asInt()).isEqualTo(100);assertThat(result.path("total").asLong()).isZero();verify(lists).countShopType(f.shop(),"PRINT",null);verify(lists).pageShopType(f.shop(),"PRINT",null,100,100);verifyNoMoreInteractions(lists);verify(orders).ownShop(f.owner().id());verify(orders).ownership(f.shop(),f.owner().id());
    }
    @ParameterizedTest @ValueSource(strings={"CREATE","EMPTY_CREATE","PAY","CANCEL"})
    void countAndItemsUseSameRealMysqlSnapshotDuringConcurrentWrites(String change)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var token=login(f.owner());String initial=change.equals("EMPTY_CREATE")?null:createId(f,student);
        doReturn(true).when(mockPayment).enabled();var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderListMapper.class);
        doAnswer(i->{long n=real.countShop(i.getArgument(0),i.getArgument(1));gate.countDown();await(release);return n;}).when(lists).countShop(eq(f.shop()),nullable(String.class));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->list(token,"?status=PENDING_PAYMENT"));await(gate);
            try {
                if(change.equals("CREATE")){assertThat(post(body(f,START.plusSeconds(3600)),student,"concurrent").statusCode()).isEqualTo(201);}
                else if(change.equals("EMPTY_CREATE")){createId(f,student);}
                else{data(call("POST","/api/v1/orders/"+initial+(change.equals("PAY")?"/payments/mock":"/cancel"),null,student,null));}
            }finally{release.countDown();}
            var old=data(request.get(15,TimeUnit.SECONDS));assertThat(old.path("total").asLong()).isEqualTo(initial==null?0:1);assertThat(ids(old)).containsExactly(initial==null?new String[]{}:new String[]{initial});
            if(initial!=null){assertThat(old.path("items").get(0).path("status").asString()).isEqualTo("PENDING_PAYMENT");assertThat(old.path("items").get(0).path("paymentStatus").asString()).isEqualTo("NOT_PAID");}
        }finally{release.countDown();}
        reset(lists);assertThat(data(list(token,"?status=PENDING_PAYMENT")).path("total").asLong()).isEqualTo(change.equals("CREATE")?2:change.equals("EMPTY_CREATE")?1:0);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","DISABLED"})
    void ownershipAndOrdersShareSnapshotStartingWithOwnShopRead(String newStatus)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var id=createId(f,student);var token=login(f.owner());
        var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{var own=real.ownShop(i.getArgument(0));gate.countDown();await(release);return own;}).when(orders).ownShop(f.owner().id());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->list(token,""));await(gate);
            try{assertThat(post(body(f,START.plusSeconds(3600)),student,"after-snapshot").statusCode()).isEqualTo(201);jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",newStatus,f.shop());}finally{release.countDown();}
            var old=data(request.get(15,TimeUnit.SECONDS));assertThat(old.path("total").asLong()).isEqualTo(1);assertThat(ids(old)).containsExactly(id);
        }finally{release.countDown();}
        reset(orders);expect(list(token,""),403,"ROLE_FORBIDDEN");expect(list(token,"?orderType=PRINT"),403,"ROLE_FORBIDDEN");
    }
    @Test void consistentReadDoesNotAcquireBusinessWriteLocks()throws Exception {
        var f=fixture();var id=createId(f,login(user("STUDENT")));var token=login(f.owner());var before=data(list(token,""));var gate=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());schedules.lockGuard(f.barber(),DATE);orders.lock(Long.parseLong(id));jdbc.update("UPDATE service_order SET shop_name_snapshot='尚未提交' WHERE id=?",id);gate.countDown();await(release);tx.setRollbackOnly();}));await(gate);
            try{assertThat(data(list(token,""))).isEqualTo(before);assertThat(release.getCount()).isEqualTo(1);}finally{release.countDown();}holder.get(15,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    private record User(long id,String name) {}
    private record Fixture(long shop,long service,long barber,User owner) {}
    private User user(String role){var a=new AccountEntity();a.setUsername("book_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("模拟学生");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());return new User(a.getId(),a.getUsername());}
    private Fixture fixture(){var owner=user("MERCHANT");var s=new ShopEntity();s.setName("模拟预约店");s.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",Long.class));s.setCampusCode("XINGHAI_MAIN");s.setLocationText("虚构校区");s.setDescription("");s.setApprovalStatus("APPROVED");s.setBusinessEnabled(true);s.setVersion(0L);shops.insert(s);shopIds.add(s.getId());jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",s.getId(),owner.id());var service=new ServiceItemEntity();service.setShopId(s.getId());service.setServiceType("HAIRCUT");service.setName("模拟理发");service.setDescription("");service.setPriceAmount(3000L);service.setDurationMinutes(30);service.setEnabled(true);service.setVersion(0L);services.insert(service);jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,?,?,?)",s.getId(),DATE.getDayOfWeek().getValue(),"09:00","12:00");return addBarber(new Fixture(s.getId(),service.getId(),0,owner));}
    private Fixture addBarber(Fixture f){var b=new BarberEntity();b.setShopId(f.shop());b.setDisplayName("模拟人员");b.setEnabled(true);b.setVersion(0L);barbers.insert(b);bindings.insert(b.getId(),f.service());schedules.insertGuard(b.getId(),DATE);schedules.insert(b.getId(),DATE,LocalDateTime.of(2026,10,23,1,0),LocalDateTime.of(2026,10,23,4,0));return new Fixture(f.shop(),f.service(),b.getId(),f.owner());}
    private ObjectNode body(Fixture f,Instant start){return json.valueToTree(Map.of("serviceId",Long.toString(f.service()),"barberId",Long.toString(f.barber()),"startAt",start.toString(),"expectedServiceVersion","0","expectedPayableAmount",3000));}
    private HttpResponse<String> create(Fixture f,String token,String key)throws Exception{return post(body(f,START),token,key);}
    private HttpResponse<String> post(Object body,String token,String key)throws Exception{return call("POST","/api/v1/orders/bookings",body,token,key);}
    private String login(User user)throws Exception{return data(call("POST","/api/v1/auth/login",Map.of("username",user.name(),"password","Booking_Test123!"),null,null)).path("accessToken").asString();}
    private List<Instant> starts(Fixture f)throws Exception{var result=data(call("GET","/api/v1/shops/"+f.shop()+"/booking-slots?serviceId="+f.service()+"&barberId="+f.barber()+"&date="+DATE,null,null,null));var list=new ArrayList<Instant>();for(var item:result.path("slots")){list.add(Instant.parse(item.path("startAt").asString()));}return list;}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void assertSqlFailure(Runnable statement,String constraint,int code) {
        var failure=catchThrowable(statement::run);assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining(constraint);
        var cause=((org.springframework.dao.DataAccessException)failure).getMostSpecificCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)cause).getErrorCode()).isEqualTo(code);
    }
    private void fixed(Instant value){doReturn(value).when(clock).instant();}
    private JsonNode data(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isIn(200,201);return json.readTree(response.body()).path("data");}
    private void expect(HttpResponse<String> response,int status,String code){assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);}
    private HttpResponse<String> call(String method,String path,Object body,String token,String key)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(40));if(token!=null){builder.header("Authorization","Bearer "+token);}if(key!=null){builder.header("Idempotency-Key",key);}if(body!=null){builder.header("Content-Type","application/json");}builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(builder.build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS)){throw new IllegalStateException("gate timeout");}}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
}
