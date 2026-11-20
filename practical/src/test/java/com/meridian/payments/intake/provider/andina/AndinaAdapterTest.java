package com.meridian.payments.intake.provider.andina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AndinaAdapterTest {

    private final MutableClock clock = new MutableClock(Instant.EPOCH);
    private final AndinaAdapter adapter = new AndinaAdapter(TestVectors.testSecrets(), clock);

    private void clockAt(Vector vector, long secondsAfterSigning) {
        clock.set(vector.signedAt().plusSeconds(secondsAfterSigning));
    }

    private CanonicalPaymentEvent mapped(Vector vector) {
        AdapterResult result = adapter.map(vector.request());
        assertThat(result).isInstanceOf(AdapterResult.Mapped.class);
        return ((AdapterResult.Mapped) result).event();
    }

    /** Builds an Andina 2.3 notification with a given reference and state, signed with the test secret. */
    private static Vector notification(String referencia, String estado) {
        String body = """
                {"notificacion_id":"ntf-test","fecha_notificacion":"2026-11-29T10:17:55-05:00",\
                "transaccion":{"id":"TRX-CO-5611042","estado":"%s","valor":"98500.00","moneda":"COP",\
                "referencia":"%s","medio_pago":"PSE","canal":"APP",\
                "fecha_transaccion":"2026-11-29T10:17:50-05:00",\
                "pagador":{"nombre":"Test","documento":"CC 0","correo":"t@example.com"}}}"""
                .formatted(estado, referencia);
        return TestVectors.signedAndina("custom", body.getBytes(StandardCharsets.UTF_8), "2026-11-29T10:17:55-05:00");
    }

    @Nested
    @DisplayName("Signature verification (FR1)")
    class Signature {

        static List<Vector> vectors() {
            return TestVectors.andinaVectors();
        }

        @ParameterizedTest(name = "accepts published test vector {0}")
        @MethodSource("vectors")
        void acceptsPublishedTestVectors(Vector vector) {
            clockAt(vector, 2);
            assertThatCode(() -> adapter.verifySignature(vector.request())).doesNotThrowAnyException();
        }

        @Test
        void rejectsWhenTheTimestampHeaderIsChangedBecauseItIsPartOfTheSignedContent() {
            Vector vector = TestVectors.ANDINA_APPROVED;
            clockAt(vector, 2);
            Map<String, String> headers = new HashMap<>(vector.headers());
            headers.put("X-Andina-Timestamp", "2026-11-28T14:03:12-05:00");

            assertThatThrownBy(() -> adapter.verifySignature(vector.request(headers)))
                    .isInstanceOf(InvalidSignatureException.class)
                    .hasMessage("signature mismatch");
        }

        @Test
        void rejectsBodyModifiedByOneByte() {
            Vector vector = TestVectors.ANDINA_APPROVED;
            clockAt(vector, 2);
            byte[] tampered = new String(vector.body(), StandardCharsets.UTF_8)
                    .replace("185000.00", "185000.01").getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> adapter.verifySignature(vector.requestWithBody(tampered)))
                    .isInstanceOf(InvalidSignatureException.class);
        }

        @Test
        void acceptsTenMinutesOfSkewAndRejectsMore() {
            Vector vector = TestVectors.ANDINA_APPROVED;

            clockAt(vector, 600);
            assertThatCode(() -> adapter.verifySignature(vector.request())).doesNotThrowAnyException();

            clockAt(vector, 601);
            assertThatThrownBy(() -> adapter.verifySignature(vector.request()))
                    .isInstanceOf(InvalidSignatureException.class)
                    .hasMessageContaining("tolerance");
        }

        @Test
        void acceptsWhenThePreviousSecretIsStillActive() {
            Vector vector = TestVectors.ANDINA_APPROVED;
            clockAt(vector, 2);
            AndinaAdapter rotating = new AndinaAdapter(
                    TestVectors.secrets("new_andina_secret", TestVectors.ANDINA_SECRET), clock);

            assertThatCode(() -> rotating.verifySignature(vector.request())).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "rejects headers {0}")
        @MethodSource("badHeaders")
        void rejectsMissingOrMalformedHeaders(Map<String, String> headers) {
            Vector vector = TestVectors.ANDINA_APPROVED;
            clockAt(vector, 2);

            assertThatThrownBy(() -> adapter.verifySignature(vector.request(headers)))
                    .isInstanceOf(InvalidSignatureException.class);
        }

        static Stream<Map<String, String>> badHeaders() {
            String ts = "2026-11-28T14:03:11-05:00";
            String sig = "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=";
            return Stream.of(
                    Map.of(),
                    Map.of("X-Andina-Signature", sig),
                    Map.of("X-Andina-Timestamp", ts),
                    Map.of("X-Andina-Timestamp", "yesterday", "X-Andina-Signature", sig),
                    Map.of("X-Andina-Timestamp", ts, "X-Andina-Signature", "%%%not-base64%%%"));
        }
    }

    @Nested
    @DisplayName("Mapping to the canonical event (FR2)")
    class Mapping {

        static Stream<Arguments> statesBySample() {
            return Stream.of(
                    Arguments.of(TestVectors.ANDINA_PENDING, PaymentStatus.PENDING),
                    Arguments.of(TestVectors.ANDINA_APPROVED, PaymentStatus.CAPTURED),
                    Arguments.of(TestVectors.ANDINA_APPROVED_RETRY, PaymentStatus.CAPTURED),
                    Arguments.of(TestVectors.ANDINA_REVERSED, PaymentStatus.REFUNDED));
        }

        @ParameterizedTest(name = "{0} maps to {1}")
        @MethodSource("statesBySample")
        void mapsEveryLifecycleStateOfTheSameTransactionToOnePaymentKey(Vector vector, PaymentStatus expected) {
            CanonicalPaymentEvent.Data data = mapped(vector).data();

            assertThat(data.status()).isEqualTo(expected);
            assertThat(data.paymentKey()).isEqualTo("ANDINA:TRX-CO-5521907");
            assertThat(data.amount()).isEqualTo(new Money("185000.00", "COP"));
            assertThat(data.policyNumber()).isEqualTo("POL-CO-000187342");
            assertThat(data.paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        }

        @Test
        void retryWithANewNotificationIdIsTheSameBusinessEvent() {
            CanonicalPaymentEvent original = mapped(TestVectors.ANDINA_APPROVED);
            CanonicalPaymentEvent retry = mapped(TestVectors.ANDINA_APPROVED_RETRY);

            assertThat(retry.data().providerEventId()).isNotEqualTo(original.data().providerEventId());
            assertThat(retry.data().dedupKey()).isEqualTo(original.data().dedupKey())
                    .isEqualTo("ANDINA:TRX-CO-5521907:APROBADA");
            assertThat(retry.id()).isEqualTo(original.id());
        }

        @Test
        void differentStatesOfTheSameTransactionAreDifferentBusinessEvents() {
            assertThat(mapped(TestVectors.ANDINA_PENDING).id())
                    .isNotEqualTo(mapped(TestVectors.ANDINA_APPROVED).id());
        }

        @Test
        void convertsTheStateChangeTimeToUtc() {
            assertThat(mapped(TestVectors.ANDINA_APPROVED).data().occurredAt())
                    .isEqualTo(Instant.parse("2026-11-28T19:03:09Z"));
        }

        @Test
        void mapsReversalWithRefundedAmountAndReason() {
            CanonicalPaymentEvent.Data data = mapped(TestVectors.ANDINA_REVERSED).data();

            assertThat(data.refundedAmount()).isEqualTo(new Money("185000.00", "COP"));
            assertThat(data.reasonCode()).isEqualTo("SOLICITUD_BANCO");
        }

        @ParameterizedTest(name = "API 2.3 reference \"{0}\" -> {1} ({2})")
        @MethodSource("references")
        void normalisesReferencesTypedByThePayer(String referencia, String expectedPolicy, ReferenceResolution resolution) {
            CanonicalPaymentEvent.Data data = mapped(notification(referencia, "APROBADA")).data();

            assertThat(data.policyNumber()).isEqualTo(expectedPolicy);
            assertThat(data.referenceResolution()).isEqualTo(resolution);
            assertThat(data.rawReference()).isEqualTo(referencia);
        }

        static Stream<Arguments> references() {
            return Stream.of(
                    // The three references from INC-0412 and the format before API 2.3
                    Arguments.of("000215560", "POL-CO-000215560", ReferenceResolution.NORMALISED),
                    Arguments.of("pol-co-000198004", "POL-CO-000198004", ReferenceResolution.NORMALISED),
                    Arguments.of("POL-CO-000187342", "POL-CO-000187342", ReferenceResolution.EXACT),
                    Arguments.of("unknown text", null, ReferenceResolution.UNRESOLVED));
        }

        @Test
        void unresolvedReferenceIsStillAcceptedBecauseTheMoneyMoved() {
            AdapterResult result = adapter.map(notification("mi poliza", "APROBADA").request());

            assertThat(result).isInstanceOf(AdapterResult.Mapped.class);
        }

        @Test
        void ignoresUnknownFieldsAddedByTheProvider() {
            // notification() includes "canal", added in API 2.3
            assertThatCode(() -> adapter.map(notification("000215560", "APROBADA").request()))
                    .doesNotThrowAnyException();
        }

        @Test
        void ignoresUnknownStates() {
            assertThat(adapter.map(notification("000215560", "EN_DISPUTA").request()))
                    .isEqualTo(new AdapterResult.Ignored("EN_DISPUTA", "ntf-test"));
        }

        @ParameterizedTest(name = "rejects invalid amount \"{0}\"")
        @ValueSource(strings = {"abc", "-1.00", "10.001", ""})
        void rejectsInvalidAmounts(String valor) {
            String body = new String(TestVectors.ANDINA_APPROVED.body(), StandardCharsets.UTF_8)
                    .replace("\"185000.00\"", "\"" + valor + "\"");
            Vector vector = TestVectors.signedAndina("bad-amount", body.getBytes(StandardCharsets.UTF_8),
                    "2026-11-28T14:03:11-05:00");

            assertThatThrownBy(() -> adapter.map(vector.request())).isInstanceOf(InvalidPayloadException.class);
        }

        @Test
        void neverCarriesPayerPersonalData() {
            for (Vector vector : TestVectors.andinaVectors()) {
                String json = new String(new CanonicalEventSerializer().toJson(mapped(vector)), StandardCharsets.UTF_8);

                assertThat(json).doesNotContain("Laura", "1020304050", "@example.com", "pagador");
            }
        }
    }
}
