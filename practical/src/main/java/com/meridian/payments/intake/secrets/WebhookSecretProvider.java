package com.meridian.payments.intake.secrets;

import java.util.List;

import com.meridian.payments.intake.domain.Provider;

/**
 * Source of webhook signing secrets. Returns the current secret first and, during a rotation window, the
 * previous one. Implementations must never log secret values.
 */
public interface WebhookSecretProvider {

    List<String> activeSecrets(Provider provider);
}
