package com.meridian.payments.intake.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Name-based UUID version 5 (RFC 9562, SHA-1). The canonical event id is derived from the dedup key, so the
 * same business event always gets the same id, including after provider retries and operator replays.
 */
public final class DeterministicIds {

    /** Fixed namespace for Meridian premium payment events. Never change it: ids would stop being stable. */
    public static final UUID PAYMENT_EVENT_NAMESPACE = UUID.fromString("3f6c1d52-8a1e-4c55-9a0b-6e2f1b7d4c90");

    private DeterministicIds() {
    }

    public static UUID eventId(String dedupKey) {
        return uuidV5(PAYMENT_EVENT_NAMESPACE, dedupKey);
    }

    public static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits());
            sha1.update(ns.array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // IETF variant
            ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
