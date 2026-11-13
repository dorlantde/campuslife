package com.campuslife.order.service;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.order.dto.OrderReviewWrite;
import com.campuslife.order.dto.ReviewHideWrite;
import com.campuslife.order.vo.OwnReviewView;
import java.sql.SQLException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class OrderReviewService {
    private final OrderReviewTransactions transactions;
    public OrderReviewService(OrderReviewTransactions transactions){this.transactions=transactions;}
    public OwnReviewView hide(AuthPrincipal principal,String reviewId,ReviewHideWrite body){
        BookingOrderTransactions.requireAdmin(principal.account().role());
        long id=BookingOrderPolicy.id(reviewId);
        if(body==null||body.reason()==null||!body.reason().isString()){throw invalidReason();}
        var reason=body.reason().asString().strip();int length=reason.codePointCount(0,reason.length());
        if(length<1||length>300){throw invalidReason();}
        try{return transactions.hide(principal,id,reason);}catch(TransientDataAccessException ex){
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_TEMPORARILY_UNAVAILABLE","数据库暂时故障已回滚，请使用相同原因重试");
        }
    }
    private static BusinessException invalidReason(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","隐藏原因须为去首尾空白后1～300个Unicode码点的字符串");}
    public OwnReviewView create(AuthPrincipal principal,String orderId,OrderReviewWrite body){
        BookingOrderTransactions.requireStudent(principal.account().role());long id=BookingOrderPolicy.id(orderId);
        if(body==null||body.rating()==null||!body.rating().isIntegralNumber()||!body.rating().toString().matches("[1-5]")
                ||body.content()==null||!body.content().isString()){throw invalid();}
        var content=body.content().asString().strip();int length=content.codePointCount(0,content.length());
        if(length<1||length>1000){throw invalid();}
        try{return transactions.create(principal,id,Integer.parseInt(body.rating().toString()),content);}
        catch(DuplicateKeyException ex){
            if(!(ex.getMostSpecificCause() instanceof SQLException sql)||sql.getErrorCode()!=1062
                    ||sql.getMessage()==null||!sql.getMessage().contains("uk_order_review_order")){throw ex;}
            // Confirm the intended unique fact in a new transaction; never mask another constraint failure.
            try{transactions.read(principal,id);}catch(BusinessException check){if(check.status()==HttpStatus.NOT_FOUND){throw ex;}throw check;}
            catch(TransientDataAccessException check){throw unavailable();}
            throw OrderReviewTransactions.alreadyReviewed();
        }catch(TransientDataAccessException ex){throw unavailable();}
    }
    public OwnReviewView read(AuthPrincipal principal,String orderId){
        BookingOrderTransactions.requireStudent(principal.account().role());long id=BookingOrderPolicy.id(orderId);
        try{return transactions.read(principal,id);}catch(TransientDataAccessException ex){throw unavailable();}
    }
    private static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","评分或评价文字格式、类型或范围不符合要求");}
    private static BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_TEMPORARILY_UNAVAILABLE","数据库竞争已回滚，可重试原输入；若已成功请读取本人评价");}
}
