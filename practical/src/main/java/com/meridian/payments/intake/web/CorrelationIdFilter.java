package com.meridian.payments.intake.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation id for the whole request, including error responses: the API Manager's
 * {@code X-Correlation-Id} when it is a valid UUID, otherwise a new one. It is put in the logging MDC, exposed
 * as a request attribute and returned in the response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlationId = parse(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.toString());
        MDC.put(ATTRIBUTE, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ATTRIBUTE);
        }
    }

    static UUID parse(String header) {
        if (header != null) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: never propagate arbitrary caller input into logs or messages.
            }
        }
        return UUID.randomUUID();
    }
}
