package com.meridian.payments.intake.provider;

import com.meridian.payments.intake.domain.CanonicalPaymentEvent;

/** Outcome of mapping an authenticated notification. */
public sealed interface AdapterResult {

    /** The notification maps to a canonical event that must be published. */
    record Mapped(CanonicalPaymentEvent event) implements AdapterResult {
    }

    /**
     * The provider sent an event type or state Meridian does not handle. It is acknowledged (so the provider
     * does not retry forever) and counted, but not published.
     */
    record Ignored(String providerEventType, String providerEventId) implements AdapterResult {
    }
}
