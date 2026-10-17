package com.campuslife.auth.service;

import com.campuslife.auth.dto.RegisterRequest;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class RegistrationService {
    private final AccountMapper accounts;
    private final PasswordEncoder passwords;

    public RegistrationService(AccountMapper accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public AccountView register(RegisterRequest request) {
        String username = RegistrationPolicy.username(request.username());
        String nickname = RegistrationPolicy.nickname(request.nickname());
        RegistrationPolicy.password(request.password());

        AccountEntity account = new AccountEntity();
        account.setUsername(username);
        account.setNickname(nickname);
        account.setPasswordHash(passwords.encode(request.password()));
        account.setRole("STUDENT");
        account.setStatus("ACTIVE");
        account.setVersion(0L);
        // One atomic INSERT. The unique index, rather than a check-then-insert, arbitrates races.
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException exception) {
            if (!exception.getMostSpecificCause().getMessage().contains("uk_account_username")) {
                throw exception;
            }
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用");
        }
        return new AccountView(account.getId().toString(), account.getUsername(), account.getNickname(),
                account.getRole(), account.getStatus(), account.getVersion().toString());
    }
}
