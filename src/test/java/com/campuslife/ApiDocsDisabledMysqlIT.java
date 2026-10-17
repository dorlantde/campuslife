package com.campuslife;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=false")
@ActiveProfiles("mysql")
class ApiDocsDisabledMysqlIT {
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void disablingApiDocsAlsoRemovesUiAndItsAnonymousAccess() throws Exception {
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var paths = List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html");
        for (String path : paths) {
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        String username = "off_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long id = 0;
        try {
            var register = post(base + "/api/v1/auth/register", Map.of("username", username, "password", "DocsOff_123!", "nickname", "关闭文档验收"));
            assertThat(register.statusCode()).isEqualTo(201);
            id = Long.parseLong(mapper.readTree(register.body()).path("data").path("id").asString());
            var login = post(base + "/api/v1/auth/login", Map.of("username", username, "password", "DocsOff_123!"));
            assertThat(login.statusCode()).isEqualTo(200);
            String token = mapper.readTree(login.body()).path("data").path("accessToken").asString();
            for (String path : paths) {
                assertThat(client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token)
                                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).as(path).isEqualTo(404);
            }
        } finally {
            if (id != 0) {
                jdbc.update("DELETE FROM auth_session WHERE account_id = ?", id);
                jdbc.update("DELETE FROM account WHERE id = ?", id);
            }
        }
    }

    private HttpResponse<String> post(String url, Map<String, String> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
