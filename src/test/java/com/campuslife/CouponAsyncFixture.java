package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.coupon.entity.*;
import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.service.*;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.messaging.mapper.OutboxMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class CouponAsyncFixture {
 @Autowired JdbcTemplate jdbc;@Autowired AccountMapper accounts;@Autowired ShopMapper shops;
 @Autowired CampaignMapper campaigns;@Autowired DispatchGateMapper gates;@Autowired CouponIssueMessage writer;
 @Autowired CouponIssuance issuance;@Autowired CouponClaimService claims;@Autowired OutboxMapper outbox;
 @Autowired PlatformTransactionManager manager;@Autowired org.mybatis.spring.SqlSessionTemplate sql;
 @MockitoSpyBean ClaimRequestMapper requests;@MockitoSpyBean CouponConsumptionMapper receipts;
 @MockitoSpyBean Clock clock;
 final List<Long> accountIds=new ArrayList<>(),shopIds=new ArrayList<>();Instant base;
 @BeforeEach void time(){assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");base=Instant.now().truncatedTo(ChronoUnit.MILLIS);doReturn(base).when(clock).instant();}
 @AfterEach void clean(){
  reset(clock,requests,receipts);
  for(long shop:shopIds){
   jdbc.update("DELETE c FROM message_consumption c JOIN message_outbox o ON o.message_id=c.message_id WHERE o.event_type='COUPON_ISSUE_REQUESTED' AND o.aggregate_id IN (SELECT r.id FROM coupon_claim_request r JOIN coupon_campaign p ON p.id=r.campaign_id WHERE p.shop_id=?)",shop);
   jdbc.update("DELETE FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED' AND aggregate_id IN (SELECT r.id FROM coupon_claim_request r JOIN coupon_campaign p ON p.id=r.campaign_id WHERE p.shop_id=?)",shop);
   jdbc.update("DELETE FROM coupon_claim_request WHERE campaign_id IN (SELECT id FROM coupon_campaign WHERE shop_id=?)",shop);
   jdbc.update("DELETE FROM user_coupon WHERE shop_id=?",shop);jdbc.update("DELETE FROM coupon_campaign WHERE shop_id=?",shop);
   jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);
  }
  for(long id:accountIds){jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);}
  for(long id:accountIds){jdbc.update("DELETE FROM account WHERE id=?",id);}
  accountIds.clear();shopIds.clear();
 }
 AccountEntity account(String role){var a=new AccountEntity();a.setUsername("async_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("虚构异步测试");a.setPasswordHash("unused-test-hash");a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());return a;}
 record Campaign(CouponCampaignEntity row,AccountEntity merchant){}
 Campaign campaign(int stock){
  var owner=account("MERCHANT");var shop=new ShopEntity();shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code='PRINT'",Long.class));shop.setName("虚构异步测试店");shop.setCampusCode("XINGHAI_MAIN");shop.setLocationText("测试位置");shop.setDescription("");shop.setApprovalStatus("APPROVED");shop.setBusinessEnabled(false);shop.setVersion(1L);shops.insert(shop);shopIds.add(shop.getId());
  jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop.getId(),owner.getId());
  var c=new CouponCampaignEntity();c.setShopId(shop.getId());c.setName("测试满30减5");c.setThresholdAmount(3000L);c.setReductionAmount(500L);c.setTotalQuantity(stock);c.setIssuedCount(0);c.setStatus("PUBLISHED");c.setVersion(1L);
  c.setCreatedAt(time(base.minusSeconds(120)));c.setUpdatedAt(time(base.minusSeconds(30)));c.setPublishedAt(time(base.minusSeconds(30)));c.setPublishedBy(owner.getId());c.setPublishExpectedVersion(0L);
  c.setClaimStartAt(time(base.minusSeconds(60)));c.setClaimEndAt(time(base.plusSeconds(900)));c.setUseStartAt(time(base.minusSeconds(120)));c.setUseEndAt(time(base.plusSeconds(1800)));campaigns.insert(c);gates.create(c.getId());return new Campaign(c,owner);
 }
 ClaimRequestEntity pending(Campaign c,AccountEntity student){return pending(c,student,UUID.randomUUID().toString());}
 ClaimRequestEntity pending(Campaign c,AccountEntity student,String key){
  // Test-only admission fixture. No production HTTP or operator bypass exists.
  jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());
  return tx().execute(s->{accounts.lockById(student.getId());shops.share(c.row().getShopId());gates.share(c.row().getId());
   var r=new ClaimRequestEntity();r.setStudentId(student.getId());r.setCampaignId(c.row().getId());r.setRequestKey(key);r.setStatus("PENDING");r.setSourceMode("ASYNC");r.setCreatedAt(time(base));r.setAcceptedAt(time(base));r.setDeadline(time(base.plusSeconds(300)));r.setReservationId(UUID.randomUUID().toString().replace("-",""));r.setAcceptedGeneration(1L);r.setSourceCampaignVersion(1L);requests.insert(r);writer.append(r);return r;});
 }
 AuthPrincipal principal(AccountEntity a){String jti=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(account_id,jti,expires_at,created_at) VALUES (?,?,?,?)",a.getId(),jti,time(base.plusSeconds(7200)),time(base));return new AuthPrincipal(a.getId(),jti,new AccountView(a.getId().toString(),a.getUsername(),a.getNickname(),a.getRole(),a.getStatus(),"0"));}
 String message(ClaimRequestEntity r){return jdbc.queryForObject("SELECT message_id FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED' AND aggregate_id=?",String.class,r.getId());}
 void deliver(ClaimRequestEntity r){String id=message(r);issuance.consume(id,outbox.find(id).payload().getBytes(StandardCharsets.UTF_8));}
 ClaimRequestEntity state(ClaimRequestEntity r){return requests.selectById(r.getId());}
 int issued(Campaign c){return jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id=?",Integer.class,c.row().getId());}
 int receiptCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE consumer_name='coupon-issue-v1'",Integer.class);}
 static LocalDateTime time(Instant i){return LocalDateTime.ofInstant(i,ZoneOffset.UTC);}
 TransactionTemplate tx(){var t=new TransactionTemplate(manager);t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return t;}
 void at(Instant i){doReturn(i).when(clock).instant();}
}
