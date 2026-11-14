package com.firstrow.observability;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Adds "requestId" to every ProblemDetail error body written by Spring MVC, in one place,
 * instead of in each exception handler. (401/403 from the security filters are handled in
 * ProblemDetailSecurityHandlers, which runs outside Spring MVC.)
 */
@RestControllerAdvice
public class ProblemDetailRequestIdAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem && request instanceof ServletServerHttpRequest servletRequest) {
            addRequestId(problem, servletRequest.getServletRequest());
        }
        return body;
    }

    public static void addRequestId(ProblemDetail problem, HttpServletRequest request) {
        String requestId = RequestIdFilter.current(request);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }
}
