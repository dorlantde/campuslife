package com.campuslife.order.service;

import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.order.entity.*;
import com.campuslife.order.mapper.*;
import com.campuslife.order.vo.OwnReviewView;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.campuslife.order.service.BookingOrderPolicy.*;

@Service
@Profile("mysql")
public class OrderReviewTransactions {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final OrderMapper orders;
    private final BookingMapper bookings;
    private final PrintOrderMapper prints;
    private final MoneyMapper money;
    private final OrderReviewMapper reviews;
    private final Clock clock;
    private final com.campuslife.merchant.mapper.RatingRevisionMapper revisions;
    public OrderReviewTransactions(AccountMapper accounts,AuthSessionMapper sessions,OrderMapper orders,BookingMapper bookings,
            PrintOrderMapper prints,MoneyMapper money,OrderReviewMapper reviews,Clock clock,
            com.campuslife.merchant.mapper.RatingRevisionMapper revisions){
        this.accounts=accounts;this.sessions=sessions;this.orders=orders;this.bookings=bookings;
        this.prints=prints;this.money=money;this.reviews=reviews;this.clock=clock;
        this.revisions=revisions;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OwnReviewView hide(AuthPrincipal principal,long id,String reason){
        BookingOrderTransactions.requireAdmin(principal.account().role());
        var account=accounts.lockById(principal.accountId());
        if(account==null){throw TokenService.failure("TOKEN_INVALID","登录账号不存在");}
        if(!"ACTIVE".equals(account.getStatus())){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        BookingOrderTransactions.requireAdmin(account.getRole());
        var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);
        var shop=reviews.locateShop(id);
        if(shop==null||orders.shareShop(shop)==null){throw missing();}
        var row=reviews.lock(id);valid(session);
        if(row==null||!shop.equals(row.getShopId())){throw missing();}
        if("HIDDEN".equals(row.getVisibility())){
            if(Long.valueOf(principal.accountId()).equals(row.getModeratedBy())&&reason.equals(row.getModerationReason())&&row.getModeratedAt()!=null){return row.view();}
            throw conflict("REVIEW_STATE_CONFLICT","评价已隐藏，首次处理人或原因不匹配，不能覆盖原审核记录");
        }
        if(!"VISIBLE".equals(row.getVisibility())||row.getModeratedBy()!=null||row.getModerationReason()!=null||row.getModeratedAt()!=null){throw new IllegalStateException("Inconsistent review moderation facts");}
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(now.isBefore(instant(row.getCreatedAt()))){throw conflict("REVIEW_TIME_INVALID","隐藏时间不可早于评价创建时间");}
        if(reviews.hide(id,principal.accountId(),reason,utc(now))!=1){throw new IllegalStateException("Review moderation update failed");}
        if(revisions.advance(shop)<1){throw new IllegalStateException("Rating revision update failed");}
        row.setVisibility("HIDDEN");row.setModeratedBy(principal.accountId());row.setModerationReason(reason);row.setModeratedAt(utc(now));
        valid(session);return row.view();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OwnReviewView create(AuthPrincipal principal,long id,int rating,String content){
        BookingOrderTransactions.requireStudent(principal.account().role());
        var account=accounts.lockById(principal.accountId());
        if(account==null){throw TokenService.failure("TOKEN_INVALID","登录账号不存在");}
        activeStudent(account.getRole(),account.getStatus());
        var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);
        var located=orders.selectById(id);own(located,principal.accountId());
        if(orders.shareShop(located.getShopId())==null){throw missing();}
        var order=orders.lock(id);own(order,principal.accountId());valid(session);
        if(reviews.existsByOrder(id)){throw alreadyReviewed();}
        if(!"COMPLETED".equals(order.getStatus())||!"PAID".equals(order.getPaymentStatus())||order.getCompletedAt()==null
                ||order.getClosedAt()!=null||order.getAcceptedAt()==null||order.getStartedAt()==null
                ||order.getStartedAt().isBefore(order.getAcceptedAt())||order.getCompletedAt().isBefore(order.getStartedAt())){throw notAllowed();}
        if("BOOKING".equals(order.getOrderType())){
            var b=bookings.selectById(id);if(b==null||!"CONSUMED".equals(b.getOccupancyStatus())){throw notAllowed();}
        }else{
            var p=prints.selectById(id);
            if(p==null||!"PICKED_UP".equals(p.getProgress())||p.getReadyAt()==null
                    ||p.getReadyAt().isBefore(order.getStartedAt())||!order.getCompletedAt().equals(p.getPickedUpAt())
                    ||order.getCompletedAt().isBefore(p.getReadyAt())){throw notAllowed();}
        }
        var payment=money.payment(id);
        if(payment==null||payment.orderId()!=id||!"SUCCEEDED".equals(payment.status())||payment.amount()!=order.getPayableAmount()
                ||!payment.paidAt().equals(order.getPaidAt())||money.findRefund(id)!=null){throw new IllegalStateException("Inconsistent completed order money facts");}
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(now.isBefore(instant(order.getCompletedAt()))){throw notAllowed();}
        var row=new OrderReviewEntity();row.setOrderId(id);row.setStudentId(principal.accountId());row.setShopId(order.getShopId());
        row.setRating(rating);row.setContent(content);row.setVisibility("VISIBLE");row.setCreatedAt(utc(now));
        if(reviews.insert(row)!=1){throw new IllegalStateException("Review insert failed");}
        if(revisions.advance(order.getShopId())<1){throw new IllegalStateException("Rating revision update failed");}
        valid(session);return row.view();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OwnReviewView read(AuthPrincipal principal,long id){
        BookingOrderTransactions.requireStudent(principal.account().role());
        var current=sessions.find(principal.accountId(),principal.jti());
        if(current==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        activeStudent(current.role(),current.status());
        var session=new AuthSessionState(current.accountId(),principal.jti(),current.expiresAt(),current.revokedAt());valid(session);
        own(orders.selectById(id),principal.accountId());var row=reviews.findByOrder(id);valid(session);
        if(row==null){throw missing();}
        if(row.getStudentId()!=principal.accountId()){throw new IllegalStateException("Review ownership mismatch");}
        return row.view();
    }
    private static void own(ServiceOrderEntity order,long student){
        if(order==null||order.getStudentId()!=student||!("BOOKING".equals(order.getOrderType())||"PRINT".equals(order.getOrderType()))){throw missing();}
    }
    private static void activeStudent(String role,String status){
        if(!"ACTIVE".equals(status)){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        BookingOrderTransactions.requireStudent(role);
    }
    private void valid(AuthSessionState session){
        if(session==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(session.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!clock.instant().isBefore(instant(session.expiresAt()))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
    }
    static BusinessException alreadyReviewed(){return conflict("REVIEW_ALREADY_EXISTS","本订单已有评价，请读取本人评价确认原记录");}
    private static BusinessException notAllowed(){return conflict("REVIEW_NOT_ALLOWED","仅合法已付完成的本人订单可评价，评价时间不可早于完成时间");}
}
