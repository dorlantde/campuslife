package com.campuslife.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.campuslife.order.vo.OwnReviewView;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@TableName("order_review")
public class OrderReviewEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long studentId;
    private Long shopId;
    private Integer rating;
    private String content;
    private String visibility;
    private LocalDateTime createdAt;
    private Long moderatedBy;
    private String moderationReason;
    private LocalDateTime moderatedAt;
    public Long getModeratedBy(){return moderatedBy;}
    public void setModeratedBy(Long value){moderatedBy=value;}
    public String getModerationReason(){return moderationReason;}
    public void setModerationReason(String value){moderationReason=value;}
    public LocalDateTime getModeratedAt(){return moderatedAt;}
    public void setModeratedAt(LocalDateTime value){moderatedAt=value;}
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    public Long getOrderId(){return orderId;}
    public void setOrderId(Long value){orderId=value;}
    public Long getStudentId(){return studentId;}
    public void setStudentId(Long value){studentId=value;}
    public Long getShopId(){return shopId;}
    public void setShopId(Long value){shopId=value;}
    public Integer getRating(){return rating;}
    public void setRating(Integer value){rating=value;}
    public String getContent(){return content;}
    public void setContent(String value){content=value;}
    public String getVisibility(){return visibility;}
    public void setVisibility(String value){visibility=value;}
    public LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(LocalDateTime value){createdAt=value;}
    public OwnReviewView view(){return new OwnReviewView(id.toString(),orderId.toString(),rating,content,visibility,createdAt.toInstant(ZoneOffset.UTC));}
}
