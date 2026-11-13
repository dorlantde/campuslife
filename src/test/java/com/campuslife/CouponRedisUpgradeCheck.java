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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.flyway.target=22","campuslife.coupon-redis.enabled=false"})
@ActiveProfiles("mysql")
class CouponRedisUpgradeCheck {
 @Autowired JdbcTemplate jdbc;@Autowired javax.sql.DataSource dataSource;@Autowired org.springframework.context.ApplicationContext context;
 @Test void actualV22GateUpgradePreservesBusinessFactsAndPausedRecovery(){
  assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");assertThat(context.containsBean("couponRedisStore")).isFalse();assertThat(context.containsBean("couponRedisConnectionFactory")).isFalse();
  var now=LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
  jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role) VALUES (840001,'coupon_upgrade','test','升级测试','MERCHANT')");
  try{
   jdbc.update("INSERT INTO shop(id,category_id,name,campus_code,location_text,description,approval_status,business_enabled,version) SELECT 840001,id,'升级测试','XINGHAI_MAIN','测试','','APPROVED',0,1 FROM shop_category WHERE code='PRINT'");
   for(long id=840001;id<=840003;id++){
    jdbc.update("INSERT INTO coupon_campaign(id,shop_id,name,threshold_amount,reduction_amount,total_quantity,issued_count,status,claim_start_at,claim_end_at,use_start_at,use_end_at,version,created_at,updated_at) VALUES (?,840001,'测试',3000,500,10,0,'DRAFT',?,?,?,?,0,?,?)",id,now,now.plusMinutes(5),now,now.plusMinutes(10),now,now);
    jdbc.update("INSERT INTO coupon_dispatch_gate(campaign_id,mode) VALUES (?,'ASYNC')",id);
   }
   jdbc.update("UPDATE coupon_dispatch_gate SET phase='PAUSED' WHERE campaign_id=840002");jdbc.update("UPDATE coupon_dispatch_gate SET phase='REBUILDING',rebuild_token=?,lease_until=? WHERE campaign_id=840003","a".repeat(32),now.plusSeconds(30));
   var before=jdbc.queryForList("SELECT * FROM coupon_campaign ORDER BY id");var gates=jdbc.queryForList("SELECT * FROM coupon_dispatch_gate ORDER BY campaign_id");
   var flyway=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("23").cleanDisabled(true).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(jdbc.queryForList("SELECT * FROM coupon_campaign ORDER BY id")).isEqualTo(before);
   for(var g:gates){var actual=jdbc.queryForMap("SELECT * FROM coupon_dispatch_gate WHERE campaign_id=?",g.get("campaign_id"));actual.remove("resume_phase");assertThat(actual).isEqualTo(g);}
   assertThat(jdbc.queryForObject("SELECT resume_phase FROM coupon_dispatch_gate WHERE campaign_id=840003",String.class)).isEqualTo("PAUSED");
   assertThatThrownBy(()->jdbc.update("UPDATE coupon_dispatch_gate SET resume_phase=NULL WHERE campaign_id=840003")).isInstanceOf(org.springframework.dao.DataAccessException.class);
   assertThatThrownBy(()->jdbc.update("UPDATE coupon_dispatch_gate SET resume_phase='OPEN' WHERE campaign_id=840001")).isInstanceOf(org.springframework.dao.DataAccessException.class);
   assertThat(flyway.migrate().migrationsExecuted).isZero();
  }finally{jdbc.update("DELETE FROM coupon_campaign WHERE shop_id=840001");jdbc.update("DELETE FROM shop WHERE id=840001");jdbc.update("DELETE FROM account WHERE id=840001");}
 }
}
