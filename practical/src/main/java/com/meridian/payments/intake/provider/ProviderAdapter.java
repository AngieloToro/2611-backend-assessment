package com.meridian.payments.intake.provider;

import com.meridian.payments.intake.domain.Provider;

/**
 * Everything that is specific to one payment provider (NFR9). The shared flow in
 * {@link com.meridian.payments.intake.service.IntakeService} only talks to this interface.
 */
public interface ProviderAdapter {

    Provider provider();

    /**
     * Verifies authenticity exactly as the provider specifies (FR1), over the raw body.
     *
     * @throws InvalidSignatureException when the signature is missing, malformed, wrong or too old.
     */
    void verifySignature(WebhookRequest request);

    /**
     * Maps an authenticated notification to the canonical event (FR2), dropping payer personal data (NFR6).
     *
     * @return {@link AdapterResult.Mapped} or {@link AdapterResult.Ignored} for event types Meridian does not use.
     * @throws InvalidPayloadException when the payload cannot be interpreted.
     */
    AdapterResult map(WebhookRequest request);
}
