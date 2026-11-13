package com.campuslife.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.coupon.entity.CouponCampaignEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface CampaignMapper extends BaseMapper<CouponCampaignEntity> {
    @Update("UPDATE coupon_campaign SET issued_count=issued_count+1 WHERE id=#{id} AND status='PUBLISHED' AND issued_count<total_quantity")
    int issue(long id);
    @Update("UPDATE coupon_campaign SET issued_count=issued_count+1 WHERE id=#{id} AND status IN ('PUBLISHED','STOPPED') AND issued_count<total_quantity")
    int issueAdmitted(long id);
    @Select("SELECT COUNT(*) FROM coupon_campaign WHERE shop_id=#{shop} AND status='PUBLISHED' AND claim_start_at<=#{now} AND claim_end_at>#{now}")
    long countPublic(@Param("shop") long shop,@Param("now") LocalDateTime now);
    @Select("SELECT * FROM coupon_campaign WHERE shop_id=#{shop} AND status='PUBLISHED' AND claim_start_at<=#{now} AND claim_end_at>#{now} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<CouponCampaignEntity> pagePublic(@Param("shop") long shop,@Param("now") LocalDateTime now,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT * FROM coupon_campaign WHERE id=#{id} AND shop_id=#{shop}")
    CouponCampaignEntity findOwn(@Param("id") long id,@Param("shop") long shop);
    @Select("SELECT * FROM coupon_campaign WHERE id=#{id} AND shop_id=#{shop} FOR UPDATE")
    CouponCampaignEntity lockOwn(@Param("id") long id,@Param("shop") long shop);
    @Select("SELECT COUNT(*) FROM coupon_campaign WHERE shop_id=#{shop}")
    long countOwn(long shop);
    @Select("SELECT * FROM coupon_campaign WHERE shop_id=#{shop} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<CouponCampaignEntity> pageOwn(@Param("shop") long shop,@Param("size") int size,@Param("offset") long offset);
    @Update("""
        UPDATE coupon_campaign SET name=#{row.name},threshold_amount=#{row.thresholdAmount},reduction_amount=#{row.reductionAmount},
            total_quantity=#{row.totalQuantity},claim_start_at=#{row.claimStartAt},claim_end_at=#{row.claimEndAt},
            use_start_at=#{row.useStartAt},use_end_at=#{row.useEndAt},updated_at=#{row.updatedAt},version=version+1
        WHERE id=#{row.id} AND shop_id=#{row.shopId} AND status='DRAFT' AND version=#{expected}
        """)
    int updateDraft(@Param("row") CouponCampaignEntity row,@Param("expected") long expected);
    @Update("""
        UPDATE coupon_campaign SET status='PUBLISHED',published_at=#{now},published_by=#{actor},publish_expected_version=#{expected},
            updated_at=#{now},version=version+1 WHERE id=#{id} AND status='DRAFT' AND version=#{expected} AND claim_end_at>#{now}
        """)
    int publish(@Param("id") long id,@Param("actor") long actor,@Param("expected") long expected,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE coupon_campaign SET status='STOPPED',stopped_at=#{now},stopped_by=#{actor},stop_expected_version=#{expected},
            updated_at=#{now},version=version+1 WHERE id=#{id} AND status='PUBLISHED' AND version=#{expected}
        """)
    int stop(@Param("id") long id,@Param("actor") long actor,@Param("expected") long expected,@Param("now") LocalDateTime now);
}
