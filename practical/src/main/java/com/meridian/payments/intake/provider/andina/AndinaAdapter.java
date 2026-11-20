package com.meridian.payments.intake.provider.andina;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

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
 * Andina Pay (Colombia), API version 2.3.
 *
 * <ul>
 *   <li>Signature: Base64 HMAC-SHA256 over {@code <X-Andina-Timestamp>.<raw_body>}; rejected if the timestamp
 *       differs from now by more than 10 minutes.</li>
 *   <li><b>Dedup key: {@code transaccion.id + transaccion.estado}</b>. {@code notificacion_id} changes on every
 *       retry, so it cannot detect duplicates; it is kept as the provider event id for traceability.</li>
 *   <li>Since 2.3 {@code referencia} arrives as typed by the payer and must be normalised.</li>
 * </ul>
 */
public class AndinaAdapter implements ProviderAdapter {

    static final String TIMESTAMP_HEADER = "X-Andina-Timestamp";
    static final String SIGNATURE_HEADER = "X-Andina-Signature";
    static final Duration TOLERANCE = Duration.ofMinutes(10);

    private final WebhookSecretProvider secrets;
    private final Clock clock;

    public AndinaAdapter(WebhookSecretProvider secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.ANDINA;
    }

    @Override
    public void verifySignature(WebhookRequest request) {
        String timestamp = request.header(TIMESTAMP_HEADER);
        String signature = request.header(SIGNATURE_HEADER);
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            throw new InvalidSignatureException("signature headers missing");
        }

        Instant signedAt;
        try {
            signedAt = OffsetDateTime.parse(timestamp.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidSignatureException("signature timestamp malformed");
        }
        if (Duration.between(signedAt, clock.instant()).abs().compareTo(TOLERANCE) > 0) {
            throw new InvalidSignatureException("timestamp outside tolerance");
        }

        byte[] candidate;
        try {
            candidate = Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("signature value malformed");
        }

        List<String> activeSecrets = secrets.activeSecrets(Provider.ANDINA);
        if (activeSecrets.isEmpty()) {
            throw new IllegalStateException("No webhook secret configured for Andina");
        }
        byte[] body = request.rawBody();
        for (String secret : activeSecrets) {
            // The header value is signed exactly as received (not re-formatted).
            if (Hmac.constantTimeEquals(Hmac.sha256(secret, timestamp, body), candidate)) {
                return;
            }
        }
        throw new InvalidSignatureException("signature mismatch");
    }

    @Override
    public AdapterResult map(WebhookRequest request) {
        JsonPayload json = JsonPayload.parse(request.rawBody());
        String notificationId = json.requiredId("notificacion_id");
        String transactionId = json.requiredId("transaccion.id");
        String estado = json.requiredText("transaccion.estado");

        PaymentStatus status = switch (estado) {
            case "PENDIENTE" -> PaymentStatus.PENDING;
            case "APROBADA" -> PaymentStatus.CAPTURED;
            case "RECHAZADA" -> PaymentStatus.FAILED;
            case "REVERSADA" -> PaymentStatus.REFUNDED;
            default -> null;
        };
        if (status == null) {
            return new AdapterResult.Ignored(estado, notificationId);
        }

        Money amount;
        try {
            amount = Money.fromMajorUnits(json.requiredText("transaccion.valor"), json.requiredText("transaccion.moneda"));
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException("transaccion.valor: " + e.getMessage());
        }

        Instant occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(json.requiredText("transaccion.fecha_transaccion")).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidPayloadException("transaccion.fecha_transaccion is not an ISO-8601 timestamp");
        }

        String rawReference = json.optionalText("transaccion.referencia");
        PolicyReferenceNormalizer.Result reference =
                PolicyReferenceNormalizer.normalize(rawReference, Provider.ANDINA.country());

        var data = new CanonicalPaymentEvent.Data(
                "ANDINA:" + transactionId + ":" + estado,
                "ANDINA:" + transactionId,
                Provider.ANDINA,
                Provider.ANDINA.country(),
                transactionId,
                notificationId,
                status,
                estado,
                amount,
                status == PaymentStatus.REFUNDED ? amount : null,
                reference.policyNumber(),
                PolicyReferenceNormalizer.rawForEvent(rawReference),
                reference.resolution(),
                paymentMethod(json.optionalText("transaccion.medio_pago")),
                status == PaymentStatus.REFUNDED ? json.optionalText("transaccion.motivo_reverso") : null,
                occurredAt,
                request.receivedAt(),
                null);
        // transaccion.pagador (name, national ID, email) is intentionally not read (NFR6).
        return new AdapterResult.Mapped(CanonicalPaymentEvent.of(data, request.correlationId()));
    }

    private static PaymentMethod paymentMethod(String medioPago) {
        if (medioPago == null) {
            return PaymentMethod.OTHER;
        }
        return switch (medioPago) {
            case "TARJETA" -> PaymentMethod.CARD;
            case "PSE" -> PaymentMethod.BANK_TRANSFER;
            case "EFECTIVO" -> PaymentMethod.CASH;
            default -> PaymentMethod.OTHER;
        };
    }
}
