package com.sporty.jackpot.messaging;

import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Value delegate of the consumer's {@code ErrorHandlingDeserializer}: parses {@link BetPlacedEvent} records as
 * strictly as the HTTP API parses request bodies. The consumer is where the stake is applied, and the topic is written
 * by more than the API (other producers, hand-edited dead-letter replays):
 * <ul>
 *   <li>a duplicate key is rejected instead of silently keeping the last value (an upstream audit trail may have read
 *       the first {@code betAmount} as the stake);</li>
 *   <li>a JSON string is never coerced into a number (the stake is a JSON number, as documented).</li>
 * </ul>
 * Both fail deserialization, so the record is dead-lettered with its original bytes. Unknown properties are still
 * ignored, as before.
 * <p>
 * Kafka instantiates it by class name ({@code spring.deserializer.value.delegate.class}), so the
 * {@code spring.json.*} consumer properties (default type, trusted packages, no type headers) still configure it: the
 * {@link JsonMapper}-only super constructor does not count as a property setter.
 */
public class BetPlacedEventDeserializer extends JacksonJsonDeserializer<BetPlacedEvent> {

    /** Spring Kafka's default consumer mapper, made strict. Immutable and thread-safe, so shared. */
    static final JsonMapper STRICT_MAPPER = JacksonMapperUtils.enhancedJsonMapper().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public BetPlacedEventDeserializer() {
        super(STRICT_MAPPER);
    }
}
