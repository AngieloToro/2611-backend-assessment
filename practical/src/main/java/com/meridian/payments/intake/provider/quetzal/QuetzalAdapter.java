package com.meridian.payments.intake.provider.quetzal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.meridian.payments.intake.domain.CanonicalPaymentEvent;
import com.meridian.payments.intake.domain.Money;
import com.meridian.payments.intake.domain.PaymentMethod;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.domain.Provider;
import com.meridian.payments.intake.provider.AdapterResult;
import com.meridian.payments.intake.provider.Hmac;
import com.meridian.payments.intake.provider.InvalidPayloadException;
import com.meridian.payments.intake.provider.InvalidSignatureException;
import com.meridian.payments.intake.provider.JsonPayload;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.secrets.WebhookSecretProvider;

/**
 * Quetzal Pagos (Mexico), API version 2025-06-01.
 *
 * <ul>
 *   <li>Signature: {@code Quetzal-Signature: t=<unix>,v1=<hex>[,v1=<hex>]}, HMAC-SHA256 over {@code <t>.<raw_body>};
 *       valid if any v1 matches any active secret; rejected if |now - t| > 300 s.</li>
 *   <li>Dedup key: event {@code id}, stable across retries. Payment key: {@code data.charge.id}.</li>
 *   <li>Amounts in minor units; currency in lower case.</li>
 * </ul>
 */
public class QuetzalAdapter implements ProviderAdapter {

    static final String SIGNATURE_HEADER = "Quetzal-Signature";
    static final Duration TOLERANCE = Duration.ofSeconds(300);

    private static final HexFormat HEX = HexFormat.of();

    private final WebhookSecretProvider secrets;
    private final Clock clock;

    public QuetzalAdapter(WebhookSecretProvider secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.QUETZAL;
    }

    @Override
    public void verifySignature(WebhookRequest request) {
        ParsedHeader header = parseHeader(request.header(SIGNATURE_HEADER));

        Instant signedAt = Instant.ofEpochSecond(header.timestamp());
        Duration skew = Duration.between(signedAt, clock.instant()).abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            throw new InvalidSignatureException("timestamp outside tolerance");
        }

        List<String> activeSecrets = secrets.activeSecrets(Provider.QUETZAL);
        if (activeSecrets.isEmpty()) {
            throw new IllegalStateException("No webhook secret configured for Quetzal");
        }
        byte[] body = request.rawBody();
        String signedPrefix = Long.toString(header.timestamp());
        for (String secret : activeSecrets) {
            byte[] expected = Hmac.sha256(secret, signedPrefix, body);
            for (byte[] candidate : header.signatures()) {
                if (Hmac.constantTimeEquals(expected, candidate)) {
                    return;
                }
            }
        }
        throw new InvalidSignatureException("signature mismatch");
    }

    @Override
    public AdapterResult map(WebhookRequest request) {
        JsonPayload json = JsonPayload.parse(request.rawBody());
        String eventId = json.requiredId("id");
        String type = json.requiredText("type");

        PaymentStatus status = switch (type) {
            case "charge.succeeded" -> PaymentStatus.CAPTURED;
            case "charge.failed" -> PaymentStatus.FAILED;
            case "charge.refunded" -> PaymentStatus.REFUNDED;
            default -> null;
        };
        if (status == null) {
            return new AdapterResult.Ignored(type, eventId);
        }

        long created = json.requiredLong("created");
        String chargeId = json.requiredId("data.charge.id");
        String currency = json.requiredText("data.charge.currency");
        Money amount = money(json.requiredLong("data.charge.amount"), currency, "data.charge.amount");
        String rawReference = json.optionalText("data.charge.reference");

        Instant occurredAt;
        Money refundedAmount = null;
        String reasonCode = null;
        switch (status) {
            case CAPTURED -> occurredAt = epochOr(json.optionalLong("data.charge.paid_at"), created);
            case FAILED -> {
                occurredAt = Instant.ofEpochSecond(created);
                reasonCode = json.optionalText("data.charge.failure_code");
            }
            case REFUNDED -> {
                occurredAt = epochOr(json.optionalLong("data.charge.refunded_at"), created);
                Long refunded = json.optionalLong("data.charge.amount_refunded");
                // Partial refunds are mapped to REFUNDED with the refunded amount (assumption A-5).
                refundedAmount = refunded == null ? amount : money(refunded, currency, "data.charge.amount_refunded");
            }
            default -> throw new IllegalStateException("unhandled status " + status);
        }

        PolicyReferenceNormalizer.Result reference =
                PolicyReferenceNormalizer.normalize(rawReference, Provider.QUETZAL.country());

        var data = new CanonicalPaymentEvent.Data(
                "QUETZAL:" + eventId,
                "QUETZAL:" + chargeId,
                Provider.QUETZAL,
                Provider.QUETZAL.country(),
                chargeId,
                eventId,
                status,
                type,
                amount,
                refundedAmount,
                reference.policyNumber(),
                PolicyReferenceNormalizer.rawForEvent(rawReference),
                reference.resolution(),
                paymentMethod(json.optionalText("data.charge.payment_method.type")),
                reasonCode,
                occurredAt,
                request.receivedAt(),
                null);
        // customer.email and payment_method.token are intentionally not read (NFR6).
        return new AdapterResult.Mapped(CanonicalPaymentEvent.of(data, request.correlationId()));
    }

    private static Money money(long minorUnits, String currency, String field) {
        try {
            return Money.fromMinorUnits(minorUnits, currency);
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException(field + ": " + e.getMessage());
        }
    }

    private static Instant epochOr(Long epochSeconds, long fallback) {
        return Instant.ofEpochSecond(epochSeconds != null ? epochSeconds : fallback);
    }

    private static PaymentMethod paymentMethod(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).equals("card") ? PaymentMethod.CARD : PaymentMethod.OTHER;
    }

    private record ParsedHeader(long timestamp, List<byte[]> signatures) {
    }

    private static ParsedHeader parseHeader(String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("signature header missing");
        }
        Long timestamp = null;
        List<byte[]> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new InvalidSignatureException("signature header malformed");
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            switch (key) {
                case "t" -> {
                    if (timestamp != null) {
                        throw new InvalidSignatureException("signature header has several timestamps");
                    }
                    try {
                        timestamp = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new InvalidSignatureException("signature timestamp malformed");
                    }
                }
                case "v1" -> {
                    try {
                        signatures.add(HEX.parseHex(value));
                    } catch (IllegalArgumentException e) {
                        throw new InvalidSignatureException("signature value malformed");
                    }
                }
                default -> {
                    // Other schemes (e.g. a future v2) are ignored, as recommended for forward compatibility.
                }
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("signature header incomplete");
        }
        return new ParsedHeader(timestamp, signatures);
    }
}
