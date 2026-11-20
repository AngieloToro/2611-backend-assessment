package com.meridian.payments.intake.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Amount in major units as a decimal string, scaled to the ISO 4217 minor units of its currency
 * (MXN 2, COP 2, CLP 0). Never a binary floating point number.
 */
public record Money(String value, String currency) {

    /** From an integer amount in minor units, e.g. Quetzal {@code 125000 mxn} = MXN 1250.00. */
    public static Money fromMinorUnits(long minorUnits, String currencyCode) {
        if (minorUnits < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        Currency currency = currency(currencyCode);
        BigDecimal major = BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
        return new Money(major.toPlainString(), currency.getCurrencyCode());
    }

    /**
     * From a decimal string in major units, e.g. Andina {@code "185000.00" COP} or Austral {@code "45990" CLP}.
     * Rejects values with more decimals than the currency allows instead of silently rounding money.
     */
    public static Money fromMajorUnits(String decimal, String currencyCode) {
        Currency currency = currency(currencyCode);
        if (decimal == null) {
            throw new IllegalArgumentException("amount is missing");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(decimal);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount is not a decimal number");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        try {
            value = value.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount has more decimals than " + currency.getCurrencyCode() + " allows");
        }
        return new Money(value.toPlainString(), currency.getCurrencyCode());
    }

    private static Currency currency(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("currency is missing");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown currency code");
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("currency has no minor unit definition");
        }
        return currency;
    }
}
