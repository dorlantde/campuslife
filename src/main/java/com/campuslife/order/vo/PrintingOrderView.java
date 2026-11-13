package com.campuslife.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="打印冻结明细及真实履约进度，无存储定位信息；学生确认取件后为PICKED_UP")
public record PrintingOrderView(String fileId,String fileName,int pageCount,int copies,
        String paperSize,String colorMode,String sideMode,long sheetCount,long unitPriceAmount,
        @Schema(allowableValues={"QUEUED","PRINTING","READY_FOR_PICKUP","PICKED_UP","ABORTED"}) String progress,
        @Schema(nullable=true) Instant readyAt,@Schema(nullable=true) Instant pickedUpAt) {}
