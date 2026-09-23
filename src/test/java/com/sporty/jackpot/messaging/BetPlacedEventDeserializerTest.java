package com.sporty.jackpot.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.kafka.support.mapping.JacksonJavaTypeMapper.TypePrecedence;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.exc.MismatchedInputException;

@DisplayName("BetPlacedEventDeserializer (consumer value delegate)")
class BetPlacedEventDeserializerTest {

    private static final String TOPIC = "jackpot-bets";
    private static final BetPlacedEvent EVENT = new BetPlacedEvent("bet-1", "user-1", "jackpot-1",
            new BigDecimal("100.00"), Instant.parse("2026-01-01T00:00:00Z"));
    private static final String VALID_JSON = """
            {"betId":"bet-1","userId":"user-1","jackpotId":"jackpot-1","betAmount":100.00,\
            "placedAt":"2026-01-01T00:00:00Z"%s}""";

    private BetPlacedEventDeserializer deserializer;

    @BeforeEach
    void setUp() {
        deserializer = new BetPlacedEventDeserializer();
        // the spring.json.* consumer properties of application.yml
        deserializer.configure(Map.of(
                JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, BetPlacedEvent.class.getName(),
                JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, "false",
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.sporty.jackpot.messaging"), false);
    }

    @AfterEach
    void tearDown() {
        deserializer.close();
    }

    private BetPlacedEvent deserialize(String json) {
        return deserializer.deserialize(TOPIC, new RecordHeaders(), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("is configured by the spring.json.* properties (no 'setters and properties' conflict)")
    void acceptsTheConsumerProperties() {
        assertThat(deserializer.getTypeMapper().getTypePrecedence()).as("spring.json.use.type.headers=false")
                .isEqualTo(TypePrecedence.INFERRED);
    }

    @Test
    @DisplayName("reads a well-formed bet, keeping the amount's scale")
    void readsAWellFormedBet() {
        BetPlacedEvent event = deserialize(VALID_JSON.formatted(""));

        assertThat(event).isEqualTo(EVENT);
        assertThat(event.betAmount()).hasScaleOf(2);
    }

    @Test
    @DisplayName("ignores unknown properties, as before")
    void ignoresUnknownProperties() {
        assertThat(deserialize(VALID_JSON.formatted(",\"channel\":\"mobile\",\"promo\":{\"code\":\"X1\"}")))
                .isEqualTo(EVENT);
    }

    @Test
    @DisplayName("reads what the service's own producer writes")
    void readsTheProducersJson() {
        byte[] json;
        try (JacksonJsonSerializer<BetPlacedEvent> serializer =
                     new JacksonJsonSerializer<BetPlacedEvent>().noTypeInfo()) {
            json = serializer.serialize(TOPIC, EVENT);
        }

        assertThat(deserializer.deserialize(TOPIC, new RecordHeaders(), json)).isEqualTo(EVENT);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "first 1.00, then 1000000000.00 | \"betAmount\":1.00,\"betAmount\":1000000000.00",
            "same value twice               | \"betAmount\":100.00,\"betAmount\":100.00",
            "duplicate id                   | \"betAmount\":100.00,\"betId\":\"bet-2\""})
    @DisplayName("rejects a duplicate key instead of keeping the last value (the stake an audit trail read first)")
    void rejectsDuplicateKeys(String description, String fields) {
        String json = """
                {"betId":"bet-1","userId":"user-1","jackpotId":"jackpot-1",%s,"placedAt":"2026-01-01T00:00:00Z"}"""
                .formatted(fields);

        assertThatThrownBy(() -> deserialize(json))
                .isInstanceOf(SerializationException.class)
                .cause().isInstanceOf(StreamReadException.class)
                .hasMessageContaining("Duplicate Object property");
    }

    @Test
    @DisplayName("rejects the stake as a JSON string instead of coercing it into a number, like the API")
    void rejectsAStringAmount() {
        String json = """
                {"betId":"bet-1","userId":"user-1","jackpotId":"jackpot-1","betAmount":"250.00",\
                "placedAt":"2026-01-01T00:00:00Z"}""";

        assertThatThrownBy(() -> deserialize(json))
                .isInstanceOf(SerializationException.class)
                .cause().isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Cannot coerce String value");
    }

    @Test
    @DisplayName("shares one strict mapper: duplicate detection on, scalar coercion off")
    void strictMapper() {
        assertThat(BetPlacedEventDeserializer.STRICT_MAPPER.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                .isTrue();
        assertThat(BetPlacedEventDeserializer.STRICT_MAPPER.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS))
                .isFalse();
    }
}
