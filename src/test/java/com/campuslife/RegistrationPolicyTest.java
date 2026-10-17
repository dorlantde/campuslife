package com.campuslife;

import com.campuslife.auth.config.RegistrationConfiguration;
import com.campuslife.auth.dto.PasswordChangeRequest;
import com.campuslife.auth.dto.RegisterRequest;
import com.campuslife.auth.service.RegistrationPolicy;
import com.campuslife.common.exception.BusinessException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationPolicyTest {
    @Test
    void usernameNormalizationDoesNotDependOnTheMachinesLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(RegistrationPolicy.username("  STuDent_I  ")).isEqualTo("student_i");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "bad-name", "学生账号", "account;drop", "    ", "Kelvin"})
    void invalidUsernameFailsWithoutReflectingItsValue(String value) {
        assertThatThrownBy(() -> RegistrationPolicy.username(value))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void nicknameCountsUnicodeCodePointsAndRejectsMalformedText() {
        assertThat(RegistrationPolicy.nickname("  " + "😀".repeat(40) + "  ")).isEqualTo("😀".repeat(40));
        for (String value : new String[]{" ", "😀".repeat(41), "broken\uD800"}) {
            assertThatThrownBy(() -> RegistrationPolicy.nickname(value)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void passwordLimitsUseCharactersForMinimumAndUtf8BytesForMaximum() {
        for (String value : new String[]{"12345678", "x".repeat(72), "中".repeat(24), "😀".repeat(18), "        "}) {
            assertThatCode(() -> RegistrationPolicy.password(value)).doesNotThrowAnyException();
        }
        for (String value : new String[]{"1234567", "😀".repeat(7), "x".repeat(73), "中".repeat(25), "bad\uD800text"}) {
            assertThatThrownBy(() -> RegistrationPolicy.password(value)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> RegistrationPolicy.password(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void bcryptUsesRandomSaltAndPreservesPasswordSpacesAndCase() {
        PasswordEncoder encoder = new RegistrationConfiguration().passwordEncoder();
        String password = " MiXeD密码1! ";
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertThat(first).startsWith("{bcrypt}$2b$12$").hasSize(68).isNotEqualTo(second);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches(password.strip(), first)).isFalse();
        assertThat(encoder.matches(password.toLowerCase(Locale.ROOT), first)).isFalse();
        assertThat(new RegisterRequest("test_student", password, "测试").toString()).doesNotContain(password);
        assertThat(new PasswordChangeRequest(password, "new-example-password").toString())
                .doesNotContain(password, "new-example-password");
    }
}
