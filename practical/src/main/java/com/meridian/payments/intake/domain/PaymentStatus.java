package com.meridian.payments.intake.domain;

/**
 * Canonical payment status. The rank is the precedence used by the processor to resolve late or
 * out-of-order notifications (SOLUTION_DESIGN section 5.3); the intake only carries it.
 */
public enum PaymentStatus {
    PENDING(1),
    CAPTURED(2),
    FAILED(2),
    REFUNDED(3);

    private final int rank;

    PaymentStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
