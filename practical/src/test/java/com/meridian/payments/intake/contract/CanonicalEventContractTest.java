package com.meridian.payments.intake.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.domain.CanonicalEventSerializer;
import com.meridian.payments.intake.provider.AdapterResult;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.TestVectors;
import com.meridian.payments.intake.support.TestVectors.Vector;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every event the adapters produce conforms to docs/api/events/premium-payment.schema.json. */
class CanonicalEventContractTest {

    private static final Path SCHEMA = Path.of("..", "docs", "api", "events", "premium-payment.schema.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema schema;

    private final CanonicalEventSerializer serializer = new CanonicalEventSerializer();

    @BeforeAll
    static void loadSchema() throws IOException {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        try (InputStream in = Files.newInputStream(SCHEMA)) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        }
    }

    static Stream<Arguments> allSamples() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        QuetzalAdapter quetzal = new QuetzalAdapter(TestVectors.testSecrets(), clock);
        AndinaAdapter andina = new AndinaAdapter(TestVectors.testSecrets(), clock);
        return Stream.concat(
                TestVectors.quetzalVectors().stream().map(v -> Arguments.of(v, quetzal)),
                TestVectors.andinaVectors().stream().map(v -> Arguments.of(v, andina)));
    }

    @ParameterizedTest(name = "{0} produces a valid canonical event")
    @MethodSource("allSamples")
    void producedEventsConformToTheSchema(Vector vector, ProviderAdapter adapter) throws IOException {
        JsonNode event = eventJson(vector, adapter);

        Set<ValidationMessage> errors = schema.validate(event);

        assertThat(errors).isEmpty();
    }

    @Test
    void schemaRejectsPersonalDataFieldsSoTheContractEnforcesMinimisation() throws IOException {
        ObjectNode event = (ObjectNode) eventJson(TestVectors.ANDINA_APPROVED,
                new AndinaAdapter(TestVectors.testSecrets(), new MutableClock(Instant.EPOCH)));
        ((ObjectNode) event.get("data")).put("payerEmail", "laura.gomez@example.com");

        assertThat(schema.validate(event)).isNotEmpty();
    }

    @Test
    void schemaRequiresRefundedAmountOnlyForRefunds() throws IOException {
        ObjectNode refund = (ObjectNode) eventJson(TestVectors.QUETZAL_REFUNDED,
                new QuetzalAdapter(TestVectors.testSecrets(), new MutableClock(Instant.EPOCH)));
        ((ObjectNode) refund.get("data")).remove("refundedAmount");

        assertThat(schema.validate(refund)).isNotEmpty();
    }

    @Test
    void unresolvedReferenceIsAValidEventWithANullPolicyNumber() throws IOException {
        Vector vector = TestVectors.signedAndina("unresolved",
                new String(TestVectors.ANDINA_APPROVED.body(), StandardCharsets.UTF_8)
                        .replace("POL-CO-000187342", "mi poliza").getBytes(StandardCharsets.UTF_8),
                "2026-11-28T14:03:11-05:00");
        JsonNode event = eventJson(vector, new AndinaAdapter(TestVectors.testSecrets(), new MutableClock(Instant.EPOCH)));

        assertThat(event.at("/data/policyNumber").isNull()).isTrue();
        assertThat(schema.validate(event)).isEmpty();
    }

    private JsonNode eventJson(Vector vector, ProviderAdapter adapter) throws IOException {
        AdapterResult.Mapped mapped = (AdapterResult.Mapped) adapter.map(vector.request());
        return MAPPER.readTree(serializer.toJson(mapped.event()));
    }
}
