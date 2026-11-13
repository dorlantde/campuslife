package com.campuslife.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("shop")
public class ShopEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long categoryId;
    private String name;
    private String campusCode;
    private String locationText;
    private String description;
    private String approvalStatus;
    private String approvalReason;
    private Boolean businessEnabled;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCampusCode() { return campusCode; }
    public void setCampusCode(String campusCode) { this.campusCode = campusCode; }
    public String getLocationText() { return locationText; }
    public void setLocationText(String locationText) { this.locationText = locationText; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public String getApprovalReason() { return approvalReason; }
    public void setApprovalReason(String approvalReason) { this.approvalReason = approvalReason; }
    public Boolean getBusinessEnabled() { return businessEnabled; }
    public void setBusinessEnabled(Boolean businessEnabled) { this.businessEnabled = businessEnabled; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
