package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PublicBarberView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "人员 ID，不是登录账号 ID") String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 40) String displayName,
        @ArraySchema(minItems = 1, maxItems = 50, schema = @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$"),
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "全部本店上架理发服务 ID，数值升序；serviceId 筛选不截断此集合")) List<String> serviceIds) {}
