package com.sporty.jackpot.integration;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Reads a whole topic from the beginning up to its current end offsets, e.g. to show deterministically (no waiting
 * window) that a record was NOT dead-lettered: the dead-letter publisher waits for the broker acknowledgement before
 * the container moves on, so once a later record of the same partition has been processed, any dead-letter record of
 * an earlier one is already below the end offsets read here.
 */
final class KafkaTopicContents {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private KafkaTopicContents() {
    }

    /** @return every record currently in {@code topic} */
    static List<ConsumerRecord<String, byte[]>> readAll(EmbeddedKafkaBroker broker, String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "dlt-contents-" + UUID.randomUUID(), false);
        try (Consumer<String, byte[]> consumer = new KafkaConsumer<>(props, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < endOffsets.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Could not read " + topic + " up to " + endOffsets);
                }
                consumer.poll(POLL_TIMEOUT).forEach(records::add);
            }
            return records;
        }
    }

    /** @return the {@code int} value of a Kafka header written as 4 big-endian bytes (e.g. the original partition) */
    static int intHeader(ConsumerRecord<?, ?> consumerRecord, String headerName) {
        return ByteBuffer.wrap(header(consumerRecord, headerName).value()).getInt();
    }

    /** @return the {@code long} value of a Kafka header written as 8 big-endian bytes (e.g. the original offset) */
    static long longHeader(ConsumerRecord<?, ?> consumerRecord, String headerName) {
        return ByteBuffer.wrap(header(consumerRecord, headerName).value()).getLong();
    }

    private static Header header(ConsumerRecord<?, ?> consumerRecord, String headerName) {
        Header header = consumerRecord.headers().lastHeader(headerName);
        if (header == null) {
            throw new AssertionError("Missing header " + headerName);
        }
        return header;
    }
}
