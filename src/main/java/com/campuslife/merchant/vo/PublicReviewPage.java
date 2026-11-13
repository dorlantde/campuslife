package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PublicReviewPage(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<PublicReviewView> items,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1") int page,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1",maximum="100") int size,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0",description="本店全部VISIBLE评价数，不只当前页") long total,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0") long totalPages) {}
