package com.eventrelay.api.security;

import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.service.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates requests to tenant-scoped endpoints using a Bearer API key.
 * On success the resolved {@link Tenant} is exposed as the {@code tenant}
 * request attribute for controllers to consume via {@code @RequestAttribute}.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = "tenant";

    private static final String[] PROTECTED_PREFIXES = {
            "/api/v1/events",
            "/api/v1/subscriptions",
            "/api/v1/dead-letter"
    };

    private final AuthenticationService authentication;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(AuthenticationService authentication, ObjectMapper objectMapper) {
        this.authentication = authentication;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "Missing or malformed Authorization header");
            return;
        }

        String rawKey = header.substring("Bearer ".length()).trim();
        Optional<Tenant> tenant = authentication.authenticate(rawKey);
        if (tenant.isEmpty()) {
            unauthorized(response, "Invalid API key");
            return;
        }

        request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", "UNAUTHORIZED", "message", message));
    }
}
