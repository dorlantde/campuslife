package com.campuslife.admin.service;

import com.campuslife.admin.mapper.AdminAccountMapper;
import com.campuslife.admin.vo.AdminAccountPage;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.SessionAccount;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("mysql")
public class AdminAccountService {
    private final AdminAccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final Clock clock;
    public AdminAccountService(AdminAccountMapper accounts,AuthSessionMapper sessions,Clock clock){
        this.accounts=accounts;this.sessions=sessions;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AdminAccountPage list(AuthPrincipal principal,String role,String status,String pageValue,String sizeValue){
        requireAdmin(principal.account().role());
        if(role!=null&&!Set.of("STUDENT","MERCHANT","ADMIN").contains(role)
                ||status!=null&&!Set.of("ACTIVE","DISABLED").contains(status)){throw invalid();}
        int page=number(pageValue,1,Integer.MAX_VALUE),size=number(sizeValue,20,100);
        var actor=sessions.find(principal.accountId(),principal.jti());
        if(actor==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(!"ACTIVE".equals(actor.status())){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        requireAdmin(actor.role());valid(actor);
        long total=accounts.count(role,status);
        var items=accounts.page(role,status,size,((long)page-1)*size);
        valid(actor);
        return new AdminAccountPage(items,page,size,total,total/size+(total%size==0?0:1));
    }
    private void valid(SessionAccount actor){
        if(actor.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!clock.instant().isBefore(actor.expiresAt().toInstant(ZoneOffset.UTC))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
    }
    private static void requireAdmin(String role){
        if(!"ADMIN".equals(role)){throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅管理员可查询账号列表");}
    }
    private static int number(String value,int fallback,int maximum){
        if(value==null){return fallback;}
        try{if(!value.matches("[0-9]+")){throw new NumberFormatException();}
            int n=Integer.parseInt(value);if(n<1||n>maximum){throw new NumberFormatException();}return n;
        }catch(NumberFormatException ex){throw invalid();}
    }
    private static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","角色、状态或分页格式与范围不符合要求");}
}
