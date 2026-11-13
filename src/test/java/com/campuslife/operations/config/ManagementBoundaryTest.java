package com.campuslife.operations.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementBoundaryTest {
    private final ManagementSecurityConfiguration config = new ManagementSecurityConfiguration();
    private MockEnvironment enabled() {
        return new MockEnvironment().withProperty("campuslife.management.enabled", "true")
                .withProperty("management.server.port", "8081").withProperty("server.port", "8080")
                .withProperty("management.server.address", "127.0.0.1")
                .withProperty("campuslife.management.password", "b".repeat(64));
    }
    @Test void disabledHasNoHttpListenerOrRequiredCredential() {
        assertThatCode(() -> config.managementBoundary(new MockEnvironment()).afterPropertiesSet()).doesNotThrowAnyException();
    }
    @Test void separateLoopbackListenerWithRandomCredentialIsValid() {
        assertThatCode(() -> config.managementBoundary(enabled()).afterPropertiesSet()).doesNotThrowAnyException();
    }
    @Test void disabledCannotAccidentallyExposeAPort() {
        assertThatThrownBy(() -> config.managementBoundary(new MockEnvironment().withProperty("management.server.port", "8081"))
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
    @Test void sameBusinessPortAndPublicBindingAreRejected() {
        assertThatThrownBy(() -> config.managementBoundary(enabled().withProperty("management.server.port", "8080"))
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.managementBoundary(enabled().withProperty("management.server.address", "0.0.0.0"))
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
    @Test void MissingWeakOrNonGeneratedCredentialIsRejected() {
        for (String value : new String[]{"", "password", "z".repeat(64)}) {
            assertThatThrownBy(() -> config.managementBoundary(enabled().withProperty("campuslife.management.password", value))
                    .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void invalidAndPrivilegedPortsAreRejected() {
        for (String value : new String[]{"-1", "0", "80", "65536"}) {
            assertThatThrownBy(() -> config.managementBoundary(enabled().withProperty("management.server.port", value))
                    .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        }
    }
}
