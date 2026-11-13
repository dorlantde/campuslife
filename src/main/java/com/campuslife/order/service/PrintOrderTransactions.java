package com.campuslife.order.service;

import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.service.*;
import com.campuslife.merchant.mapper.*;
import com.campuslife.order.entity.*;
import com.campuslife.order.mapper.*;
import com.campuslife.order.vo.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.campuslife.order.service.BookingOrderPolicy.*;

@Service
@Profile("mysql")
public class PrintOrderTransactions {
    private final com.campuslife.messaging.service.OutboxWriter outbox;
    private final com.campuslife.coupon.service.OrderCouponService coupons;
    private final AccountMapper accounts;private final AuthSessionMapper sessions;
    private final OrderMapper orders;private final PrintOrderMapper prints;private final IdempotencyMapper keys;
    private final OrderEventMapper events;private final FileAssetMapper files;private final FileReferenceProtection protection;
    private final ServiceItemMapper services;private final PublicShopMapper shops;private final PrintPriceRuleMapper prices;
    private final BusinessHourMapper hours;private final Clock clock;
    private final MoneyMapper money;private final MockPaymentPolicy mockPayment;
    private final OrderReviewMapper reviews;
    public PrintOrderTransactions(AccountMapper accounts,AuthSessionMapper sessions,OrderMapper orders,PrintOrderMapper prints,
            IdempotencyMapper keys,OrderEventMapper events,FileAssetMapper files,FileReferenceProtection protection,
            ServiceItemMapper services,PublicShopMapper shops,PrintPriceRuleMapper prices,BusinessHourMapper hours,Clock clock,MoneyMapper money,MockPaymentPolicy mockPayment,OrderReviewMapper reviews,com.campuslife.coupon.service.OrderCouponService coupons,com.campuslife.messaging.service.OutboxWriter outbox){
        this.outbox=outbox;this.coupons=coupons;this.accounts=accounts;this.sessions=sessions;this.orders=orders;this.prints=prints;this.keys=keys;this.events=events;
        this.files=files;this.protection=protection;this.services=services;this.shops=shops;this.prices=prices;this.hours=hours;this.clock=clock;
        this.money=money;this.mockPayment=mockPayment;
        this.reviews=reviews;
    }
    public record Creation(OrderDetail order,boolean replayed){}
    public record Cancellation(OrderDetail order,String deadlineError){}
    public record Payment(PaymentResult result,String deadlineError){}

    /** Replay bypasses disk IO and the instance guard; database locks keep the returned facts consistent. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Creation replay(AuthPrincipal principal,String key,String hash){
        var session=access(principal);var result=previous(principal.accountId(),key,hash);valid(session);return result;
    }
    private Creation previous(long account,String key,String hash){
        var old=keys.findPrint(account,key);if(old==null){return null;}
        if(!hash.equals(old.requestHash())){throw conflict("IDEMPOTENCY_KEY_CONFLICT","相同请求键对应不同打印内容");}
        var order=orders.selectById(old.orderId());own(order,account);
        var detail=prints.selectById(order.getId());if(detail==null){throw new IllegalStateException("Missing print detail");}
        orders.shareShop(order.getShopId());files.lock(detail.getFileId());order=orders.lock(order.getId());own(order,account);
        return new Creation(lockedView(order.getId()),true);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Creation create(AuthPrincipal principal,String key,PrintOrderPolicy.Request request,FileAssetEntity location){
        var session=access(principal);var old=previous(principal.accountId(),key,request.hash());if(old!=null){valid(session);return old;}
        var spec=request.spec();Long shopId=orders.serviceShop(spec.service());
        if(shopId==null||orders.shareShop(shopId)==null){throw missing();}
        var file=files.lock(spec.file());
        // All business locks are ready before choosing the creation/expiry decision instant.
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);valid(session);file(file,principal.accountId(),now);
        try { com.campuslife.file.storage.FileLocation.requireSame(location,file); }
        catch(java.io.IOException|IllegalArgumentException ex) { throw FileRules.unavailable(); }
        var shop=shops.visible(shopId);var service=services.findOwn(spec.service(),shopId);
        if(shop==null||!"PRINT".equals(shop.categoryCode())||service==null||!"PRINT".equals(service.getServiceType())){throw missing();}
        if(!Boolean.TRUE.equals(service.getEnabled())){throw conflict("SERVICE_UNAVAILABLE","打印服务已下架");}
        var local=now.atZone(ZONE);var time=local.toLocalTime();
        boolean open=shop.businessEnabled()&&hours.views(shopId).stream().anyMatch(h->h.weekday()==local.getDayOfWeek().getValue()
                &&!time.isBefore(LocalTime.parse(h.startTime()))&&time.isBefore(LocalTime.parse(h.endTime())));
        if(!open){throw conflict("SHOP_UNAVAILABLE","店铺当前不在营业时间");}
        var price=prices.list(service.getId()).stream().filter(p->spec.paper().equals(p.getPaperSize())&&spec.color().equals(p.getColorMode())&&spec.side().equals(p.getSideMode())).findFirst().orElse(null);
        if(price==null||!Boolean.TRUE.equals(price.getEnabled())){throw conflict("PRINT_SPEC_UNAVAILABLE","打印规格未配置或已停用");}
        var totals=PrintQuotePolicy.calculate(file.getPdfPageCount(),spec.copies(),spec.side(),price.getPricePerImpression());
        if(price.getVersion()!=request.version()||(request.coupon()==null && totals.amount()!=request.amount())){throw conflict("QUOTE_CHANGED","价目版本或金额已变化，请重新报价");}
        var order=new ServiceOrderEntity();order.setOrderNo(UUID.randomUUID().toString().replace("-",""));order.setStudentId(principal.accountId());order.setShopId(shopId);
        order.setOrderType("PRINT");order.setStatus("PENDING_PAYMENT");order.setPaymentStatus("NOT_PAID");order.setOriginalAmount(totals.amount());order.setDiscountAmount(0L);order.setPayableAmount(totals.amount());order.setCurrency("CNY");
        order.setShopNameSnapshot(shop.name());order.setStudentNote(request.note());order.setCreatedAt(utc(now));order.setPaymentDeadline(utc(now.plusSeconds(900)));order.setVersion(0L);one(orders.insert(order));
        coupons.lockAll(java.util.Collections.singletonList(request.coupon()));
        now=clock.instant().truncatedTo(ChronoUnit.MILLIS);valid(session);file(file,principal.accountId(),now);
        var finalLocal=now.atZone(ZONE);var finalTime=finalLocal.toLocalTime();
        if(!shop.businessEnabled()||hours.views(shopId).stream().noneMatch(h->h.weekday()==finalLocal.getDayOfWeek().getValue()&&!finalTime.isBefore(LocalTime.parse(h.startTime()))&&finalTime.isBefore(LocalTime.parse(h.endTime())))){throw conflict("SHOP_UNAVAILABLE","店铺当前不在营业时间");}
        long discount=coupons.reserve(request.coupon(),order,now);
        if(order.getOriginalAmount()-discount!=request.amount()){throw conflict("QUOTE_CHANGED","用券后的实付已变化，请重新报价");}
        order.setDiscountAmount(discount);order.setPayableAmount(order.getOriginalAmount()-discount);order.setCreatedAt(utc(now));order.setPaymentDeadline(utc(now.plusSeconds(900)));
        one(orders.couponAmount(order.getId(),discount,order.getCreatedAt(),order.getPaymentDeadline()));

        var detail=new PrintDetailEntity();detail.setOrderId(order.getId());detail.setServiceId(service.getId());detail.setFileId(file.getId());detail.setPriceRuleId(price.getId());
        detail.setServiceNameSnapshot(service.getName());detail.setFileNameSnapshot(file.getOriginalName());detail.setPageCount(file.getPdfPageCount());detail.setCopies(spec.copies());detail.setPaperSize(spec.paper());detail.setColorMode(spec.color());detail.setSideMode(spec.side());detail.setSheetCount(totals.sheets());detail.setUnitPriceAmount(price.getPricePerImpression());detail.setSourcePriceVersion(price.getVersion());detail.setProgress("QUEUED");one(prints.insert(detail));
        one(events.append(order.getId(),0,"CREATE",null,"PENDING_PAYMENT","STUDENT",principal.accountId(),null,utc(now)));
        one(keys.savePrint(principal.accountId(),key,request.hash(),order.getId(),utc(now)));protection.refresh(file,utc(now));valid(session);
        return new Creation(view(order),false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public boolean isPrint(AuthPrincipal principal,long id){var order=orders.selectById(id);if(order==null||order.getStudentId()!=principal.accountId()){throw missing();}return "PRINT".equals(order.getOrderType());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderDetail detail(AuthPrincipal principal,long id){var order=orders.selectById(id);own(order,principal.accountId());return view(order);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public boolean adminIsPrint(AuthPrincipal principal,long id){
        BookingOrderTransactions.requireAdmin(principal.account().role());var order=orders.selectById(id);
        if(order==null||("PRINT".equals(order.getOrderType())&&!orders.hasPrintDetail(id))){throw missing();}return "PRINT".equals(order.getOrderType());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderDetail adminDetail(AuthPrincipal principal,long id){
        BookingOrderTransactions.requireAdmin(principal.account().role());var order=orders.selectById(id);requirePrint(order);return view(order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail adminClose(AuthPrincipal principal,long id,String reason,long expected){
        var session=access(principal,"ADMIN");var located=orders.selectById(id);requirePrint(located);
        var locked=locked(located);var order=locked.order();var detail=locked.detail();valid(session);
        if("CLOSED".equals(order.getStatus())){
            var event=events.latest(id);
            boolean replay="ADMIN_EXCEPTION".equals(order.getCloseReason())&&"REFUNDED".equals(order.getPaymentStatus())
                    &&reason.equals(order.getCloseNote())&&order.getVersion()>0&&expected==order.getVersion()-1
                    &&event!=null&&event.eventSeq()==order.getVersion()&&"ADMIN_EXCEPTION".equals(event.action())
                    &&"ADMIN".equals(event.actorType())&&Long.valueOf(principal.accountId()).equals(event.actorId())
                    &&reason.equals(event.reason())&&"CLOSED".equals(event.toStatus());
            if(!replay){throw conflict("ORDER_STATE_CONFLICT","关闭记录与本次核实请求不符");}
            var payment=paymentFact(order);var refund=money.closureRefund(id);
            String previousProgress=detail.getReadyAt()!=null?"READY_FOR_PICKUP":order.getStartedAt()!=null?"PRINTING":"QUEUED";
            if(!"ABORTED".equals(detail.getProgress())||!adminStage(order,detail,event.fromStatus(),previousProgress)
                    ||order.getClosedAt()==null||!order.getClosedAt().equals(event.occurredAt())
                    ||order.getClosedAt().isBefore(lastStageTime(order,detail))
                    ||refund==null||refund.paymentId()!=payment.id()||refund.amount()!=payment.amount()||!"SUCCEEDED".equals(refund.status())
                    ||!"ADMIN_EXCEPTION".equals(refund.reason())||!reason.equals(refund.note())||!order.getClosedAt().equals(refund.refundedAt())){
                throw new IllegalStateException("Inconsistent administrator print closure facts");
            }
        }else{
            if(expected!=order.getVersion()){throw conflict("CONFIG_VERSION_CONFLICT","订单已变化，请重新查看并核实");}
            if(!"PAID".equals(order.getPaymentStatus())||order.getClosedAt()!=null||!adminStage(order,detail,order.getStatus(),detail.getProgress())){
                throw conflict("ORDER_STATE_CONFLICT","仅已付且未终结的有效打印单可核实退款");
            }
            var payment=paymentFact(order);if(money.findRefund(id)!=null){throw new IllegalStateException("Unexpected administrator print refund");}
            protectedFile(locked);versionAvailable(order);var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);var at=utc(now);
            if(at.isBefore(lastStageTime(order,detail))){throw conflict("PRINT_TIME_INVALID","异常关闭时间不可早于已发生的打印阶段时间");}
            mockPayment.requireEnabled();
            one(money.refundWithNote(id,payment.id(),number(),payment.amount(),"ADMIN_EXCEPTION",reason,at));
            one(orders.adminRefundPrint(id,order.getVersion(),order.getStatus(),reason,at));one(prints.adminAbort(id,detail.getProgress()));
            one(events.append(id,order.getVersion()+1,"ADMIN_EXCEPTION",order.getStatus(),"CLOSED","ADMIN",principal.accountId(),reason,at));
            protection.refresh(locked.file(),at);
        }
        var result=lockedView(id);valid(session);return result;
    }
    private static void requirePrint(ServiceOrderEntity order){if(order==null||!"PRINT".equals(order.getOrderType())){throw missing();}}
    private static boolean adminStage(ServiceOrderEntity order,PrintDetailEntity detail,String status,String progress){
        if(status==null||order.getCompletedAt()!=null||detail.getPickedUpAt()!=null){return false;}
        return switch(status){
            case "PENDING_ACCEPTANCE"->"QUEUED".equals(progress)&&order.getAcceptedAt()==null&&order.getStartedAt()==null&&detail.getReadyAt()==null;
            case "ACCEPTED"->"QUEUED".equals(progress)&&order.getAcceptedAt()!=null&&order.getStartedAt()==null&&detail.getReadyAt()==null;
            case "IN_SERVICE"->order.getAcceptedAt()!=null&&order.getStartedAt()!=null&&!order.getStartedAt().isBefore(order.getAcceptedAt())
                    &&(("PRINTING".equals(progress)&&detail.getReadyAt()==null)||("READY_FOR_PICKUP".equals(progress)&&detail.getReadyAt()!=null&&!detail.getReadyAt().isBefore(order.getStartedAt())));
            default->false;
        };
    }
    private static LocalDateTime lastStageTime(ServiceOrderEntity order,PrintDetailEntity detail){
        var latest=order.getPaidAt();
        for(var at:new LocalDateTime[]{order.getAcceptedAt(),order.getStartedAt(),detail.getReadyAt()}){if(at!=null&&at.isAfter(latest)){latest=at;}}
        return latest;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public boolean merchantIsPrint(AuthPrincipal principal,long id){
        BookingOrderTransactions.requireMerchant(principal.account().role());long shop=merchantShop(principal);
        var order=orders.selectById(id);if(order==null||order.getShopId()!=shop){throw missing();}
        if("PRINT".equals(order.getOrderType())&&!orders.hasPrintDetail(id)){throw missing();}
        return "PRINT".equals(order.getOrderType());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderDetail merchantDetail(AuthPrincipal principal,long id){
        BookingOrderTransactions.requireMerchant(principal.account().role());long shop=merchantShop(principal);
        var order=orders.selectById(id);ownShopPrint(order,shop);merchantOwnership(shop,principal.accountId(),false);return view(order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Cancellation merchantDecision(AuthPrincipal principal,long id,boolean accepting,String reason,String body){
        var entry=lockMerchantPrint(principal,id,accepting);var session=entry.session();var locked=entry.locked();var order=locked.order();
        if(accepting&&body!=null){throw invalid();}
        if(accepting&&"PAID".equals(order.getPaymentStatus())&&order.getAcceptedAt()!=null&&java.util.Set.of("ACCEPTED","IN_SERVICE","COMPLETED").contains(order.getStatus())){
            paymentFact(order);if(money.findRefund(id)!=null){throw new IllegalStateException("Unexpected accepted print refund");}
            return merchantResult(session,id,null);
        }
        if(!accepting&&"CLOSED".equals(order.getStatus())&&"MERCHANT_REJECT".equals(order.getCloseReason())&&"REFUNDED".equals(order.getPaymentStatus())&&reason.equals(order.getCloseNote())){
            return merchantResult(session,id,null);
        }
        if("CLOSED".equals(order.getStatus())&&"ACCEPTANCE_TIMEOUT".equals(order.getCloseReason())){return merchantResult(session,id,"ACCEPTANCE_DEADLINE_PASSED");}
        if(!awaiting(order)){throw conflict("ORDER_STATE_CONFLICT","当前打印订单不可接单或拒单");}
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(!now.isBefore(instant(order.getAcceptanceDeadline()))){
            refundClose(locked,"ACCEPTANCE_TIMEOUT",null,now);return merchantResult(session,id,"ACCEPTANCE_DEADLINE_PASSED");
        }
        if(accepting){
            protectedFile(locked);queued(locked.detail());paymentFact(order);versionAvailable(order);
            if(money.findRefund(id)!=null){throw new IllegalStateException("Unexpected pending print refund");}
            one(orders.accept(id,order.getVersion(),utc(now)));one(events.append(id,order.getVersion()+1,"ACCEPT","PENDING_ACCEPTANCE","ACCEPTED","MERCHANT",principal.accountId(),null,utc(now)));
        }else{refundClose(locked,"MERCHANT_REJECT",reason,"MERCHANT",principal.accountId(),now);}
        return merchantResult(session,id,null);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail merchantCancel(AuthPrincipal principal,long id,String reason){
        var entry=lockMerchantPrint(principal,id,false);var locked=entry.locked();var order=locked.order();var detail=locked.detail();
        if("CLOSED".equals(order.getStatus())&&"REFUNDED".equals(order.getPaymentStatus())&&"MERCHANT_UNAVAILABLE".equals(order.getCloseReason())
                &&"ABORTED".equals(detail.getProgress())&&reason.equals(order.getCloseNote())){
            return merchantResult(entry.session(),id,null).order();
        }
        // PRINT has no booking start time: the locked fulfillment state defines the cancellation window.
        if(!"ACCEPTED".equals(order.getStatus())||!"PAID".equals(order.getPaymentStatus())||!"QUEUED".equals(detail.getProgress())
                ||order.getAcceptedAt()==null||order.getStartedAt()!=null||detail.getReadyAt()!=null||detail.getPickedUpAt()!=null){
            throw conflict("ORDER_STATE_CONFLICT","仅已接单且尚未开始打印的订单可因无法履约取消");
        }
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        refundClose(locked,"MERCHANT_UNAVAILABLE",reason,"MERCHANT",principal.accountId(),now);
        return merchantResult(entry.session(),id,null).order();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail merchantFulfill(AuthPrincipal principal,long id,boolean ready,String body){
        var entry=lockMerchantPrint(principal,id,false);var locked=entry.locked();var order=locked.order();var detail=locked.detail();
        if(body!=null){throw invalid();}
        boolean common="PAID".equals(order.getPaymentStatus())&&order.getAcceptedAt()!=null&&order.getCompletedAt()==null&&detail.getPickedUpAt()==null;
        boolean queued=common&&"ACCEPTED".equals(order.getStatus())&&"QUEUED".equals(detail.getProgress())&&order.getStartedAt()==null&&detail.getReadyAt()==null;
        boolean printing=common&&"IN_SERVICE".equals(order.getStatus())&&"PRINTING".equals(detail.getProgress())&&order.getStartedAt()!=null&&detail.getReadyAt()==null;
        boolean waiting=common&&"IN_SERVICE".equals(order.getStatus())&&"READY_FOR_PICKUP".equals(detail.getProgress())&&order.getStartedAt()!=null&&detail.getReadyAt()!=null;
        boolean completed=completedPrint(order,detail);
        if(!(completed||(ready?(printing||waiting):(queued||printing||waiting)))){throw conflict("ORDER_STATE_CONFLICT","当前打印订单状态不可开始或标记待取件");}
        paymentFact(order);if(money.findRefund(id)!=null){throw new IllegalStateException("Unexpected print fulfillment refund");}
        // A legal replay returns its original facts, without moving a later stage backwards.
        if(completed||waiting||(!ready&&printing)){return merchantResult(entry.session(),id,null).order();}
        protectedFile(locked);versionAvailable(order);var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(now.isBefore(instant(ready?order.getStartedAt():order.getAcceptedAt()))){throw conflict("PRINT_TIME_INVALID","打印动作时间不可早于前序动作时间");}
        var at=utc(now);
        if(ready){one(orders.advancePrintReady(id,order.getVersion()));one(prints.readyPrinting(id,at));}
        else{one(orders.startService(id,order.getVersion(),at));one(prints.startPrinting(id));}
        one(events.append(id,order.getVersion()+1,ready?"READY_FOR_PICKUP":"START",order.getStatus(),"IN_SERVICE","MERCHANT",principal.accountId(),null,at));
        if(ready){outbox.append(order,"PRINT_READY_FOR_PICKUP",order.getVersion()+1,at);}
        return merchantResult(entry.session(),id,null).order();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail pickup(AuthPrincipal principal,long id,String body){
        var session=access(principal);var located=orders.selectById(id);own(located,principal.accountId());
        var locked=locked(located);var order=locked.order();var detail=locked.detail();own(order,principal.accountId());valid(session);
        if(body!=null){throw invalid();}
        boolean completed=completedPrint(order,detail);
        boolean waiting="IN_SERVICE".equals(order.getStatus())&&"PAID".equals(order.getPaymentStatus())
                &&"READY_FOR_PICKUP".equals(detail.getProgress())&&stageTimes(order,detail)
                &&order.getCompletedAt()==null&&detail.getPickedUpAt()==null&&order.getClosedAt()==null;
        if(!completed&&!waiting){throw conflict("ORDER_STATE_CONFLICT","仅待取件打印订单可确认取件");}
        paymentFact(order);if(money.findRefund(id)!=null){throw new IllegalStateException("Unexpected print pickup refund");}
        if(!completed){
            protectedFile(locked);versionAvailable(order);var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if(now.isBefore(instant(detail.getReadyAt()))){throw conflict("PRINT_TIME_INVALID","取件时间不可早于待取件时间");}
            var at=utc(now);one(orders.completeService(id,order.getVersion(),at));one(prints.pickup(id,at));
            one(events.append(id,order.getVersion()+1,"PICKUP","IN_SERVICE","COMPLETED","STUDENT",principal.accountId(),null,at));
            protection.refresh(locked.file(),at);
        }
        var result=lockedView(id);valid(session);return result;
    }
    private static boolean stageTimes(ServiceOrderEntity order,PrintDetailEntity detail){
        return order.getAcceptedAt()!=null&&order.getStartedAt()!=null&&detail.getReadyAt()!=null
                &&!order.getStartedAt().isBefore(order.getAcceptedAt())&&!detail.getReadyAt().isBefore(order.getStartedAt());
    }
    private static boolean completedPrint(ServiceOrderEntity order,PrintDetailEntity detail){
        return "COMPLETED".equals(order.getStatus())&&"PAID".equals(order.getPaymentStatus())&&"PICKED_UP".equals(detail.getProgress())
                &&stageTimes(order,detail)&&order.getClosedAt()==null&&order.getCompletedAt()!=null
                &&order.getCompletedAt().equals(detail.getPickedUpAt())&&!order.getCompletedAt().isBefore(detail.getReadyAt());
    }
    private record MerchantLocked(AuthSessionState session,Locked locked){}
    private MerchantLocked lockMerchantPrint(AuthPrincipal principal,long id,boolean accepting){
        var session=access(principal,"MERCHANT");long shop=merchantShop(principal);var located=orders.selectById(id);ownShopPrint(located,shop);
        if(orders.shareShop(shop)==null){throw missing();}merchantOwnership(shop,principal.accountId(),accepting);
        var locked=locked(located);ownShopPrint(locked.order(),shop);valid(session);return new MerchantLocked(session,locked);
    }
    private Cancellation merchantResult(AuthSessionState session,long id,String error){var result=lockedView(id);valid(session);return new Cancellation(result,error);}
    private long merchantShop(AuthPrincipal principal){var shop=orders.ownShop(principal.accountId());if(shop==null){throw missing();}return shop;}
    private static void ownShopPrint(ServiceOrderEntity order,long shop){if(order==null||order.getShopId()!=shop||!"PRINT".equals(order.getOrderType())){throw missing();}}
    private void merchantOwnership(long shop,long account,boolean accepting){
        var owner=orders.ownership(shop,account);
        if(owner==null||!"ACTIVE".equals(owner.memberStatus())){throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","本人店主成员未激活或已停用");}
        if(accepting&&!"APPROVED".equals(owner.approvalStatus())){throw new BusinessException(HttpStatus.FORBIDDEN,"SHOP_NOT_APPROVED","店铺未获经营资格或已被暂停");}
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Cancellation cancel(AuthPrincipal principal,long id){
        var session=access(principal);var located=orders.selectById(id);own(located,principal.accountId());var locked=locked(located);
        var order=locked.order();own(order,principal.accountId());valid(session);String error=deadlineError(order);
        if(pending(order)){
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);boolean expired=!now.isBefore(instant(order.getPaymentDeadline()));close(order,locked.file(),expired?"PAYMENT_TIMEOUT":"USER_CANCEL",expired?null:principal.accountId(),now);error=expired?"PAYMENT_DEADLINE_PASSED":null;
        }else if(awaiting(order)){
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);boolean expired=!now.isBefore(instant(order.getAcceptanceDeadline()));
            refundClose(locked,expired?"ACCEPTANCE_TIMEOUT":"USER_CANCEL",expired?null:principal.accountId(),now);error=expired?"ACCEPTANCE_DEADLINE_PASSED":null;
        }else if(!"CLOSED".equals(order.getStatus())||!(error!=null||"USER_CANCEL".equals(order.getCloseReason()))){throw conflict("ORDER_STATE_CONFLICT","当前状态不可取消");}
        valid(session);return new Cancellation(lockedView(id),error);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Payment pay(AuthPrincipal principal,long id,String body){
        var session=access(principal);var located=orders.selectById(id);own(located,principal.accountId());var locked=locked(located);
        var order=locked.order();own(order,principal.accountId());valid(session);
        if(body!=null){throw invalid();}
        if("PAYMENT_TIMEOUT".equals(order.getCloseReason())){return new Payment(null,"PAYMENT_DEADLINE_PASSED");}
        if("PAID".equals(order.getPaymentStatus())&&!"CLOSED".equals(order.getStatus())){
            var result=paymentResult(order);valid(session);return new Payment(result,null);
        }
        if(!pending(order)){throw conflict("ORDER_STATE_CONFLICT","当前状态不可支付");}
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(!now.isBefore(instant(order.getPaymentDeadline()))){close(order,locked.file(),"PAYMENT_TIMEOUT",null,now);valid(session);return new Payment(null,"PAYMENT_DEADLINE_PASSED");}
        var shop=shops.visible(order.getShopId());if(shop==null||!"PRINT".equals(shop.categoryCode())){throw conflict("SHOP_UNAVAILABLE","店铺经营资格不可用");}
        protectedFile(locked);queued(locked.detail());mockPayment.requireEnabled();versionAvailable(order);
        coupons.use(order,now);
        one(money.pay(id,number(),order.getPayableAmount(),utc(now)));one(orders.pay(id,order.getVersion(),utc(now),utc(now.plusSeconds(1800))));
        one(events.append(id,order.getVersion()+1,"PAYMENT","PENDING_PAYMENT","PENDING_ACCEPTANCE","STUDENT",principal.accountId(),null,utc(now)));
        valid(session);return new Payment(paymentResult(orders.selectById(id)),null);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean expire(long id){
        var located=orders.selectById(id);if(located==null||!"PRINT".equals(located.getOrderType())){return false;}
        var locked=locked(located);var order=locked.order();var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(pending(order)&&!now.isBefore(instant(order.getPaymentDeadline()))){close(order,locked.file(),"PAYMENT_TIMEOUT",null,now);return true;}
        if(awaiting(order)&&!now.isBefore(instant(order.getAcceptanceDeadline()))){refundClose(locked,"ACCEPTANCE_TIMEOUT",null,now);return true;}
        return false;
    }
    private record Locked(ServiceOrderEntity order,PrintDetailEntity detail,FileAssetEntity file){}
    private Locked locked(ServiceOrderEntity located){
        if(located==null||!"PRINT".equals(located.getOrderType())){throw missing();}
        var detail=prints.selectById(located.getId());if(detail==null){throw new IllegalStateException("Missing print detail");}
        if(orders.shareShop(located.getShopId())==null){throw missing();}
        var file=files.lock(detail.getFileId());var order=orders.lock(located.getId());var current=prints.lock(located.getId());
        if(order==null||!"PRINT".equals(order.getOrderType())||current==null||!current.getFileId().equals(detail.getFileId())){throw new IllegalStateException("Print lock location mismatch");}
        coupons.lockOrder(order.getId());return new Locked(order,current,file);
    }
    private void refundClose(Locked locked,String reason,Long actor,Instant now){
        refundClose(locked,reason,null,actor==null?"SYSTEM":"STUDENT",actor,now);
    }
    private void refundClose(Locked locked,String reason,String note,String actorType,Long actor,Instant now){
        var order=locked.order();mockPayment.requireEnabled();versionAvailable(order);protectedFile(locked);queued(locked.detail());var payment=paymentFact(order);
        if(money.findRefund(order.getId())!=null){throw new IllegalStateException("Unexpected existing print refund");}
        if(note==null){one(money.refund(order.getId(),payment.id(),number(),payment.amount(),reason,utc(now)));one(orders.refundClose(order.getId(),order.getVersion(),reason,utc(now)));}
        else{one(money.refundWithNote(order.getId(),payment.id(),number(),payment.amount(),reason,note,utc(now)));one(orders.refundCloseFrom(order.getId(),order.getVersion(),order.getStatus(),reason,note,utc(now)));}
        one(prints.abort(order.getId()));
        one(events.append(order.getId(),order.getVersion()+1,reason,order.getStatus(),"CLOSED",actorType,actor,note==null?reason:note,utc(now)));
        protection.refresh(locked.file(),utc(now));
    }
    private static void protectedFile(Locked locked){
        var file=locked.file();if(file==null||file.getOwnerId()!=locked.order().getStudentId().longValue()||!"AVAILABLE".equals(file.getStatus())||file.getExpiresAt()!=null){throw FileRules.unavailable();}
    }
    private static void queued(PrintDetailEntity detail){if(!"QUEUED".equals(detail.getProgress())){throw new IllegalStateException("Expected queued print order");}}
    private MoneyMapper.Payment paymentFact(ServiceOrderEntity order){
        var p=money.payment(order.getId());if(p==null||p.orderId()!=order.getId()||p.amount()!=order.getPayableAmount()||!"SUCCEEDED".equals(p.status())||!p.paidAt().equals(order.getPaidAt())||order.getAcceptanceDeadline()==null){throw new IllegalStateException("Missing or inconsistent print payment fact");}return p;
    }
    private PaymentResult paymentResult(ServiceOrderEntity order){var p=paymentFact(order);return new PaymentResult(order.getId().toString(),order.getStatus(),order.getPaymentStatus(),p.paymentNo(),p.amount(),instant(p.paidAt()),instant(order.getAcceptanceDeadline()));}
    private static String number(){return UUID.randomUUID().toString().replace("-","");}
    private static void versionAvailable(ServiceOrderEntity order){if(order.getVersion()==Long.MAX_VALUE){throw conflict("ORDER_STATE_CONFLICT","订单版本无法递增");}}
    private static String deadlineError(ServiceOrderEntity order){return switch(order.getCloseReason()==null?"":order.getCloseReason()){case "PAYMENT_TIMEOUT"->"PAYMENT_DEADLINE_PASSED";case "ACCEPTANCE_TIMEOUT"->"ACCEPTANCE_DEADLINE_PASSED";default->null;};}
    private void close(ServiceOrderEntity order,FileAssetEntity file,String reason,Long actor,Instant now){
        versionAvailable(order);
        coupons.release(order,now);
        one(orders.close(order.getId(),order.getVersion(),reason,utc(now)));one(prints.abort(order.getId()));
        one(events.append(order.getId(),order.getVersion()+1,reason,"PENDING_PAYMENT","CLOSED",actor==null?"SYSTEM":"STUDENT",actor,reason,utc(now)));
        protection.refresh(file,utc(now));
    }
    private OrderDetail view(ServiceOrderEntity order){
        return view(order,prints.selectById(order.getId()));
    }
    /** Locator selects can remain in MyBatis's session cache after another transaction commits. */
    private OrderDetail lockedView(long id){return view(orders.lock(id),prints.lock(id));}
    private OrderDetail view(ServiceOrderEntity order,PrintDetailEntity p){
        if(p==null){throw new IllegalStateException("Missing print detail");}
        var payment=money.payment(order.getId());var refund=money.findRefund(order.getId());
        if(!"NOT_PAID".equals(order.getPaymentStatus())){payment=paymentFact(order);}else if(payment!=null||refund!=null){throw new IllegalStateException("Unexpected print money fact");}
        if(("REFUNDED".equals(order.getPaymentStatus()))!=(refund!=null)||(refund!=null&&refund.amount()!=order.getPayableAmount())){throw new IllegalStateException("Inconsistent print refund fact");}
        return new OrderDetail(order.getId().toString(),order.getOrderNo(),order.getOrderType(),order.getStatus(),order.getPaymentStatus(),order.getShopId().toString(),order.getShopNameSnapshot(),p.getServiceNameSnapshot(),
                order.getOriginalAmount(),order.getDiscountAmount(),order.getPayableAmount(),order.getCurrency(),instant(order.getCreatedAt()),instant(order.getPaymentDeadline()),instant(order.getAcceptanceDeadline()),instant(order.getPaidAt()),instant(order.getAcceptedAt()),instant(order.getStartedAt()),instant(order.getCompletedAt()),instant(order.getClosedAt()),order.getCloseReason(),order.getCloseNote(),order.getStudentNote(),order.getVersion().toString(),reviews.existsByOrder(order.getId()),null,
                new PrintingOrderView(p.getFileId().toString(),p.getFileNameSnapshot(),p.getPageCount(),p.getCopies(),p.getPaperSize(),p.getColorMode(),p.getSideMode(),p.getSheetCount(),p.getUnitPriceAmount(),p.getProgress(),instant(p.getReadyAt()),instant(p.getPickedUpAt())),
                payment==null?null:new PaymentView(payment.paymentNo(),payment.amount(),payment.status(),instant(payment.paidAt())),refund==null?null:new RefundView(refund.refundNo(),refund.amount(),refund.status(),instant(refund.refundedAt())),coupons.view(order.getId()));
    }
    private static void own(ServiceOrderEntity order,long account){if(order==null||order.getStudentId()!=account||!"PRINT".equals(order.getOrderType())){throw missing();}}
    private static boolean pending(ServiceOrderEntity order){return "PRINT".equals(order.getOrderType())&&"PENDING_PAYMENT".equals(order.getStatus())&&"NOT_PAID".equals(order.getPaymentStatus());}
    private static boolean awaiting(ServiceOrderEntity order){return "PRINT".equals(order.getOrderType())&&"PENDING_ACCEPTANCE".equals(order.getStatus())&&"PAID".equals(order.getPaymentStatus());}
    private static void file(FileAssetEntity file,long account,Instant now){
        if(file==null||file.getOwnerId()!=account||"UPLOADING".equals(file.getStatus())){throw missing();}
        if(!"AVAILABLE".equals(file.getStatus())||(file.getExpiresAt()!=null&&!now.isBefore(instant(file.getExpiresAt())))){throw new BusinessException(HttpStatus.GONE,"FILE_EXPIRED","文件已过期或进入删除流程");}
    }
    private AuthSessionState access(AuthPrincipal principal){
        return access(principal,"STUDENT");
    }
    private AuthSessionState access(AuthPrincipal principal,String role){
        requireRole(principal.account().role(),role);var account=accounts.lockById(principal.accountId());if(account==null){throw TokenService.failure("TOKEN_INVALID","登录账号不存在");}
        if(!"ACTIVE".equals(account.getStatus())){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        requireRole(account.getRole(),role);var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);return session;
    }
    private static void requireRole(String actual,String expected){
        switch(expected){case "MERCHANT"->BookingOrderTransactions.requireMerchant(actual);case "ADMIN"->BookingOrderTransactions.requireAdmin(actual);default->BookingOrderTransactions.requireStudent(actual);}
    }
    private void valid(AuthSessionState session){
        if(session==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(session.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!clock.instant().isBefore(instant(session.expiresAt()))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
    }
    private static void one(int n){if(n!=1){throw new IllegalStateException("Print conditional write failed");}}
}
