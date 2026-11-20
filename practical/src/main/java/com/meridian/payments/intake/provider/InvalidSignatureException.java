package com.meridian.payments.intake.provider;

/** The request is not authentic or is too old. Answered with 401; the message never contains request data. */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String reason) {
        super(reason);
    }
}
