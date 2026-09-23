package com.sporty.jackpot.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.awaitility.Awaitility;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Reads the dead-letter topic and keeps every record it has polled, so several records can be awaited one after
 * the other with the same reader. The topic is shared by all tests of a context: always match on something unique
 * (e.g. {@link #withKey(String)} with a unique key). Close it (try-with-resources).
 *
 * <pre>{@code
 * try (DeadLetterReader dlt = openDeadLetterReader()) {
 *     ConsumerRecord<String, byte[]> dead = dlt.await(DeadLetterReader.withKey(key));
 *     assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).isEqualTo(...);
 * }
 * }</pre>
 */
public final class DeadLetterReader implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final Consumer<String, byte[]> consumer;
    private final List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();

    public DeadLetterReader(Consumer<String, byte[]> consumer) {
        this.consumer = consumer;
    }

    /** Waits up to 30 s for a matching record; see {@link #await(Predicate, Duration)}. */
    public ConsumerRecord<String, byte[]> await(Predicate<ConsumerRecord<String, byte[]>> matcher) {
        return await(matcher, DEFAULT_TIMEOUT);
    }

    /**
     * Returns the first record (already received or newly polled) matching {@code matcher}.
     *
     * @param matcher which record to wait for
     * @param atMost  how long to wait
     * @return the matching record
     */
    public ConsumerRecord<String, byte[]> await(Predicate<ConsumerRecord<String, byte[]>> matcher, Duration atMost) {
        Awaitility.await().atMost(atMost).pollInterval(Duration.ZERO).until(() -> {
            if (find(matcher).isPresent()) {
                return true;
            }
            KafkaTestUtils.getRecords(consumer, POLL_TIMEOUT).forEach(received::add);
            return find(matcher).isPresent();
        });
        return find(matcher).orElseThrow();
    }

    @Override
    public void close() {
        consumer.close();
    }

    /** @return a matcher on the record key */
    public static Predicate<ConsumerRecord<String, byte[]>> withKey(String key) {
        return consumerRecord -> Objects.equals(consumerRecord.key(), key);
    }

    /** @return a matcher on the UTF-8 record value containing {@code text} */
    public static Predicate<ConsumerRecord<String, byte[]>> withValueContaining(String text) {
        return consumerRecord -> {
            String value = value(consumerRecord);
            return value != null && value.contains(text);
        };
    }

    /** @return the UTF-8 value of the last header with that name, or {@code null} */
    public static String header(ConsumerRecord<?, ?> consumerRecord, String headerName) {
        Header header = consumerRecord.headers().lastHeader(headerName);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** @return the record value as UTF-8 text, or {@code null} for a tombstone */
    public static String value(ConsumerRecord<String, byte[]> consumerRecord) {
        return consumerRecord.value() == null ? null : new String(consumerRecord.value(), StandardCharsets.UTF_8);
    }

    private Optional<ConsumerRecord<String, byte[]>> find(Predicate<ConsumerRecord<String, byte[]>> matcher) {
        return received.stream().filter(matcher).findFirst();
    }
}
