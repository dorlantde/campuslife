package com.campuslife.order.mapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

public interface MoneyMapper {
    record Payment(long id,long orderId,String paymentNo,long amount,String status,LocalDateTime paidAt) {}
    record Refund(String refundNo,long amount,String status,LocalDateTime refundedAt) {}
    @Select("SELECT id,order_id,payment_no,amount,status,paid_at FROM payment_record WHERE order_id=#{id}")
    Payment payment(long id);
    @Select("SELECT refund_no,amount,status,refunded_at FROM refund_record WHERE order_id=#{id}")
    Refund findRefund(long id);
    record ClosureRefund(long paymentId,long amount,String status,String reason,String note,LocalDateTime refundedAt) {}
    @Select("SELECT payment_id,amount,status,reason,reason_note AS note,refunded_at FROM refund_record WHERE order_id=#{id}")
    ClosureRefund closureRefund(long id);
    @Insert("INSERT INTO payment_record(order_id,payment_no,amount,channel,status,paid_at) VALUES(#{id},#{number},#{amount},'MOCK','SUCCEEDED',#{at})")
    int pay(@Param("id") long id,@Param("number") String number,@Param("amount") long amount,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO refund_record(order_id,payment_id,refund_no,amount,reason,status,refunded_at) VALUES(#{id},#{payment},#{number},#{amount},#{reason},'SUCCEEDED',#{at})")
    int refund(@Param("id") long id,@Param("payment") long payment,@Param("number") String number,@Param("amount") long amount,@Param("reason") String reason,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO refund_record(order_id,payment_id,refund_no,amount,reason,reason_note,status,refunded_at) VALUES(#{id},#{payment},#{number},#{amount},#{reason},#{note},'SUCCEEDED',#{at})")
    int refundWithNote(@Param("id") long id,@Param("payment") long payment,@Param("number") String number,@Param("amount") long amount,@Param("reason") String reason,@Param("note") String note,@Param("at") LocalDateTime at);
}
