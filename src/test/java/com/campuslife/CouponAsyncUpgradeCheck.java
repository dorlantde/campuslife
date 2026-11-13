package com.campuslife;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Run alone against a fresh owned fixture: its initial target is intentionally V21. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.flyway.target=21","campuslife.coupon.expiry-enabled=false"})
@ActiveProfiles("mysql")
class CouponAsyncUpgradeCheck {
 @Autowired JdbcTemplate jdbc;@Autowired javax.sql.DataSource dataSource;
 @Test void actualV21SuccessFactsUpgradeWithoutChangingTheirIdentityOrCoupon(){
  assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");
  var now=LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
  jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role) VALUES (830001,'upgrade_m','test','测试商家','MERCHANT'),(830002,'upgrade_s','test','测试学生','STUDENT')");
  try {
   jdbc.update("INSERT INTO shop(id,category_id,name,campus_code,location_text,description,approval_status,business_enabled,version) SELECT 830001,id,'升级测试店','XINGHAI_MAIN','测试','', 'APPROVED',0,1 FROM shop_category WHERE code='PRINT'");
   jdbc.update("INSERT INTO coupon_campaign(id,shop_id,name,threshold_amount,reduction_amount,total_quantity,issued_count,status,claim_start_at,claim_end_at,use_start_at,use_end_at,version,created_at,updated_at,published_at,published_by,publish_expected_version) VALUES (830001,830001,'满30减5',3000,500,10,1,'PUBLISHED',?,?,?,?,1,?,?,?,830001,0)",now.minusMinutes(1),now.plusMinutes(5),now.minusMinutes(1),now.plusMinutes(10),now.minusMinutes(1),now.minusMinutes(1),now.minusMinutes(1));
   jdbc.update("INSERT INTO user_coupon(id,student_id,campaign_id,shop_id,name_snapshot,threshold_amount,reduction_amount,use_start_at,use_end_at,source_campaign_version,claimed_at) VALUES (830001,830002,830001,830001,'满30减5',3000,500,?,?,1,?)",now.minusMinutes(1),now.plusMinutes(10),now);
   jdbc.update("INSERT INTO coupon_claim_request(id,student_id,campaign_id,request_key,user_coupon_id,completed_at) VALUES (830001,830002,830001,'legacy_key',830001,?)",now);
   var before=jdbc.queryForList("SELECT id,student_id,campaign_id,request_key,user_coupon_id,completed_at FROM coupon_claim_request");var coupons=jdbc.queryForList("SELECT * FROM user_coupon");
   var flyway=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("22").cleanDisabled(true).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
   assertThat(jdbc.queryForList("SELECT id,student_id,campaign_id,request_key,user_coupon_id,completed_at FROM coupon_claim_request")).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM user_coupon")).isEqualTo(coupons);
   assertThat(jdbc.queryForObject("SELECT mode FROM coupon_dispatch_gate WHERE campaign_id=830001",String.class)).isEqualTo("SYNC");
   assertThat(jdbc.queryForObject("SELECT status FROM coupon_claim_request WHERE id=830001",String.class)).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT source_mode FROM coupon_claim_request WHERE id=830001",String.class)).isEqualTo("SYNC");
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE created_at=completed_at AND source_campaign_version=1 AND accepted_at IS NULL AND deadline IS NULL",Integer.class)).isEqualTo(1);
   flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
  }finally{
   jdbc.update("DELETE FROM coupon_claim_request WHERE id=830001");jdbc.update("DELETE FROM user_coupon WHERE id=830001");jdbc.update("DELETE FROM coupon_campaign WHERE id=830001");jdbc.update("DELETE FROM shop WHERE id=830001");jdbc.update("DELETE FROM account WHERE id IN (830001,830002)");
  }
 }
}
