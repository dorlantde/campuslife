package com.campuslife.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.order.entity.OrderReviewEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

public interface OrderReviewMapper extends BaseMapper<OrderReviewEntity> {
    @Select("SELECT EXISTS(SELECT 1 FROM order_review WHERE order_id=#{id})")
    boolean existsByOrder(long id);
    @Select("SELECT * FROM order_review WHERE order_id=#{id}")
    OrderReviewEntity findByOrder(long id);
    @Select("SELECT shop_id FROM order_review WHERE id=#{id}")
    Long locateShop(long id);
    @Select("SELECT * FROM order_review WHERE id=#{id} FOR UPDATE")
    OrderReviewEntity lock(long id);
    @Update("UPDATE order_review SET visibility='HIDDEN',moderated_by=#{admin},moderation_reason=#{reason},moderated_at=#{at} WHERE id=#{id} AND visibility='VISIBLE' AND moderated_by IS NULL AND moderation_reason IS NULL AND moderated_at IS NULL")
    int hide(long id,long admin,String reason,LocalDateTime at);
}
