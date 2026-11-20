package com.meridian.payments.intake.provider;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An incoming webhook exactly as received: raw body bytes (signatures are computed over them, so the body is
 * never re-serialised) and headers with case-insensitive lookup.
 */
public final class WebhookRequest {

    private final byte[] rawBody;
    private final Map<String, String> headers;
    private final Instant receivedAt;
    private final UUID correlationId;

    public WebhookRequest(byte[] rawBody, Map<String, String> headers, Instant receivedAt, UUID correlationId) {
        this.rawBody = Objects.requireNonNull(rawBody, "rawBody").clone();
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        this.headers = copy;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    public byte[] rawBody() {
        return rawBody.clone();
    }

    public String header(String name) {
        return headers.get(name);
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public UUID correlationId() {
        return correlationId;
    }

    /** Deliberately does not print the body or the headers: they may contain personal data or signatures. */
    @Override
    public String toString() {
        return "WebhookRequest[bytes=" + rawBody.length + ", correlationId=" + correlationId + "]";
    }
}
