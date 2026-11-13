package com.campuslife.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("coupon_campaign")
public class CouponCampaignEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long shopId;
    private String name;
    private Long thresholdAmount;
    private Long reductionAmount;
    private Integer totalQuantity;
    private Integer issuedCount;
    private String status;
    private LocalDateTime claimStartAt;
    private LocalDateTime claimEndAt;
    private LocalDateTime useStartAt;
    private LocalDateTime useEndAt;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private Long publishExpectedVersion;
    private LocalDateTime stoppedAt;
    private Long stoppedBy;
    private Long stopExpectedVersion;

    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    public Long getShopId(){return shopId;}
    public void setShopId(Long value){shopId=value;}
    public String getName(){return name;}
    public void setName(String value){name=value;}
    public Long getThresholdAmount(){return thresholdAmount;}
    public void setThresholdAmount(Long value){thresholdAmount=value;}
    public Long getReductionAmount(){return reductionAmount;}
    public void setReductionAmount(Long value){reductionAmount=value;}
    public Integer getTotalQuantity(){return totalQuantity;}
    public void setTotalQuantity(Integer value){totalQuantity=value;}
    public Integer getIssuedCount(){return issuedCount;}
    public void setIssuedCount(Integer value){issuedCount=value;}
    public String getStatus(){return status;}
    public void setStatus(String value){status=value;}
    public LocalDateTime getClaimStartAt(){return claimStartAt;}
    public void setClaimStartAt(LocalDateTime value){claimStartAt=value;}
    public LocalDateTime getClaimEndAt(){return claimEndAt;}
    public void setClaimEndAt(LocalDateTime value){claimEndAt=value;}
    public LocalDateTime getUseStartAt(){return useStartAt;}
    public void setUseStartAt(LocalDateTime value){useStartAt=value;}
    public LocalDateTime getUseEndAt(){return useEndAt;}
    public void setUseEndAt(LocalDateTime value){useEndAt=value;}
    public Long getVersion(){return version;}
    public void setVersion(Long value){version=value;}
    public LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(LocalDateTime value){createdAt=value;}
    public LocalDateTime getUpdatedAt(){return updatedAt;}
    public void setUpdatedAt(LocalDateTime value){updatedAt=value;}
    public LocalDateTime getPublishedAt(){return publishedAt;}
    public void setPublishedAt(LocalDateTime value){publishedAt=value;}
    public Long getPublishedBy(){return publishedBy;}
    public void setPublishedBy(Long value){publishedBy=value;}
    public Long getPublishExpectedVersion(){return publishExpectedVersion;}
    public void setPublishExpectedVersion(Long value){publishExpectedVersion=value;}
    public LocalDateTime getStoppedAt(){return stoppedAt;}
    public void setStoppedAt(LocalDateTime value){stoppedAt=value;}
    public Long getStoppedBy(){return stoppedBy;}
    public void setStoppedBy(Long value){stoppedBy=value;}
    public Long getStopExpectedVersion(){return stopExpectedVersion;}
    public void setStopExpectedVersion(Long value){stopExpectedVersion=value;}
}
