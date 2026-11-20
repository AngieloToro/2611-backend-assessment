package com.meridian.payments.intake.provider.quetzal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.domain.CanonicalEventSerializer;
import com.meridian.payments.intake.domain.CanonicalPaymentEvent;
import com.meridian.payments.intake.domain.Money;
import com.meridian.payments.intake.domain.PaymentMethod;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.ReferenceResolution;
import com.meridian.payments.intake.provider.AdapterResult;
import com.meridian.payments.intake.provider.InvalidPayloadException;
import com.meridian.payments.intake.provider.InvalidSignatureException;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.TestVectors;
import com.meridian.payments.intake.support.TestVectors.Vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuetzalAdapterTest {

    private final MutableClock clock = new MutableClock(Instant.EPOCH);
    private final QuetzalAdapter adapter = new QuetzalAdapter(TestVectors.testSecrets(), clock);

    private void clockAt(Vector vector, long secondsAfterSigning) {
        clock.set(vector.signedAt().plusSeconds(secondsAfterSigning));
    }

    @Nested
    @DisplayName("Signature verification (FR1)")
    class Signature {

        static List<Vector> vectors() {
            return TestVectors.quetzalVectors();
        }

        @ParameterizedTest(name = "accepts published test vector {0}")
        @MethodSource("vectors")
        void acceptsPublishedTestVectors(Vector vector) {
            clockAt(vector, 2);
            assertThatCode(() -> adapter.verifySignature(vector.request())).doesNotThrowAnyException();
        }

        @Test
        void rejectsBodyModifiedByOneByte() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);
            byte[] tampered = vector.body().clone();
            tampered[tampered.length - 2] = (byte) ' ';

            assertThatThrownBy(() -> adapter.verifySignature(vector.requestWithBody(tampered)))
                    .isInstanceOf(InvalidSignatureException.class);
        }

        @Test
        void rejectsReSerialisedJsonEvenIfSemanticallyEqual() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);
            byte[] compact = new String(vector.body(), StandardCharsets.UTF_8)
                    .replace("\n", "").replace("  ", "").getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> adapter.verifySignature(vector.requestWithBody(compact)))
                    .isInstanceOf(InvalidSignatureException.class);
        }

        @Test
        void rejectsSignatureMadeWithAnotherSecret() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);
            QuetzalAdapter withOtherSecret = new QuetzalAdapter(TestVectors.secrets("another_secret"), clock);

            assertThatThrownBy(() -> withOtherSecret.verifySignature(vector.request()))
                    .isInstanceOf(InvalidSignatureException.class);
        }

        @Test
        void acceptsDuringRotationWhenAnyOfTwoSignaturesMatches() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);
            String header = vector.headers().get("Quetzal-Signature");
            String rotated = header.replace("t=1795888803,", "t=1795888803,v1=" + "0".repeat(64) + ",");

            assertThatCode(() -> adapter.verifySignature(vector.request(Map.of("Quetzal-Signature", rotated))))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsWhenThePreviousSecretIsStillActive() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);
            QuetzalAdapter rotating = new QuetzalAdapter(
                    TestVectors.secrets("new_secret_not_yet_used_by_provider", TestVectors.QUETZAL_SECRET), clock);

            assertThatCode(() -> rotating.verifySignature(vector.request())).doesNotThrowAnyException();
        }

        @Test
        void acceptsAtTheEdgeOfTheTolerance() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 300);
            assertThatCode(() -> adapter.verifySignature(vector.request())).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "rejects a timestamp {0} s away from now")
        @ValueSource(longs = {301, -301, 86_400})
        void rejectsTimestampsOutsideTolerance(long secondsAfterSigning) {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, secondsAfterSigning);

            assertThatThrownBy(() -> adapter.verifySignature(vector.request()))
                    .isInstanceOf(InvalidSignatureException.class)
                    .hasMessageContaining("tolerance");
        }

        @ParameterizedTest(name = "rejects header \"{0}\"")
        @ValueSource(strings = {"", "v1=abc", "t=1795888803", "t=abc,v1=00",
                "t=1795888803,v1=not-hex", "t=1795888803,t=1795888803,v1=00", "garbage"})
        void rejectsMalformedHeaders(String header) {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);

            assertThatThrownBy(() -> adapter.verifySignature(vector.request(Map.of("Quetzal-Signature", header))))
                    .isInstanceOf(InvalidSignatureException.class);
        }

        @Test
        void rejectsMissingHeader() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            clockAt(vector, 2);

            assertThatThrownBy(() -> adapter.verifySignature(vector.request(Map.of())))
                    .isInstanceOf(InvalidSignatureException.class);
        }
    }

    @Nested
    @DisplayName("Mapping to the canonical event (FR2)")
    class Mapping {

        private CanonicalPaymentEvent.Data map(Vector vector) {
            AdapterResult result = adapter.map(vector.request());
            assertThat(result).isInstanceOf(AdapterResult.Mapped.class);
            return ((AdapterResult.Mapped) result).event().data();
        }

        @Test
        void mapsChargeSucceededToCapturedWithMinorUnitsConverted() {
            CanonicalPaymentEvent.Data data = map(TestVectors.QUETZAL_SUCCEEDED);

            assertThat(data.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(data.amount()).isEqualTo(new Money("1250.00", "MXN"));
            assertThat(data.paymentKey()).isEqualTo("QUETZAL:ch_7Hk2Lm9QpZ");
            assertThat(data.dedupKey()).isEqualTo("QUETZAL:evt_01JDQ3K8M2X7");
            assertThat(data.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
            assertThat(data.policyNumber()).isEqualTo("POL-MX-000412785");
            assertThat(data.referenceResolution()).isEqualTo(ReferenceResolution.EXACT);
            assertThat(data.paymentMethod()).isEqualTo(PaymentMethod.CARD);
            assertThat(data.occurredAt()).isEqualTo(Instant.ofEpochSecond(1795888796)); // paid_at
            assertThat(data.refundedAmount()).isNull();
        }

        @Test
        void mapsChargeFailedWithItsReason() {
            CanonicalPaymentEvent.Data data = map(TestVectors.QUETZAL_FAILED);

            assertThat(data.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(data.amount()).isEqualTo(new Money("980.00", "MXN"));
            assertThat(data.reasonCode()).isEqualTo("insufficient_funds");
            assertThat(data.occurredAt()).isEqualTo(Instant.ofEpochSecond(1795889400)); // created
        }

        @Test
        void mapsChargeRefundedToRefundedOnTheSamePaymentKey() {
            CanonicalPaymentEvent.Data data = map(TestVectors.QUETZAL_REFUNDED);

            assertThat(data.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(data.paymentKey()).isEqualTo("QUETZAL:ch_7Hk2Lm9QpZ");
            assertThat(data.refundedAmount()).isEqualTo(new Money("1250.00", "MXN"));
            assertThat(data.occurredAt()).isEqualTo(Instant.ofEpochSecond(1795975195)); // refunded_at
        }

        @Test
        void retriedDeliveryProducesTheSameEventId() {
            Vector vector = TestVectors.QUETZAL_SUCCEEDED;
            CanonicalPaymentEvent first = ((AdapterResult.Mapped) adapter.map(vector.request())).event();
            CanonicalPaymentEvent retry = ((AdapterResult.Mapped) adapter.map(vector.request())).event();

            assertThat(retry.id()).isEqualTo(first.id());
            assertThat(retry.data().dedupKey()).isEqualTo(first.data().dedupKey());
        }

        @Test
        void ignoresEventTypesMeridianDoesNotUse() {
            byte[] body = """
                    {"id":"evt_x","type":"customer.updated","created":1795888800,"data":{"customer":{"id":"c1"}}}"""
                    .getBytes(StandardCharsets.UTF_8);
            Vector vector = TestVectors.signedQuetzal("unknown-type", body, 1795888800);

            assertThat(adapter.map(vector.request()))
                    .isEqualTo(new AdapterResult.Ignored("customer.updated", "evt_x"));
        }

        @ParameterizedTest(name = "rejects payload: {0}")
        @ValueSource(strings = {
                "not json",
                "[]",
                "{\"type\":\"charge.succeeded\",\"created\":1,\"data\":{\"charge\":{\"id\":\"c\",\"amount\":1,\"currency\":\"mxn\"}}}",
                "{\"id\":\"e\",\"type\":\"charge.succeeded\",\"created\":1,\"data\":{\"charge\":{\"amount\":1,\"currency\":\"mxn\"}}}",
                "{\"id\":\"e\",\"type\":\"charge.succeeded\",\"created\":1,\"data\":{\"charge\":{\"id\":\"c\",\"amount\":\"1\",\"currency\":\"mxn\"}}}",
                "{\"id\":\"e\",\"type\":\"charge.succeeded\",\"created\":1,\"data\":{\"charge\":{\"id\":\"c\",\"amount\":-5,\"currency\":\"mxn\"}}}",
                "{\"id\":\"e\",\"type\":\"charge.succeeded\",\"created\":1,\"data\":{\"charge\":{\"id\":\"c\",\"amount\":1,\"currency\":\"xyz\"}}}"
        })
        void rejectsPayloadsThatCannotBeInterpreted(String body) {
            Vector vector = TestVectors.signedQuetzal("invalid", body.getBytes(StandardCharsets.UTF_8), 1795888800);

            assertThatThrownBy(() -> adapter.map(vector.request())).isInstanceOf(InvalidPayloadException.class);
        }

        @Test
        void neverCarriesPayerPersonalDataOrPaymentTokens() {
            for (Vector vector : TestVectors.quetzalVectors()) {
                CanonicalPaymentEvent event = ((AdapterResult.Mapped) adapter.map(vector.request())).event();
                String json = new String(new CanonicalEventSerializer().toJson(event), StandardCharsets.UTF_8);

                assertThat(json).doesNotContain("@example.com", "tok_qz_", "customer");
            }
        }
    }
}
