package com.campuslife.order.service;

import com.campuslife.order.dto.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

final class PrintOrderPolicy {
    private PrintOrderPolicy() {}
    private static com.campuslife.common.exception.BusinessException invalid(){return new com.campuslife.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","打印订单字段格式或范围不符合要求");}
    record Request(PrintQuotePolicy.Request spec,long version,long amount,String note,String hash,Long coupon) {}
    static Request parse(PrintCreate body) {
        if(body==null){throw invalid();}
        var spec=PrintQuotePolicy.parse(new PrintQuoteRequest(body.serviceId(),body.fileId(),body.paperSize(),body.colorMode(),body.sideMode(),body.copies()));
        var version=body.expectedPriceVersion();long v;
        if(version==null||!version.isString()||!version.asString().matches("0|[1-9][0-9]{0,18}")){throw invalid();}
        try{v=Long.parseLong(version.asString());}catch(NumberFormatException ex){throw invalid();}
        var amount=body.expectedPayableAmount();long a;
        if(amount==null||!amount.isIntegralNumber()||!amount.toString().matches("[1-9][0-9]{0,9}")){throw invalid();}
        a=Long.parseLong(amount.toString());if(a>5000000000L){throw invalid();}
        String note=null;
        if(body.note()!=null&&!body.note().isNull()){
            if(!body.note().isString()){throw invalid();}
            note=body.note().asString().strip();if(note.codePointCount(0,note.length())>300){throw invalid();}
            if(note.isEmpty()){note=null;}
        }
        var canonical="PRINT\n"+spec.service()+"\n"+spec.file()+"\n"+spec.paper()+"\n"+spec.color()+"\n"+spec.side()+"\n"+spec.copies()+"\n"+v+"\n"+a+"\n"+(note==null?"-1:":note.length()+":"+note);
        Long coupon=com.campuslife.coupon.service.OrderCouponService.id(body.userCouponId());
        if(coupon!=null){canonical="PRINT_COUPON_V1\n"+canonical+"\n"+coupon;}
        try{return new Request(spec,v,a,note,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))),coupon);}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
