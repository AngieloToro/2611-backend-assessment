package com.meridian.payments.intake.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical payment event, CloudEvents 1.0 structured mode.
 * Contract: docs/api/events/premium-payment.schema.json. Contains no payer personal data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalPaymentEvent(
        String specversion,
        UUID id,
        String source,
        String type,
        String dataschema,
        Instant time,
        String datacontenttype,
        UUID correlationid,
        Data data) {

    public static final String TYPE = "com.meridian.payments.premium-payment.status-changed.v1";
    public static final String SCHEMA_VERSION = "1.0.0";
    public static final String DATASCHEMA = "https://schemas.meridian.example/payments/premium-payment/" + SCHEMA_VERSION;

    /**
     * Provider-independent payment data.
     *
     * @param paymentVersion set by the processor (monotonic per payment); always null at intake.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(
            String dedupKey,
            String paymentKey,
            Provider provider,
            Country country,
            String providerTransactionId,
            String providerEventId,
            PaymentStatus status,
            String providerStatus,
            Money amount,
            Money refundedAmount,
            @JsonInclude(JsonInclude.Include.ALWAYS) String policyNumber,
            String rawReference,
            ReferenceResolution referenceResolution,
            PaymentMethod paymentMethod,
            String reasonCode,
            Instant occurredAt,
            Instant receivedAt,
            Long paymentVersion) {
    }

    /** Wraps provider-independent data in the envelope. The event id is derived from the dedup key. */
    public static CanonicalPaymentEvent of(Data data, UUID correlationId) {
        return new CanonicalPaymentEvent(
                "1.0",
                DeterministicIds.eventId(data.dedupKey()),
                data.provider().eventSource(),
                TYPE,
                DATASCHEMA,
                data.occurredAt(),
                "application/json",
                correlationId,
                data);
    }
}
