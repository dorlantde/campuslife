package com.campuslife;

import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.mapper.CouponConsumptionMapper;
import com.campuslife.coupon.service.*;
import com.campuslife.common.exception.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class CouponAsyncMysqlIT extends CouponAsyncFixture {
 @Test void successAndDuplicatesCommitOnlyOneCouponReceiptAndOriginalVersion(){
  var c=campaign(2);var a=account("STUDENT");var r=pending(c,a);var p=principal(a);
  var before=claims.detail(p,r.getId().toString());assertThat(before.status()).isEqualTo("PENDING");assertThat(before.coupon()).isNull();assertThat(before.completedAt()).isNull();
  deliver(r);var fact=state(r);deliver(r);assertThat(state(r).getCompletedAt()).isEqualTo(fact.getCompletedAt());assertThat(issued(c)).isEqualTo(1);assertThat(receiptCount()).isEqualTo(1);
  var detail=claims.detail(p,r.getId().toString());assertThat(detail.status()).isEqualTo("SUCCEEDED");assertThat(detail.coupon().sourceCampaignVersion()).isEqualTo("1");assertThat(detail.acceptedAt()).isEqualTo(base);
  assertThat(jdbc.queryForObject("SELECT version FROM coupon_campaign WHERE id=?",Long.class,c.row().getId())).isEqualTo(1L);
  assertThat(jdbc.queryForObject("SELECT redis_settled_generation FROM coupon_claim_request WHERE id=?",Long.class,r.getId())).isNull();
 }
 @Test void concurrentIssuanceAndDuplicateDeliveriesCannotOversell()throws Exception{
  var c=campaign(3);var rows=new ArrayList<com.campuslife.coupon.entity.ClaimRequestEntity>();for(int i=0;i<9;i++){rows.add(pending(c,account("STUDENT")));}
  try(var pool=Executors.newFixedThreadPool(6)){
   var tasks=new ArrayList<Future<?>>();for(var r:rows){tasks.add(pool.submit(()->deliver(r)));tasks.add(pool.submit(()->deliver(r)));}for(var task:tasks){task.get(30,TimeUnit.SECONDS);}
  }
  assertThat(issued(c)).isEqualTo(3);assertThat(receiptCount()).isEqualTo(9);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(3);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE status='FAILED' AND failure_code='SOLD_OUT'",Integer.class)).isEqualTo(6);
 }
 @ParameterizedTest @ValueSource(longs={-1,0,1}) void deadlineUsesStrictMillisecondBoundary(long offset){
  var c=campaign(1);var r=pending(c,account("STUDENT"));at(base.plusSeconds(300).plusMillis(offset));deliver(r);
  assertThat(state(r).getStatus()).isEqualTo(offset<0?"SUCCEEDED":"FAILED");assertThat(issued(c)).isEqualTo(offset<0?1:0);
 }
 @Test void useEndClampsDeadlineAndEqualityFails(){
  var c=campaign(1);jdbc.update("UPDATE coupon_campaign SET claim_end_at=?,use_end_at=? WHERE id=?",time(base.plusSeconds(60)),time(base.plusSeconds(90)),c.row().getId());
  var r=pending(c,account("STUDENT"));jdbc.update("UPDATE coupon_claim_request SET deadline=? WHERE id=?",time(base.plusSeconds(90)),r.getId());
  // Replace the test-only command so its immutable snapshot matches this earlier deadline.
  jdbc.update("DELETE FROM message_outbox WHERE aggregate_id=? AND event_type='COUPON_ISSUE_REQUESTED'",r.getId());r.setDeadline(time(base.plusSeconds(90)));tx().executeWithoutResult(s->writer.append(r));at(base.plusSeconds(90));deliver(r);assertThat(state(r).getFailureCode()).isEqualTo("PROCESSING_EXPIRED");
 }
 @ParameterizedTest @ValueSource(strings={"studentDisabled","studentRole","shopSuspended","ownerDisabled","memberDisabled"}) void currentQualificationFailureIsTerminal(String kind){
  var c=campaign(1);var a=account("STUDENT");var r=pending(c,a);
  switch(kind){case "studentDisabled"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.getId());case "studentRole"->jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",a.getId());case "shopSuspended"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",c.row().getShopId());case "ownerDisabled"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",c.merchant().getId());default->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",c.row().getShopId());}
  deliver(r);assertThat(state(r).getFailureCode()).isEqualTo("QUALIFICATION_CHANGED");assertThat(issued(c)).isZero();assertThat(receiptCount()).isEqualTo(1);
 }
 @Test void admittedBeforeStopCanIssueAfterStopAndOriginalSessionEnds(){
  var c=campaign(1);var a=account("STUDENT");var r=pending(c,a);var p=principal(a);jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",time(base),a.getId());
  tx().executeWithoutResult(s->{shops.share(c.row().getShopId());gates.lock(c.row().getId());campaigns.stop(c.row().getId(),c.merchant().getId(),1, time(base));});
  deliver(r);assertThat(state(r).getStatus()).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT source_campaign_version FROM user_coupon WHERE campaign_id=?",Long.class,c.row().getId())).isEqualTo(1L);
 }
 @Test void receiptFailureRollsBackInventoryCouponAndResultTogether(){
  var c=campaign(1);var r=pending(c,account("STUDENT"));var real=sql.getMapper(CouponConsumptionMapper.class);
  doAnswer(i->{real.receipt(i.getArgument(0),i.getArgument(1),i.getArgument(2));throw new IllegalStateException("isolated failure after receipt");}).when(receipts).receipt(anyString(),anyString(),any());
  assertThatThrownBy(()->deliver(r)).isInstanceOf(IllegalStateException.class);assertThat(issued(c)).isZero();assertThat(state(r).getStatus()).isEqualTo("PENDING");assertThat(receiptCount()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon",Integer.class)).isZero();
  reset(receipts);deliver(r);assertThat(issued(c)).isEqualTo(1);
 }
 @Test void receiptHashMismatchCannotCommitIssuance(){
  var c=campaign(1);var r=pending(c,account("STUDENT"));jdbc.update("INSERT INTO message_consumption VALUES ('coupon-issue-v1',?,?,?)",message(r),"a".repeat(64),time(base));
  assertThatThrownBy(()->deliver(r)).isInstanceOf(CouponIssuance.InvalidMessage.class);assertThat(issued(c)).isZero();assertThat(state(r).getStatus()).isEqualTo("PENDING");
 }
 @Test void timeoutAndLateMessagesNeverReopenResult(){
  var c=campaign(1);var a=account("STUDENT");var r=pending(c,a);at(base.plusSeconds(300));assertThat(issuance.expire(r.getId())).isTrue();var end=state(r).getCompletedAt();assertThat(issuance.expire(r.getId())).isFalse();deliver(r);assertThat(issued(c)).isZero();assertThat(state(r).getCompletedAt()).isEqualTo(end);assertThat(receiptCount()).isEqualTo(1);
  assertThat(pending(c,a).getId()).isNotEqualTo(r.getId());
 }
 @Test void timeoutAndConsumerCompeteForOneTerminal()throws Exception{
  var c=campaign(1);var r=pending(c,account("STUDENT"));at(base.plusSeconds(300));try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->issuance.expire(r.getId()));var y=pool.submit(()->deliver(r));x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);}
  assertThat(state(r).getFailureCode()).isEqualTo("PROCESSING_EXPIRED");assertThat(issued(c)).isZero();assertThat(receiptCount()).isEqualTo(1);
 }
 @Test void rebuildingPausesWithoutExtendingDeadlineAndOldGenerationPendingSurvives(){
  var c=campaign(1);var r=pending(c,account("STUDENT"));jdbc.update("UPDATE coupon_dispatch_gate SET phase='REBUILDING',resume_phase='OPEN',generation=2,rebuild_token=?,lease_until=? WHERE campaign_id=?","b".repeat(32),time(base.plusSeconds(60)),c.row().getId());
  assertThatThrownBy(()->deliver(r)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);at(base.plusSeconds(300));assertThat(issuance.expire(r.getId())).isFalse();assertThat(state(r).getStatus()).isEqualTo("PENDING");
  jdbc.update("UPDATE coupon_dispatch_gate SET phase='OPEN',resume_phase=NULL,rebuild_token=NULL,lease_until=NULL WHERE campaign_id=?",c.row().getId());deliver(r);assertThat(state(r).getFailureCode()).isEqualTo("PROCESSING_EXPIRED");
 }
 @Test void pausedAdmissionDoesNotStopPreviouslyAcceptedIssuance(){var c=campaign(1);var r=pending(c,account("STUDENT"));jdbc.update("UPDATE coupon_dispatch_gate SET phase='PAUSED',generation=2 WHERE campaign_id=?",c.row().getId());deliver(r);assertThat(state(r).getStatus()).isEqualTo("SUCCEEDED");}
 @Test void pendingAndSuccessUniqueGuardAllowsFailedNewKeyOnly(){
  var c=campaign(3);var a=account("STUDENT");var r=pending(c,a);assertThatThrownBy(()->pending(c,a)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  deliver(r);assertThatThrownBy(()->pending(c,a)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);assertThat(issued(c)).isEqualTo(1);
 }
 @Test void asyncModeAndMissingGateCannotFallThroughToSynchronousIssuer(){
  var c=campaign(1);var a=account("STUDENT");var p=principal(a);jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());
  var node=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(c.row().getId().toString());
  assertThatThrownBy(()->claims.claim(p,"new",new CouponClaim(node))).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"));
  jdbc.update("DELETE FROM coupon_dispatch_gate WHERE campaign_id=?",c.row().getId());assertThatThrownBy(()->claims.claim(p,"new",new CouponClaim(node))).isInstanceOf(IllegalStateException.class);assertThat(issued(c)).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"body","id","snapshot"}) void untrustedOrMismatchedCommandsWriteNothing(String kind){
  var c=campaign(1);var r=pending(c,account("STUDENT"));String id=message(r);byte[] body=outbox.find(id).payload().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  if(kind.equals("body")){body="{}".getBytes();}if(kind.equals("id")){id="a".repeat(32);}if(kind.equals("snapshot")){jdbc.update("UPDATE coupon_claim_request SET reservation_id=? WHERE id=?","b".repeat(32),r.getId());}
  String mid=id;byte[] b=body;assertThatThrownBy(()->issuance.consume(mid,b)).isInstanceOf(CouponIssuance.InvalidMessage.class);assertThat(issued(c)).isZero();assertThat(receiptCount()).isZero();
 }
 @Test void pendingAndOutboxRollbackTogetherIfAdmissionWriterFails(){
  var c=campaign(1);var a=account("STUDENT");assertThatThrownBy(()->tx().executeWithoutResult(s->{pending(c,a);throw new IllegalStateException("test abort");})).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox",Integer.class)).isZero();
 }
 @Test void lockWaitUsesClockAfterCampaignAndRequestAcquisition()throws Exception{
  var c=campaign(1);var r=pending(c,account("STUDENT"));var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var holder=pool.submit(()->tx().executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM coupon_campaign WHERE id=? FOR UPDATE",Long.class,c.row().getId());held.countDown();try{assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new RuntimeException(ex);}}));
   try{assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var consumer=pool.submit(()->deliver(r));Thread.sleep(200);assertThat(consumer.isDone()).isFalse();at(base.plusSeconds(300));release.countDown();holder.get(15,TimeUnit.SECONDS);consumer.get(15,TimeUnit.SECONDS);}finally{release.countDown();}
  }
  assertThat(state(r).getFailureCode()).isEqualTo("PROCESSING_EXPIRED");assertThat(issued(c)).isZero();
 }
 @Test void backwardClockDoesNotInventCompletion(){var c=campaign(1);var r=pending(c,account("STUDENT"));at(base.minusMillis(1));assertThatThrownBy(()->deliver(r)).isInstanceOf(IllegalStateException.class);assertThat(state(r).getStatus()).isEqualTo("PENDING");assertThat(receiptCount()).isZero();}
 @Test void claimWindowEndingDoesNotRevokeDurableAdmission(){
  var c=campaign(1);jdbc.update("UPDATE coupon_campaign SET claim_end_at=? WHERE id=?",time(base.plusSeconds(60)),c.row().getId());var r=pending(c,account("STUDENT"));at(base.plusSeconds(90));deliver(r);assertThat(state(r).getStatus()).isEqualTo("SUCCEEDED");
 }
 @Test void scannerSkipsRebuildAndProcessesEligibleBatch(){
  var c=campaign(3);var a=pending(c,account("STUDENT"));var b=pending(c,account("STUDENT"));var frozen=campaign(1);var x=pending(frozen,account("STUDENT"));
  jdbc.update("UPDATE coupon_dispatch_gate SET phase='REBUILDING',resume_phase='OPEN',rebuild_token=?,lease_until=? WHERE campaign_id=?","c".repeat(32),time(base.plusSeconds(600)),frozen.row().getId());at(base.plusSeconds(300));
  var expiry=new CouponClaimExpiryJob(requests,issuance,clock,false);assertThat(expiry.scan()).isEqualTo(2);assertThat(state(a).getStatus()).isEqualTo("FAILED");assertThat(state(b).getStatus()).isEqualTo("FAILED");assertThat(state(x).getStatus()).isEqualTo("PENDING");
 }
 @Test void sharedGateAllowsConcurrentAdmissionFencesAndBlocksExclusiveStop()throws Exception{
  var c=campaign(1);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(3)){
   var holder=pool.submit(()->tx().executeWithoutResult(s->{gates.share(c.row().getId());held.countDown();awaitLatch(release);}));
   try{assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();pool.submit(()->tx().execute(s->gates.share(c.row().getId()))).get(5,TimeUnit.SECONDS);
    var stop=pool.submit(()->tx().executeWithoutResult(s->{shops.share(c.row().getShopId());gates.lock(c.row().getId());campaigns.stop(c.row().getId(),c.merchant().getId(),1,time(base));}));Thread.sleep(150);assertThat(stop.isDone()).isFalse();release.countDown();holder.get(10,TimeUnit.SECONDS);stop.get(10,TimeUnit.SECONDS);
   }finally{release.countDown();}
  }
 }
 @ParameterizedTest @ValueSource(strings={"pendingCoupon","missingDeadline","longDeadline","completedPending","negativeGeneration","orphanPending"}) void databaseRejectsInconsistentRequestShapes(String kind){
  var c=campaign(2);var r=pending(c,account("STUDENT"));String sql=switch(kind){case "pendingCoupon"->"user_coupon_id=999999";case "missingDeadline"->"deadline=NULL";case "longDeadline"->"deadline=TIMESTAMPADD(SECOND,301,accepted_at)";case "completedPending"->"completed_at=accepted_at";case "negativeGeneration"->"accepted_generation=0";default->"accepted_at=NULL,deadline=NULL";};
  assertThatThrownBy(()->jdbc.update("UPDATE coupon_claim_request SET "+sql+" WHERE id=?",r.getId())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(state(r).getStatus()).isEqualTo("PENDING");
 }
 @Test void failedOriginalKeyRemainsBoundAcrossCampaignAndAllowsNewKey(){
  var c=campaign(2);var a=account("STUDENT");var r=pending(c,a,"fixed_key");at(base.plusSeconds(300));issuance.expire(r.getId());var other=campaign(1);
  assertThatThrownBy(()->pending(other,a,"fixed_key")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);assertThat(pending(c,a,"new_key").getId()).isNotEqualTo(r.getId());
 }
 private static void awaitLatch(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS)){throw new IllegalStateException("Test latch timeout");}}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}
 @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment env;
 @org.springframework.beans.factory.annotation.Autowired tools.jackson.databind.ObjectMapper json;
 @org.springframework.beans.factory.annotation.Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
 @Test void httpReplayAndReadExposeOnlyPersistedPendingOrFailedWithoutAdmission()throws Exception{
  var c=campaign(1);var a=account("STUDENT");var r=pending(c,a,"http_original");String pw="Owned_async_test_2026!";jdbc.update("UPDATE account SET password_hash=? WHERE id=?",passwords.encode(pw),a.getId());
  var client=java.net.http.HttpClient.newHttpClient();String host="http://127.0.0.1:"+env.getRequiredProperty("local.server.port");
  var login=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(host+"/api/v1/auth/login")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username",a.getUsername(),"password",pw)))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
  assertThat(login.statusCode()).isEqualTo(200);String token=json.readTree(login.body()).path("data").path("accessToken").asString();
  var post=java.net.http.HttpRequest.newBuilder(java.net.URI.create(host+"/api/v1/coupon-claims")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key","http_original").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("campaignId",c.row().getId().toString())))).build();
  var pending=client.send(post,java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(pending.statusCode()).isEqualTo(202);assertThat(pending.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(json.readTree(pending.body()).path("data").path("coupon").isNull()).isTrue();
  at(base.plusSeconds(300));var get=java.net.http.HttpRequest.newBuilder(java.net.URI.create(host+"/api/v1/coupon-claims/"+r.getId())).header("Authorization","Bearer "+token).GET().build();var read=client.send(get,java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(read.statusCode()).isEqualTo(200);assertThat(json.readTree(read.body()).path("data").path("status").asString()).isEqualTo("PENDING");
  issuance.expire(r.getId());var failed=client.send(post,java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(failed.statusCode()).isEqualTo(200);assertThat(json.readTree(failed.body()).path("data").path("status").asString()).isEqualTo("FAILED");assertThat(issued(c)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox",Integer.class)).isEqualTo(1);
 }
}
