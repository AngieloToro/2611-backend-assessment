package com.meridian.payments.intake.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Intake configuration.
 *
 * <p>Webhook secrets are never written in configuration files. In Azure they are injected as environment
 * variables from Key Vault references resolved by Container Apps with a managed identity, e.g.
 * {@code PAYMENTS_INTAKE_PROVIDERS_QUETZAL_SECRETS_0}. Index 0 is the current secret, index 1 (optional) the
 * previous one during a rotation window.
 *
 * @param publishTimeout  maximum time to wait for the broker to acknowledge a message before answering 503.
 *                        Must stay well below the shortest provider timeout (Andina, 5 s).
 * @param maxBodyBytes    larger requests are rejected with 413.
 * @param duplicateWindow duplicate-detection window of the in-memory broker stub (mirrors Service Bus).
 * @param providers       per-provider settings, keyed by provider path segment (quetzal, andina).
 */
@ConfigurationProperties(prefix = "payments.intake")
public record IntakeProperties(
        @DefaultValue("2s") Duration publishTimeout,
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue("7d") Duration duplicateWindow,
        Map<String, ProviderSettings> providers) {

    public record ProviderSettings(List<String> secrets) {
    }
}
