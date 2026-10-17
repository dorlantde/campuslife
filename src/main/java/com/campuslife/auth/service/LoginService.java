package com.campuslife.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campuslife.auth.dto.LoginRequest;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.vo.LoginView;
import com.campuslife.common.exception.BusinessException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class LoginService {
    private final AccountMapper accounts;
    private final PasswordEncoder passwords;
    private final SessionService sessions;
    private final String dummyHash;

    public LoginService(AccountMapper accounts, PasswordEncoder passwords, SessionService sessions) {
        this.accounts = accounts; this.passwords = passwords; this.sessions = sessions;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public LoginView login(LoginRequest request) {
        String username = RegistrationPolicy.username(request.username());
        RegistrationPolicy.password(request.password());
        AccountEntity account = accounts.selectOne(Wrappers.<AccountEntity>lambdaQuery().eq(AccountEntity::getUsername, username));
        boolean matches;
        try { matches = passwords.matches(request.password(), account == null ? dummyHash : account.getPasswordHash()); }
        catch (IllegalArgumentException exception) { matches = false; }
        if (!matches || account == null) { throw failed(); }
        // BCrypt runs outside the transaction. Recheck the account under a row lock before creating a session.
        return sessions.create(account.getId(), account.getPasswordHash());
    }

    static BusinessException failed() { return TokenService.failure("LOGIN_FAILED", "用户名或密码错误"); }
}
