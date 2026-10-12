package com.fraud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final byte[] expectedApiKey;

    public ApiKeyAuthenticationFilter(String apiKey) {
        this.expectedApiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        if (expectedApiKey.length < 32) {
            throw new IllegalArgumentException("APP_API_KEY must contain at least 32 UTF-8 bytes");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String suppliedKey = request.getHeader(API_KEY_HEADER);
        if (suppliedKey == null || !MessageDigest.isEqual(
                expectedApiKey, suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401}");
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "api-client", null, AuthorityUtils.createAuthorityList("ROLE_API_CLIENT"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
