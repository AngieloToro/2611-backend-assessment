package com.meridian.payments.intake.web;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.meridian.payments.intake.config.IntakeProperties;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.service.IntakeOutcome;
import com.meridian.payments.intake.service.IntakeService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Webhook endpoints, contract in docs/api/webhooks.openapi.yaml. */
@RestController
public class WebhookController {

    private final IntakeService intake;
    private final IntakeProperties properties;
    private final Clock clock;

    public WebhookController(IntakeService intake, IntakeProperties properties, Clock clock) {
        this.intake = intake;
        this.properties = properties;
        this.clock = clock;
    }

    public record Acknowledgement(IntakeOutcome status, UUID correlationId) {
    }

    /** The body is bound as raw bytes: signatures are verified over the exact bytes received. */
    @PostMapping(path = "/payments/webhooks/v1/{provider}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Acknowledgement> receive(@PathVariable("provider") String provider,
                                                   @RequestHeader HttpHeaders headers,
                                                   @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId,
                                                   @RequestBody byte[] body) {
        if (body.length > properties.maxBodyBytes()) {
            throw new PayloadTooLargeException();
        }
        WebhookRequest request = new WebhookRequest(body, headers.toSingleValueMap(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS), correlationId);
        IntakeOutcome outcome = intake.receive(provider, request);
        return ResponseEntity.ok(new Acknowledgement(outcome, correlationId));
    }
}
