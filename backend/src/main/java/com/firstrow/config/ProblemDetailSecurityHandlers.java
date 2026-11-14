package com.firstrow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrow.observability.ProblemDetailRequestIdAdvice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Security errors happen in the filter chain, BEFORE any controller runs, so @RestControllerAdvice
 * never sees them. These handlers make 401 and 403 responses use the same ProblemDetail JSON
 * as the rest of the API (plus the standard WWW-Authenticate header from Spring's bearer handlers).
 */
@Component
public class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();

    public ProblemDetailSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401: no token, or the token is invalid/expired. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        bearerEntryPoint.commence(request, response, authException); // sets 401 + WWW-Authenticate
        write(request, response, HttpStatus.UNAUTHORIZED,
                "A valid access token is required. Send it as 'Authorization: Bearer <token>'.");
    }

    /** 403: valid token, but the user's role isn't allowed here. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        bearerAccessDenied.handle(request, response, accessDeniedException); // sets 403 + WWW-Authenticate
        write(request, response, HttpStatus.FORBIDDEN, "You don't have permission to do this.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        ProblemDetailRequestIdAdvice.addRequestId(problem, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
