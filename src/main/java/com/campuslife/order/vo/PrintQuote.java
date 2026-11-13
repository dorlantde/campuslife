package com.campuslife.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description="只读快照报价，不锁价、不续期或创建订单；金额为整数分，双面按每份分别取整计算纸张")
public record PrintQuote(
        @Schema(example="201") String serviceId,
        @Schema(example="101") String shopId,
        @Schema(example="301") String fileId,
        @Schema(minimum="1",maximum="500",example="5") int pageCount,
        @Schema(minimum="1",maximum="100",example="2") int copies,
        @Schema(allowableValues="A4",example="A4") String paperSize,
        @Schema(allowableValues={"BW","COLOR"},example="BW") String colorMode,
        @Schema(allowableValues={"SINGLE","DOUBLE"},example="DOUBLE") String sideMode,
        @Schema(minimum="1",maximum="50000",example="6") long sheetCount,
        @Schema(example="401") String priceRuleId,
        @Schema(example="0",description="命中规格的独立版本，不是服务/店铺版本") String priceVersion,
        @Schema(minimum="1",maximum="100000",example="20",description="分/印刷面") long unitPriceAmount,
        @Schema(minimum="1",maximum="5000000000",example="200") long originalAmount,
        @Schema(minimum="0",maximum="4999999999",example="0") long discountAmount,
        @Schema(minimum="1",maximum="5000000000",example="200") long payableAmount,
        @Schema(allowableValues="CNY",example="CNY") String currency, @Schema(nullable=true) String userCouponId) {}
