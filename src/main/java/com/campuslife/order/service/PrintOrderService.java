package com.campuslife.order.service;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.FileStorage;
import com.campuslife.file.storage.FileLocation;
import com.campuslife.order.dto.PrintCreate;
import com.campuslife.order.vo.OrderDetail;
import com.campuslife.order.vo.PaymentResult;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class PrintOrderService {
    private final PrintOrderTransactions transactions;private final FileTransactions files;private final FileStorage storage;
    public PrintOrderService(PrintOrderTransactions transactions,FileTransactions files,FileStorage storage){this.transactions=transactions;this.files=files;this.storage=storage;}
    public PrintOrderTransactions.Creation create(AuthPrincipal principal,String key,PrintCreate body){
        BookingOrderTransactions.requireStudent(principal.account().role());var validKey=BookingOrderPolicy.key(key);var request=PrintOrderPolicy.parse(body);
        try{
            var old=transactions.replay(principal,validKey,request.hash());if(old!=null){return old;}
            var location=files.download(principal,request.spec().file());var guard=storage.lock(location.getStorageKey());
            // Never wait for the instance guard while holding a database transaction or row lock.
            guard.lock();try{
                old=transactions.replay(principal,validKey,request.hash());if(old!=null){return old;}
                var current=files.download(principal,request.spec().file());
                try { FileLocation.requireSame(location,current); } catch(IOException|IllegalArgumentException ex) { throw FileRules.unavailable(); }
                location=current;
                try(var input=storage.open(location)){if(input.read()<0){throw new IOException("Empty object");}}
                catch(IOException ex){throw FileRules.unavailable();}
                return transactions.create(principal,validKey,request,location);
            }finally{guard.unlock();}
        }catch(DuplicateKeyException ex){
            try{var old=transactions.replay(principal,validKey,request.hash());if(old!=null){return old;}throw unavailable();}
            catch(TransientDataAccessException retry){throw unavailable();}
        }catch(TransientDataAccessException ex){throw unavailable();}
    }
    public boolean isPrint(AuthPrincipal principal,long id){return transactions.isPrint(principal,id);}
    public OrderDetail detail(AuthPrincipal principal,long id){return transactions.detail(principal,id);}
    public boolean adminIsPrint(AuthPrincipal principal,long id){return transactions.adminIsPrint(principal,id);}
    public OrderDetail adminDetail(AuthPrincipal principal,long id){return transactions.adminDetail(principal,id);}
    public OrderDetail adminClose(AuthPrincipal principal,long id,String reason,long expected){
        try{return transactions.adminClose(principal,id,reason,expected);}catch(TransientDataAccessException ex){throw unavailable();}
    }
    public OrderDetail pickup(AuthPrincipal principal,long id,String body){
        try{return transactions.pickup(principal,id,body);}catch(TransientDataAccessException ex){throw unavailable();}
    }
    public boolean merchantIsPrint(AuthPrincipal principal,long id){return transactions.merchantIsPrint(principal,id);}
    public OrderDetail merchantDetail(AuthPrincipal principal,long id){return transactions.merchantDetail(principal,id);}
    public OrderDetail merchantFulfill(AuthPrincipal principal,long id,boolean ready,String body){
        try{return transactions.merchantFulfill(principal,id,ready,body);}catch(TransientDataAccessException ex){throw unavailable();}
    }
    public OrderDetail merchantCancel(AuthPrincipal principal,long id,String reason){
        try{return transactions.merchantCancel(principal,id,reason);}catch(TransientDataAccessException ex){throw unavailable();}
    }
    public OrderDetail merchantDecision(AuthPrincipal principal,long id,boolean accepting,String reason,String body){
        PrintOrderTransactions.Cancellation result;
        try{result=transactions.merchantDecision(principal,id,accepting,reason,body);}catch(TransientDataAccessException ex){throw unavailable();}
        deadline(result.deadlineError());return result.order();
    }
    public OrderDetail cancel(AuthPrincipal principal,long id){
        PrintOrderTransactions.Cancellation result;
        try{result=transactions.cancel(principal,id);}catch(TransientDataAccessException ex){throw unavailable();}
        deadline(result.deadlineError());
        return result.order();
    }
    public PaymentResult pay(AuthPrincipal principal,long id,String body){
        PrintOrderTransactions.Payment result;
        try{result=transactions.pay(principal,id,body);}catch(TransientDataAccessException ex){throw unavailable();}
        deadline(result.deadlineError());return result.result();
    }
    private static void deadline(String error){
        if(error!=null){throw BookingOrderPolicy.conflict(error,"期限已到，打印订单已关闭；已付款订单已全额模拟退款");}
    }
    private static BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_TEMPORARILY_UNAVAILABLE","数据库竞争已回滚，请稍后重试原请求");}
}
