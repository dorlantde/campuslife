package com.campuslife.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record AccountStatusWrite(
        @Schema(type="string",allowableValues={"ACTIVE","DISABLED"},requiredMode=Schema.RequiredMode.REQUIRED) JsonNode status,
        @Schema(type="string",pattern="0|[1-9][0-9]{0,18}",example="0",requiredMode=Schema.RequiredMode.REQUIRED,
                description="账号列表读取的version，规范非负BIGINT字符串") JsonNode expectedVersion) {}
