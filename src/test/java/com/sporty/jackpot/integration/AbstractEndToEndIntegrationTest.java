package com.sporty.jackpot.integration;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import com.sporty.jackpot.support.AbstractKafkaIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * End-to-end helpers on top of the shared Embedded-Kafka context (adds no configuration, so the context stays the
 * shared one).
 */
abstract class AbstractEndToEndIntegrationTest extends AbstractKafkaIntegrationTest {

    /** Listener observation timer (spring.kafka.listener.observation-enabled): one sample per listener call. */
    private static final String LISTENER_TIMER = "spring.kafka.listener";

    @Autowired
    protected MeterRegistry meterRegistry;

    protected JackpotLedger ledger;

    @BeforeEach
    void setUpEndToEndIntegrationTest() {
        ledger = new JackpotLedger(jdbcTemplate);
    }

    /** @return a valid bet payload by a fixed user, placed now */
    protected static BetPlacedEvent event(String betId, String jackpotId, String amount) {
        return new BetPlacedEvent(betId, "user-e2e", jackpotId, new BigDecimal(amount),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Waits until every record sent so far with this key has been consumed: publishes a marker bet for an unknown
     * jackpot with the same key (same partition, consumed in order) and waits until it is stored.
     */
    protected void awaitRecordsWithKeyConsumed(String key) {
        String markerBetId = uniqueId("marker");
        BetPlacedEvent marker = new BetPlacedEvent(markerBetId, "marker-user", uniqueId("no-such-jackpot"),
                new BigDecimal("1.00"), Instant.now().truncatedTo(ChronoUnit.MICROS));
        send(new ProducerRecord<>(betsTopic(), null, key, marker));
        awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}", markerBetId);
    }

    /** @return the records currently in the dead-letter topic with this key (read up to the end offsets) */
    protected List<ConsumerRecord<String, byte[]>> deadLettersWithKey(String key) {
        return KafkaTopicContents.readAll(embeddedKafka, properties.kafka().deadLetterTopic()).stream()
                .filter(deadLetter -> Objects.equals(deadLetter.key(), key))
                .toList();
    }

    /**
     * @return how often the bet listener has been called so far, per outcome: the {@code error} tag of the listener
     *         observation is the simple name of the exception the call failed with, or {@code none}
     */
    protected Map<String, Long> listenerCallsByError() {
        return meterRegistry.find(LISTENER_TIMER).timers().stream()
                .collect(Collectors.groupingBy(timer -> String.valueOf(timer.getId().getTag("error")),
                        Collectors.summingLong(Timer::count)));
    }

    /** @return listener calls that failed with {@code exceptionSimpleName} since the {@code before} snapshot */
    protected long listenerFailuresSince(Map<String, Long> before, String exceptionSimpleName) {
        return listenerCallsByError().getOrDefault(exceptionSimpleName, 0L)
                - before.getOrDefault(exceptionSimpleName, 0L);
    }

    /** @return the body of a response as UTF-8 text */
    protected static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** @return the JSON body of a response */
    protected static DocumentContext json(MvcTestResult result) {
        return JsonPath.parse(body(result));
    }

    /** @return the JSON number at {@code path} as an exact decimal */
    protected static BigDecimal decimal(MvcTestResult result, String path) {
        return new BigDecimal(String.valueOf(json(result).read(path, Object.class)));
    }

    /** @return the JSON string at {@code path} */
    protected static String text(MvcTestResult result, String path) {
        return json(result).read(path, String.class);
    }
}
