package com.campuslife.admin.bootstrap;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.service.RegistrationPolicy;
import com.campuslife.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Only imported by the explicit CLI context; never exposed as an HTTP operation. */
public class AdminAccountInitializer {
    private final AccountMapper accounts;
    private final PasswordEncoder passwords;
    public AdminAccountInitializer(AccountMapper accounts, PasswordEncoder passwords) { this.accounts = accounts; this.passwords = passwords; }

    public String create(String username, String nickname, String password) {
        String name = RegistrationPolicy.username(username);
        String display = RegistrationPolicy.nickname(nickname);
        RegistrationPolicy.password(password);
        AccountEntity account = new AccountEntity();
        account.setUsername(name); account.setNickname(display); account.setPasswordHash(passwords.encode(password));
        account.setRole("ADMIN"); account.setStatus("ACTIVE"); account.setVersion(0L);
        try { accounts.insert(account); }
        catch (DuplicateKeyException exception) {
            if (!String.valueOf(exception.getMostSpecificCause().getMessage()).contains("uk_account_username")) { throw exception; }
            throw new BusinessException(HttpStatus.CONFLICT,"USERNAME_EXISTS","用户名已存在，未修改已有账号、角色或密码");
        }
        return account.getId().toString();
    }
}
