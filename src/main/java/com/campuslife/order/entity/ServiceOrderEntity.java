package com.campuslife.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;

@TableName("service_order")
public class ServiceOrderEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long studentId;
    private Long shopId;
    private Long originalAmount;
    private Long discountAmount;
    private Long payableAmount;
    private Long version;
    private String orderNo;
    private String orderType;
    private String status;
    private String paymentStatus;
    private String currency;
    private String shopNameSnapshot;
    private String studentNote;
    private String closeReason;
    private String closeNote;
    private LocalDateTime createdAt;
    private LocalDateTime paymentDeadline;
    private LocalDateTime acceptanceDeadline;
    private LocalDateTime paidAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime closedAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long value) { this.studentId = value; }
    public Long getShopId() { return shopId; }
    public void setShopId(Long value) { this.shopId = value; }
    public Long getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(Long value) { this.originalAmount = value; }
    public Long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(Long value) { this.discountAmount = value; }
    public Long getPayableAmount() { return payableAmount; }
    public void setPayableAmount(Long value) { this.payableAmount = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String value) { this.orderNo = value; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String value) { this.orderType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String value) { this.paymentStatus = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { this.currency = value; }
    public String getShopNameSnapshot() { return shopNameSnapshot; }
    public void setShopNameSnapshot(String value) { this.shopNameSnapshot = value; }
    public String getStudentNote() { return studentNote; }
    public void setStudentNote(String value) { this.studentNote = value; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String value) { this.closeReason = value; }
    public String getCloseNote() { return closeNote; }
    public void setCloseNote(String value) { this.closeNote = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getPaymentDeadline() { return paymentDeadline; }
    public void setPaymentDeadline(LocalDateTime value) { this.paymentDeadline = value; }
    public LocalDateTime getAcceptanceDeadline() { return acceptanceDeadline; }
    public void setAcceptanceDeadline(LocalDateTime value) { this.acceptanceDeadline = value; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime value) { this.paidAt = value; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime value) { this.acceptedAt = value; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { this.startedAt = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { this.completedAt = value; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime value) { this.closedAt = value; }
}
