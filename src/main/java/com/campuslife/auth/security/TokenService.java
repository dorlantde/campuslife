package com.campuslife.auth.security;

import com.campuslife.common.exception.BusinessException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** JOSE performs signature verification; the application validates its own strict claim contract. */
@Service
@Profile("mysql")
public class TokenService {
    public static final Duration LIFETIME = Duration.ofHours(2);
    private static final String ISSUER = "https://campuslife.local";
    private static final String AUDIENCE = "campuslife-api";
    private final Clock clock;
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public TokenService(@Value("${campuslife.jwt.secret-base64}") String secret, Clock clock) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(secret); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64"); }
        if (bytes.length < 32) { throw new IllegalStateException("JWT_SECRET_BASE64 must contain at least 32 random bytes"); }
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Time is checked below using the application's Clock, with no expiration grace period.
        this.decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
    }

    public IssuedToken issue(long accountId, String jti) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(LIFETIME);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(Long.toString(accountId)).id(jti).issuedAt(issuedAt).expiresAt(expiresAt).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
                .getTokenValue();
        return new IssuedToken(token, issuedAt, expiresAt);
    }

    public Jwt verify(String token) {
        Jwt jwt;
        try {
            if (token.length() > 4096) { throw new IllegalArgumentException(); }
            jwt = decoder.decode(token);
            if (!"JWT".equals(jwt.getHeaders().get("typ")) || !ISSUER.equals(jwt.getIssuer().toString())
                    || !List.of(AUDIENCE).equals(jwt.getAudience()) || jwt.getSubject() == null
                    || !jwt.getSubject().matches("[1-9][0-9]{0,18}") || Long.parseLong(jwt.getSubject()) <= 0
                    || !UUID.fromString(jwt.getId()).toString().equals(jwt.getId())
                    || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().equals(jwt.getIssuedAt().plus(LIFETIME))
                    || jwt.getIssuedAt().isAfter(clock.instant())
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(clock.instant()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw failure("TOKEN_INVALID", "登录凭证无效");
        }
        if (!clock.instant().isBefore(jwt.getExpiresAt())) { throw failure("SESSION_EXPIRED", "登录已过期，请重新登录"); }
        return jwt;
    }

    public static BusinessException failure(String code, String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {
        @Override public String toString() { return "IssuedToken[value=REDACTED]"; }
    }
}
