package com.campuslife.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
public class OpenApiConfiguration {
    @Bean
    public OpenAPI campusLifeOpenApi() {
        return new OpenAPI()
                .info(new Info().title("CampusLife 本地接口调试").version("v1")
                        .description("虚构校园学习项目。先注册、登录，再把 data.accessToken 填入 Authorize。"
                                + "只填写 token，不需要添加 Bearer 前缀。修改昵称保留登录；修改密码撤销本人全部会话。"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("登录响应中的 data.accessToken；仅填写 token 本身。")));
    }

    @Bean
    public OpenApiCustomizer documentedErrors() {
        return api -> {
            // Register together with its references, after springdoc removes unreferenced input components.
            ObjectSchema failure = new ObjectSchema();
            failure.addProperty("code", new StringSchema().description("业务错误码，具体值见各 HTTP 状态说明"));
            failure.addProperty("message", new StringSchema().description("对外错误提示"));
            ObjectSchema emptyData = new ObjectSchema();
            emptyData.setNullable(true);
            failure.addProperty("data", emptyData);
            failure.addProperty("traceId", new StringSchema().description("与响应头 X-Trace-Id 相同的请求标识"));
            failure.setRequired(List.of("code", "message", "data", "traceId"));
            api.getComponents().addSchemas("ApiError", failure);
            Schema<?> emptyResponse = api.getComponents().getSchemas().get("ApiResponseVoid");
            if (emptyResponse != null) { emptyResponse.getProperties().get("data").setNullable(true); }
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                error(operation, "401", "AUTH_REQUIRED / TOKEN_INVALID / SESSION_EXPIRED / SESSION_REVOKED；登录还可能为 LOGIN_FAILED");
                error(operation, "406", "NOT_ACCEPTABLE：不支持请求的响应格式");
                error(operation, "500", "INTERNAL_ERROR：内部失败，不返回敏感细节");
                if (!path.endsWith("/health")) {
                    error(operation, "403", "ACCOUNT_DISABLED：账号停用");
                }
                if (operation.getRequestBody() != null) {
                    error(operation, "400", "VALIDATION_FAILED：请求格式错误；改密还可能为 CURRENT_PASSWORD_INCORRECT");
                    error(operation, "415", "UNSUPPORTED_MEDIA_TYPE：请使用 application/json");
                }
                if (path.endsWith("/register")) {
                    operation.getResponses().remove("403");
                    error(operation, "409", "USERNAME_EXISTS：用户名已占用");
                } else if ("/api/v1/me".equals(path) && item.getPatch() == operation) {
                    error(operation, "409", "CONFIG_VERSION_CONFLICT：版本过期或不能递增，请重新 GET /me");
                } else if (path.endsWith("/password")) {
                    error(operation, "409", "ACCOUNT_CREDENTIALS_CHANGED：校验后凭据已变化，拒绝覆盖");
                }
            }));
        };
    }

    private static void error(io.swagger.v3.oas.models.Operation operation, String status, String description) {
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
    }
}
