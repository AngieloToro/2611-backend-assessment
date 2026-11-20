package com.meridian.payments.intake.service;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.meridian.payments.intake.config.IntakeProperties;
import com.meridian.payments.intake.domain.CanonicalEventSerializer;
import com.meridian.payments.intake.domain.CanonicalPaymentEvent;
import com.meridian.payments.intake.messaging.EventPublisher;
import com.meridian.payments.intake.messaging.OutboundMessage;
import com.meridian.payments.intake.provider.AdapterResult;
import com.meridian.payments.intake.provider.InvalidPayloadException;
import com.meridian.payments.intake.provider.InvalidSignatureException;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.ProviderRegistry;
import com.meridian.payments.intake.provider.WebhookRequest;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared intake flow, identical for every provider: verify, map, enqueue durably, then acknowledge.
 * Provider-specific logic lives only in {@link ProviderAdapter} implementations.
 *
 * <p>Logging rule: identifiers only (provider, provider ids, payment key, correlation id). Request bodies and
 * headers are never logged, at any level (NFR6).
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final ProviderRegistry registry;
    private final EventPublisher publisher;
    private final CanonicalEventSerializer serializer;
    private final ExecutorService publishExecutor;
    private final IntakeProperties properties;
    private final MeterRegistry meters;

    public IntakeService(ProviderRegistry registry, EventPublisher publisher, CanonicalEventSerializer serializer,
                         ExecutorService publishExecutor, IntakeProperties properties, MeterRegistry meters) {
        this.registry = registry;
        this.publisher = publisher;
        this.serializer = serializer;
        this.publishExecutor = publishExecutor;
        this.properties = properties;
        this.meters = meters;
    }

    public IntakeOutcome receive(String providerPathSegment, WebhookRequest request) {
        ProviderAdapter adapter = registry.forPathSegment(providerPathSegment);
        String provider = adapter.provider().name();
        String country = adapter.provider().country().name();

        try {
            adapter.verifySignature(request);
        } catch (InvalidSignatureException e) {
            count(provider, country, "invalid_signature");
            log.warn("webhook rejected provider={} reason=\"{}\"", provider, e.getMessage());
            throw e;
        }

        AdapterResult result;
        try {
            result = adapter.map(request);
        } catch (InvalidPayloadException e) {
            count(provider, country, "invalid_payload");
            log.error("webhook rejected provider={} reason=\"{}\"", provider, e.getMessage());
            throw e;
        }

        if (result instanceof AdapterResult.Ignored ignored) {
            count(provider, country, "ignored");
            log.info("webhook ignored provider={} providerEventId={} providerEventType={}",
                    provider, ignored.providerEventId(), ignored.providerEventType());
            return IntakeOutcome.IGNORED;
        }

        CanonicalPaymentEvent event = ((AdapterResult.Mapped) result).event();
        boolean enqueued = publishWithDeadline(toMessage(event));
        count(provider, country, enqueued ? "accepted" : "duplicate");
        log.info("webhook accepted provider={} providerEventId={} paymentKey={} status={} reference={} duplicate={}",
                provider, event.data().providerEventId(), event.data().paymentKey(), event.data().status(),
                event.data().referenceResolution(), !enqueued);
        return IntakeOutcome.ACCEPTED;
    }

    /**
     * Waits for the broker acknowledgement at most {@code publishTimeout} (2 s, below Andina's 5 s timeout).
     * If the deadline passes the provider gets 503 and retries; if the send completes later anyway, the retry
     * is dropped by duplicate detection, so nothing is lost and nothing is duplicated.
     */
    private boolean publishWithDeadline(OutboundMessage message) {
        Future<Boolean> send = publishExecutor.submit(() -> publisher.publish(message));
        try {
            return send.get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            send.cancel(true);
            throw new NotAcceptedException("broker acknowledgement timed out", e);
        } catch (ExecutionException e) {
            throw new NotAcceptedException("broker rejected the message", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotAcceptedException("interrupted while publishing", e);
        }
    }

    private OutboundMessage toMessage(CanonicalPaymentEvent event) {
        CanonicalPaymentEvent.Data data = event.data();
        return new OutboundMessage(
                data.dedupKey(),
                event.correlationid().toString(),
                event.type(),
                Map.of(
                        "provider", data.provider().name(),
                        "country", data.country().name(),
                        "status", data.status().name(),
                        "paymentKey", data.paymentKey(),
                        "referenceResolution", data.referenceResolution().name(),
                        "schemaVersion", CanonicalPaymentEvent.SCHEMA_VERSION),
                serializer.toJson(event));
    }

    private void count(String provider, String country, String outcome) {
        meters.counter("payments.intake.notifications", "provider", provider, "country", country, "outcome", outcome)
                .increment();
    }
}
