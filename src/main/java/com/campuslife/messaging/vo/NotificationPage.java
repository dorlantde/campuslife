package com.campuslife.messaging.vo;
import java.util.List;
public record NotificationPage(List<NotificationView> items,int page,int size,long total) {}
