package com.meridian.payments.intake.domain;

/** How the policy number was obtained from the provider's payment reference. */
public enum ReferenceResolution {
    /** The reference was already a valid policy number. */
    EXACT,
    /** The reference was turned into a valid policy number (case, spaces, missing prefix or padding). */
    NORMALISED,
    /** No policy number could be derived; the payment is accepted and parked for operations. */
    UNRESOLVED
}
