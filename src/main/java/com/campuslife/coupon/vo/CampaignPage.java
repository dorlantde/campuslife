package com.campuslife.coupon.vo;

import java.util.List;

public record CampaignPage(int page,int size,long total,List<CampaignView> items) {}
