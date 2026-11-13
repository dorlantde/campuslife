package com.campuslife;

import com.campuslife.order.service.MockPaymentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"campuslife.mock-payment.enabled=true","campuslife.booking.expiry-enabled=false"})
@ActiveProfiles("mysql")
class MockPaymentConfigurationMysqlIT {
    @Autowired MockPaymentPolicy policy;
    @Test void explicitDevelopmentConfigurationEnablesTheRealMoneyGate() {
        assertThat(policy.enabled()).isTrue();policy.requireEnabled();
    }
}
