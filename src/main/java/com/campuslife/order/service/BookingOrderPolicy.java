package com.campuslife.order.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.order.dto.BookingCreate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

final class BookingOrderPolicy {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private BookingOrderPolicy() {}
    record Request(long service,long barber,Instant start,long version,long amount,String note,String hash,Long coupon) {}
    static Request parse(BookingCreate body) {
        long service=id(string(body.serviceId())),barber=id(string(body.barberId()));
        String v=string(body.expectedServiceVersion());
        if (!v.matches("0|[1-9][0-9]{0,18}")) { throw invalid(); }
        long version;
        try { version=Long.parseLong(v); } catch (NumberFormatException ex) { throw invalid(); }
        var amount=body.expectedPayableAmount();
        if (amount==null || !amount.isIntegralNumber() || !amount.toString().matches("[0-9]{1,6}")) { throw invalid(); }
        long money=Long.parseLong(amount.toString()); if (money<1 || money>100000) { throw invalid(); }
        Instant start;
        try { start=OffsetDateTime.parse(string(body.startAt())).toInstant(); } catch (DateTimeException ex) { throw invalid(); }
        var local=start.atZone(ZONE);
        if (local.getYear()<1000 || local.getYear()>9999 || local.getSecond()!=0 || local.getNano()!=0 || local.getMinute()%15!=0) { throw invalid(); }
        String note=null;
        if (body.note()!=null && !body.note().isNull()) {
            note=string(body.note()).strip(); if (note.codePointCount(0,note.length())>300) { throw invalid(); }
            if (note.isEmpty()) { note=null; }
        }
        // Fixed fields plus length-prefixed optional note: no delimiter ambiguity or JSON-order dependency.
        String canonical=service+"\n"+barber+"\n"+start+"\n"+version+"\n"+money+"\n"+(note==null?"-1:":note.length()+":"+note);
        Long coupon=com.campuslife.coupon.service.OrderCouponService.id(body.userCouponId());
        if(coupon!=null){canonical="BOOKING_COUPON_V1\n"+canonical+"\n"+coupon;}
        try { return new Request(service,barber,start,version,money,note,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))),coupon); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String key(String key) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{1,64}")) { throw new BusinessException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","须提供 1～64 位合法 Idempotency-Key"); }
        return key;
    }
    static long id(String value) {
        if (value==null || !value.matches("[1-9][0-9]{0,18}")) { throw invalid(); }
        try { return Long.parseLong(value); } catch (NumberFormatException ex) { throw invalid(); }
    }
    private static String string(JsonNode node) { if (node==null || !node.isString()) { throw invalid(); } return node.asString(); }
    static void time(Instant start,Instant now) {
        var today=now.atZone(ZONE).toLocalDate();var day=start.atZone(ZONE).toLocalDate();
        if (day.isBefore(today)||day.isAfter(today.plusDays(6))||start.isBefore(now.plusSeconds(7200))) { throw conflict("BOOKING_TIME_INVALID","日期或提前时间已不满足预约条件"); }
    }
    static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant,ZoneOffset.UTC); }
    static Instant instant(LocalDateTime time) { return time==null?null:time.toInstant(ZoneOffset.UTC); }
    static BusinessException invalid() { return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","预约字段格式或范围不符合要求"); }
    static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","可见资源不存在"); }
    static BusinessException conflict(String code,String message) { return new BusinessException(HttpStatus.CONFLICT,code,message); }
}
