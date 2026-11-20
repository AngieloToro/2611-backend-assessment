package com.meridian.payments.intake.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Serialises canonical events with ISO-8601 UTC timestamps. */
public class CanonicalEventSerializer {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public byte[] toJson(CanonicalPaymentEvent event) {
        try {
            return mapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical event cannot be serialised", e);
        }
    }
}
