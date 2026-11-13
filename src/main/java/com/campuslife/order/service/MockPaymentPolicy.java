package com.campuslife.order.service;
import com.campuslife.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MockPaymentPolicy {
    private final boolean enabled;
    public MockPaymentPolicy(@Value("${campuslife.mock-payment.enabled:false}") boolean enabled) { this.enabled=enabled; }
    public boolean enabled() { return enabled; }
    public void requireEnabled() {
        if (!enabled()) { throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"MOCK_PAYMENT_DISABLED","当前环境未开启模拟支付与退款"); }
    }
}
