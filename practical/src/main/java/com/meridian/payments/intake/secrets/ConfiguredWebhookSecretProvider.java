package com.meridian.payments.intake.secrets;

import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.config.IntakeProperties;
import com.meridian.payments.intake.domain.Provider;

/**
 * Reads secrets from configuration properties bound from environment variables. In Azure Container Apps those
 * environment variables are Key Vault references resolved with the app's managed identity, so the code needs
 * no Key Vault SDK and no secret is ever stored in a file. In tests the providers' sandbox secrets are used.
 */
public class ConfiguredWebhookSecretProvider implements WebhookSecretProvider {

    private final Map<String, IntakeProperties.ProviderSettings> providers;

    public ConfiguredWebhookSecretProvider(IntakeProperties properties) {
        this.providers = properties.providers() == null ? Map.of() : properties.providers();
    }

    @Override
    public List<String> activeSecrets(Provider provider) {
        IntakeProperties.ProviderSettings settings = providers.get(provider.pathSegment());
        if (settings == null || settings.secrets() == null) {
            return List.of();
        }
        return settings.secrets().stream().filter(s -> s != null && !s.isBlank()).toList();
    }
}
