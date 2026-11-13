package com.campuslife.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;

@TableName("booking_detail")
public class BookingDetailEntity {
    @TableId(type = IdType.INPUT)
    private Long orderId;
    private Long serviceId;
    private Long barberId;
    private Long servicePriceSnapshot;
    private Long sourceServiceVersion;
    private Integer durationMinutesSnapshot;
    private String occupancyStatus;
    private String serviceNameSnapshot;
    private String barberNameSnapshot;
    private LocalDate serviceDate;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { this.orderId = value; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long value) { this.serviceId = value; }
    public Long getBarberId() { return barberId; }
    public void setBarberId(Long value) { this.barberId = value; }
    public Long getServicePriceSnapshot() { return servicePriceSnapshot; }
    public void setServicePriceSnapshot(Long value) { this.servicePriceSnapshot = value; }
    public Long getSourceServiceVersion() { return sourceServiceVersion; }
    public void setSourceServiceVersion(Long value) { this.sourceServiceVersion = value; }
    public Integer getDurationMinutesSnapshot() { return durationMinutesSnapshot; }
    public void setDurationMinutesSnapshot(Integer value) { this.durationMinutesSnapshot = value; }
    public String getOccupancyStatus() { return occupancyStatus; }
    public void setOccupancyStatus(String value) { this.occupancyStatus = value; }
    public String getServiceNameSnapshot() { return serviceNameSnapshot; }
    public void setServiceNameSnapshot(String value) { this.serviceNameSnapshot = value; }
    public String getBarberNameSnapshot() { return barberNameSnapshot; }
    public void setBarberNameSnapshot(String value) { this.barberNameSnapshot = value; }
    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate value) { this.serviceDate = value; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime value) { this.startAt = value; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime value) { this.endAt = value; }
}
