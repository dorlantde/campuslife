package com.campuslife.order.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.order.dto.PrintQuoteRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class PrintQuotePolicy {
    private PrintQuotePolicy() {}
    record Request(long service,long file,String paper,String color,String side,int copies) {}
    record Totals(long sheets,long amount) {}

    static Request parse(PrintQuoteRequest body) {
        if(body==null){throw invalid();}
        long service=id(body.serviceId()),file=id(body.fileId());
        String paper=choice(body.paperSize(),Set.of("A4"));
        String color=choice(body.colorMode(),Set.of("BW","COLOR"));
        String side=choice(body.sideMode(),Set.of("SINGLE","DOUBLE"));
        var copies=body.copies();
        if(copies==null||!copies.isIntegralNumber()||!copies.toString().matches("[1-9][0-9]{0,2}")){throw invalid();}
        int count=Integer.parseInt(copies.toString());
        if(count>100){throw invalid();}
        return new Request(service,file,paper,color,side,count);
    }

    static Totals calculate(int pages,int copies,String side,long unit) {
        // These bounds also detect corrupt metadata instead of silently quoting it.
        if(pages<1||pages>500||copies<1||copies>100||unit<1||unit>100000||!Set.of("SINGLE","DOUBLE").contains(side)) {
            throw new IllegalStateException("Invalid persisted print quote inputs");
        }
        long impressions=Math.multiplyExact((long)pages,copies);
        long sheets="DOUBLE".equals(side)?Math.multiplyExact((pages+1L)/2,copies):impressions;
        return new Totals(sheets,Math.multiplyExact(impressions,unit));
    }

    private static long id(JsonNode value) {
        if(value==null||!value.isString()||!value.asString().matches("[1-9][0-9]{0,18}")){throw invalid();}
        try{return Long.parseLong(value.asString());}catch(NumberFormatException ex){throw invalid();}
    }
    private static String choice(JsonNode value,Set<String> allowed) {
        if(value==null||!value.isString()||!allowed.contains(value.asString())){throw invalid();}
        return value.asString();
    }
    private static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","打印报价字段格式或范围不符合要求");}
}
