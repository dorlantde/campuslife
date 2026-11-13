package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record MerchantBarberView(
        @Schema(type = "string", example = "301") String id,
        @Schema(example = "模拟理发师小林") String displayName,
        boolean enabled,
        @ArraySchema(schema = @Schema(type = "string", example = "201"), arraySchema = @Schema(description = "按数值 ID 升序，包含已绑定但下架的服务")) List<String> serviceIds,
        @Schema(type = "string", example = "0", description = "资料和服务绑定共用的理发师版本") String version) {}
