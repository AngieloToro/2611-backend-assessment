package com.meridian.payments.intake.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.messaging.InMemoryEventPublisher;
import com.meridian.payments.intake.messaging.OutboundMessage;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.TestVectors;
import com.meridian.payments.intake.support.TestVectors.Vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end through HTTP: raw sample bytes and published signatures in, canonical events on the (stubbed)
 * queue out. Covers acceptance only after durable enqueue (NFR3), the deadline below provider timeouts (NFR4),
 * duplicates and retries (FR4) and the absence of personal data in logs (NFR6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WebhookIntakeIntegrationTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.EPOCH);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @MockitoSpyBean
    private InMemoryEventPublisher queue;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void emptyQueue() {
        queue.clear();
    }

    private ResultActions deliver(String provider, Vector vector) throws Exception {
        clock.set(vector.signedAt().plusSeconds(2));
        MockHttpServletRequestBuilder request = post("/payments/webhooks/v1/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(vector.body());
        vector.headers().forEach(request::header);
        return mvc.perform(request);
    }

    private JsonNode body(OutboundMessage message) throws Exception {
        return json.readTree(message.body());
    }

    @Test
    void acceptsAValidQuetzalNotificationOnlyAfterItIsEnqueued() throws Exception {
        UUID correlationId = UUID.randomUUID();
        clock.set(TestVectors.QUETZAL_SUCCEEDED.signedAt().plusSeconds(2));
        MockHttpServletRequestBuilder request = post("/payments/webhooks/v1/quetzal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TestVectors.QUETZAL_SUCCEEDED.body())
                .header("X-Correlation-Id", correlationId.toString());
        TestVectors.QUETZAL_SUCCEEDED.headers().forEach(request::header);

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(header().string("X-Correlation-Id", correlationId.toString()));

        assertThat(queue.enqueued()).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo("QUETZAL:evt_01JDQ3K8M2X7");
            assertThat(message.correlationId()).isEqualTo(correlationId.toString());
            assertThat(message.properties()).containsEntry("country", "MX").containsEntry("status", "CAPTURED");
            JsonNode event = body(message);
            assertThat(event.at("/correlationid").asText()).isEqualTo(correlationId.toString());
            assertThat(event.at("/data/amount/value").asText()).isEqualTo("1250.00");
        });
    }

    @Test
    void acceptsAValidAndinaNotification() throws Exception {
        deliver("andina", TestVectors.ANDINA_APPROVED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(queue.enqueued()).singleElement()
                .satisfies(m -> assertThat(m.messageId()).isEqualTo("ANDINA:TRX-CO-5521907:APROBADA"));
    }

    @Test
    void quetzalRedeliveryOfTheSameEventIsAcknowledgedButEnqueuedOnce() throws Exception {
        deliver("quetzal", TestVectors.QUETZAL_SUCCEEDED).andExpect(status().isOk());
        deliver("quetzal", TestVectors.QUETZAL_SUCCEEDED).andExpect(status().isOk());

        assertThat(queue.enqueued()).hasSize(1);
    }

    @Test
    void andinaRetryWithANewNotificationIdIsAcknowledgedButEnqueuedOnce() throws Exception {
        deliver("andina", TestVectors.ANDINA_APPROVED).andExpect(status().isOk());
        deliver("andina", TestVectors.ANDINA_APPROVED_RETRY).andExpect(status().isOk());

        assertThat(queue.enqueued()).singleElement()
                .satisfies(m -> assertThat(m.messageId()).isEqualTo("ANDINA:TRX-CO-5521907:APROBADA"));
    }

    @Test
    void outOfOrderNotificationsAreAllEnqueuedSoTheProcessorCanResolveOrder() throws Exception {
        deliver("andina", TestVectors.ANDINA_REVERSED).andExpect(status().isOk());
        deliver("andina", TestVectors.ANDINA_APPROVED).andExpect(status().isOk());
        deliver("andina", TestVectors.ANDINA_PENDING).andExpect(status().isOk());

        assertThat(queue.enqueued()).extracting(m -> m.properties().get("status"))
                .containsExactly("REFUNDED", "CAPTURED", "PENDING");
        assertThat(queue.enqueued()).extracting(m -> m.properties().get("paymentKey"))
                .containsOnly("ANDINA:TRX-CO-5521907");
    }

    @Test
    void rejectsAnInvalidSignatureAndEnqueuesNothing() throws Exception {
        byte[] tampered = new String(TestVectors.ANDINA_APPROVED.body(), StandardCharsets.UTF_8)
                .replace("APROBADA", "REVERSADA").getBytes(StandardCharsets.UTF_8);
        Vector forged = new Vector("forged", tampered, TestVectors.ANDINA_APPROVED.headers(),
                TestVectors.ANDINA_APPROVED.signedAt());

        deliver("andina", forged)
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.correlationId").exists());

        assertThat(queue.enqueued()).isEmpty();
    }

    @Test
    void rejectsAReplayedRequestOutsideTheToleranceWindow() throws Exception {
        clock.set(TestVectors.QUETZAL_SUCCEEDED.signedAt().plus(Duration.ofHours(1)));
        MockHttpServletRequestBuilder request = post("/payments/webhooks/v1/quetzal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TestVectors.QUETZAL_SUCCEEDED.body());
        TestVectors.QUETZAL_SUCCEEDED.headers().forEach(request::header);

        mvc.perform(request).andExpect(status().isUnauthorized());
        assertThat(queue.enqueued()).isEmpty();
    }

    @Test
    void answers503WhenTheBrokerFailsSoTheProviderRetries() throws Exception {
        doThrow(new IllegalStateException("service bus unavailable"))
                .when(queue).publish(argThat(m -> true));

        deliver("andina", TestVectors.ANDINA_APPROVED)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"));

        assertThat(queue.enqueued()).isEmpty();
    }

    @Test
    void answers503WithinTheDeadlineWhenTheBrokerIsSlow() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(3_000);
            return invocation.callRealMethod();
        }).when(queue).publish(argThat(m -> true));

        long start = System.nanoTime();
        deliver("andina", TestVectors.ANDINA_APPROVED).andExpect(status().isServiceUnavailable());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Test profile deadline is 500 ms; production uses 2 s. Both are far below Andina's 5 s timeout.
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void rejectsAPayloadThatCannotBeInterpreted() throws Exception {
        Vector vector = TestVectors.signedAndina("not-json", "{\"notificacion_id\":".getBytes(StandardCharsets.UTF_8),
                "2026-11-28T14:03:11-05:00");

        deliver("andina", vector).andExpect(status().isBadRequest());
        assertThat(queue.enqueued()).isEmpty();
    }

    @Test
    void acknowledgesAndIgnoresUnknownEventTypes() throws Exception {
        byte[] body = "{\"id\":\"evt_x\",\"type\":\"payout.paid\",\"created\":1795888800,\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8);

        deliver("quetzal", TestVectors.signedQuetzal("unknown", body, 1795888800))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));
        assertThat(queue.enqueued()).isEmpty();
    }

    @Test
    void rejectsUnknownProvidersAndOversizedBodies() throws Exception {
        deliver("austral", TestVectors.QUETZAL_SUCCEEDED).andExpect(status().isNotFound());

        byte[] huge = new byte[70_000];
        java.util.Arrays.fill(huge, (byte) ' ');
        deliver("quetzal", TestVectors.signedQuetzal("huge", huge, 1795888800))
                .andExpect(status().isPayloadTooLarge());

        assertThat(queue.enqueued()).isEmpty();
    }

    @Test
    void neverWritesPayerPersonalDataToTheLogs(CapturedOutput output) throws Exception {
        for (Vector vector : TestVectors.andinaVectors()) {
            deliver("andina", vector);
        }
        for (Vector vector : TestVectors.quetzalVectors()) {
            deliver("quetzal", vector);
        }
        deliver("andina", new Vector("forged", "{\"x\":\"laura.gomez@example.com\"}".getBytes(StandardCharsets.UTF_8),
                TestVectors.ANDINA_APPROVED.headers(), TestVectors.ANDINA_APPROVED.signedAt()));

        assertThat(output.getAll())
                .contains("webhook accepted")
                .doesNotContain("Laura", "Gómez", "1020304050", "@example.com", "tok_qz_", "Signature=");
    }
}
