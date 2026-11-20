package com.meridian.payments.intake.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomainTest {

    @Nested
    class PolicyReferences {

        @ParameterizedTest(name = "[{1}] \"{0}\" -> {2} ({3})")
        @CsvSource(nullValues = "NULL", value = {
                "POL-CO-000187342,  CO, POL-CO-000187342, EXACT",
                "pol-co-000198004,  CO, POL-CO-000198004, NORMALISED",
                "' POL-CO-000187342 ', CO, POL-CO-000187342, NORMALISED",
                "POL-CO 000187342,  CO, NULL,             UNRESOLVED",
                "000215560,         CO, POL-CO-000215560, NORMALISED",
                "215560,            CO, POL-CO-000215560, NORMALISED",
                "1234567890,        CO, NULL,             UNRESOLVED",
                "POL-MX-000412785,  CO, NULL,             UNRESOLVED",
                "POL-MX-000412785,  MX, POL-MX-000412785, EXACT",
                "CC 1020304050,     CO, NULL,             UNRESOLVED",
                "'',                CO, NULL,             UNRESOLVED",
                "NULL,              CO, NULL,             UNRESOLVED"
        })
        void normalisesOrRefusesToGuess(String raw, Country country, String expected, ReferenceResolution resolution) {
            PolicyReferenceNormalizer.Result result = PolicyReferenceNormalizer.normalize(raw, country);

            assertThat(result.policyNumber()).isEqualTo(expected);
            assertThat(result.resolution()).isEqualTo(resolution);
        }

        @Test
        void boundsTheRawReferenceCarriedInTheEvent() {
            assertThat(PolicyReferenceNormalizer.rawForEvent("x".repeat(500))).hasSize(100);
            assertThat(PolicyReferenceNormalizer.rawForEvent(null)).isEmpty();
        }
    }

    @Nested
    class Amounts {

        @Test
        void convertsMinorUnitsUsingTheCurrencyDefinition() {
            assertThat(Money.fromMinorUnits(125000, "mxn")).isEqualTo(new Money("1250.00", "MXN"));
            assertThat(Money.fromMinorUnits(45990, "CLP")).isEqualTo(new Money("45990", "CLP"));
        }

        @Test
        void keepsMajorUnitsExactAndScaled() {
            assertThat(Money.fromMajorUnits("185000.00", "COP")).isEqualTo(new Money("185000.00", "COP"));
            assertThat(Money.fromMajorUnits("185000", "COP")).isEqualTo(new Money("185000.00", "COP"));
            assertThat(Money.fromMajorUnits("45990", "CLP")).isEqualTo(new Money("45990", "CLP"));
        }

        @Test
        void neverRoundsMoneySilently() {
            assertThatThrownBy(() -> Money.fromMajorUnits("10.005", "COP")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.fromMajorUnits("45990.50", "CLP")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeAndUnknown() {
            assertThatThrownBy(() -> Money.fromMinorUnits(-1, "MXN")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.fromMajorUnits("1.00", "ABC")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.fromMajorUnits(null, "COP")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class EventIds {

        @Test
        void implementsUuidVersion5() {
            // Reference value: uuid5(NAMESPACE_DNS, "python.org") from the Python standard library.
            UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

            assertThat(DeterministicIds.uuidV5(dns, "python.org"))
                    .isEqualTo(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"));
        }

        @Test
        void sameDedupKeyAlwaysGivesTheSameEventId() {
            assertThat(DeterministicIds.eventId("ANDINA:TRX-CO-5521907:APROBADA"))
                    .isEqualTo(DeterministicIds.eventId("ANDINA:TRX-CO-5521907:APROBADA"))
                    .isNotEqualTo(DeterministicIds.eventId("ANDINA:TRX-CO-5521907:REVERSADA"));
        }
    }
}
