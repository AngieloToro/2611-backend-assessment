package com.meridian.payments.intake.domain;

/** Payment method category. Payment tokens are never carried. */
public enum PaymentMethod {
    CARD, BANK_TRANSFER, CASH, OTHER
}
