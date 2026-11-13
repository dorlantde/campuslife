package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record MerchantApplicationView(@Schema(type = "string") String accountId,
                                      @Schema(type = "string") String shopId,
                                      @Schema(allowableValues = {"PENDING"}) String approvalStatus) {
}
