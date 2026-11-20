package com.meridian.payments.intake.web;

import java.net.URI;

import com.meridian.payments.intake.provider.InvalidPayloadException;
import com.meridian.payments.intake.provider.InvalidSignatureException;
import com.meridian.payments.intake.provider.UnknownProviderException;
import com.meridian.payments.intake.service.NotAcceptedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to RFC 9457 problem responses. Every non-2xx response means "not accepted": the provider
 * retries according to its own policy. Responses never echo request data and use generic details, so that a
 * caller probing the endpoint learns nothing about why a signature failed.
 */
@RestControllerAdvice
public class WebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "30";

    @ExceptionHandler(InvalidSignatureException.class)
    ResponseEntity<ProblemDetail> invalidSignature() {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-signature", "Signature verification failed");
    }

    @ExceptionHandler({InvalidPayloadException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalidPayload() {
        return problem(HttpStatus.BAD_REQUEST, "invalid-payload", "The notification payload could not be interpreted");
    }

    @ExceptionHandler(UnknownProviderException.class)
    ResponseEntity<ProblemDetail> unknownProvider() {
        return problem(HttpStatus.NOT_FOUND, "unknown-provider", "Unknown webhook endpoint");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge() {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Request body too large");
    }

    @ExceptionHandler(NotAcceptedException.class)
    ResponseEntity<ProblemDetail> notAccepted(NotAcceptedException e) {
        log.error("webhook not accepted reason=\"{}\" cause={}", e.getMessage(),
                e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
        ResponseEntity<ProblemDetail> response =
                problem(HttpStatus.SERVICE_UNAVAILABLE, "not-accepted", "Notification not accepted, retry later");
        return ResponseEntity.status(response.getStatusCode())
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .body(response.getBody());
    }

    /** Anything unexpected is also "not accepted"; logged by type only, never with request content. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("webhook failed unexpectedly type={}", e.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Notification not accepted");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://errors.meridian.example/payments/" + type));
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.status(status).body(problem);
    }
}
