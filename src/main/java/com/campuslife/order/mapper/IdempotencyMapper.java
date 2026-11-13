package com.campuslife.order.mapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;
public interface IdempotencyMapper {
    @Select("SELECT order_id,request_hash FROM idempotency_record WHERE account_id=#{account} AND operation='CREATE_PRINT' AND request_key=#{key}")
    Result findPrint(@Param("account") long account,@Param("key") String key);
    @Insert("INSERT INTO idempotency_record(account_id,operation,request_key,request_hash,order_id,completed_at) VALUES (#{account},'CREATE_PRINT',#{key},#{hash},#{order},#{now})")
    int savePrint(@Param("account") long account,@Param("key") String key,@Param("hash") String hash,@Param("order") long order,@Param("now") LocalDateTime now);
    record Result(long orderId,String requestHash) {}
    @Select("SELECT order_id,request_hash FROM idempotency_record WHERE account_id=#{account} AND operation='CREATE_BOOKING' AND request_key=#{key}")
    Result find(@Param("account") long account,@Param("key") String key);
    @Insert("INSERT INTO idempotency_record(account_id,operation,request_key,request_hash,order_id,completed_at) VALUES (#{account},'CREATE_BOOKING',#{key},#{hash},#{order},#{now})")
    int save(@Param("account") long account,@Param("key") String key,@Param("hash") String hash,@Param("order") long order,@Param("now") LocalDateTime now);
}
