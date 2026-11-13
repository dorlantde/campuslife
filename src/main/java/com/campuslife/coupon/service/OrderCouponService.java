package com.campuslife.coupon.service;
import com.campuslife.coupon.entity.OwnedCouponEntity;
import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.vo.OrderCouponView;
import com.campuslife.order.entity.ServiceOrderEntity;
import com.campuslife.common.exception.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Joins the caller's existing order transaction; never opens a separate write transaction. */
@Service @Profile("mysql")
public class OrderCouponService {
    private final OwnedCouponMapper coupons;private final OrderCouponMapper snapshots;
    public OrderCouponService(OwnedCouponMapper coupons,OrderCouponMapper snapshots){this.coupons=coupons;this.snapshots=snapshots;}
    public static Long id(JsonNode n){if(n==null||n.isNull()){return null;}if(!n.isString()){throw CampaignPolicy.invalid();}return CampaignPolicy.id(n.asString());}
    public void lockAll(Collection<Long> ids){ids.stream().filter(Objects::nonNull).distinct().sorted().forEach(coupons::lock);}
    public Long couponFor(long order){var row=snapshots.find(order);return row==null?null:row.userCouponId();}
    public void lockOrder(long order){Long id=couponFor(order);if(id!=null){coupons.lock(id);}}
    public OwnedCouponEntity readQuote(Long id){return id==null?null:coupons.selectById(id);}
    public long quote(Long id,OwnedCouponEntity row,long student,long shop,long original,Instant now){return id==null?0:eligible(row,student,shop,original,now).getReductionAmount();}
    private OwnedCouponEntity eligible(OwnedCouponEntity c,long student,long shop,long original,Instant now){
        if(c==null||c.getStudentId()!=student||c.getShopId()!=shop){throw new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","本人同店优惠券不存在");}
        if(!"AVAILABLE".equals(c.getState())||c.getCurrentOrderId()!=null||now.isBefore(instant(c.getUseStartAt()))||!now.isBefore(instant(c.getUseEndAt()))){throw conflict("COUPON_UNAVAILABLE","优惠券已占用、核销或过期");}
        if(original<c.getThresholdAmount()){throw conflict("COUPON_THRESHOLD_NOT_MET","订单原价未达到用券门槛");}
        if(c.getVersion()==Long.MAX_VALUE){throw conflict("COUPON_UNAVAILABLE","优惠券版本无法递增");}
        return c;
    }
    public long reserve(Long id,ServiceOrderEntity order,Instant now){
        if(id==null){return 0;}var c=eligible(coupons.lock(id),order.getStudentId(),order.getShopId(),order.getOriginalAmount(),now);
        one(coupons.reserve(id,order.getId()));one(snapshots.create(order.getId(),c,utc(now)));return c.getReductionAmount();
    }
    public void use(ServiceOrderEntity order,Instant now){
        var r=snapshots.find(order.getId());if(r==null){return;}var c=coupons.lock(r.userCouponId());binding(order,r,c);
        if(now.isBefore(instant(r.lockedAt()))){throw conflict("COUPON_TIME_INVALID","时间早于锁券事实");}
        one(coupons.consume(c.getId(),order.getId()));one(snapshots.use(order.getId(),utc(now)));
    }
    public void release(ServiceOrderEntity order,Instant now){
        var r=snapshots.find(order.getId());if(r==null){return;}var c=coupons.lock(r.userCouponId());binding(order,r,c);
        if(now.isBefore(instant(r.lockedAt()))){throw conflict("COUPON_TIME_INVALID","时间早于锁券事实");}
        one(coupons.release(c.getId(),order.getId(),now.isBefore(instant(c.getUseEndAt()))?"AVAILABLE":"EXPIRED"));one(snapshots.release(order.getId(),utc(now)));
    }
    private static void binding(ServiceOrderEntity o,OrderCouponMapper.Row r,OwnedCouponEntity c){
        if(c!=null&&c.getVersion()==Long.MAX_VALUE){throw conflict("COUPON_UNAVAILABLE","优惠券版本无法递增");}
        if(c==null||!"LOCKED".equals(c.getState())||!Long.valueOf(o.getId()).equals(c.getCurrentOrderId())||!"LOCKED".equals(r.state())||o.getDiscountAmount()!=r.reductionAmount()){throw new IllegalStateException("Coupon/order fact mismatch");}
    }
    public OrderCouponView view(long id){var r=snapshots.find(id);return r==null?null:new OrderCouponView(Long.toString(r.userCouponId()),Long.toString(r.campaignId()),r.nameSnapshot(),r.thresholdAmount(),r.reductionAmount(),instant(r.useStartAt()),instant(r.useEndAt()),Long.toString(r.sourceCampaignVersion()),r.state(),instant(r.lockedAt()),instant(r.usedAt()),instant(r.releasedAt()));}
    private static LocalDateTime utc(Instant t){return LocalDateTime.ofInstant(t,ZoneOffset.UTC);}
    private static Instant instant(LocalDateTime t){return t==null?null:t.toInstant(ZoneOffset.UTC);}
    private static void one(int n){if(n!=1){throw new IllegalStateException("Coupon write failed or version exhausted");}}
    private static BusinessException conflict(String c,String m){return new BusinessException(HttpStatus.CONFLICT,c,m);}
}
