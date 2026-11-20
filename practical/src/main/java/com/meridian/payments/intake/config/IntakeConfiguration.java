package com.meridian.payments.intake.config;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.meridian.payments.intake.domain.CanonicalEventSerializer;
import com.meridian.payments.intake.messaging.InMemoryEventPublisher;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.ProviderRegistry;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.secrets.ConfiguredWebhookSecretProvider;
import com.meridian.payments.intake.secrets.WebhookSecretProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IntakeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WebhookSecretProvider webhookSecretProvider(IntakeProperties properties) {
        return new ConfiguredWebhookSecretProvider(properties);
    }

    @Bean
    QuetzalAdapter quetzalAdapter(WebhookSecretProvider secrets, Clock clock) {
        return new QuetzalAdapter(secrets, clock);
    }

    @Bean
    AndinaAdapter andinaAdapter(WebhookSecretProvider secrets, Clock clock) {
        return new AndinaAdapter(secrets, clock);
    }

    /** Adding a provider = adding an adapter bean. The shared flow does not change (NFR9). */
    @Bean
    ProviderRegistry providerRegistry(List<ProviderAdapter> adapters) {
        return new ProviderRegistry(adapters);
    }

    @Bean
    CanonicalEventSerializer canonicalEventSerializer() {
        return new CanonicalEventSerializer();
    }

    /**
     * Broker stub. In Azure this bean is replaced by a Service Bus implementation (same interface) that sends
     * with the managed identity of the intake app; see practical/README.md.
     */
    @Bean
    InMemoryEventPublisher eventPublisher(IntakeProperties properties, Clock clock) {
        return new InMemoryEventPublisher(properties.duplicateWindow(), clock);
    }

    @Bean(destroyMethod = "close")
    ExecutorService publishExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
