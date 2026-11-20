package com.meridian.payments.intake.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 helpers shared by provider adapters. */
public final class Hmac {

    private Hmac() {
    }

    /** HMAC-SHA256 of {@code <prefix>.<rawBody>}, where the prefix is a signed timestamp. */
    public static byte[] sha256(String secret, String prefix, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (prefix != null) {
                mac.update(prefix.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) '.');
            }
            return mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    /** Constant-time comparison, so timing does not reveal how many bytes matched. */
    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
