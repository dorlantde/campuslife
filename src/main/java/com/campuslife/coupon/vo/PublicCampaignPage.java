package com.campuslife.coupon.vo;
import java.util.List;
public record PublicCampaignPage(int page,int size,long total,List<PublicCampaignView> items) {}
