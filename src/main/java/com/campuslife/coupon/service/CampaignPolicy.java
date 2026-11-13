package com.campuslife.coupon.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.dto.CampaignCreate;
import java.time.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class CampaignPolicy {
    private CampaignPolicy() {}
    record Fields(String name,long threshold,long reduction,int quantity,LocalDateTime claimStart,LocalDateTime claimEnd,LocalDateTime useStart,LocalDateTime useEnd) {}
    static Fields fields(CampaignCreate body) {
        if(body==null){throw invalid();}
        String name=text(body.name()).strip();
        if(name.codePointCount(0,name.length())<1 || name.codePointCount(0,name.length())>80
                || name.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF)){throw invalid();}
        long threshold=integer(body.thresholdAmount(),2,5000000000L),reduction=integer(body.reductionAmount(),1,4999999999L);
        if(reduction>=threshold){throw invalid();}
        int quantity=(int)integer(body.totalQuantity(),1,100000);
        var start=timestamp(body.claimStartAt());var end=timestamp(body.claimEndAt());
        var useStart=timestamp(body.useStartAt());var useEnd=timestamp(body.useEndAt());
        if(useStart.isAfter(start)||!start.isBefore(end)||end.isAfter(useEnd)){throw invalid();}
        return new Fields(name,threshold,reduction,quantity,start,end,useStart,useEnd);
    }
    static long version(JsonNode value) {
        var s=text(value);if(!s.matches("0|[1-9][0-9]{0,18}")){throw invalid();}
        try{return Long.parseLong(s);}catch(NumberFormatException e){throw invalid();}
    }
    static long id(String s) {
        if(s==null||!s.matches("[1-9][0-9]{0,18}")){throw invalid();}
        try{return Long.parseLong(s);}catch(NumberFormatException e){throw invalid();}
    }
    static int page(String s,int fallback,int max) {
        if(s==null){return fallback;}
        try{if(!s.matches("[0-9]+")){throw invalid();}int n=Integer.parseInt(s);if(n<1||n>max){throw invalid();}return n;}
        catch(NumberFormatException e){throw invalid();}
    }
    private static long integer(JsonNode n,long min,long max) {
        if(n==null||!n.isIntegralNumber()||!n.toString().matches("[1-9][0-9]{0,9}")){throw invalid();}
        long value=Long.parseLong(n.toString());if(value<min||value>max){throw invalid();}return value;
    }
    private static LocalDateTime timestamp(JsonNode n) {
        try{
            var value=OffsetDateTime.parse(text(n));var utc=LocalDateTime.ofInstant(value.toInstant(),ZoneOffset.UTC);
            if(value.getYear()<1000||value.getYear()>9999||utc.getYear()<1000||utc.getYear()>9999||utc.getNano()%1000000!=0){throw invalid();}
            return utc;
        }catch(DateTimeException e){throw invalid();}
    }
    private static String text(JsonNode n){if(n==null||!n.isString()){throw invalid();}return n.asString();}
    static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","活动字段、整数金额、时间窗口、ID或版本格式不符合要求");}
}
