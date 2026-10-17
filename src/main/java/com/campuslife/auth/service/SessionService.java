package com.campuslife.auth.service;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.SessionAccount;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.auth.vo.LoginView;
import com.campuslife.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class SessionService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final TokenService tokens;
    private final Clock clock;

    public SessionService(AccountMapper accounts, AuthSessionMapper sessions, TokenService tokens, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.tokens = tokens; this.clock = clock;
    }

    @Transactional
    public LoginView create(long accountId, String verifiedHash) {
        AccountEntity account = accounts.lockById(accountId);
        if (account == null || !account.getPasswordHash().equals(verifiedHash)) { throw LoginService.failed(); }
        requireActive(account.getStatus());
        String jti = UUID.randomUUID().toString();
        TokenService.IssuedToken token = tokens.issue(accountId, jti);
        sessions.create(accountId, jti, LocalDateTime.ofInstant(token.issuedAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC));
        return new LoginView(token.value(), "Bearer", token.expiresAt(),
                new AccountView(account.getId().toString(), account.getUsername(), account.getNickname(),
                        account.getRole(), account.getStatus(), account.getVersion().toString()));
    }

    public AuthPrincipal authenticate(Jwt jwt, boolean allowRevoked) {
        long id = Long.parseLong(jwt.getSubject());
        SessionAccount account = sessions.find(id, jwt.getId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(account.expiresAt().toInstant(ZoneOffset.UTC))) {
            throw TokenService.failure("SESSION_EXPIRED", "登录已过期，请重新登录");
        }
        if (!account.expiresAt().toInstant(ZoneOffset.UTC).equals(jwt.getExpiresAt())) {
            throw TokenService.failure("TOKEN_INVALID", "登录凭证与会话不匹配");
        }
        if (account.revokedAt() != null && !allowRevoked) {
            throw TokenService.failure("SESSION_REVOKED", "登录已退出，请重新登录");
        }
        requireActive(account.status());
        return new AuthPrincipal(id, jwt.getId(), new AccountView(Long.toString(id), account.username(), account.nickname(),
                account.role(), account.status(), Long.toString(account.version())));
    }

    public void logout(AuthPrincipal principal) {
        sessions.revoke(principal.accountId(), principal.jti(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private static void requireActive(String status) {
        if (!"ACTIVE".equals(status)) { throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用"); }
    }
}
