package com.meridian.payments.intake.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.meridian.payments.intake.domain.Provider;
import com.meridian.payments.intake.provider.Hmac;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.secrets.WebhookSecretProvider;

/**
 * Provider samples and signature test vectors, read from the repository's {@code reference/} folder so the
 * tests always use the providers' published material byte for byte.
 */
public final class TestVectors {

    public static final String QUETZAL_SECRET = "qz_test_whsec_4f8a1c2e9b7d";
    public static final String ANDINA_SECRET = "andina_test_secret_7c3e19ab";

    private static final Path REFERENCE = Path.of("..", "reference", "providers");

    private TestVectors() {
    }

    public record Vector(String sample, byte[] body, Map<String, String> headers, Instant signedAt) {

        public WebhookRequest request() {
            return new WebhookRequest(body, headers, signedAt.plusSeconds(2), UUID.randomUUID());
        }

        public WebhookRequest request(Map<String, String> otherHeaders) {
            return new WebhookRequest(body, otherHeaders, signedAt.plusSeconds(2), UUID.randomUUID());
        }

        public WebhookRequest requestWithBody(byte[] otherBody) {
            return new WebhookRequest(otherBody, headers, signedAt.plusSeconds(2), UUID.randomUUID());
        }

        @Override
        public String toString() {
            return sample;
        }
    }

    // --- Quetzal Pagos (MX): reference/providers/quetzal-mx/WEBHOOK_SPEC.md ---

    public static final Vector QUETZAL_SUCCEEDED = quetzal("01-charge-succeeded.json", 1795888803L,
            "2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031");
    public static final Vector QUETZAL_FAILED = quetzal("02-charge-failed.json", 1795889403L,
            "d793d3da0a8d4d66e984ada3daa8b9f473a0eef153d99cdd83bb70f60f13ccc9");
    public static final Vector QUETZAL_REFUNDED = quetzal("03-charge-refunded.json", 1795975203L,
            "a61f9d88d861cd4cc63359b5ca6e6d11d55369db7079387dee9a5024ccb2cae6");

    public static List<Vector> quetzalVectors() {
        return List.of(QUETZAL_SUCCEEDED, QUETZAL_FAILED, QUETZAL_REFUNDED);
    }

    // --- Andina Pay (CO): reference/providers/andina-co/WEBHOOK_SPEC.md ---

    public static final Vector ANDINA_PENDING = andina("01-transaccion-pendiente.json",
            "2026-11-28T14:02:59-05:00", "JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k=");
    public static final Vector ANDINA_APPROVED = andina("02-transaccion-aprobada.json",
            "2026-11-28T14:03:11-05:00", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=");
    public static final Vector ANDINA_APPROVED_RETRY = andina("03-transaccion-aprobada-reintento.json",
            "2026-11-28T14:08:11-05:00", "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424=");
    public static final Vector ANDINA_REVERSED = andina("04-transaccion-reversada.json",
            "2026-12-03T09:15:42-05:00", "AfGO+MEffk0AWIEEE3cgyff4hWewoOaDhygCWxs/fNI=");

    public static List<Vector> andinaVectors() {
        return List.of(ANDINA_PENDING, ANDINA_APPROVED, ANDINA_APPROVED_RETRY, ANDINA_REVERSED);
    }

    // --- Signing helpers for bodies that are not published samples (negative and edge cases) ---

    public static Vector signedQuetzal(String name, byte[] body, long timestamp) {
        String signature = HexFormat.of().formatHex(Hmac.sha256(QUETZAL_SECRET, Long.toString(timestamp), body));
        return new Vector(name, body, Map.of("Quetzal-Signature", "t=" + timestamp + ",v1=" + signature),
                Instant.ofEpochSecond(timestamp));
    }

    public static Vector signedAndina(String name, byte[] body, String timestamp) {
        String signature = Base64.getEncoder().encodeToString(Hmac.sha256(ANDINA_SECRET, timestamp, body));
        return new Vector(name, body, Map.of("X-Andina-Timestamp", timestamp, "X-Andina-Signature", signature),
                OffsetDateTime.parse(timestamp).toInstant());
    }

    public static byte[] sample(String provider, String file) {
        try {
            return Files.readAllBytes(REFERENCE.resolve(provider).resolve("samples").resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Run the tests from the practical/ folder", e);
        }
    }

    public static WebhookSecretProvider secrets(String... values) {
        return provider -> List.of(values);
    }

    public static WebhookSecretProvider testSecrets() {
        return provider -> provider == Provider.QUETZAL ? List.of(QUETZAL_SECRET) : List.of(ANDINA_SECRET);
    }

    private static Vector quetzal(String file, long t, String v1) {
        return new Vector("quetzal/" + file, sample("quetzal-mx", file),
                Map.of("Quetzal-Signature", "t=" + t + ",v1=" + v1), Instant.ofEpochSecond(t));
    }

    private static Vector andina(String file, String timestamp, String signature) {
        return new Vector("andina/" + file, sample("andina-co", file),
                Map.of("X-Andina-Timestamp", timestamp, "X-Andina-Signature", signature),
                OffsetDateTime.parse(timestamp).toInstant());
    }
}
