package com.campuslife.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.order.entity.PrintDetailEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface PrintOrderMapper extends BaseMapper<PrintDetailEntity> {
    @Select("SELECT * FROM print_detail WHERE order_id=#{id} FOR UPDATE")
    PrintDetailEntity lock(long id);
    @Update("UPDATE print_detail SET progress='ABORTED' WHERE order_id=#{id} AND progress='QUEUED'")
    int abort(long id);
    @Update("UPDATE print_detail SET progress='ABORTED' WHERE order_id=#{id} AND progress=#{progress} AND progress IN ('QUEUED','PRINTING','READY_FOR_PICKUP') AND picked_up_at IS NULL")
    int adminAbort(@Param("id") long id,@Param("progress") String progress);
    @Update("UPDATE print_detail SET progress='PRINTING' WHERE order_id=#{id} AND progress='QUEUED' AND ready_at IS NULL AND picked_up_at IS NULL")
    int startPrinting(long id);
    @Update("UPDATE print_detail SET progress='READY_FOR_PICKUP',ready_at=#{at} WHERE order_id=#{id} AND progress='PRINTING' AND ready_at IS NULL AND picked_up_at IS NULL")
    int readyPrinting(@Param("id") long id,@Param("at") LocalDateTime at);
    @Update("UPDATE print_detail SET progress='PICKED_UP',picked_up_at=#{at} WHERE order_id=#{id} AND progress='READY_FOR_PICKUP' AND ready_at IS NOT NULL AND picked_up_at IS NULL")
    int pickup(@Param("id") long id,@Param("at") LocalDateTime at);
    @Select("SELECT id FROM service_order WHERE order_type='PRINT' AND status='PENDING_PAYMENT' AND payment_status='NOT_PAID' AND payment_deadline<=#{now} ORDER BY payment_deadline,id LIMIT 100")
    List<Long> due(LocalDateTime now);
    @Select("SELECT id FROM service_order WHERE order_type='PRINT' AND status='PENDING_ACCEPTANCE' AND payment_status='PAID' AND acceptance_deadline<=#{now} ORDER BY acceptance_deadline,id LIMIT 100")
    List<Long> acceptanceDue(LocalDateTime now);
}
