package com.campuslife.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OrderPage(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<OrderSummary> items,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") int page,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1",maximum="100") int size,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0") long total,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0") long totalPages) {}
