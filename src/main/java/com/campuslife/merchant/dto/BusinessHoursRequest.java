package com.campuslife.merchant.dto;

import com.campuslife.merchant.vo.BusinessHourView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BusinessHoursRequest(
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "1", description = "实际店铺版本，不是账号版本") JsonNode expectedVersion,
        @NotNull @ArraySchema(maxItems = 28, schema = @Schema(implementation = BusinessHourView.class),
                arraySchema = @Schema(description = "完整替换整周，每天最多 4 段；未列星期休息，空数组清空。相邻合并，重叠拒绝")) JsonNode intervals) {}
