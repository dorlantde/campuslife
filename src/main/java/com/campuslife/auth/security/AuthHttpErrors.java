package com.campuslife.auth.security;

import com.campuslife.common.api.ApiResponse;
import com.campuslife.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Filter failures use the same envelope as MVC failures, without dispatching to an HTML error page. */
@Component
@Profile("mysql")
public class AuthHttpErrors {
    private final ObjectMapper mapper;
    public AuthHttpErrors(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletResponse response, BusinessException failure) throws IOException {
        response.setStatus(failure.status().value());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (failure.status().value() == 401) { response.setHeader("WWW-Authenticate", "Bearer"); }
        mapper.writeValue(response.getOutputStream(), ApiResponse.failure(failure.code(), failure.getMessage()));
    }
}
