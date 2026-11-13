package com.campuslife.order.service;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.coupon.service.OrderCouponService;
import com.campuslife.merchant.mapper.*;
import com.campuslife.order.dto.BookingQuoteRequest;
import com.campuslife.order.vo.BookingQuote;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @Profile("mysql")
public class BookingQuoteService {
 private final AuthSessionMapper sessions;private final ServiceItemMapper services;private final PublicShopMapper shops;private final OrderCouponService coupons;private final Clock clock;
 public BookingQuoteService(AuthSessionMapper sessions,ServiceItemMapper services,PublicShopMapper shops,OrderCouponService coupons,Clock clock){this.sessions=sessions;this.services=services;this.shops=shops;this.coupons=coupons;this.clock=clock;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public BookingQuote quote(AuthPrincipal principal,BookingQuoteRequest body){
  if(body==null||body.serviceId()==null||!body.serviceId().isString()){throw BookingOrderPolicy.invalid();}
  long id=BookingOrderPolicy.id(body.serviceId().asString());Long coupon=OrderCouponService.id(body.userCouponId());
  var account=sessions.find(principal.accountId(),principal.jti());var service=services.selectById(id);var shop=service==null?null:shops.visible(service.getShopId());
  var couponRow=coupons.readQuote(coupon);var now=clock.instant();PrintQuoteService.access(account,now);
  if(shop==null||!"HAIRCUT".equals(shop.categoryCode())||!"HAIRCUT".equals(service.getServiceType())||!Boolean.TRUE.equals(service.getEnabled())){throw BookingOrderPolicy.missing();}
  long discount=coupons.quote(coupon,couponRow,principal.accountId(),shop.id(),service.getPriceAmount(),now);
  return new BookingQuote(Long.toString(id),Long.toString(shop.id()),service.getVersion().toString(),service.getPriceAmount(),discount,service.getPriceAmount()-discount,"CNY",coupon==null?null:coupon.toString());
 }
}
