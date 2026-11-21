package com.meridian.payments.intake.provider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tolerant reader over a provider payload: unknown fields are ignored (e.g. Andina 2.3 {@code canal}), required
 * fields are checked explicitly. Error messages name the field, never its value.
 */
public final class JsonPayload {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_ID_LENGTH = 80;

    private final JsonNode root;

    private JsonPayload(JsonNode root) {
        this.root = root;
    }

    public static JsonPayload parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (JacksonException e) {
            throw new InvalidPayloadException("body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidPayloadException("body is not a JSON object");
        }
        return new JsonPayload(root);
    }

    /** Required non-blank text, max 80 characters (identifiers end up in Salesforce Text(80) fields). */
    public String requiredId(String path) {
        String value = requiredText(path);
        if (value.length() > MAX_ID_LENGTH) {
            throw new InvalidPayloadException(path + " is longer than " + MAX_ID_LENGTH + " characters");
        }
        return value;
    }

    public String requiredText(String path) {
        JsonNode node = node(path);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw new InvalidPayloadException(path + " is missing or not text");
        }
        return node.asString();
    }

    public String optionalText(String path) {
        JsonNode node = node(path);
        return node != null && node.isString() ? node.asString() : null;
    }

    public long requiredLong(String path) {
        JsonNode node = node(path);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidPayloadException(path + " is missing or not an integer");
        }
        return node.asLong();
    }

    public Long optionalLong(String path) {
        JsonNode node = node(path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidPayloadException(path + " is not an integer");
        }
        return node.asLong();
    }

    private JsonNode node(String dottedPath) {
        JsonNode current = root;
        for (String part : dottedPath.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }
}
