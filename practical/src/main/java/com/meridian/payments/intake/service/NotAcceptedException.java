package com.meridian.payments.intake.service;

/**
 * The notification could not be durably enqueued in time. Answered with 503 so that the provider retries;
 * the provider's retry policy is the buffer while the broker is unavailable.
 */
public class NotAcceptedException extends RuntimeException {

    public NotAcceptedException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
