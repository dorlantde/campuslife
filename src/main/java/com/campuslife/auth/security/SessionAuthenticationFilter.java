package com.campuslife.auth.security;

import com.campuslife.auth.service.SessionService;
import com.campuslife.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Constructed inside SecurityFilterChain, not also registered as a servlet Filter bean. */
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final SessionService sessions;
    private final AuthHttpErrors errors;

    public SessionAuthenticationFilter(TokenService tokens, SessionService sessions, AuthHttpErrors errors) {
        this.tokens = tokens; this.sessions = sessions; this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (!headers.isEmpty()) {
            try {
                if (headers.size() != 1 || !headers.getFirst().matches("(?i)Bearer [A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+")) {
                    throw TokenService.failure("TOKEN_INVALID", "登录凭证无效");
                }
                boolean logout = "POST".equals(request.getMethod()) && "/api/v1/auth/logout".equals(request.getServletPath());
                AuthPrincipal principal = sessions.authenticate(tokens.verify(headers.getFirst().substring(7)), logout);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.account().role()))));
                SecurityContextHolder.setContext(context);
            } catch (BusinessException exception) {
                errors.write(response, exception);
                return;
            } catch (DataAccessException exception) {
                errors.write(response, new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
