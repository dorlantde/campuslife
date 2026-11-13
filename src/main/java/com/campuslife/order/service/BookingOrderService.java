package com.campuslife.order.service;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.order.dto.BookingCreate;
import com.campuslife.order.dto.BookingRejection;
import com.campuslife.order.dto.BookingCancellation;
import com.campuslife.order.dto.BookingExceptionClose;
import com.campuslife.order.vo.OrderDetail;
import com.campuslife.order.vo.PaymentResult;
import com.campuslife.order.vo.OrderPage;
import com.campuslife.order.vo.OrderEventView;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class BookingOrderService {
    private final BookingOrderTransactions transactions;
    private final PrintOrderService printing;
    public BookingOrderService(BookingOrderTransactions transactions,PrintOrderService printing) { this.transactions=transactions;this.printing=printing; }
    public BookingOrderTransactions.Creation create(AuthPrincipal principal,String key,BookingCreate body) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        var validKey=BookingOrderPolicy.key(key);var request=BookingOrderPolicy.parse(body);
        try { return transactions.create(principal,validKey,request); }
        catch (DuplicateKeyException ex) { return retryResult(principal,validKey,request.hash()); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
    }
    private BookingOrderTransactions.Creation retryResult(AuthPrincipal principal,String key,String hash) {
        try { return transactions.replayAfterConflict(principal,key,hash); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
    }
    public OrderDetail detail(AuthPrincipal principal,String id) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        return printing.isPrint(principal,order)?printing.detail(principal,order):transactions.detail(principal,order);
    }
    public List<OrderEventView> events(AuthPrincipal principal,String id) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        return transactions.events(principal,BookingOrderPolicy.id(id));
    }
    public List<OrderEventView> merchantEvents(AuthPrincipal principal,String id) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        return transactions.merchantEvents(principal,BookingOrderPolicy.id(id));
    }
    public OrderPage list(AuthPrincipal principal,String orderType,String status,String pageText,String sizeText) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        var query=listQuery(orderType,status,pageText,sizeText);
        return transactions.list(principal,orderType,status,query.page(),query.size());
    }
    public OrderPage merchantList(AuthPrincipal principal,String orderType,String status,String pageText,String sizeText) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        var query=listQuery(orderType,status,pageText,sizeText);
        return transactions.merchantList(principal,orderType,status,query.page(),query.size());
    }
    private record ListQuery(int page,int size) {}
    private static ListQuery listQuery(String orderType,String status,String pageText,String sizeText) {
        if (orderType!=null && !java.util.Set.of("BOOKING","PRINT").contains(orderType)) { throw BookingOrderPolicy.invalid(); }
        if (status!=null && !java.util.Set.of("PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED").contains(status)) { throw BookingOrderPolicy.invalid(); }
        return new ListQuery(positiveInt(pageText,1,Integer.MAX_VALUE),positiveInt(sizeText,20,100));
    }
    private static int positiveInt(String text,int fallback,int max) {
        if (text==null) { return fallback; }
        try {
            if (!text.matches("[0-9]+")) { throw new NumberFormatException(); }
            int value=Integer.parseInt(text);
            if (value<1 || value>max) { throw new NumberFormatException(); }
            return value;
        } catch (NumberFormatException ex) { throw BookingOrderPolicy.invalid(); }
    }
    public OrderDetail cancel(AuthPrincipal principal,String id) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        if(printing.isPrint(principal,order)){return printing.cancel(principal,order);}
        BookingOrderTransactions.Cancellation result;
        try { result=transactions.cancel(principal,BookingOrderPolicy.id(id)); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
        // Transaction proxy has committed the timeout release before this HTTP error is produced.
        deadline(result.deadlineError());
        return result.order();
    }
    public OrderDetail pickup(AuthPrincipal principal,String id,String body) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        return printing.pickup(principal,BookingOrderPolicy.id(id),body);
    }
    public PaymentResult pay(AuthPrincipal principal,String id,String body) {
        BookingOrderTransactions.requireStudent(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        if(printing.isPrint(principal,order)){return printing.pay(principal,order,body);}
        BookingOrderTransactions.Payment result;
        try { result=transactions.pay(principal,BookingOrderPolicy.id(id),body); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
        deadline(result.deadlineError());return result.result();
    }
    public OrderDetail merchantDetail(AuthPrincipal principal,String id) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        return printing.merchantIsPrint(principal,order)?printing.merchantDetail(principal,order):transactions.merchantDetail(principal,order);
    }
    public OrderDetail adminDetail(AuthPrincipal principal,String id) {
        BookingOrderTransactions.requireAdmin(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        return printing.adminIsPrint(principal,order)?printing.adminDetail(principal,order):transactions.adminDetail(principal,order);
    }
    public OrderDetail adminClose(AuthPrincipal principal,String id,BookingExceptionClose body) {
        BookingOrderTransactions.requireAdmin(principal.account().role());
        var note=reason(body==null?null:body.reason());
        var node=body==null?null:body.expectedVersion();
        if (node==null || !node.isString() || !node.asString().matches("0|[1-9][0-9]{0,18}")) { throw BookingOrderPolicy.invalid(); }
        long version;
        try { version=Long.parseLong(node.asString()); } catch (NumberFormatException ex) { throw BookingOrderPolicy.invalid(); }
        long order=BookingOrderPolicy.id(id);
        try { return printing.adminIsPrint(principal,order)?printing.adminClose(principal,order,note,version):transactions.adminClose(principal,order,note,version); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
    }
    public OrderDetail accept(AuthPrincipal principal,String id,String body) { return decision(principal,id,true,null,body); }
    public OrderDetail reject(AuthPrincipal principal,String id,BookingRejection body) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        return decision(principal,id,false,reason(body==null?null:body.reason()),null);
    }
    public OrderDetail merchantCancel(AuthPrincipal principal,String id,BookingCancellation body) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        var reason=reason(body==null?null:body.reason());
        long order=BookingOrderPolicy.id(id);
        try { return printing.merchantIsPrint(principal,order)?printing.merchantCancel(principal,order,reason):transactions.merchantCancel(principal,order,reason); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
    }
    public OrderDetail fulfill(AuthPrincipal principal,String id,boolean complete,String body) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        try { if(!complete&&printing.merchantIsPrint(principal,order)){return printing.merchantFulfill(principal,order,false,body);}return transactions.fulfill(principal,order,complete,body); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
    }
    public OrderDetail readyForPickup(AuthPrincipal principal,String id,String body) {
        BookingOrderTransactions.requireMerchant(principal.account().role());long order=BookingOrderPolicy.id(id);
        try{if(!printing.merchantIsPrint(principal,order)){throw new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","本店打印订单不存在");}return printing.merchantFulfill(principal,order,true,body);}
        catch(TransientDataAccessException ex){throw unavailable();}
    }
    private static String reason(tools.jackson.databind.JsonNode node) {
        if (node==null || !node.isString()) { throw BookingOrderPolicy.invalid(); }
        var value=node.asString().strip();int length=value.codePointCount(0,value.length());
        if (length<1 || length>300) { throw BookingOrderPolicy.invalid(); }
        return value;
    }
    private OrderDetail decision(AuthPrincipal principal,String id,boolean accept,String reason,String body) {
        BookingOrderTransactions.requireMerchant(principal.account().role());
        long order=BookingOrderPolicy.id(id);
        if(printing.merchantIsPrint(principal,order)){return printing.merchantDecision(principal,order,accept,reason,body);}
        BookingOrderTransactions.Cancellation result;
        try { result=transactions.merchantDecision(principal,BookingOrderPolicy.id(id),accept,reason,body); }
        catch (TransientDataAccessException ex) { throw unavailable(); }
        deadline(result.deadlineError());return result.order();
    }
    private static void deadline(String code) {
        if (code!=null) { throw BookingOrderPolicy.conflict(code,"期限已到，订单已关闭并释放时段；已付款订单已全额模拟退款"); }
    }
    private static BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_TEMPORARILY_UNAVAILABLE","数据库竞争已回滚，请保留原请求键稍后重试"); }
}
