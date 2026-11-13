package com.campuslife;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("web-only")
class ManagementSecurityTest {
    private static final String PASSWORD = "a".repeat(64);
    private static final int PORT = freePort();
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("campuslife.management.enabled", () -> "true");
        registry.add("campuslife.management.password", () -> PASSWORD);
        registry.add("management.server.port", () -> PORT);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private HttpResponse<String> request(String method, String path, String authorization) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) builder.header("Authorization", authorization);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("campuslife_ops:" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test void anonymousAndBearerCannotReadManagement() throws Exception {
        assertThat(request("GET", "/actuator/metrics", null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/actuator/metrics", "Bearer header.payload.signature").statusCode()).isEqualTo(401);
        assertThat(request("GET", "/actuator/metrics", basic("incorrect")).statusCode()).isEqualTo(401);
    }

    @Test void independentCredentialReadsOnlySummaryAndMetrics() throws Exception {
        var health = request("GET", "/actuator/health/liveness", basic(PASSWORD));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
        var metric = request("GET", "/actuator/metrics/jvm.memory.used", basic(PASSWORD));
        assertThat(metric.statusCode()).isEqualTo(200);
        assertThat(metric.body()).contains("jvm.memory.used").doesNotContain(PASSWORD);
    }

    @Test void nonWhitelistedEndpointsAndMethodsAreDeniedEvenToOperations() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/env", "/actuator/heapdump", "/actuator/loggers",
                "/actuator/configprops", "/actuator/shutdown", "/api/v1/system/health"}) {
            assertThat(request("GET", path, basic(PASSWORD)).statusCode()).as(path).isEqualTo(403);
        }
        assertThat(request("POST", "/actuator/metrics", basic(PASSWORD)).statusCode()).isEqualTo(403);
        assertThat(request("HEAD", "/actuator/health", basic(PASSWORD)).statusCode()).isEqualTo(403);
    }
}
