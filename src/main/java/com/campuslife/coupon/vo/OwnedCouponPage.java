package com.campuslife.coupon.vo;
import java.util.List;
public record OwnedCouponPage(int page,int size,long total,List<OwnedCouponView> items) {}
