package com.sporty.jackpot.support;

import static org.awaitility.Awaitility.await;

import com.sporty.jackpot.config.JackpotProperties;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Base class of all full-context tests: the whole application on H2 + an embedded KRaft broker, listeners running.
 * Every subclass shares ONE cached Spring context, so subclasses must not add {@code @MockitoBean},
 * {@code @DirtiesContext}, {@code @TestPropertySource} or other context-changing configuration, must not be
 * {@code @Transactional}, and must create their own jackpots/bets with unique ids ({@link #jackpots},
 * {@link TestJackpots#uniqueId(String)}); the seeded jackpots may only be read.
 *
 * <p>Topics are created by the application's {@code NewTopic} beans (the broker does not auto-create topics).
 * Before each test the listener container is waited for until it owns all partitions of the bets topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 12, brokerProperties = "auto.create.topics.enable=false",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class AbstractKafkaIntegrationTest {

    protected static final Duration AWAIT_AT_MOST = Duration.ofSeconds(30);
    protected static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    protected static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    protected EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    protected KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    protected JackpotProperties properties;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected TestJackpots jackpots;

    @BeforeEach
    void setUpKafkaIntegrationTest() {
        jackpots = new TestJackpots(jdbcTemplate);
        waitForAssignment();
    }

    /**
     * Blocks until every listener container owns all partitions of the bets topic, spread over all its consumers
     * (the cooperative-sticky assignor rebalances incrementally; waiting avoids rebalances in the middle of a test).
     */
    protected void waitForAssignment() {
        int partitions = properties.kafka().partitions();
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, partitions);
            await().atMost(AWAIT_AT_MOST).pollInterval(POLL_INTERVAL)
                    .until(() -> isBalanced((ConcurrentMessageListenerContainer<?, ?>) container, partitions));
        }
    }

    private static boolean isBalanced(ConcurrentMessageListenerContainer<?, ?> container, int partitions) {
        Map<String, Collection<TopicPartition>> assignments = container.getAssignmentsByClientId();
        return assignments.size() == container.getConcurrency()
                && assignments.values().stream().noneMatch(Collection::isEmpty)
                && assignments.values().stream().mapToInt(Collection::size).sum() == partitions;
    }

    protected static String uniqueId(String prefix) {
        return TestJackpots.uniqueId(prefix);
    }

    // ---------------------------------------------------------------- HTTP

    /** {@code POST /api/v1/bets}; {@code amount} is a JSON number literal, e.g. {@code "250.00"}. */
    protected MvcTestResult placeBet(String betId, String userId, String jackpotId, String amount) {
        return mvc.post().uri("/api/v1/bets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(betJson(betId, userId, jackpotId, amount))
                .exchange();
    }

    /** JSON body of a bet ({@code amount} is inserted as a raw JSON number literal). */
    protected static String betJson(String betId, String userId, String jackpotId, String amount) {
        return "{\"betId\":\"" + betId + "\",\"userId\":\"" + userId + "\",\"jackpotId\":\"" + jackpotId
                + "\",\"betAmount\":" + amount + "}";
    }

    /**
     * Polls {@code GET uriTemplate} until it answers with {@code status} (e.g. waits for a bet to be processed).
     *
     * @return the first result with that status
     */
    protected MvcTestResult awaitStatus(HttpStatus status, String uriTemplate, Object... uriVariables) {
        AtomicReference<MvcTestResult> result = new AtomicReference<>();
        await().atMost(AWAIT_AT_MOST).pollInterval(POLL_INTERVAL).until(() -> {
            MvcTestResult current = mvc.get().uri(uriTemplate, uriVariables).exchange();
            result.set(current);
            return current.getResponse().getStatus() == status.value();
        });
        return result.get();
    }

    // ---------------------------------------------------------------- producing

    /** Publishes a payload exactly like the API does (key = jackpot id, JSON without type headers). */
    protected SendResult<String, Object> sendEvent(BetPlacedEvent event) {
        return send(new ProducerRecord<>(betsTopic(), null, event.jackpotId(), event));
    }

    /** Publishes arbitrary bytes (poison pills) to the bets topic, optionally with headers. */
    protected SendResult<String, Object> sendRaw(String key, byte[] value, Header... headers) {
        return send(new ProducerRecord<>(betsTopic(), null, key, value, List.of(headers)));
    }

    /** Publishes an arbitrary JSON document (UTF-8 bytes) to the bets topic, optionally with headers. */
    protected SendResult<String, Object> sendJson(String key, String json, Header... headers) {
        return sendRaw(key, json.getBytes(StandardCharsets.UTF_8), headers);
    }

    /** Publishes a tombstone (null value) to the bets topic. */
    protected SendResult<String, Object> sendTombstone(String key) {
        return send(new ProducerRecord<>(betsTopic(), null, key, null));
    }

    protected SendResult<String, Object> send(ProducerRecord<String, Object> producerRecord) {
        try {
            return kafkaTemplate.send(producerRecord).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Sending the test record failed", e);
        }
    }

    protected String betsTopic() {
        return properties.kafka().betsTopic();
    }

    // ---------------------------------------------------------------- dead-letter topic

    /**
     * A raw consumer of the dead-letter topic: unique group, reads from the beginning, already subscribed.
     * Prefer {@link #openDeadLetterReader()}.
     */
    protected Consumer<String, byte[]> createDeadLetterConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "dlt-test-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, byte[]> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new ByteArrayDeserializer()).createConsumer();
        consumer.subscribe(List.of(properties.kafka().deadLetterTopic()));
        return consumer;
    }

    /** A {@link DeadLetterReader} over {@link #createDeadLetterConsumer()}; close it (try-with-resources). */
    protected DeadLetterReader openDeadLetterReader() {
        return new DeadLetterReader(createDeadLetterConsumer());
    }
}
