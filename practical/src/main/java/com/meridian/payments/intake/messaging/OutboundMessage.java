package com.meridian.payments.intake.messaging;

import java.util.Map;

/**
 * A broker-neutral message, shaped after Azure Service Bus.
 *
 * @param messageId     the dedup key; the broker's duplicate detection drops a second message with the same id.
 * @param correlationId end-to-end correlation id (NFR7).
 * @param subject       CloudEvents type, usable by subscription filters.
 * @param properties    application properties for routing and operations (no personal data).
 * @param body          canonical event as JSON.
 */
public record OutboundMessage(
        String messageId,
        String correlationId,
        String subject,
        Map<String, String> properties,
        byte[] body) {
}
