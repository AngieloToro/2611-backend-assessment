package com.meridian.payments.intake.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a provider payment reference into a Salesforce policy number ({@code POL-<country>-<9 digits>}).
 *
 * <p>References are customer-influenced input: since Andina Pay API 2.3 the reference arrives exactly as the
 * payer typed it (e.g. {@code 000215560} or {@code pol-co-000198004}). Sending it unchanged to Salesforce
 * fails with {@code INVALID_FIELD} (INC-0412). Rules (assumption A-6):
 * <ol>
 *   <li>trim, remove inner whitespace, upper-case;</li>
 *   <li>{@code POL-<country>-<9 digits>} for the provider's country is accepted;</li>
 *   <li>1 to 9 digits are the numeric part: left-pad to 9 and add the prefix;</li>
 *   <li>anything else is {@link ReferenceResolution#UNRESOLVED}.</li>
 * </ol>
 */
public final class PolicyReferenceNormalizer {

    private static final Pattern POLICY_NUMBER = Pattern.compile("^POL-(MX|CO|CL)-\\d{9}$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{1,9}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PolicyReferenceNormalizer() {
    }

    /** Maximum length of the raw reference carried in the event (diagnosis only). */
    public static final int MAX_RAW_REFERENCE_LENGTH = 100;

    public record Result(String policyNumber, ReferenceResolution resolution) {
    }

    /** The raw reference as carried in the event: never null, bounded in length. */
    public static String rawForEvent(String rawReference) {
        if (rawReference == null) {
            return "";
        }
        return rawReference.length() <= MAX_RAW_REFERENCE_LENGTH
                ? rawReference
                : rawReference.substring(0, MAX_RAW_REFERENCE_LENGTH);
    }

    public static Result normalize(String rawReference, Country expectedCountry) {
        if (rawReference == null || rawReference.isBlank()) {
            return new Result(null, ReferenceResolution.UNRESOLVED);
        }
        String cleaned = WHITESPACE.matcher(rawReference).replaceAll("").toUpperCase(Locale.ROOT);

        if (POLICY_NUMBER.matcher(cleaned).matches()) {
            if (!cleaned.startsWith("POL-" + expectedCountry.name() + "-")) {
                // A policy of another country paid through this country's provider: never guess.
                return new Result(null, ReferenceResolution.UNRESOLVED);
            }
            ReferenceResolution resolution = cleaned.equals(rawReference)
                    ? ReferenceResolution.EXACT
                    : ReferenceResolution.NORMALISED;
            return new Result(cleaned, resolution);
        }
        if (DIGITS_ONLY.matcher(cleaned).matches()) {
            String padded = "0".repeat(9 - cleaned.length()) + cleaned;
            return new Result("POL-" + expectedCountry.name() + "-" + padded, ReferenceResolution.NORMALISED);
        }
        return new Result(null, ReferenceResolution.UNRESOLVED);
    }
}
