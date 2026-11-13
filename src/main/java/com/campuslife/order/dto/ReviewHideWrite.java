package com.campuslife.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record ReviewHideWrite(@Schema(type="string",minLength=1,maxLength=300,example="包含不适当内容",
    requiredMode=Schema.RequiredMode.REQUIRED,description="strip后1～300个Unicode码点；内部换行保留") JsonNode reason) {}
