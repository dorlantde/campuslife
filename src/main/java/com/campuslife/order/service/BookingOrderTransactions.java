package com.campuslife.order.service;

import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.*;
import com.campuslife.merchant.service.BookingSlotPolicy;
import com.campuslife.order.entity.*;
import com.campuslife.order.mapper.*;
import com.campuslife.order.mapper.model.OrderEventRow;
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
public class BookingOrderTransactions {
    private final com.campuslife.messaging.service.OutboxWriter outbox;
    private final com.campuslife.coupon.service.OrderCouponService coupons;
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final OrderMapper orders;
    private final BookingMapper bookings;
    private final IdempotencyMapper keys;
    private final OrderEventMapper events;
    private final PublicShopMapper shops;
    private final ServiceItemMapper services;
    private final BarberMapper barbers;
    private final BarberServiceMapper bindings;
    private final BarberScheduleMapper schedules;
    private final BusinessHourMapper hours;
    private final Clock clock;
    private final MoneyMapper money;
    private final MockPaymentPolicy mockPayment;
    private final OrderListMapper lists;
    private final OrderReviewMapper reviews;
    public BookingOrderTransactions(AccountMapper accounts,AuthSessionMapper sessions,OrderMapper orders,BookingMapper bookings,
            IdempotencyMapper keys,OrderEventMapper events,PublicShopMapper shops,ServiceItemMapper services,BarberMapper barbers,
            BarberServiceMapper bindings,BarberScheduleMapper schedules,BusinessHourMapper hours,Clock clock,MoneyMapper money,MockPaymentPolicy mockPayment,OrderListMapper lists,OrderReviewMapper reviews,com.campuslife.coupon.service.OrderCouponService coupons,com.campuslife.messaging.service.OutboxWriter outbox) {
        this.outbox=outbox;this.coupons=coupons;this.accounts=accounts;this.sessions=sessions;this.orders=orders;this.bookings=bookings;this.keys=keys;this.events=events;
        this.shops=shops;this.services=services;this.barbers=barbers;this.bindings=bindings;this.schedules=schedules;this.hours=hours;this.clock=clock;
        this.money=money;this.mockPayment=mockPayment;
        this.lists=lists;
        this.reviews=reviews;
    }
    public record Creation(OrderDetail order,boolean replayed) {}
    public record Cancellation(OrderDetail order,String deadlineError) {}
    public record Payment(PaymentResult result,String deadlineError) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Creation create(AuthPrincipal principal,String key,Request request) {
        var session=access(principal);
        var previous=replay(principal.accountId(),key,request.hash());
        if (previous!=null) { valid(session);return new Creation(previous,true); }
        Long shopId=orders.serviceShop(request.service());
        if (shopId==null || orders.shareShop(shopId)==null) { throw missing(); }
        if (barbers.findOwn(request.barber(),shopId)==null) { throw missing(); }
        var day=request.start().atZone(ZONE).toLocalDate();
        // Never create a missing guard. Shop S prevents configuration from changing while acquiring it.
        boolean guardExists=schedules.lockGuard(request.barber(),day)!=null;
        valid(session);
        var shop=shops.visible(shopId);
        if (shop==null || !"HAIRCUT".equals(shop.categoryCode())) { throw missing(); }
        var service=services.findOwn(request.service(),shopId);
        var barber=barbers.findOwn(request.barber(),shopId);
        if (service==null || barber==null) { throw missing(); }
        if (!shop.businessEnabled()) { throw conflict("SHOP_UNAVAILABLE","店铺已停止接收新预约"); }
        if (!Boolean.TRUE.equals(service.getEnabled()) || !"HAIRCUT".equals(service.getServiceType())) { throw conflict("SERVICE_UNAVAILABLE","服务不可预约"); }
        if (!Boolean.TRUE.equals(barber.getEnabled()) || !bindings.bound(request.barber(),request.service())) { throw conflict("BARBER_UNAVAILABLE","人员停用或未绑定服务"); }
        if (service.getVersion()!=request.version() || (request.coupon()==null && service.getPriceAmount()!=request.amount())) { throw conflict("QUOTE_CHANGED","报价版本或金额已变化，请重新确认"); }
        var now=clock.instant();time(request.start(),now);
        if (!guardExists) { throw conflict("BOOKING_TIME_INVALID","目标日期没有有效排班"); }
        var business=hours.views(shopId);var shifts=schedules.rows(request.barber(),day);
        if (BookingSlotPolicy.candidates(day,now,service.getDurationMinutes(),business,shifts).stream().noneMatch(x -> x.startAt().equals(request.start()))) {
            throw conflict("BOOKING_TIME_INVALID","完整服务无法落在营业时间与排班内");
        }
        var end=request.start().plusSeconds(service.getDurationMinutes()*60L);
        record Prior(BookingDetailEntity booking,ServiceOrderEntity order) {}
        var priorOrders=new java.util.ArrayList<Prior>();
        for(var held:bookings.lockOccupied(request.barber(),day).stream().filter(b->overlaps(b,request.start(),end)).sorted(java.util.Comparator.comparingLong(b->b.getOrderId())).toList()){
            priorOrders.add(new Prior(held,orders.lock(held.getOrderId())));
        }
        now=clock.instant();valid(session);time(request.start(),now);
        now=now.truncatedTo(ChronoUnit.MILLIS);
        var order=new ServiceOrderEntity();
        order.setOrderNo(UUID.randomUUID().toString().replace("-",""));order.setStudentId(principal.accountId());order.setShopId(shopId);
        order.setOrderType("BOOKING");order.setStatus("PENDING_PAYMENT");order.setPaymentStatus("NOT_PAID");
        order.setOriginalAmount(service.getPriceAmount());order.setDiscountAmount(0L);order.setPayableAmount(service.getPriceAmount());order.setCurrency("CNY");
        order.setShopNameSnapshot(shop.name());order.setStudentNote(request.note());order.setCreatedAt(utc(now));order.setPaymentDeadline(utc(now.plusSeconds(900)));order.setVersion(0L);
        requireOne(orders.insert(order));
        var couponIds=new java.util.ArrayList<Long>();couponIds.add(request.coupon());
        for(var prior:priorOrders){couponIds.add(coupons.couponFor(prior.order().getId()));}
        coupons.lockAll(couponIds);
        now=clock.instant().truncatedTo(ChronoUnit.MILLIS);valid(session);time(request.start(),now);
        for(var prior:priorOrders){
            var held=prior.booking();var old=prior.order();
            if("HELD".equals(held.getOccupancyStatus())&&pending(old)&&!now.isBefore(instant(old.getPaymentDeadline()))){close(old,"PAYMENT_TIMEOUT",null,now);}
            else if("ACTIVE".equals(held.getOccupancyStatus())&&awaiting(old)&&!now.isBefore(instant(old.getAcceptanceDeadline()))&&mockPayment.enabled()){refundClose(old,"ACCEPTANCE_TIMEOUT",null,now);}
            else{throw conflict("BOOKING_CONFLICT","该时间段已被预约，请重新选择");}
        }
        long discount=coupons.reserve(request.coupon(),order,now);
        if(order.getOriginalAmount()-discount!=request.amount()){throw conflict("QUOTE_CHANGED","用券后的实付已变化，请重新报价");}
        order.setDiscountAmount(discount);order.setPayableAmount(order.getOriginalAmount()-discount);order.setCreatedAt(utc(now));order.setPaymentDeadline(utc(now.plusSeconds(900)));
        requireOne(orders.couponAmount(order.getId(),discount,order.getCreatedAt(),order.getPaymentDeadline()));

        var detail=new BookingDetailEntity();detail.setOrderId(order.getId());detail.setServiceId(request.service());detail.setBarberId(request.barber());detail.setServiceDate(day);
        detail.setStartAt(utc(request.start()));detail.setEndAt(utc(end));detail.setOccupancyStatus("HELD");detail.setServiceNameSnapshot(service.getName());detail.setBarberNameSnapshot(barber.getDisplayName());
        detail.setDurationMinutesSnapshot(service.getDurationMinutes());detail.setServicePriceSnapshot(service.getPriceAmount());detail.setSourceServiceVersion(service.getVersion());
        requireOne(bookings.insert(detail));
        requireOne(events.append(order.getId(),0,"CREATE",null,"PENDING_PAYMENT","STUDENT",principal.accountId(),null,utc(now)));
        requireOne(keys.save(principal.accountId(),key,request.hash(),order.getId(),utc(now)));
        valid(session);time(request.start(),clock.instant());
        return new Creation(view(order,detail),false);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Creation replayAfterConflict(AuthPrincipal principal,String key,String hash) {
        var session=access(principal);var previous=replay(principal.accountId(),key,hash);valid(session);
        if (previous==null) { throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_TEMPORARILY_UNAVAILABLE","创建竞争已回滚，请保留原键重试"); }
        return new Creation(previous,true);
    }
    private OrderDetail replay(long account,String key,String hash) {
        var previous=keys.find(account,key);if (previous==null) { return null; }
        if (!hash.equals(previous.requestHash())) { throw conflict("IDEMPOTENCY_KEY_CONFLICT","相同请求键对应不同预约内容"); }
        var order=orders.selectById(previous.orderId());
        if (order==null || order.getStudentId()!=account) { throw missing(); }
        lockedBooking(order);
        return view(orders.lock(order.getId()),bookings.lockByOrder(order.getId()));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderPage list(AuthPrincipal principal,String type,String status,int page,int size) {
        requireStudent(principal.account().role());
        // Both reads use this transaction's first consistent snapshot. Historical visibility never joins live shop eligibility.
        long total=type==null?lists.countOwn(principal.accountId(),status):lists.countOwnType(principal.accountId(),type,status);
        var items=(type==null?lists.pageOwn(principal.accountId(),status,((long)page-1)*size,size):lists.pageOwnType(principal.accountId(),type,status,((long)page-1)*size,size)).stream()
                .map(com.campuslife.order.mapper.model.OrderSummaryRow::view).toList();
        return new OrderPage(items,page,size,total,total/size+(total%size==0?0:1));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public java.util.List<OrderEventView> events(AuthPrincipal principal,long id) {
        requireStudent(principal.account().role());
        var order=orders.selectById(id);
        if (order==null || order.getStudentId()!=principal.accountId() || !java.util.Set.of("BOOKING","PRINT").contains(order.getOrderType())) { throw missing(); }
        if ("PRINT".equals(order.getOrderType()) && !orders.hasPrintDetail(id)) { throw missing(); }
        return events.listByOrder(id).stream().map(OrderEventRow::view).toList();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public java.util.List<OrderEventView> merchantEvents(AuthPrincipal principal,long id) {
        requireMerchant(principal.account().role());
        var shop=orders.ownShop(principal.accountId());if (shop==null) { throw missing(); }
        var order=orders.selectById(id);
        if(order==null||order.getShopId().longValue()!=shop.longValue()||!java.util.Set.of("BOOKING","PRINT").contains(order.getOrderType())){throw missing();}
        if("PRINT".equals(order.getOrderType())&&!orders.hasPrintDetail(id)){throw missing();}
        merchantOwnership(shop,principal.accountId(),false);
        return events.listByOrder(id).stream().map(OrderEventRow::view).toList();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderDetail detail(AuthPrincipal principal,long id) {
        requireStudent(principal.account().role());
        var order=orders.selectById(id);if (order==null || order.getStudentId()!=principal.accountId()) { throw missing(); }
        return view(order,bookings.selectById(id));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Cancellation cancel(AuthPrincipal principal,long id) {
        var session=access(principal);
        var located=orders.selectById(id);
        if (located==null || located.getStudentId()!=principal.accountId()) { throw missing(); }
        var row=lockedBooking(located);
        var order=orders.lock(id);coupons.lockOrder(id);valid(session);
        if (order.getStudentId()!=principal.accountId()) { throw missing(); }
        String deadline=deadlineError(order);
        if (pending(order)) {
            var now=clock.instant();boolean expired=!now.isBefore(instant(order.getPaymentDeadline()));
            close(order,expired?"PAYMENT_TIMEOUT":"USER_CANCEL",expired?null:principal.accountId(),now);
            deadline=expired?"PAYMENT_DEADLINE_PASSED":null;
        } else if (awaiting(order)) {
            var now=clock.instant();boolean expired=!now.isBefore(instant(order.getAcceptanceDeadline()));
            refundClose(order,expired?"ACCEPTANCE_TIMEOUT":"USER_CANCEL",expired?null:principal.accountId(),now);
            deadline=expired?"ACCEPTANCE_DEADLINE_PASSED":null;
        } else if ("ACCEPTED".equals(order.getStatus()) && "PAID".equals(order.getPaymentStatus())) {
            var now=clock.instant();
            if (now.isAfter(instant(row.getStartAt()).minusSeconds(3600))) { throw conflict("CANCEL_WINDOW_CLOSED","距预约开始不足1小时，不可直接取消"); }
            refundClose(order,"USER_CANCEL",null,"STUDENT",principal.accountId(),now);
        } else if (!"CLOSED".equals(order.getStatus()) || !(deadline!=null || "USER_CANCEL".equals(order.getCloseReason()))) {
            throw conflict("ORDER_STATE_CONFLICT","当前状态不可取消");
        }
        valid(session);
        return new Cancellation(view(orders.lock(id),bookings.lockByOrder(row.getOrderId())),deadline);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Payment pay(AuthPrincipal principal,long id,String body) {
        var session=access(principal);var located=orders.selectById(id);
        if (located==null || located.getStudentId()!=principal.accountId()) { throw missing(); }
        lockedBooking(located);var order=orders.lock(id);var booking=bookings.lockByOrder(id);coupons.lockOrder(id);valid(session);
        if (body!=null) { throw invalid(); }
        if ("PAYMENT_TIMEOUT".equals(order.getCloseReason())) { return new Payment(null,"PAYMENT_DEADLINE_PASSED"); }
        if ("PAID".equals(order.getPaymentStatus()) && !"CLOSED".equals(order.getStatus())) {
            var result=paymentResult(order);valid(session);return new Payment(result,null);
        }
        if (!pending(order)) { throw conflict("ORDER_STATE_CONFLICT","当前状态不可支付"); }
        // Fix the decision instant only after acquiring all business locks. Do not extend deadlines during writes.
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!now.isBefore(instant(order.getPaymentDeadline()))) {
            close(order,"PAYMENT_TIMEOUT",null,now);valid(session);return new Payment(null,"PAYMENT_DEADLINE_PASSED");
        }
        if (shops.visible(order.getShopId())==null) { throw conflict("SHOP_UNAVAILABLE","店铺经营资格不可用"); }
        mockPayment.requireEnabled();versionAvailable(order);
        if (!"HELD".equals(booking.getOccupancyStatus())) { throw new IllegalStateException("Payment requires held occupancy"); }
        coupons.use(order,now);
        requireOne(money.pay(id,number(),order.getPayableAmount(),utc(now)));
        requireOne(orders.pay(id,order.getVersion(),utc(now),utc(now.plusSeconds(1800))));
        requireOne(bookings.activate(id));
        requireOne(events.append(id,order.getVersion()+1,"PAYMENT","PENDING_PAYMENT","PENDING_ACCEPTANCE","STUDENT",principal.accountId(),null,utc(now)));
        valid(session);return new Payment(paymentResult(orders.lock(id)),null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderPage merchantList(AuthPrincipal principal,String type,String status,int page,int size) {
        requireMerchant(principal.account().role());
        // The first consistent read fixes the snapshot for ownership, count and items together.
        var shop=orders.ownShop(principal.accountId());if (shop==null) { throw missing(); }
        merchantOwnership(shop,principal.accountId(),false);
        long total=type==null?lists.countShop(shop,status):lists.countShopType(shop,type,status);
        var items=(type==null?lists.pageShop(shop,status,((long)page-1)*size,size):lists.pageShopType(shop,type,status,((long)page-1)*size,size)).stream()
                .map(com.campuslife.order.mapper.model.OrderSummaryRow::view).toList();
        return new OrderPage(items,page,size,total,total/size+(total%size==0?0:1));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderDetail merchantDetail(AuthPrincipal principal,long id) {
        requireMerchant(principal.account().role());
        var shop=orders.ownShop(principal.accountId());if (shop==null) { throw missing(); }
        var order=orders.selectById(id);ownBooking(order,shop);
        merchantOwnership(shop,principal.accountId(),false);
        return view(order,bookings.selectById(id));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Cancellation merchantDecision(AuthPrincipal principal,long id,boolean accept,String reason,String body) {
        var locked=lockMerchantBooking(principal,id,accept);
        var session=locked.session();var order=locked.order();var booking=locked.booking();
        if (accept && body!=null) { throw invalid(); }
        if (accept && "PAID".equals(order.getPaymentStatus()) && order.getAcceptedAt()!=null
                && java.util.Set.of("ACCEPTED","IN_SERVICE","COMPLETED").contains(order.getStatus())) {
            paymentResult(order);valid(session);return new Cancellation(view(order,booking),null);
        }
        if (!accept && "CLOSED".equals(order.getStatus()) && "MERCHANT_REJECT".equals(order.getCloseReason())
                && "REFUNDED".equals(order.getPaymentStatus()) && reason.equals(order.getCloseNote())) {
            valid(session);return new Cancellation(view(order,booking),null);
        }
        if ("CLOSED".equals(order.getStatus()) && "ACCEPTANCE_TIMEOUT".equals(order.getCloseReason())) {
            return new Cancellation(view(order,booking),"ACCEPTANCE_DEADLINE_PASSED");
        }
        if (!awaiting(order)) { throw conflict("ORDER_STATE_CONFLICT","当前状态不可接单或拒单"); }
        var now=clock.instant();
        if (!now.isBefore(instant(order.getAcceptanceDeadline()))) {
            refundClose(order,"ACCEPTANCE_TIMEOUT",null,now);valid(session);
            return new Cancellation(view(orders.lock(id),bookings.lockByOrder(id)),"ACCEPTANCE_DEADLINE_PASSED");
        }
        if (accept) {
            if (!now.isBefore(instant(booking.getStartAt()))) { throw conflict("BOOKING_TIME_INVALID","预约已经开始，不可接单"); }
            if (!"ACTIVE".equals(booking.getOccupancyStatus()) || money.findRefund(id)!=null) { throw new IllegalStateException("Invalid paid booking occupancy or refund"); }
            paymentResult(order);versionAvailable(order);var at=utc(now.truncatedTo(ChronoUnit.MILLIS));
            requireOne(orders.accept(id,order.getVersion(),at));
            requireOne(events.append(id,order.getVersion()+1,"ACCEPT","PENDING_ACCEPTANCE","ACCEPTED","MERCHANT",principal.accountId(),null,at));
            outbox.append(order,"BOOKING_ACCEPTED",order.getVersion()+1,at);
        } else { refundClose(order,"MERCHANT_REJECT",reason,"MERCHANT",principal.accountId(),now); }
        valid(session);return new Cancellation(view(orders.lock(id),bookings.lockByOrder(id)),null);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail merchantCancel(AuthPrincipal principal,long id,String reason) {
        var locked=lockMerchantBooking(principal,id,false);var order=locked.order();
        if ("CLOSED".equals(order.getStatus()) && "REFUNDED".equals(order.getPaymentStatus())
                && "MERCHANT_UNAVAILABLE".equals(order.getCloseReason()) && reason.equals(order.getCloseNote())) {
            valid(locked.session());return view(order,locked.booking());
        }
        if (!"ACCEPTED".equals(order.getStatus()) || !"PAID".equals(order.getPaymentStatus())) {
            throw conflict("ORDER_STATE_CONFLICT","仅已接单且未开始的预约可因无法履约取消");
        }
        var now=clock.instant();
        if (!now.isBefore(instant(locked.booking().getStartAt()))) { throw conflict("CANCEL_WINDOW_CLOSED","预约开始时间已到，不可普通取消"); }
        refundClose(order,"MERCHANT_UNAVAILABLE",reason,"MERCHANT",principal.accountId(),now);
        valid(locked.session());return view(orders.lock(id),bookings.lockByOrder(id));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail fulfill(AuthPrincipal principal,long id,boolean complete,String body) {
        var locked=lockMerchantBooking(principal,id,false);var order=locked.order();var booking=locked.booking();
        if (body!=null) { throw invalid(); }
        boolean completed="COMPLETED".equals(order.getStatus());
        boolean inService="IN_SERVICE".equals(order.getStatus());
        boolean replay=completed || (!complete && inService);
        if (!"PAID".equals(order.getPaymentStatus()) || (!replay && !(complete?inService:"ACCEPTED".equals(order.getStatus())))) {
            throw conflict("ORDER_STATE_CONFLICT","当前状态不可开始或完成服务");
        }
        paymentResult(order);
        if (money.findRefund(id)!=null || !((completed?"CONSUMED":"ACTIVE").equals(booking.getOccupancyStatus()))) {
            throw new IllegalStateException("Invalid fulfillment payment or occupancy fact");
        }
        if (replay) {
            if (order.getStartedAt()==null || (completed && order.getCompletedAt()==null)) { throw new IllegalStateException("Missing fulfillment time fact"); }
            valid(locked.session());return view(order,booking);
        }
        var now=clock.instant();
        if (complete) {
            if (order.getStartedAt()==null || order.getCompletedAt()!=null) { throw new IllegalStateException("Invalid completion time fact"); }
            if (now.isBefore(instant(order.getStartedAt()))) { throw conflict("BOOKING_TIME_INVALID","完成时间不可早于实际开始时间"); }
        } else {
            if (order.getAcceptedAt()==null || order.getStartedAt()!=null || order.getCompletedAt()!=null) { throw new IllegalStateException("Invalid start time fact"); }
            if (now.isBefore(instant(booking.getStartAt())) || !now.isBefore(instant(booking.getEndAt()))) {
                throw conflict("BOOKING_TIME_INVALID","仅可在预约开始至结束之前标记开始");
            }
        }
        versionAvailable(order);var at=utc(now.truncatedTo(ChronoUnit.MILLIS));
        if (complete) { requireOne(orders.completeService(id,order.getVersion(),at));requireOne(bookings.consumeActive(id)); }
        else { requireOne(orders.startService(id,order.getVersion(),at)); }
        requireOne(events.append(id,order.getVersion()+1,complete?"COMPLETE":"START",order.getStatus(),complete?"COMPLETED":"IN_SERVICE","MERCHANT",principal.accountId(),null,at));
        valid(locked.session());return view(orders.lock(id),bookings.lockByOrder(id));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderDetail adminDetail(AuthPrincipal principal,long id) {
        requireAdmin(principal.account().role());
        var order=orders.selectById(id);
        if (order==null || !"BOOKING".equals(order.getOrderType())) { throw missing(); }
        return view(order,bookings.selectById(id));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDetail adminClose(AuthPrincipal principal,long id,String reason,long expected) {
        var session=access(principal,"ADMIN");var located=orders.selectById(id);
        if (located==null || !"BOOKING".equals(located.getOrderType())) { throw missing(); }
        lockedBooking(located);
        var order=orders.lock(id);var booking=bookings.lockByOrder(id);valid(session);
        if ("CLOSED".equals(order.getStatus())) {
            var event=events.latest(id);
            boolean replay="ADMIN_EXCEPTION".equals(order.getCloseReason()) && "REFUNDED".equals(order.getPaymentStatus())
                && reason.equals(order.getCloseNote()) && order.getVersion()>0 && expected==order.getVersion()-1
                && event!=null && event.eventSeq()==order.getVersion() && "ADMIN_EXCEPTION".equals(event.action())
                && "ADMIN".equals(event.actorType()) && Long.valueOf(principal.accountId()).equals(event.actorId())
                && reason.equals(event.reason()) && "CLOSED".equals(event.toStatus());
            if (!replay) { throw conflict("ORDER_STATE_CONFLICT","关闭记录与本次核实请求不符"); }
            var p=money.payment(id);var r=money.findRefund(id);
            boolean started="IN_SERVICE".equals(event.fromStatus());
            if ((!started && !java.util.Set.of("PENDING_ACCEPTANCE","ACCEPTED").contains(event.fromStatus()))
                    || p==null || r==null || p.amount()!=order.getPayableAmount() || r.amount()!=p.amount()
                    || !"SUCCEEDED".equals(p.status()) || !"SUCCEEDED".equals(r.status())
                    || order.getClosedAt()==null || (started!=(order.getStartedAt()!=null))
                    || !(started?"CONSUMED":"RELEASED").equals(booking.getOccupancyStatus())) {
                throw new IllegalStateException("Inconsistent administrator closure facts");
            }
            valid(session);return view(order,booking);
        }
        if (expected!=order.getVersion()) { throw conflict("CONFIG_VERSION_CONFLICT","订单已变化，请重新查看并核实"); }
        if (!"PAID".equals(order.getPaymentStatus()) || !java.util.Set.of("PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE").contains(order.getStatus())
                || !"ACTIVE".equals(booking.getOccupancyStatus())) { throw conflict("ORDER_STATE_CONFLICT","仅已付且未终结的有效预约可核实退款"); }
        boolean started="IN_SERVICE".equals(order.getStatus());
        if (started!=(order.getStartedAt()!=null) || order.getCompletedAt()!=null || money.findRefund(id)!=null) {
            throw new IllegalStateException("Inconsistent paid booking facts");
        }
        paymentResult(order);versionAvailable(order);
        refundClose(order,"ADMIN_EXCEPTION",reason,"ADMIN",principal.accountId(),clock.instant());
        valid(session);return view(orders.lock(id),bookings.lockByOrder(id));
    }

    private record LockedMerchantBooking(AuthSessionState session,ServiceOrderEntity order,BookingDetailEntity booking) {}
    private LockedMerchantBooking lockMerchantBooking(AuthPrincipal principal,long id,boolean accepting) {
        var session=access(principal,"MERCHANT");
        var shop=orders.ownShop(principal.accountId());if (shop==null) { throw missing(); }
        var located=orders.selectById(id);ownBooking(located,shop);
        if (orders.shareShop(shop)==null) { throw missing(); }
        merchantOwnership(shop,principal.accountId(),accepting);
        var locator=bookings.selectById(id);if (locator==null) { throw new IllegalStateException("Missing booking detail"); }
        if (schedules.lockGuard(locator.getBarberId(),locator.getServiceDate())==null) { throw new IllegalStateException("Missing booking guard"); }
        var order=orders.lock(id);var booking=bookings.lockByOrder(id);ownBooking(order,shop);valid(session);
        if (booking==null) { throw new IllegalStateException("Missing booking detail"); }
        return new LockedMerchantBooking(session,order,booking);
    }

    private static void ownBooking(ServiceOrderEntity order,long shop) {
        if (order==null || order.getShopId()!=shop || !"BOOKING".equals(order.getOrderType())) { throw missing(); }
    }
    private void merchantOwnership(long shop,long account,boolean accepting) {
        var owner=orders.ownership(shop,account);
        if (owner==null || !"ACTIVE".equals(owner.memberStatus())) { throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","本人店主成员未激活或已停用"); }
        if (accepting && !"APPROVED".equals(owner.approvalStatus())) { throw new BusinessException(HttpStatus.FORBIDDEN,"SHOP_NOT_APPROVED","店铺未获经营资格或已被暂停"); }
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean expire(long id) {
        var located=orders.selectById(id);if (located==null || !(pending(located)||awaiting(located))) { return false; }
        lockedBooking(located);var order=orders.lock(id);coupons.lockOrder(id);var now=clock.instant();
        if (pending(order) && !now.isBefore(instant(order.getPaymentDeadline()))) {
            close(order,"PAYMENT_TIMEOUT",null,now);return true;
        }
        if (awaiting(order) && !now.isBefore(instant(order.getAcceptanceDeadline()))) {
            refundClose(order,"ACCEPTANCE_TIMEOUT",null,now);return true;
        }
        return false;
    }
    private BookingDetailEntity lockedBooking(ServiceOrderEntity located) {
        var booking=bookings.selectById(located.getId());
        if (booking==null || !"BOOKING".equals(located.getOrderType()) || orders.shareShop(located.getShopId())==null) { throw missing(); }
        if (schedules.lockGuard(booking.getBarberId(),booking.getServiceDate())==null) { throw new IllegalStateException("Missing booking guard"); }
        return booking;
    }
    private void close(ServiceOrderEntity order,String reason,Long actor,Instant now) {
        versionAvailable(order);
        var at=utc(now.truncatedTo(ChronoUnit.MILLIS));
        coupons.release(order,now);
        requireOne(orders.close(order.getId(),order.getVersion(),reason,at));requireOne(bookings.release(order.getId()));
        requireOne(events.append(order.getId(),order.getVersion()+1,reason,"PENDING_PAYMENT","CLOSED",actor==null?"SYSTEM":"STUDENT",actor,reason,at));
    }
    private void refundClose(ServiceOrderEntity order,String reason,Long actor,Instant now) {
        refundClose(order,reason,null,actor==null?"SYSTEM":"STUDENT",actor,now);
    }
    private void refundClose(ServiceOrderEntity order,String reason,String note,String actorType,Long actor,Instant now) {
        mockPayment.requireEnabled();versionAvailable(order);
        var payment=money.payment(order.getId());
        if (payment==null || payment.orderId()!=order.getId() || payment.amount()!=order.getPayableAmount()) { throw new IllegalStateException("Payment fact mismatch"); }
        var at=utc(now.truncatedTo(ChronoUnit.MILLIS));
        if (note==null) { requireOne(money.refund(order.getId(),payment.id(),number(),payment.amount(),reason,at)); }
        else { requireOne(money.refundWithNote(order.getId(),payment.id(),number(),payment.amount(),reason,note,at)); }
        if ("ADMIN_EXCEPTION".equals(reason)) { requireOne(orders.adminRefundClose(order.getId(),order.getVersion(),order.getStatus(),note,at)); }
        else if (note==null && "PENDING_ACCEPTANCE".equals(order.getStatus())) { requireOne(orders.refundClose(order.getId(),order.getVersion(),reason,at)); }
        else { requireOne(orders.refundCloseFrom(order.getId(),order.getVersion(),order.getStatus(),reason,note,at)); }
        if ("ADMIN_EXCEPTION".equals(reason) && "IN_SERVICE".equals(order.getStatus())) { requireOne(bookings.consumeActive(order.getId())); }
        else { requireOne(bookings.releaseActive(order.getId())); }
        requireOne(events.append(order.getId(),order.getVersion()+1,reason,order.getStatus(),"CLOSED",actorType,actor,note==null?reason:note,at));
    }
    private PaymentResult paymentResult(ServiceOrderEntity row) {
        var p=money.payment(row.getId());
        if (p==null || p.amount()!=row.getPayableAmount()) { throw new IllegalStateException("Missing or inconsistent payment fact"); }
        return new PaymentResult(row.getId().toString(),row.getStatus(),row.getPaymentStatus(),p.paymentNo(),p.amount(),instant(p.paidAt()),instant(row.getAcceptanceDeadline()));
    }
    private static String number() { return UUID.randomUUID().toString().replace("-",""); }
    private static void versionAvailable(ServiceOrderEntity order) { if (order.getVersion()==Long.MAX_VALUE) { throw conflict("ORDER_STATE_CONFLICT","订单版本无法递增"); } }
    private static String deadlineError(ServiceOrderEntity order) {
        return switch (order.getCloseReason()==null?"":order.getCloseReason()) {
            case "PAYMENT_TIMEOUT" -> "PAYMENT_DEADLINE_PASSED";
            case "ACCEPTANCE_TIMEOUT" -> "ACCEPTANCE_DEADLINE_PASSED";
            default -> null;
        };
    }
    private static boolean awaiting(ServiceOrderEntity row) { return row!=null && "PENDING_ACCEPTANCE".equals(row.getStatus()) && "PAID".equals(row.getPaymentStatus()); }
    private static boolean pending(ServiceOrderEntity row) { return row!=null && "PENDING_PAYMENT".equals(row.getStatus()) && "NOT_PAID".equals(row.getPaymentStatus()); }
    private static boolean overlaps(BookingDetailEntity row,Instant start,Instant end) { return instant(row.getStartAt()).isBefore(end) && instant(row.getEndAt()).isAfter(start); }
    private AuthSessionState access(AuthPrincipal principal) {
        return access(principal,"STUDENT");
    }
    private AuthSessionState access(AuthPrincipal principal,String role) {
        requireRole(principal.account().role(),role);
        var account=accounts.lockById(principal.accountId());if (account==null) { throw TokenService.failure("TOKEN_INVALID","登录账号不存在"); }
        if (!"ACTIVE".equals(account.getStatus())) { throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用"); }
        requireRole(account.getRole(),role);var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);return session;
    }
    private void valid(AuthSessionState session) {
        if (session==null) { throw TokenService.failure("TOKEN_INVALID","登录会话不存在"); }
        if (session.revokedAt()!=null) { throw TokenService.failure("SESSION_REVOKED","登录已退出"); }
        if (!clock.instant().isBefore(instant(session.expiresAt()))) { throw TokenService.failure("SESSION_EXPIRED","登录已过期"); }
    }
    static void requireStudent(String role) { if (!"STUDENT".equals(role)) { throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅学生可操作本人预约订单"); } }
    static void requireMerchant(String role) { if (!"MERCHANT".equals(role)) { throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅商家可处理本店预约订单"); } }
    static void requireAdmin(String role) { if (!"ADMIN".equals(role)) { throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅管理员可核实预约异常"); } }
    private static void requireRole(String actual,String expected) {
        switch (expected) { case "STUDENT" -> requireStudent(actual); case "MERCHANT" -> requireMerchant(actual); case "ADMIN" -> requireAdmin(actual); default -> throw new IllegalArgumentException("Unknown order role"); }
    }
    private static void requireOne(int changed) { if (changed!=1) { throw new IllegalStateException("Order write affected unexpected row count"); } }
    private OrderDetail view(ServiceOrderEntity row,BookingDetailEntity b) {
        if (b==null) { throw new IllegalStateException("Missing booking detail"); }
        var p=money.payment(row.getId());var r=money.findRefund(row.getId());
        return new OrderDetail(row.getId().toString(),row.getOrderNo(),row.getOrderType(),row.getStatus(),row.getPaymentStatus(),row.getShopId().toString(),row.getShopNameSnapshot(),b.getServiceNameSnapshot(),
            row.getOriginalAmount(),row.getDiscountAmount(),row.getPayableAmount(),row.getCurrency(),instant(row.getCreatedAt()),instant(row.getPaymentDeadline()),instant(row.getAcceptanceDeadline()),instant(row.getPaidAt()),
            instant(row.getAcceptedAt()),instant(row.getStartedAt()),instant(row.getCompletedAt()),instant(row.getClosedAt()),row.getCloseReason(),row.getCloseNote(),row.getStudentNote(),row.getVersion().toString(),reviews.existsByOrder(row.getId()),
            new BookingOrderView(b.getServiceId().toString(),b.getServiceNameSnapshot(),b.getBarberId().toString(),b.getBarberNameSnapshot(),instant(b.getStartAt()),instant(b.getEndAt()),b.getDurationMinutesSnapshot(),b.getOccupancyStatus()),null,
            p==null?null:new PaymentView(p.paymentNo(),p.amount(),p.status(),instant(p.paidAt())),
            r==null?null:new RefundView(r.refundNo(),r.amount(),r.status(),instant(r.refundedAt())),coupons.view(row.getId()));
    }
}
