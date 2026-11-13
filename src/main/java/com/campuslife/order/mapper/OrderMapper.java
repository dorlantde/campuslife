package com.campuslife.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.order.entity.ServiceOrderEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface OrderMapper extends BaseMapper<ServiceOrderEntity> {
    @Update("UPDATE service_order SET discount_amount=#{discount},payable_amount=original_amount-#{discount},created_at=#{created},payment_deadline=#{deadline} WHERE id=#{id} AND status='PENDING_PAYMENT' AND version=0")
    int couponAmount(@Param("id") long id,@Param("discount") long discount,@Param("created") LocalDateTime created,@Param("deadline") LocalDateTime deadline);
    @Select("SELECT EXISTS(SELECT 1 FROM print_detail WHERE order_id=#{id})")
    boolean hasPrintDetail(long id);
    record Ownership(String memberStatus,String approvalStatus) {}
    @Select("SELECT shop_id FROM shop_member WHERE account_id=#{account} AND member_role='OWNER'")
    Long ownShop(long account);
    @Select("SELECT m.status AS member_status,s.approval_status FROM shop_member m JOIN shop s ON s.id=m.shop_id WHERE m.shop_id=#{shop} AND m.account_id=#{account} AND m.member_role='OWNER'")
    Ownership ownership(@Param("shop") long shop,@Param("account") long account);

    @Update("UPDATE service_order SET status='ACCEPTED',accepted_at=#{now},version=version+1 WHERE id=#{id} AND status='PENDING_ACCEPTANCE' AND payment_status='PAID' AND version=#{version}")
    int accept(@Param("id") long id,@Param("version") long version,@Param("now") LocalDateTime now);

    @Update("UPDATE service_order SET status='IN_SERVICE',started_at=#{now},version=version+1 WHERE id=#{id} AND status='ACCEPTED' AND payment_status='PAID' AND accepted_at IS NOT NULL AND started_at IS NULL AND version=#{version}")
    int startService(@Param("id") long id,@Param("version") long version,@Param("now") LocalDateTime now);
    @Update("UPDATE service_order SET version=version+1 WHERE id=#{id} AND order_type='PRINT' AND status='IN_SERVICE' AND payment_status='PAID' AND started_at IS NOT NULL AND completed_at IS NULL AND version=#{version}")
    int advancePrintReady(@Param("id") long id,@Param("version") long version);

    @Update("UPDATE service_order SET status='COMPLETED',completed_at=#{now},version=version+1 WHERE id=#{id} AND status='IN_SERVICE' AND payment_status='PAID' AND started_at IS NOT NULL AND completed_at IS NULL AND version=#{version}")
    int completeService(@Param("id") long id,@Param("version") long version,@Param("now") LocalDateTime now);

    @Update("UPDATE service_order SET status='CLOSED',payment_status='REFUNDED',closed_at=#{now},close_reason=#{reason},close_note=#{note},version=version+1 WHERE id=#{id} AND status=#{from} AND status IN ('PENDING_ACCEPTANCE','ACCEPTED') AND payment_status='PAID' AND version=#{version}")
    int refundCloseFrom(@Param("id") long id,@Param("version") long version,@Param("from") String from,@Param("reason") String reason,@Param("note") String note,@Param("now") LocalDateTime now);
    @Update("UPDATE service_order SET status='CLOSED',payment_status='REFUNDED',closed_at=#{now},close_reason='ADMIN_EXCEPTION',close_note=#{note},version=version+1 WHERE id=#{id} AND order_type='BOOKING' AND status=#{from} AND status IN ('PENDING_ACCEPTANCE','ACCEPTED','IN_SERVICE') AND payment_status='PAID' AND version=#{version}")
    int adminRefundClose(@Param("id") long id,@Param("version") long version,@Param("from") String from,@Param("note") String note,@Param("now") LocalDateTime now);
    @Update("UPDATE service_order SET status='CLOSED',payment_status='REFUNDED',closed_at=#{now},close_reason='ADMIN_EXCEPTION',close_note=#{note},version=version+1 WHERE id=#{id} AND order_type='PRINT' AND status=#{from} AND status IN ('PENDING_ACCEPTANCE','ACCEPTED','IN_SERVICE') AND payment_status='PAID' AND completed_at IS NULL AND closed_at IS NULL AND version=#{version}")
    int adminRefundPrint(@Param("id") long id,@Param("version") long version,@Param("from") String from,@Param("note") String note,@Param("now") LocalDateTime now);
    @Select("SELECT * FROM service_order WHERE id=#{id} FOR UPDATE")
    ServiceOrderEntity lock(long id);

    @Select("SELECT id FROM shop WHERE id=#{id} FOR SHARE")
    Long shareShop(long id);

    @Select("SELECT shop_id FROM service_item WHERE id=#{id}")
    Long serviceShop(long id);

    @Select("SELECT id FROM service_order WHERE order_type='BOOKING' AND status='PENDING_PAYMENT' AND payment_deadline <= #{now} ORDER BY payment_deadline,id LIMIT 100")
    List<Long> due(LocalDateTime now);

    @Select("SELECT id FROM service_order WHERE order_type='BOOKING' AND status='PENDING_ACCEPTANCE' AND payment_status='PAID' AND acceptance_deadline <= #{now} ORDER BY acceptance_deadline,id LIMIT 100")
    List<Long> acceptanceDue(LocalDateTime now);

    @Update("UPDATE service_order SET status='PENDING_ACCEPTANCE',payment_status='PAID',paid_at=#{now},acceptance_deadline=#{deadline},version=version+1 WHERE id=#{id} AND status='PENDING_PAYMENT' AND payment_status='NOT_PAID' AND version=#{version}")
    int pay(@Param("id") long id,@Param("version") long version,@Param("now") LocalDateTime now,@Param("deadline") LocalDateTime deadline);

    @Update("UPDATE service_order SET status='CLOSED',payment_status='REFUNDED',closed_at=#{now},close_reason=#{reason},version=version+1 WHERE id=#{id} AND status='PENDING_ACCEPTANCE' AND payment_status='PAID' AND version=#{version}")
    int refundClose(@Param("id") long id,@Param("version") long version,@Param("reason") String reason,@Param("now") LocalDateTime now);

    @Update("UPDATE service_order SET status='CLOSED',closed_at=#{now},close_reason=#{reason},version=version+1 WHERE id=#{id} AND status='PENDING_PAYMENT' AND payment_status='NOT_PAID' AND version=#{version}")
    int close(@Param("id") long id,@Param("version") long version,@Param("reason") String reason,@Param("now") LocalDateTime now);
}
