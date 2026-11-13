package com.campuslife;
import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.coupon-redis.enabled=false","campuslife.messaging.enabled=false"})
@ActiveProfiles("mysql")
class CouponAdmissionDisabledMysqlIT extends CouponAsyncFixture {
 @Autowired CouponAdmissionService admission;@Autowired CouponMaintenance maintenance;@Autowired ObjectMapper json;
 @Test void defaultSyncRemainsUsableWithoutEitherExternalService(){var c=campaign(1);var p=principal(account("STUDENT"));assertThat(admission.claim(p,"sync",new CouponClaim(json.valueToTree(Long.toString(c.row().getId())))).result().status()).isEqualTo("SUCCEEDED");}
 @Test void accidentalAsyncGateCannotFallbackAndMaintenanceCannotEnable(){
  var c=campaign(1);var id=c.row().getId();maintenance.run("Pause",id,0L,false);
  assertThatThrownBy(()->maintenance.run("Async",id,1L,true)).isInstanceOf(CouponMaintenanceTransactions.Refused.class).hasMessage("MESSAGING_MUST_BE_ENABLED");
  jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC',phase='OPEN' WHERE campaign_id=?",id);
  var p=principal(account("STUDENT"));assertThatThrownBy(()->admission.claim(p,"closed",new CouponClaim(json.valueToTree(id.toString())))).isInstanceOf(com.campuslife.common.exception.BusinessException.class);assertThat(issued(c)).isZero();
 }
 @Test void durablePendingAndFailedCanReplayWhileBothDependenciesDisabled(){
  var c=campaign(1);var a=account("STUDENT");var r=pending(c,a,"prior");var p=principal(a);var b=new CouponClaim(json.valueToTree(c.row().getId().toString()));
  assertThat(admission.claim(p,"prior",b).result().status()).isEqualTo("PENDING");at(base.plusSeconds(300));issuance.expire(r.getId());assertThat(admission.claim(p,"prior",b).result().status()).isEqualTo("FAILED");
 }
}
