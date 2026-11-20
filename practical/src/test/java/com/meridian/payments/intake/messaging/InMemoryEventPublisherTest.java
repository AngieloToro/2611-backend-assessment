package com.meridian.payments.intake.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.meridian.payments.intake.support.MutableClock;

import org.junit.jupiter.api.Test;

class InMemoryEventPublisherTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-28T19:00:00Z"));
    private final InMemoryEventPublisher publisher = new InMemoryEventPublisher(Duration.ofDays(7), clock);

    private static OutboundMessage message(String id) {
        return new OutboundMessage(id, "c", "t", Map.of(), new byte[0]);
    }

    @Test
    void dropsDuplicateMessageIdsInsideTheWindow() {
        assertThat(publisher.publish(message("ANDINA:TRX-1:APROBADA"))).isTrue();
        clock.set(clock.instant().plus(Duration.ofDays(6)));

        assertThat(publisher.publish(message("ANDINA:TRX-1:APROBADA"))).isFalse();
        assertThat(publisher.enqueued()).hasSize(1);
    }

    @Test
    void acceptsTheSameIdAgainAfterTheWindow() {
        publisher.publish(message("QUETZAL:evt_1"));
        clock.set(clock.instant().plus(Duration.ofDays(7)).plusSeconds(1));

        assertThat(publisher.publish(message("QUETZAL:evt_1"))).isTrue();
        assertThat(publisher.enqueued()).hasSize(2);
    }
}
