package com.campuslife.auth.service;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate bean so the transaction proxy covers the complete write, after BCrypt preparation. */
@Service
@Profile("mysql")
public class PasswordChangeTransactions {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final Clock clock;

    public PasswordChangeTransactions(AccountMapper accounts, AuthSessionMapper sessions, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.clock = clock;
    }

    @Transactional
    public void commit(AuthPrincipal principal, String verifiedHash, String newHash) {
        // Same first lock as session creation: login cannot commit an old-password session after this change.
        AccountEntity account = accounts.lockById(principal.accountId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        }
        AuthSessionState session = sessions.lockOwnSession(principal.accountId(), principal.jti());
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) {
            throw TokenService.failure("SESSION_EXPIRED", "登录已过期，请重新登录");
        }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出，请重新登录"); }
        if (!account.getPasswordHash().equals(verifiedHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_CREDENTIALS_CHANGED", "账号凭据已变化，请重新验证");
        }
        accounts.updatePassword(principal.accountId(), newHash);
        sessions.revokeAll(principal.accountId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
