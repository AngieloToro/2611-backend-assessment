package com.meridian.payments.intake.provider;

/** No adapter is registered for the requested path. Answered with 404. */
public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException() {
        super("No adapter for this provider");
    }
}
