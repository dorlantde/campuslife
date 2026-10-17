package com.campuslife;

import com.campuslife.common.api.ApiResponse;
import com.campuslife.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("web-only")
@Import(WebFoundationTest.FixtureController.class)
class WebFoundationTest {
    private static final String PROBE = "/_test/probe";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    Environment environment;

    @Autowired
    ObjectMapper mapper;

    @Test
    void healthRunsOnRealServerWithTraceAndUtcTimestamp() throws Exception {
        HttpResponse<String> response = exchange("GET", "/api/v1/system/health", null, null);
        JsonNode body = assertEnvelope(response, 200, "OK");
        assertThat(body.path("message").asString()).isEqualTo("成功");
        assertThat(body.path("data").path("application").asString()).isEqualTo("CampusLife");
        assertThat(body.path("data").path("status").asString()).isEqualTo("UP");
        assertThat(Instant.parse(body.path("data").path("timestamp").asString())).isNotNull();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void requestTraceIsGeneratedByServerAndDoesNotLeakAcrossRequests() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/system/health"))
                .header("X-Trace-Id", "untrusted-client-value").GET().build();
        HttpResponse<String> first = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> second = exchange("GET", "/api/v1/system/health", null, null);
        String firstId = assertEnvelope(first, 200, "OK").path("traceId").asString();
        String secondId = assertEnvelope(second, 200, "OK").path("traceId").asString();
        assertThat(firstId).isNotEqualTo("untrusted-client-value").isNotEqualTo(secondId);
    }

    @Test
    void validRequestReachesController() throws Exception {
        JsonNode body = assertEnvelope(exchange("POST", PROBE, "{\"name\":\"同学\"}", "application/json"), 200, "OK");
        assertThat(body.path("data").path("name").asString()).isEqualTo("同学");
    }

    @Test
    void invalidBeanIsRejectedWithoutEchoingSubmittedValue() throws Exception {
        assertEnvelope(exchange("POST", PROBE, "{\"name\":\" \"}", "application/json"), 400, "VALIDATION_FAILED");
    }

    @Test
    void unknownFieldsCannotBeMassAssigned() throws Exception {
        assertEnvelope(exchange("POST", PROBE, "{\"name\":\"学生\",\"role\":\"ADMIN\"}", "application/json"),
                400, "VALIDATION_FAILED");
    }

    @Test
    void malformedJsonUsesUnifiedFailure() throws Exception {
        assertEnvelope(exchange("POST", PROBE, "{broken", "application/json"), 400, "VALIDATION_FAILED");
    }

    @Test
    void invalidQueryParameterUsesUnifiedFailure() throws Exception {
        assertEnvelope(exchange("GET", PROBE + "?count=abc", null, null), 400, "VALIDATION_FAILED");
    }

    @Test
    void unknownRouteUsesUnifiedNotFound() throws Exception {
        assertEnvelope(exchange("GET", "/api/v1/nonexistent", null, null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void unsupportedMethodKeepsAllowHeader() throws Exception {
        HttpResponse<String> response = exchange("POST", "/api/v1/system/health", null, null);
        assertEnvelope(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.headers().firstValue("Allow").orElse("")).contains("GET");
    }

    @Test
    void unsupportedRequestTypeUsesUnifiedFailure() throws Exception {
        assertEnvelope(exchange("POST", PROBE, "hello", "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unsupportedAcceptStillReceivesReadableJsonFailure() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/system/health"))
                .header("Accept", "text/plain").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEnvelope(response, 406, "NOT_ACCEPTABLE");
    }

    @Test
    void businessFailurePreservesHttpStatusAndCode() throws Exception {
        assertEnvelope(exchange("GET", "/_test/conflict", null, null), 409, "ORDER_STATE_CONFLICT");
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalDetails() throws Exception {
        HttpResponse<String> response = exchange("GET", "/_test/failure", null, null);
        assertEnvelope(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("fixture-secret", "IllegalStateException", "stackTrace");
    }

    private HttpResponse<String> exchange(String method, String path, String payload, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private JsonNode assertEnvelope(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        MediaType contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("message").asString()).isNotBlank();
        String traceId = body.path("traceId").asString();
        assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(traceId);
        if (status >= 400) {
            assertThat(body.has("data")).isTrue();
            assertThat(body.path("data").isNull()).isTrue();
        }
        return body;
    }

    // These endpoints are compiled only into the test classpath, never the application jar.
    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class FixtureController {
        @PostMapping(PROBE)
        ApiResponse<ProbeRequest> validate(@Valid @RequestBody ProbeRequest request) {
            return ApiResponse.success(request);
        }

        @GetMapping(PROBE)
        ApiResponse<Integer> query(@RequestParam int count) {
            return ApiResponse.success(count);
        }

        @GetMapping("/_test/conflict")
        ApiResponse<Void> conflict() {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_STATE_CONFLICT", "当前状态不允许该操作");
        }

        @GetMapping("/_test/failure")
        ApiResponse<Void> failure() {
            throw new IllegalStateException("fixture-secret: internal failure");
        }
    }

    record ProbeRequest(@NotBlank @Size(max = 40) String name) {
    }
}
