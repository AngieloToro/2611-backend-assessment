package com.meridian.payments.intake.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub of the Azure Service Bus inbound queue. It reproduces the behaviour the design relies on: duplicate
 * detection by {@code MessageId} within a time window. Thread-safe.
 *
 * <p>Production replaces it with a Service Bus sender (Azure SDK, {@code DefaultAzureCredential} with the
 * app's managed identity, queue configured with {@code requiresDuplicateDetection=true} and a 7-day window).
 */
public class InMemoryEventPublisher implements EventPublisher {

    private final Duration duplicateWindow;
    private final Clock clock;
    private final Map<String, Instant> seenMessageIds = new ConcurrentHashMap<>();
    private final List<OutboundMessage> enqueued = new ArrayList<>();

    public InMemoryEventPublisher(Duration duplicateWindow, Clock clock) {
        this.duplicateWindow = duplicateWindow;
        this.clock = clock;
    }

    @Override
    public synchronized boolean publish(OutboundMessage message) {
        Instant now = clock.instant();
        Instant firstSeen = seenMessageIds.get(message.messageId());
        if (firstSeen != null && firstSeen.plus(duplicateWindow).isAfter(now)) {
            return false;
        }
        seenMessageIds.put(message.messageId(), now);
        enqueued.add(message);
        return true;
    }

    /** Messages currently in the queue, in enqueue order (test and local inspection only). */
    public synchronized List<OutboundMessage> enqueued() {
        return List.copyOf(enqueued);
    }

    public synchronized void clear() {
        enqueued.clear();
        seenMessageIds.clear();
    }
}
