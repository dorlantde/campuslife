package com.campuslife;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class OpenApiMysqlIT {
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;

    @Test
    void generatedSpecificationDescribesOnlyImplementedOperationsAndExportsAnImportableSnapshot() throws Exception {
        var response = call("GET", "/v3/api-docs", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        ObjectNode spec = (ObjectNode) mapper.readTree(response.body());
        assertThat(spec.path("openapi").asString()).startsWith("3.0.");
        assertThat(spec.path("paths").propertyNames()).containsExactlyInAnyOrder("/api/v1/system/health", "/api/v1/auth/register",
                "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/me", "/api/v1/me/password");
        assertThat(spec.path("paths").properties().stream().mapToInt(entry -> entry.getValue().size()).sum()).isEqualTo(7);
        JsonNode components = spec.path("components");
        assertThat(components.path("securitySchemes").path("bearerAuth").path("scheme").asString()).isEqualTo("bearer");
        for (String endpoint : List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/system/health")) {
            assertThat(spec.path("paths").path(endpoint).path(endpoint.endsWith("/health") ? "get" : "post")
                    .path("security").isMissingNode()).isTrue();
        }
        for (String method : List.of("get", "patch")) {
            assertThat(spec.path("paths").path("/api/v1/me").path(method).path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(spec.path("paths").path("/api/v1/me").path(method).path("parameters").isMissingNode()).isTrue();
        }
        assertThat(spec.path("paths").path("/api/v1/auth/register").path("post").path("responses").has("201")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/me").path("patch").path("responses").path("409")
                .path("content").path("application/json").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/ApiError");
        JsonNode profile = components.path("schemas").path("ProfileUpdateRequest");
        assertThat(profile.path("properties").path("nickname").path("type").asString()).isEqualTo("string");
        assertThat(profile.path("properties").path("expectedVersion").path("type").asString()).isEqualTo("string");
        assertThat(profile.path("required").toString()).contains("expectedVersion").doesNotContain("nickname");
        assertThat(profile.path("additionalProperties").asBoolean()).isFalse();
        assertThat(profile.path("properties").path("expectedVersion").path("pattern").asString()).isEqualTo("^(0|[1-9][0-9]{0,18})$");
        assertThat(components.path("schemas").path("ApiResponseVoid").path("properties").path("data").path("nullable").asBoolean()).isTrue();
        assertThat(components.path("schemas").path("AccountView").path("properties").path("version").path("type").asString()).isEqualTo("string");
        assertThat(response.body()).doesNotContain("AuthPrincipal", "passwordHash", "password_hash", "JWT_SECRET", "DB_PASSWORD", "_test");
        assertThat(spec.path("servers").get(0).path("url").asString()).isEqualTo(baseUrl());
        validateReferences(spec, components.path("schemas"));
        // Only a public schema is exported; normalize the random test port for local Apifox import.
        spec.putArray("servers").addObject().put("url", "http://127.0.0.1:8080").put("description", "本机开发环境");
        Path output = Path.of("target", "openapi", "campuslife.openapi.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec), StandardCharsets.UTF_8);
    }

    @Test
    void swaggerPageConfigurationAndJavascriptAreReachableWithoutLoggingIn() throws Exception {
        assertThat(call("GET", "/swagger-ui.html", null, null).statusCode()).isIn(301, 302, 307, 308);
        assertThat(call("GET", "/swagger-ui/index.html", null, null).body()).contains("Swagger UI", "swagger-ui-bundle.js");
        for (String path : List.of("/swagger-ui/swagger-ui.css", "/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-initializer.js")) {
            assertThat(call("GET", path, null, null).statusCode()).as(path).isEqualTo(200);
        }
        var configResponse = call("GET", "/v3/api-docs/swagger-config", null, null);
        assertThat(configResponse.statusCode()).isEqualTo(200);
        JsonNode config = mapper.readTree(configResponse.body());
        assertThat(config.path("url").asString()).isEqualTo("/v3/api-docs");
        assertThat(config.path("persistAuthorization").asBoolean()).isFalse();
        assertThat(config.path("validatorUrl").asString()).isEqualTo("none");
        assertThat(call("GET", "/v3/api-docs", null, "invalid").statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/private/future", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void documentedExamplesSupportLoginProfileUpdateAndConflictAgainstRealMysql() throws Exception {
        JsonNode schemas = mapper.readTree(call("GET", "/v3/api-docs", null, null).body()).path("components").path("schemas");
        String username = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String password = schemas.path("RegisterRequest").path("properties").path("password").path("example").asString();
        long id = 0;
        try {
            var registration = call("POST", "/api/v1/auth/register", mapper.writeValueAsString(Map.of("username", username,
                    "password", password, "nickname", schemas.path("RegisterRequest").path("properties").path("nickname").path("example").asString())), null);
            assertThat(registration.statusCode()).isEqualTo(201);
            id = Long.parseLong(mapper.readTree(registration.body()).path("data").path("id").asString());
            var login = call("POST", "/api/v1/auth/login", mapper.writeValueAsString(Map.of("username", username, "password", password)), null);
            assertThat(login.statusCode()).isEqualTo(200);
            String token = mapper.readTree(login.body()).path("data").path("accessToken").asString();
            JsonNode before = mapper.readTree(call("GET", "/api/v1/me", null, token).body()).path("data");
            String nickname = schemas.path("ProfileUpdateRequest").path("properties").path("nickname").path("example").asString();
            String body = mapper.writeValueAsString(Map.of("nickname", nickname, "expectedVersion", before.path("version").asString()));
            var updated = call("PATCH", "/api/v1/me", body, token);
            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(updated.body()).path("data").path("nickname").asString()).isEqualTo(nickname);
            var conflict = call("PATCH", "/api/v1/me", body, token);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(mapper.readTree(conflict.body()).path("code").asString()).isEqualTo("CONFIG_VERSION_CONFLICT");
        } finally {
            if (id != 0) {
                jdbc.update("DELETE FROM auth_session WHERE account_id = ?", id);
                jdbc.update("DELETE FROM account WHERE id = ?", id);
            }
        }
    }

    private String baseUrl() { return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"); }

    private void validateReferences(JsonNode node, JsonNode schemas) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String reference = node.path("$ref").asString();
                assertThat(reference).startsWith("#/components/schemas/");
                assertThat(schemas.has(reference.substring("#/components/schemas/".length())))
                        .as("Missing schema for %s", reference).isTrue();
            }
            node.properties().forEach(entry -> validateReferences(entry.getValue(), schemas));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) { validateReferences(node.get(index), schemas); }
        }
    }
    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(java.time.Duration.ofSeconds(15));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
