package com.campuslife.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("print_price_rule")
public class PrintPriceRuleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serviceId;
    private String paperSize;
    private String colorMode;
    private String sideMode;
    private Long pricePerImpression;
    private Boolean enabled;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public String getPaperSize() { return paperSize; }
    public void setPaperSize(String paperSize) { this.paperSize = paperSize; }
    public String getColorMode() { return colorMode; }
    public void setColorMode(String colorMode) { this.colorMode = colorMode; }
    public String getSideMode() { return sideMode; }
    public void setSideMode(String sideMode) { this.sideMode = sideMode; }
    public Long getPricePerImpression() { return pricePerImpression; }
    public void setPricePerImpression(Long pricePerImpression) { this.pricePerImpression = pricePerImpression; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
