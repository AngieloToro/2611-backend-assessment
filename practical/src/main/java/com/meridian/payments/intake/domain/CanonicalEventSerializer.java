package com.meridian.payments.intake.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises canonical events. Jackson 3 supports java.time natively and writes dates as ISO-8601 strings
 * (UTC instants end with {@code Z}) by default.
 */
public class CanonicalEventSerializer {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public byte[] toJson(CanonicalPaymentEvent event) {
        try {
            return mapper.writeValueAsBytes(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("Canonical event cannot be serialised", e);
        }
    }
}
