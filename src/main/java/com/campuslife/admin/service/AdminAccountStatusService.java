package com.campuslife.admin.service;

import com.campuslife.admin.dto.AccountStatusWrite;
import com.campuslife.admin.mapper.*;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("mysql")
public class AdminAccountStatusService {
    private final AdminAccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final AccountStatusChangeMapper changes;
    private final Clock clock;
    public AdminAccountStatusService(AdminAccountMapper accounts,AuthSessionMapper sessions,AccountStatusChangeMapper changes,Clock clock){
        this.accounts=accounts;this.sessions=sessions;this.changes=changes;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public AccountView change(AuthPrincipal principal,String accountId,AccountStatusWrite body){
        admin(principal.account().role());
        long targetId=decimal(accountId,false);
        if(body==null||body.status()==null||!body.status().isString()||!Set.of("ACTIVE","DISABLED").contains(body.status().asString())
                ||body.expectedVersion()==null||!body.expectedVersion().isString()){throw invalid();}
        long expected=decimal(body.expectedVersion().asString(),true);String status=body.status().asString();
        // Separate primary-key reads guarantee the order of two account locks, before any session lock.
        long low=Math.min(principal.accountId(),targetId),high=Math.max(principal.accountId(),targetId);
        var first=accounts.lockView(low);var second=low==high?first:accounts.lockView(high);
        var actor=principal.accountId()==low?first:second;var target=targetId==low?first:second;
        if(actor==null){throw TokenService.failure("TOKEN_INVALID","登录账号不存在");}
        if(!"ACTIVE".equals(actor.status())){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        admin(actor.role());
        var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);
        if(target==null){throw new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","目标账号不存在");}
        if(targetId==principal.accountId()){throw new BusinessException(HttpStatus.CONFLICT,"ADMIN_SELF_MODIFICATION_FORBIDDEN","请由另一名有效管理员修改本人状态");}
        if(Long.parseLong(target.version())!=expected){throw conflict();}
        if(target.status().equals(status)){return target;}
        if(expected==Long.MAX_VALUE){throw conflict();}
        var now=LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
        if(accounts.updateStatus(targetId,target.status(),status,expected)!=1){throw conflict();}
        sessions.revokeAll(targetId,now);
        if(changes.append(principal.accountId(),targetId,target.status(),status,expected,expected+1,now)!=1){throw new IllegalStateException("Account status history insert failed");}
        valid(session);
        return new AccountView(target.id(),target.username(),target.nickname(),target.role(),status,Long.toString(expected+1));
    }
    private void valid(AuthSessionState session){
        if(session==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(session.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
    }
    private static void admin(String role){if(!"ADMIN".equals(role)){throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅管理员可修改账号状态");}}
    private static long decimal(String value,boolean zero){
        if(value==null||!value.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}")){throw invalid();}
        try{return Long.parseLong(value);}catch(NumberFormatException e){throw invalid();}
    }
    private static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","账号ID、状态或版本格式不符合要求");}
    private static BusinessException conflict(){return new BusinessException(HttpStatus.CONFLICT,"CONFIG_VERSION_CONFLICT","账号版本已变化或无法递增，请刷新列表确认");}
}
