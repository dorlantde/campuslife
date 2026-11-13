package com.campuslife;
import com.campuslife.messaging.service.*;
import com.campuslife.messaging.mapper.NotificationMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=false","campuslife.booking.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
class NotificationMysqlIT extends OutboxOrderFixture {
 @Autowired NotificationDelivery delivery;@MockitoSpyBean NotificationMapper notices;
 @AfterEach void resetNotice(){reset(notices);}
 private String prepare(Case c)throws Exception{String id=beforeNotify(c);data(notify(c,id,login(c.f().owner())));return jdbc.queryForObject("SELECT message_id FROM message_outbox WHERE aggregate_id=?",String.class,id);}
 private void consume(String id){delivery.consume(id,outbox.find(id).payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"}) void deliveryAndReadAreIdempotentAndIsolated(String type)throws Exception{
  var c=setup(type);String id=prepare(c);consume(id);consume(id);var page=data(call("GET","/api/v1/notifications",null,c.token(),null));assertThat(page.path("total").asInt()).isEqualTo(1);var n=page.path("items").get(0);assertThat(n.path("readAt").isNull()).isTrue();String nid=n.path("id").asString();
  data(call("PATCH","/api/v1/notifications/"+nid+"/read",null,c.token(),null));var time=jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",java.sql.Timestamp.class,nid);fixed(NOW.plusSeconds(1));data(call("PATCH","/api/v1/notifications/"+nid+"/read",null,c.token(),null));assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",java.sql.Timestamp.class,nid)).isEqualTo(time);
  assertThat(data(call("GET","/api/v1/notifications?read=false",null,c.token(),null)).path("total").asInt()).isZero();expect(call("PATCH","/api/v1/notifications/"+nid+"/read",null,login(user("STUDENT")),null),404,"RESOURCE_NOT_FOUND");expect(call("GET","/api/v1/notifications?read=TRUE",null,c.token(),null),400,"VALIDATION_FAILED");expect(call("GET","/api/v1/notifications",null,null,null),401,"AUTH_REQUIRED");
 }
 @Test void concurrentRedeliveryCreatesOneEffect()throws Exception{var c=setup("BOOKING");String id=prepare(c);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var jobs=new ArrayList<Future<?>>();for(int n=0;n<10;n++){jobs.add(pool.submit(()->consume(id)));}for(var job:jobs){job.get(20,TimeUnit.SECONDS);}}assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE source_message_id=?",Integer.class,id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE message_id=?",Integer.class,id)).isEqualTo(1);}
 @Test void failedNotificationRollsBackReceiptAndAllowsRetry()throws Exception{var c=setup("PRINT");String id=prepare(c);doThrow(new IllegalStateException("injected")).when(notices).create(anyString(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any(),any());assertThatThrownBy(()->consume(id)).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE message_id=?",Integer.class,id)).isZero();reset(notices);consume(id);}
 @Test void changedPayloadIsPermanentlyRejected()throws Exception{var c=setup("BOOKING");String id=prepare(c);assertThatThrownBy(()->delivery.consume(id,"{}".getBytes())).isInstanceOf(NotificationDelivery.InvalidMessage.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE message_id=?",Integer.class,id)).isZero();}
 @Test void ninetyDayBoundaryAndHistoricalDelivery()throws Exception{var c=setup("PRINT");String id=prepare(c);consume(id);jdbc.update("UPDATE notification SET created_at=? WHERE source_message_id=?",java.sql.Timestamp.from(NOW.minusSeconds(90L*86400)),id);assertThat(data(call("GET","/api/v1/notifications",null,c.token(),null)).path("total").asInt()).isZero();String nid=jdbc.queryForObject("SELECT id FROM notification WHERE source_message_id=?",Long.class,id).toString();expect(call("PATCH","/api/v1/notifications/"+nid+"/read",null,c.token(),null),404,"RESOURCE_NOT_FOUND");}

 @Test void readWriteSessionExpiryRollsBackReadTimestamp()throws Exception{var c=setup("BOOKING");String id=prepare(c);consume(id);String nid=jdbc.queryForObject("SELECT id FROM notification WHERE source_message_id=?",Long.class,id).toString();var real=sqlSession.getMapper(NotificationMapper.class);doAnswer(inv->{int n=real.mark(inv.getArgument(0),inv.getArgument(1));fixed(NOW.plusSeconds(7200));return n;}).when(notices).mark(anyLong(),any());expect(call("PATCH","/api/v1/notifications/"+nid+"/read",null,c.token(),null),401,"SESSION_EXPIRED");assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",java.sql.Timestamp.class,nid)).isNull();}
 @Test void maintenanceInspectAndReplayUseOriginalIdentity()throws Exception{var c=setup("BOOKING");String id=prepare(c);String payload=outbox.find(id).payload();assertThat(maintenance("Inspect",id)).contains("READY");assertThat(maintenance("Replay",id)).contains("Replay scheduled");assertThat(outbox.find(id).payload()).isEqualTo(payload);assertThat(jdbc.queryForObject("SELECT replay_count FROM message_outbox WHERE message_id=?",Integer.class,id)).isEqualTo(1);consume(id);assertThat(maintenance("Replay",id)).contains("Already consumed");assertThat(jdbc.queryForObject("SELECT replay_count FROM message_outbox WHERE message_id=?",Integer.class,id)).isEqualTo(1);}
 private String maintenance(String action,String id)throws Exception{return maintenance(action,id,0);}
 private String maintenance(String action,String id,int exit)throws Exception{var p=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Dloader.main=com.campuslife.messaging.bootstrap.MessageMaintenance","-cp","target/campuslife-0.0.1-SNAPSHOT.jar","org.springframework.boot.loader.launch.PropertiesLauncher").redirectErrorStream(true);p.environment().put("MESSAGE_ACTION",action);p.environment().put("MESSAGE_ID",id);p.environment().put("MESSAGE_REASON","isolated retry verification");var process=p.start();assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertThat(process.exitValue()).as(output).isEqualTo(exit);return output;}
 @Test void maintenanceRefusesAnActiveUtcLease()throws Exception{var c=setup("BOOKING");String id=prepare(c);jdbc.update("UPDATE message_outbox SET status='PUBLISHING',attempts=1,next_attempt_at=NULL,last_attempt_at=UTC_TIMESTAMP(3),lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE message_id=?","a".repeat(32),id);assertThat(maintenance("Replay",id,1)).contains("maintenance failed");assertThat(outbox.find(id).status()).isEqualTo("PUBLISHING");}
}
