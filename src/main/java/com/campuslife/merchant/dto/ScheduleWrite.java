package com.campuslife.merchant.dto;

import com.campuslife.merchant.vo.ScheduleInterval;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleWrite(
        @NotNull @Schema(type = "string", format = "date", example = "2026-10-22", description = "严格 YYYY-MM-DD；Asia/Shanghai 今天至第 6 天，含首尾") JsonNode date,
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "0", description = "理发师版本，非店铺版本；最大 9223372036854775807") JsonNode expectedBarberVersion,
        @NotNull @ArraySchema(maxItems = 4, schema = @Schema(implementation = ScheduleInterval.class),
                arraySchema = @Schema(description = "完整替换该日，空数组清空；原始最多 4 段，相邻合并，重叠拒绝")) JsonNode intervals) {}
