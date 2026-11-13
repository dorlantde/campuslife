package com.campuslife.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("print_detail")
public class PrintDetailEntity {
    @TableId(type=IdType.INPUT)
    private Long orderId;
    private Long serviceId;
    private Long fileId;
    private Long priceRuleId;
    private Long sheetCount;
    private Long unitPriceAmount;
    private Long sourcePriceVersion;
    private Integer pageCount;
    private Integer copies;
    private String serviceNameSnapshot;
    private String fileNameSnapshot;
    private String paperSize;
    private String colorMode;
    private String sideMode;
    private String progress;
    private LocalDateTime readyAt;
    private LocalDateTime pickedUpAt;
    public Long getOrderId(){return orderId;}
    public void setOrderId(Long value){orderId=value;}
    public Long getServiceId(){return serviceId;}
    public void setServiceId(Long value){serviceId=value;}
    public Long getFileId(){return fileId;}
    public void setFileId(Long value){fileId=value;}
    public Long getPriceRuleId(){return priceRuleId;}
    public void setPriceRuleId(Long value){priceRuleId=value;}
    public Long getSheetCount(){return sheetCount;}
    public void setSheetCount(Long value){sheetCount=value;}
    public Long getUnitPriceAmount(){return unitPriceAmount;}
    public void setUnitPriceAmount(Long value){unitPriceAmount=value;}
    public Long getSourcePriceVersion(){return sourcePriceVersion;}
    public void setSourcePriceVersion(Long value){sourcePriceVersion=value;}
    public Integer getPageCount(){return pageCount;}
    public void setPageCount(Integer value){pageCount=value;}
    public Integer getCopies(){return copies;}
    public void setCopies(Integer value){copies=value;}
    public String getServiceNameSnapshot(){return serviceNameSnapshot;}
    public void setServiceNameSnapshot(String value){serviceNameSnapshot=value;}
    public String getFileNameSnapshot(){return fileNameSnapshot;}
    public void setFileNameSnapshot(String value){fileNameSnapshot=value;}
    public String getPaperSize(){return paperSize;}
    public void setPaperSize(String value){paperSize=value;}
    public String getColorMode(){return colorMode;}
    public void setColorMode(String value){colorMode=value;}
    public String getSideMode(){return sideMode;}
    public void setSideMode(String value){sideMode=value;}
    public String getProgress(){return progress;}
    public void setProgress(String value){progress=value;}
    public LocalDateTime getReadyAt(){return readyAt;}
    public void setReadyAt(LocalDateTime value){readyAt=value;}
    public LocalDateTime getPickedUpAt(){return pickedUpAt;}
    public void setPickedUpAt(LocalDateTime value){pickedUpAt=value;}
}
