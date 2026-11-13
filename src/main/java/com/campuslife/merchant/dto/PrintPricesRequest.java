package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PrintPricesRequest(
        @NotNull @Size(min = 1, max = 4)
        @ArraySchema(minItems = 1, maxItems = 4, schema = @Schema(implementation = PrintPriceRuleRequest.class))
        List<@NotNull @Valid PrintPriceRuleRequest> rules) {}
