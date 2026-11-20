package com.meridian.payments.intake.service;

/** Result of a successfully handled webhook; both outcomes are answered with 200. */
public enum IntakeOutcome {
    /** Durably enqueued (or already enqueued earlier: a duplicate delivery). */
    ACCEPTED,
    /** Authentic, but an event type Meridian does not process. */
    IGNORED
}
