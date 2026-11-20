package com.meridian.payments.intake.web;

/** Request body above the configured limit. Answered with 413. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException() {
        super("Request body too large");
    }
}
