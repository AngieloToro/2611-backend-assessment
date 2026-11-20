package com.meridian.payments.intake.messaging;

/**
 * Durable publication of canonical events to the inbound queue. Returning normally means the broker
 * acknowledged the message; only then may the intake answer 2xx to the provider (NFR3).
 */
public interface EventPublisher {

    /**
     * @return {@code true} if the message was enqueued, {@code false} if the broker discarded it as a duplicate
     *         (already enqueued earlier, so it is equally durable).
     * @throws RuntimeException if the message could not be enqueued.
     */
    boolean publish(OutboundMessage message);
}
