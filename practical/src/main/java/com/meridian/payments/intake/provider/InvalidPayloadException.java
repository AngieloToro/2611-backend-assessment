package com.meridian.payments.intake.provider;

/**
 * An authenticated payload that cannot be interpreted. Answered with 400. The message names the field, never
 * its value, so it is safe to log.
 */
public class InvalidPayloadException extends RuntimeException {

    public InvalidPayloadException(String reason) {
        super(reason);
    }
}
