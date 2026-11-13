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
class OrderEventsMysqlIT {
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

    private HttpResponse<String> read(String who,String id,String token)throws Exception{return call("GET",(who.equals("STUDENT")?"/api/v1/orders/":"/api/v1/merchant/orders/")+id+"/events",null,token,null);}
    private List<String> actions(JsonNode array){var result=new ArrayList<String>();array.forEach(x->result.add(x.path("action").asString()));return result;}
    private JsonNode both(String id,User student,Fixture f)throws Exception {
        var a=data(read("STUDENT",id,login(student)));var b=data(read("MERCHANT",id,login(f.owner())));assertThat(a).isEqualTo(b);saved(id,a);return a;
    }
    private void saved(String id,JsonNode array){
        var rows=jdbc.queryForList("SELECT * FROM order_event WHERE order_id=? ORDER BY event_seq",id);assertThat(array.isArray()).isTrue();assertThat(array.size()).isEqualTo(rows.size());
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);var item=array.get(i);assertThat(item.propertyNames()).containsExactlyInAnyOrder("id","eventSeq","action","fromStatus","toStatus","actorType","actorId","reason","occurredAt");
            assertThat(item.path("id").isString()).isTrue();assertThat(item.path("id").asString()).isEqualTo(row.get("id").toString());assertThat(item.path("eventSeq").isString()).isTrue();assertThat(item.path("eventSeq").asString()).isEqualTo(row.get("event_seq").toString());
            for(var column:Map.of("action","action","fromStatus","from_status","toStatus","to_status","actorType","actor_type","actorId","actor_id","reason","reason").entrySet()){
                var expected=row.get(column.getValue());if(expected==null){assertThat(item.path(column.getKey()).isNull()).isTrue();}else{assertThat(item.path(column.getKey()).asString()).isEqualTo(expected.toString());}
            }
            var at=row.get("occurred_at");var instant=at instanceof LocalDateTime t?t.toInstant(ZoneOffset.UTC):((java.sql.Timestamp)at).toInstant();assertThat(Instant.parse(item.path("occurredAt").asString())).isEqualTo(instant);
        }
    }
    @Test void completeLifecycleAndLegitimateReplaysExposeOnlyOriginalSavedEvents()throws Exception {
        var f=fixture();var student=user("STUDENT");var token=login(student);var key="event-replay";var id=data(create(f,token,key)).path("id").asString();data(create(f,token,key));
        var initial=both(id,student,f);assertThat(actions(initial)).containsExactly("CREATE");assertThat(initial.get(0).path("eventSeq").asString()).isEqualTo("0");assertThat(initial.get(0).path("fromStatus").isNull()).isTrue();assertThat(initial.get(0).path("reason").isNull()).isTrue();
        doReturn(true).when(mockPayment).enabled();data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,token,null));data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,token,null));assertThat(actions(both(id,student,f))).containsExactly("CREATE","PAYMENT");
        var merchant=login(f.owner());data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));assertThat(actions(both(id,student,f))).containsExactly("CREATE","PAYMENT","ACCEPT");
        fixed(START);merchant=login(f.owner());data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,merchant,null));data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,merchant,null));assertThat(actions(both(id,student,f))).containsExactly("CREATE","PAYMENT","ACCEPT","START");
        data(call("POST","/api/v1/merchant/orders/"+id+"/complete",null,merchant,null));var completed=both(id,student,f);data(call("POST","/api/v1/merchant/orders/"+id+"/complete",null,merchant,null));data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,merchant,null));assertThat(both(id,student,f)).isEqualTo(completed);assertThat(actions(completed)).containsExactly("CREATE","PAYMENT","ACCEPT","START","COMPLETE");
    }
    @ParameterizedTest @ValueSource(strings={"USER_UNPAID","USER_PAID","USER_ACCEPTED","PAYMENT_TIMEOUT","ACCEPTANCE_TIMEOUT","MERCHANT_REJECT","MERCHANT_UNAVAILABLE","ADMIN_WAITING","ADMIN_ACCEPTED","ADMIN_STARTED"})
    void actualClosureBranchesExposeActorAndReasonWithoutSyntheticRefundEvent(String branch)throws Exception {
        var f=fixture();var student=user("STUDENT");var state=switch(branch){case "USER_UNPAID","PAYMENT_TIMEOUT"->"PENDING_PAYMENT";case "USER_ACCEPTED","MERCHANT_UNAVAILABLE","ADMIN_ACCEPTED"->"ACCEPTED";case "ADMIN_STARTED"->"IN_SERVICE";default->"PENDING_ACCEPTANCE";};
        var id=prepare(f,student,state);String action;Long actor;String actorType;String reason;
        if(branch.startsWith("USER")){action="USER_CANCEL";actor=student.id();actorType="STUDENT";reason=action;var token=login(student);data(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null));data(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null));}
        else if(branch.endsWith("TIMEOUT")){action=branch;actor=null;actorType="SYSTEM";reason=action;fixed(NOW.plusSeconds(branch.equals("PAYMENT_TIMEOUT")?900:1800));transactions.expire(Long.parseLong(id));transactions.expire(Long.parseLong(id));}
        else if(branch.startsWith("MERCHANT")){action=branch;actor=f.owner().id();actorType="MERCHANT";reason="已核实：测试原因🙂 <b>原文</b>";var token=login(f.owner());var path="/api/v1/merchant/orders/"+id+(branch.equals("MERCHANT_REJECT")?"/reject":"/cancel");data(call("POST",path,Map.of("reason","  "+reason+"  "),token,null));data(call("POST",path,Map.of("reason",reason),token,null));}
        else{action="ADMIN_EXCEPTION";var admin=user("ADMIN");actor=admin.id();actorType="ADMIN";reason="核实未到店/服务异常：测试🙂 <b>原文</b>";var version=jdbc.queryForObject("SELECT version FROM service_order WHERE id=?",Long.class,id).toString();var token=login(admin);var path="/api/v1/admin/orders/"+id+"/close-exception";var body=Map.of("reason",reason,"expectedVersion",version);data(call("POST",path,body,token,null));data(call("POST",path,body,token,null));}
        var timeline=both(id,student,f);var last=timeline.get(timeline.size()-1);assertThat(last.path("action").asString()).isEqualTo(action);assertThat(last.path("actorType").asString()).isEqualTo(actorType);assertThat(last.path("reason").asString()).isEqualTo(reason);assertThat(last.path("toStatus").asString()).isEqualTo("CLOSED");if(actor==null){assertThat(last.path("actorId").isNull()).isTrue();}else{assertThat(last.path("actorId").asString()).isEqualTo(actor.toString());}assertThat(actions(timeline)).doesNotContain("REFUND");
        assertThat(timeline.size()).isEqualTo(jdbc.queryForObject("SELECT version+1 FROM service_order WHERE id=?",Integer.class,id));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void overdueReadsDoNotWriteOrRequireMockFunds(boolean paid)throws Exception {
        var f=fixture();var student=user("STUDENT");var id=prepare(f,student,paid?"PENDING_ACCEPTANCE":"PENDING_PAYMENT");fixed(NOW.plusSeconds(paid?1800:900));doReturn(false).when(mockPayment).enabled();
        var studentToken=login(student);var merchant=login(f.owner());var before=jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id);var eventRows=jdbc.queryForList("SELECT * FROM order_event WHERE order_id=? ORDER BY event_seq",id);clearInvocations(events,money,bookings,keys,mockPayment);
        saved(id,data(read("STUDENT",id,studentToken)));saved(id,data(read("MERCHANT",id,merchant)));assertThat(jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id)).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM order_event WHERE order_id=? ORDER BY event_seq",id)).isEqualTo(eventRows);verify(events,times(2)).listByOrder(Long.parseLong(id));verifyNoMoreInteractions(events);verifyNoInteractions(money,bookings,keys,mockPayment);
    }
    @ParameterizedTest @CsvSource({"STUDENT,MERCHANT","STUDENT,ADMIN","STUDENT,ANONYMOUS","STUDENT,INVALID","STUDENT,DISABLED","STUDENT,REVOKED","STUDENT,EXPIRED","MERCHANT,STUDENT","MERCHANT,ADMIN","MERCHANT,ANONYMOUS","MERCHANT,INVALID","MERCHANT,DISABLED","MERCHANT,REVOKED","MERCHANT,EXPIRED"})
    void endpointRequiresCorrectActiveRoleAndSession(String endpoint,String kind)throws Exception {
        var f=fixture();var student=user("STUDENT");var id=createId(f,login(student));String token=null;int status=403;String code="ROLE_FORBIDDEN";
        if(kind.equals("ANONYMOUS")){status=401;code="AUTH_REQUIRED";}
        else if(kind.equals("INVALID")){token="invalid";status=401;code="TOKEN_INVALID";}
        else{var actor=Set.of("STUDENT","MERCHANT","ADMIN").contains(kind)?user(kind):endpoint.equals("STUDENT")?student:f.owner();token=login(actor);
            if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",actor.id());code="ACCOUNT_DISABLED";}
            if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),actor.id());status=401;code="SESSION_REVOKED";}
            if(kind.equals("EXPIRED")){fixed(NOW.plusSeconds(7200));status=401;code="SESSION_EXPIRED";}}
        clearInvocations(events);expect(read(endpoint,id,token),status,code);verifyNoInteractions(events);
    }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","%2B1","1.0","%201","9223372036854775808","99999999999999999999","1%20OR%201=1"})
    void malformedIdsFailBeforeEventRead(String id)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());clearInvocations(events);expect(read("STUDENT",id,student),400,"VALIDATION_FAILED");expect(read("MERCHANT",id,merchant),400,"VALIDATION_FAILED");verifyNoInteractions(events);
    }
    @Test void nonexistentForeignAndUnsupportedOrdersAreIndistinguishableAndNeverReadEvents()throws Exception {
        var f=fixture();var other=fixture();var student=user("STUDENT");var a=login(student);var b=login(user("STUDENT"));var merchant=login(f.owner());var id=createId(f,a);var foreign=createId(other,a);clearInvocations(events);
        for(var target:List.of(id,"9223372036854775807")){expect(read("STUDENT",target,b),404,"RESOURCE_NOT_FOUND");}
        for(var target:List.of(foreign,"9223372036854775807")){expect(read("MERCHANT",target,merchant),404,"RESOURCE_NOT_FOUND");}
        jdbc.update("UPDATE service_order SET order_type='PRINT' WHERE id=?",id);expect(read("STUDENT",id,a),404,"RESOURCE_NOT_FOUND");expect(read("MERCHANT",id,merchant),404,"RESOURCE_NOT_FOUND");verifyNoInteractions(events);
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","PENDING","DISABLED"})
    void merchantMembershipIsRequiredAndCrossShopStillReturns404(String kind)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var id=createId(f,student);var other=fixture();var foreign=createId(other,student);User actor;
        if(kind.equals("MISSING")){actor=user("MERCHANT");}else{actor=f.owner();jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",kind,f.shop());}
        var token=login(actor);clearInvocations(events);expect(read("MERCHANT",id,token),kind.equals("MISSING")?404:403,kind.equals("MISSING")?"RESOURCE_NOT_FOUND":"ROLE_FORBIDDEN");expect(read("MERCHANT",foreign,token),404,"RESOURCE_NOT_FOUND");verifyNoInteractions(events);
    }
    @Test void historySurvivesShopConfigAndOtherActorsBeingDisabled()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=prepare(f,student,"ACCEPTED");var original=both(id,student,f);var a=login(student);var merchant=login(f.owner());
        jdbc.update("UPDATE shop SET approval_status='SUSPENDED',business_enabled=FALSE WHERE id=?",f.shop());jdbc.update("UPDATE service_item SET enabled=FALSE,name='已改名',price_amount=5000 WHERE id=?",f.service());jdbc.update("UPDATE barber SET enabled=FALSE WHERE id=?",f.barber());jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.owner().id());assertThat(data(read("STUDENT",id,a))).isEqualTo(original);
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",f.owner().id());jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE shop_id=?",f.shop());jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",student.id());assertThat(data(read("MERCHANT",id,merchant))).isEqualTo(original);
    }
    @Test void sequenceWinsOverTimestampAndInsertionOrder()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=createId(f,login(student));jdbc.update("DELETE FROM order_event WHERE order_id=?",id);
        var at=LocalDateTime.ofInstant(NOW,ZoneOffset.UTC);events.append(Long.parseLong(id),2,"ACCEPT","PENDING_ACCEPTANCE","ACCEPTED","MERCHANT",f.owner().id(),null,at);events.append(Long.parseLong(id),0,"CREATE",null,"PENDING_PAYMENT","STUDENT",student.id(),null,at);events.append(Long.parseLong(id),1,"PAYMENT","PENDING_PAYMENT","PENDING_ACCEPTANCE","STUDENT",student.id(),null,at.plusSeconds(1));
        assertThat(actions(both(id,student,f))).containsExactly("CREATE","PAYMENT","ACCEPT");
    }
    @Test void authorizedOrderWithoutEventsReturnsEmptyWithoutRepair()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=createId(f,login(student));jdbc.update("DELETE FROM order_event WHERE order_id=?",id);clearInvocations(events);var view=both(id,student,f);assertThat(view.isEmpty()).isTrue();verify(events,times(2)).listByOrder(Long.parseLong(id));verifyNoMoreInteractions(events);
    }
    @Test void arrayIsNotTruncatedAndLargeSequenceRemainsAnExactString()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=createId(f,login(student));var at=LocalDateTime.ofInstant(NOW,ZoneOffset.UTC);
        for(long seq=1;seq<9;seq++){events.append(Long.parseLong(id),seq,"PAYMENT","PENDING_PAYMENT","PENDING_ACCEPTANCE","STUDENT",student.id(),null,at);}
        events.append(Long.parseLong(id),9007199254740993L,"ACCEPT","PENDING_ACCEPTANCE","ACCEPTED","MERCHANT",f.owner().id(),null,at);var view=both(id,student,f);assertThat(view.size()).isEqualTo(10);assertThat(view.get(9).path("eventSeq").asString()).isEqualTo("9007199254740993");
    }
    @ParameterizedTest @CsvSource({"STUDENT,PAY","STUDENT,CANCEL","STUDENT,START","MERCHANT,PAY","MERCHANT,CANCEL","MERCHANT,START"})
    void ownershipAndTimelineUseSameRealSnapshotDuringConcurrentAction(String endpoint,String action)throws Exception {
        var f=fixture();var student=user("STUDENT");var id=prepare(f,student,action.equals("START")?"ACCEPTED":"PENDING_PAYMENT");if(action.equals("START")){fixed(START);}var a=login(student);var merchant=login(f.owner());var token=endpoint.equals("STUDENT")?a:merchant;var before=data(read(endpoint,id,token));doReturn(true).when(mockPayment).enabled();
        var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderEventMapper.class);doAnswer(i->{gate.countDown();await(release);return real.listByOrder(i.getArgument(0));}).when(events).listByOrder(Long.parseLong(id));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->read(endpoint,id,token));await(gate);
            try{data(call("POST",action.equals("START")?"/api/v1/merchant/orders/"+id+"/start":"/api/v1/orders/"+id+(action.equals("PAY")?"/payments/mock":"/cancel"),null,action.equals("START")?merchant:a,null));}finally{release.countDown();}
            assertThat(data(request.get(15,TimeUnit.SECONDS))).isEqualTo(before);
        }finally{release.countDown();}
        reset(events);var after=data(read(endpoint,id,token));assertThat(after.size()).isEqualTo(before.size()+1);saved(id,after);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","DISABLED"})
    void merchantQualificationAndTimelineShareSnapshot(String next)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var id=createId(f,student);var merchant=login(f.owner());var before=data(read("MERCHANT",id,merchant));doReturn(true).when(mockPayment).enabled();
        var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderMapper.class);doAnswer(i->{var shop=real.ownShop(i.getArgument(0));gate.countDown();await(release);return shop;}).when(orders).ownShop(f.owner().id());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->read("MERCHANT",id,merchant));await(gate);
            try{data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,student,null));jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",next,f.shop());}finally{release.countDown();}
            assertThat(data(request.get(15,TimeUnit.SECONDS))).isEqualTo(before);
        }finally{release.countDown();}
        reset(orders);clearInvocations(events);expect(read("MERCHANT",id,merchant),403,"ROLE_FORBIDDEN");verifyNoInteractions(events);
    }
    @ParameterizedTest @ValueSource(strings={"STUDENT","MERCHANT"})
    void readsDoNotWaitForBusinessWriteLocksOrSeeUncommittedEventChanges(String endpoint)throws Exception {
        var f=fixture();var student=user("STUDENT");var id=createId(f,login(student));var token=login(endpoint.equals("STUDENT")?student:f.owner());var before=data(read(endpoint,id,token));var gate=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());schedules.lockGuard(f.barber(),DATE);orders.lock(Long.parseLong(id));jdbc.update("UPDATE order_event SET reason='尚未提交' WHERE order_id=?",id);gate.countDown();await(release);tx.setRollbackOnly();}));await(gate);
            try{assertThat(data(read(endpoint,id,token))).isEqualTo(before);assertThat(release.getCount()).isEqualTo(1);}finally{release.countDown();}holder.get(15,TimeUnit.SECONDS);
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
