package com.campuslife;

import com.campuslife.auth.dto.LoginRequest;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.vo.LoginView;
import com.campuslife.common.exception.BusinessException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private final byte[] key = randomKey();

    @Test
    void issuedTokenIsSignedAndCarriesOnlyIdentityAndLifetime() {
        String id = UUID.randomUUID().toString();
        var token = service(NOW).issue(123L, id);
        var decoded = service(NOW).verify(token.value());
        assertThat(decoded.getSubject()).isEqualTo("123");
        assertThat(decoded.getId()).isEqualTo(id);
        assertThat(decoded.getExpiresAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(decoded.getClaims()).doesNotContainKeys("password", "passwordHash", "role");
        assertThat(token.toString()).doesNotContain(token.value());
        assertThat(new LoginRequest("student", "example-password").toString()).doesNotContain("example-password");
        assertThat(new LoginView(token.value(), "Bearer", token.expiresAt(), null).toString()).doesNotContain(token.value());
    }

    @Test
    void expirationHasNoGracePeriod() {
        var token = service(NOW).issue(123L, UUID.randomUUID().toString());
        assertThat(service(NOW.plusSeconds(7199)).verify(token.value())).isNotNull();
        assertFailure(() -> service(NOW.plusSeconds(7200)).verify(token.value()), "SESSION_EXPIRED");
    }

    @Test
    void anotherKeyCannotValidateTheToken() {
        var token = service(NOW).issue(123L, UUID.randomUUID().toString());
        var other = new TokenService(Base64.getEncoder().encodeToString(randomKey()), Clock.fixed(NOW, ZoneOffset.UTC));
        assertFailure(() -> other.verify(token.value()), "TOKEN_INVALID");
    }

    @Test
    void algorithmsArePinnedToHs256() throws Exception {
        String token = signed(baseClaims(), JWSAlgorithm.HS512);
        assertFailure(() -> service(NOW).verify(token), "TOKEN_INVALID");
        assertFailure(() -> service(NOW).verify("eyJhbGciOiJub25lIn0.e30."), "TOKEN_INVALID");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClaims")
    void evenCorrectlySignedInvalidClaimsAreRejected(String label, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var claims = baseClaims();
        change.accept(claims);
        String token = signed(claims, JWSAlgorithm.HS256);
        assertFailure(() -> service(NOW).verify(token), "TOKEN_INVALID");
    }

    static Stream<Arguments> invalidClaims() {
        return Stream.of(
                bad("issuer", b -> b.issuer("https://another.local")),
                bad("audience", b -> b.audience("other-api")),
                bad("missing expiry", b -> b.expirationTime(null)),
                bad("missing issue time", b -> b.issueTime(null)),
                bad("future issue time", b -> b.issueTime(Date.from(NOW.plusSeconds(1)))),
                bad("future not-before", b -> b.notBeforeTime(Date.from(NOW.plusSeconds(1)))),
                bad("non-decimal subject", b -> b.subject("admin")),
                bad("zero subject", b -> b.subject("0")),
                bad("overflow subject", b -> b.subject("9999999999999999999")),
                bad("missing jti", b -> b.jwtID(null)),
                bad("invalid jti", b -> b.jwtID("not-a-uuid")),
                bad("longer lifetime", b -> b.expirationTime(Date.from(NOW.plusSeconds(7201)))));
    }

    @Test
    void missingOrWeakKeyCannotStartTheSigner() {
        assertThatThrownBy(() -> new TokenService("not base64", Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenService(Base64.getEncoder().encodeToString(new byte[16]), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Arguments bad(String label, Consumer<JWTClaimsSet.Builder> change) { return Arguments.of(label, change); }
    private TokenService service(Instant now) {
        return new TokenService(Base64.getEncoder().encodeToString(key), Clock.fixed(now, ZoneOffset.UTC));
    }
    private JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder().issuer("https://campuslife.local").audience("campuslife-api").subject("123")
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(7200)));
    }
    private String signed(JWTClaimsSet.Builder claims, JWSAlgorithm algorithm) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims.build());
        token.sign(new MACSigner(key));
        return token.serialize();
    }
    private static byte[] randomKey() { byte[] key = new byte[64]; new SecureRandom().nextBytes(key); return key; }
    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code()).isEqualTo(code));
    }
}
