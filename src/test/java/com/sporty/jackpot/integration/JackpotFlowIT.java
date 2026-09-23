package com.sporty.jackpot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.sporty.jackpot.config.JackpotProperties;
import com.sporty.jackpot.messaging.BetEventListener;
import com.sporty.jackpot.service.BetProcessingService;
import com.sporty.jackpot.support.TestJackpots;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The production-like setup ({@code postgres} profile) against real PostgreSQL 17 and a real Kafka 4.2 broker:
 * Flyway migrations and {@code ddl-auto=validate} on PostgreSQL, the full HTTP → Kafka → PostgreSQL flow, and the
 * concurrency scenarios (a), (b) and (c) under PostgreSQL's row locking ({@code FOR NO KEY UPDATE}) and unique-index
 * waits. Needs Docker; run with {@code ./mvnw verify -Pit} (see the README for the colima environment variables).
 *
 * <p>{@code @DirtiesContext} closes the application context (graceful Kafka consumer/producer shutdown) at the end of
 * the class, before the {@code @Testcontainers} extension stops the broker and the database; a context left in the
 * cache until JVM exit would keep reconnecting to the stopped containers.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
                ConcurrencyScenarios.POOL_ACQUIRE_TIMEOUT_PROPERTY})
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@Import(ScriptedRandomTestConfiguration.class)
@DirtiesContext
@DisplayName("Jackpot flow on PostgreSQL 17 + Kafka 4.2 (Testcontainers, postgres profile)")
class JackpotFlowIT {

    private static final Duration AWAIT_AT_MOST = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BetProcessingService processingService;

    @Autowired
    private BetEventListener betEventListener;

    @Autowired
    private ScriptedRandomGenerator random;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JackpotProperties properties;

    @LocalManagementPort
    private int managementPort;

    private TestJackpots jackpots;
    private JackpotLedger ledger;
    private ConcurrencyScenarios scenarios;

    @BeforeEach
    void setUp() {
        jackpots = new TestJackpots(jdbcTemplate);
        ledger = new JackpotLedger(jdbcTemplate);
        scenarios = new ConcurrencyScenarios(processingService, betEventListener, jackpots, ledger, random,
                meterRegistry, clock);
        random.reset();
        waitForBalancedAssignment();
    }

    @Test
    @DisplayName("Flyway migrated PostgreSQL 17 (schema + placeholder seeds); the postgres profile settings apply")
    void flywayMigratedPostgresAndTheProfileApplies() {
        assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 17");
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
        Map<String, BigDecimal> seededPools = Map.of("jackpot-fixed", new BigDecimal("1000.00"),
                "jackpot-variable", new BigDecimal("5000.00"), "jackpot-mixed", new BigDecimal("10000.00"),
                "jackpot-lucky", new BigDecimal("100.00"));
        seededPools.forEach((id, pool) -> assertThat(jdbcTemplate.queryForObject(
                "SELECT initial_pool_amount FROM jackpot WHERE id = ?", BigDecimal.class, id))
                .as(id).isEqualByComparingTo(pool));
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class))
                .as("hikari connection-init-sql of the postgres profile").isEqualTo("3s");
        assertThat(consumerFactory.getConfigurationProperties().get(ConsumerConfig.GROUP_ID_CONFIG))
                .isEqualTo("jackpot-service");
        MvcTestResult listed = mvc.get().uri("/api/v1/jackpots").exchange();
        assertThat(listed).hasStatusOk();
        List<String> listedIds = json(listed).read("$[*].id");
        assertThat(listedIds).containsAll(seededPools.keySet());
    }

    @Test
    @DisplayName("HTTP -> Kafka -> PostgreSQL: a bet reaching the pool limit wins the pool, the jackpot resets")
    void placedBetWinsAndResetsTheJackpot() {
        String jackpotId = jackpots.createLuckyLike();
        String betId = TestJackpots.uniqueId("bet");

        MvcTestResult accepted = mvc.post().uri("/api/v1/bets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"betId\":\"" + betId + "\",\"userId\":\"user-it\",\"jackpotId\":\"" + jackpotId
                        + "\",\"betAmount\":250.00}")
                .exchange();
        assertThat(accepted).hasStatus(HttpStatus.ACCEPTED).hasHeader(HttpHeaders.LOCATION, "/api/v1/bets/" + betId);

        MvcTestResult evaluation = awaitOk("/api/v1/bets/{betId}/evaluation", betId);
        assertThat(json(evaluation).read("$.outcome", String.class)).isEqualTo("WON");
        assertThat(evaluation).bodyText().contains("\"rewardAmount\":150.00", "\"winChancePercentage\":100.0000");
        MvcTestResult contribution = mvc.get().uri("/api/v1/bets/{betId}/contribution", betId).exchange();
        assertThat(contribution).hasStatusOk().bodyText()
                .contains("\"contributionAmount\":50.00", "\"currentJackpotAmount\":150.00");
        MvcTestResult bet = mvc.get().uri("/api/v1/bets/{betId}", betId).exchange();
        assertThat(json(bet).read("$.placedAt", String.class))
                .as("the acceptance instant survives Kafka and PostgreSQL unchanged")
                .isEqualTo(json(accepted).read("$.acceptedAt", String.class));
        assertThat(Instant.parse(json(bet).read("$.processedAt", String.class)))
                .isEqualTo(Instant.parse(json(evaluation).read("$.evaluatedAt", String.class)));

        MvcTestResult jackpot = mvc.get().uri("/api/v1/jackpots/{jackpotId}", jackpotId).exchange();
        assertThat(jackpot).hasStatusOk().bodyText().contains("\"currentPoolAmount\":100.00", "\"cycle\":2");
        ledger.assertConsistent(jackpotId);
    }

    @Test
    @DisplayName("HTTP -> Kafka -> PostgreSQL: a bet for an unknown jackpot is stored and answers 422 on evaluation")
    void betForUnknownJackpotAnswers422() {
        String betId = TestJackpots.uniqueId("bet");
        String jackpotId = TestJackpots.uniqueId("unknown-jackpot");

        assertThat(mvc.post().uri("/api/v1/bets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"betId\":\"" + betId + "\",\"userId\":\"user-it\",\"jackpotId\":\"" + jackpotId
                        + "\",\"betAmount\":10.00}")
                .exchange()).hasStatus(HttpStatus.ACCEPTED);

        MvcTestResult bet = awaitOk("/api/v1/bets/{betId}", betId);
        assertThat(json(bet).read("$.status", String.class)).isEqualTo("NO_MATCHING_JACKPOT");
        MvcTestResult evaluation = mvc.get().uri("/api/v1/bets/{betId}/evaluation", betId).exchange();
        assertThat(evaluation).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(json(evaluation).read("$.code", String.class)).isEqualTo("BET_NOT_CONTRIBUTING");
    }

    @Test
    @DisplayName("(a) 96 parallel bets on one jackpot: no lost update, one reward per cycle, consistent version")
    void parallelContributionsOnOneJackpotKeepThePoolConsistent() {
        scenarios.parallelContributionsKeepThePoolConsistent(3, 8);
    }

    @Test
    @DisplayName("(b) the same bet id raced to two jackpots is processed once, the losing jackpot is untouched")
    void sameBetIdOnTwoJackpotsIsProcessedOnce() {
        scenarios.sameBetIdOnTwoJackpotsIsProcessedOnce();
        scenarios.sameBetIdRacingThroughTheListenerIsAckedOnce();
    }

    @Test
    @DisplayName("(c) parallel bets on a 100 % jackpot: each wins its own cycle, the pool ends at its initial value")
    void everyParallelBetWinsACertainJackpotInItsOwnCycle() {
        scenarios.everyParallelBetWinsACertainJackpotInItsOwnCycle();
    }

    @Test
    @DisplayName("the management port of the postgres profile serves health: ready, database PostgreSQL")
    void managementPortServesHealth() {
        RestClient management = RestClient.create("http://localhost:" + managementPort);

        DocumentContext readiness = JsonPath.parse(management.get().uri("/actuator/health/readiness").retrieve()
                .body(String.class));
        DocumentContext health = JsonPath.parse(management.get().uri("/actuator/health").retrieve()
                .body(String.class));

        assertThat(readiness.read("$.status", String.class)).isEqualTo("UP");
        assertThat(health.read("$.status", String.class)).isEqualTo("UP");
        assertThat(health.read("$.components.db.details.database", String.class)).isEqualTo("PostgreSQL");
        assertThat(health.read("$.components.kafkaListeners.status", String.class)).isEqualTo("UP");
    }

    private MvcTestResult awaitOk(String uriTemplate, Object... uriVariables) {
        AtomicReference<MvcTestResult> result = new AtomicReference<>();
        await().atMost(AWAIT_AT_MOST).pollInterval(POLL_INTERVAL).until(() -> {
            result.set(mvc.get().uri(uriTemplate, uriVariables).exchange());
            return result.get().getResponse().getStatus() == HttpStatus.OK.value();
        });
        return result.get();
    }

    private void waitForBalancedAssignment() {
        int partitions = properties.kafka().partitions();
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, partitions);
            ConcurrentMessageListenerContainer<?, ?> concurrent = (ConcurrentMessageListenerContainer<?, ?>) container;
            await().atMost(AWAIT_AT_MOST).pollInterval(POLL_INTERVAL).until(() -> {
                Map<String, Collection<TopicPartition>> assignments = concurrent.getAssignmentsByClientId();
                return assignments.size() == concurrent.getConcurrency()
                        && assignments.values().stream().noneMatch(Collection::isEmpty);
            });
        }
    }

    private static DocumentContext json(MvcTestResult result) {
        return JsonPath.parse(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
