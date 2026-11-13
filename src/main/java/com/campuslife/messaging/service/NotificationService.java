package com.campuslife.messaging.service;
import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.SessionAccount;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.messaging.mapper.NotificationMapper;
import com.campuslife.messaging.model.NotificationRow;
import com.campuslife.messaging.vo.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @Profile("mysql")
public class NotificationService {
 private final NotificationMapper rows;private final AccountMapper accounts;private final AuthSessionMapper sessions;private final Clock clock;
 public NotificationService(NotificationMapper rows,AccountMapper accounts,AuthSessionMapper sessions,Clock clock){this.rows=rows;this.accounts=accounts;this.sessions=sessions;this.clock=clock;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public NotificationPage list(AuthPrincipal p,String read,String pageText,String sizeText){
  if(read!=null&&!read.equals("true")&&!read.equals("false")){throw invalid();}
  int page=number(pageText,1,Integer.MAX_VALUE),size=number(sizeText,20,100);var a=sessions.find(p.accountId(),p.jti());access(a);
  var cutoff=now().minusDays(90);Boolean filter=read==null?null:Boolean.valueOf(read);
  long total=rows.count(p.accountId(),cutoff,filter);var items=rows.page(p.accountId(),cutoff,filter,size,((long)page-1)*size).stream().map(NotificationService::view).toList();access(a);return new NotificationPage(items,page,size,total);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void mark(AuthPrincipal p,String id,String body){
  if(body!=null||id==null||!id.matches("[1-9][0-9]{0,18}")){throw invalid();}long value;try{value=Long.parseLong(id);}catch(NumberFormatException ex){throw invalid();}
  var a=accounts.lockById(p.accountId());if(a==null){throw TokenService.failure("TOKEN_INVALID","账号不存在");}role(a.getRole(),a.getStatus());var session=sessions.lockOwnSession(p.accountId(),p.jti());valid(session==null?null:session.expiresAt(),session==null?null:session.revokedAt());
  var row=rows.lockOwn(value,p.accountId());var time=now();if(row==null||!row.createdAt().isAfter(time.minusDays(90))){throw new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","本人通知不存在");}
  if(row.readAt()==null){if(time.isBefore(row.createdAt())){throw new BusinessException(HttpStatus.CONFLICT,"NOTIFICATION_TIME_INVALID","当前时间早于通知");}if(rows.mark(value,time)!=1){throw new IllegalStateException("Notification read write failed");}}
  valid(session.expiresAt(),session.revokedAt());
 }
 private void access(SessionAccount a){if(a==null){throw TokenService.failure("TOKEN_INVALID","会话不存在");}role(a.role(),a.status());valid(a.expiresAt(),a.revokedAt());}
 private void valid(LocalDateTime expires,LocalDateTime revoked){if(expires==null){throw TokenService.failure("TOKEN_INVALID","会话不存在");}if(revoked!=null){throw TokenService.failure("SESSION_REVOKED","会话已撤销");}if(!clock.instant().isBefore(expires.toInstant(ZoneOffset.UTC))){throw TokenService.failure("SESSION_EXPIRED","会话过期");}}
 private static void role(String role,String status){if(!"ACTIVE".equals(status)){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}if(!"STUDENT".equals(role)){throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅学生可读取本人通知");}}
 private static NotificationView view(NotificationRow r){String title="BOOKING_ACCEPTED".equals(r.type())?"预约已接单":"打印曾进入待取件";return new NotificationView(Long.toString(r.id()),r.type(),Long.toString(r.orderId()),r.orderNo(),r.shopName(),r.occurredAt().toInstant(ZoneOffset.UTC),r.createdAt().toInstant(ZoneOffset.UTC),r.readAt()==null?null:r.readAt().toInstant(ZoneOffset.UTC),title,"订单在 "+r.occurredAt().toInstant(ZoneOffset.UTC)+" 发生上述进展，请以订单当前状态为准。");}
 private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
 private static int number(String s,int fallback,int max){if(s==null){return fallback;}try{if(!s.matches("[0-9]+")){throw invalid();}int n=Integer.parseInt(s);if(n<1||n>max){throw invalid();}return n;}catch(NumberFormatException ex){throw invalid();}}
 private static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","通知参数不合法");}
}
