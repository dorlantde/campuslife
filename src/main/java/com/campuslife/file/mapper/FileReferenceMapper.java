package com.campuslife.file.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

/** All reference writers hold file_asset X; these reads must not lock orders across shops. */
public interface FileReferenceMapper {
    record DownloadOwner(long shopId,String memberStatus) {}
    @Select("SELECT shop_id,status AS member_status FROM shop_member WHERE account_id=#{account} AND member_role='OWNER'")
    DownloadOwner downloadOwner(long account);
    /** An order relationship grants content access; retention alone never grants merchant access. */
    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM print_detail p JOIN service_order o ON o.id=p.order_id
                JOIN file_asset f ON f.id=p.file_id AND f.owner_id=o.student_id
                WHERE p.file_id=#{file} AND o.shop_id=#{shop} AND o.order_type='PRINT'
                  AND o.payment_status='PAID' AND o.accepted_at IS NOT NULL
                  AND o.completed_at IS NULL AND p.picked_up_at IS NULL
                  AND ((o.status='ACCEPTED' AND p.progress='QUEUED' AND o.started_at IS NULL AND p.ready_at IS NULL)
                    OR (o.status='IN_SERVICE' AND o.started_at IS NOT NULL
                      AND ((p.progress='PRINTING' AND p.ready_at IS NULL)
                        OR (p.progress='READY_FOR_PICKUP' AND p.ready_at IS NOT NULL))))
            )
            """)
    boolean merchantDownloadAllowed(@Param("shop") long shop,@Param("file") long file);
    @Select("SELECT COUNT(*) FROM print_detail p JOIN service_order o ON o.id=p.order_id WHERE p.file_id=#{file} AND o.order_type='PRINT' AND o.status NOT IN ('CLOSED','COMPLETED')")
    long unfinished(long file);
    @Select("SELECT MAX(CASE WHEN o.status='CLOSED' THEN o.closed_at WHEN o.status='COMPLETED' THEN o.completed_at END) FROM print_detail p JOIN service_order o ON o.id=p.order_id WHERE p.file_id=#{file} AND o.order_type='PRINT'")
    LocalDateTime lastTerminal(long file);
}
