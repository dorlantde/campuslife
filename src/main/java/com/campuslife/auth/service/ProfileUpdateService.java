package com.campuslife.auth.service;

import com.campuslife.auth.dto.ProfileUpdateRequest;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@Profile("mysql")
public class ProfileUpdateService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final Clock clock;

    public ProfileUpdateService(AccountMapper accounts, AuthSessionMapper sessions, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.clock = clock;
    }

    @Transactional
    public AccountView update(AuthPrincipal principal, ProfileUpdateRequest request) {
        long expectedVersion = version(request.expectedVersion());
        String nickname = null;
        if (request.nickname() != null) {
            if (!request.nickname().isString()) { throw invalid("昵称须为字符串，不能为 null"); }
            nickname = RegistrationPolicy.nickname(request.nickname().asString());
        }

        // Same account -> session lock order as password changes; recheck identity before writing.
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
        if (account.getVersion() != expectedVersion) { throw conflict(); }

        if (nickname != null && !nickname.equals(account.getNickname())) {
            if (expectedVersion == Long.MAX_VALUE) { throw conflict(); }
            if (accounts.updateNickname(principal.accountId(), nickname, expectedVersion) != 1) { throw conflict(); }
            account.setNickname(nickname);
            account.setVersion(expectedVersion + 1);
        }
        return new AccountView(account.getId().toString(), account.getUsername(), account.getNickname(),
                account.getRole(), account.getStatus(), account.getVersion().toString());
    }

    private static long version(JsonNode value) {
        if (value == null || !value.isString() || !value.asString().matches("0|[1-9][0-9]{0,18}")) {
            throw invalid("expectedVersion 须为非负十进制整数字符串");
        }
        try { return Long.parseLong(value.asString()); }
        catch (NumberFormatException exception) { throw invalid("expectedVersion 超出 BIGINT 范围"); }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static BusinessException conflict() {
        return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "账号资料版本已变化或无法递增，请重新读取");
    }
}
