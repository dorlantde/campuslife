package com.campuslife.auth.service;

import com.campuslife.auth.dto.PasswordChangeRequest;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class PasswordChangeService {
    private final AccountMapper accounts;
    private final PasswordEncoder passwords;
    private final PasswordChangeTransactions transactions;

    public PasswordChangeService(AccountMapper accounts, PasswordEncoder passwords, PasswordChangeTransactions transactions) {
        this.accounts = accounts; this.passwords = passwords; this.transactions = transactions;
    }

    public void change(AuthPrincipal principal, PasswordChangeRequest request) {
        RegistrationPolicy.password(request.currentPassword());
        RegistrationPolicy.password(request.newPassword());
        AccountEntity account = accounts.selectById(principal.accountId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        boolean matches;
        try { matches = passwords.matches(request.currentPassword(), account.getPasswordHash()); }
        catch (IllegalArgumentException exception) { matches = false; }
        if (!matches) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", "当前密码不正确");
        }
        String newHash = passwords.encode(request.newPassword());
        transactions.commit(principal, account.getPasswordHash(), newHash);
    }
}
