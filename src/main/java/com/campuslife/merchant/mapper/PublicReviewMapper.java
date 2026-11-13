package com.campuslife.merchant.mapper;

import com.campuslife.merchant.vo.PublicReviewView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Ordinary RR reads: visibility is a content decision, independent of the author's current role/status. */
public interface PublicReviewMapper {
    record RatingRow(long shopId,BigDecimal ratingSum,long reviewCount) {
        public Double average(){
            if(reviewCount<1||ratingSum==null){throw new IllegalStateException("Invalid public rating aggregate");}
            return ratingSum.divide(BigDecimal.valueOf(reviewCount),2,RoundingMode.HALF_UP).doubleValue();
        }
    }
    record ReviewRow(long reviewId,int rating,String content,String reviewerNickname,LocalDateTime createdAt) {
        public PublicReviewView view(){return new PublicReviewView(Long.toString(reviewId),rating,content,reviewerNickname,createdAt.toInstant(ZoneOffset.UTC));}
    }
    @Select("SELECT COUNT(*) FROM order_review WHERE shop_id=#{shop} AND visibility='VISIBLE'")
    long count(long shop);
    @Select("""
            SELECT r.id AS review_id,r.rating,r.content,a.nickname AS reviewer_nickname,r.created_at
            FROM order_review r JOIN account a ON a.id=r.student_id
            WHERE r.shop_id=#{shop} AND r.visibility='VISIBLE'
            ORDER BY r.created_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<ReviewRow> page(@Param("shop") long shop,@Param("size") int size,@Param("offset") long offset);
    @Select("""
            <script>
            SELECT shop_id,SUM(rating) AS rating_sum,COUNT(*) AS review_count FROM order_review
            WHERE visibility='VISIBLE' AND shop_id IN
            <foreach collection="shops" item="shop" open="(" separator="," close=")">#{shop}</foreach>
            GROUP BY shop_id
            </script>
            """)
    List<RatingRow> ratings(@Param("shops") List<Long> shops);
}
