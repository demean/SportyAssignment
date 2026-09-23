package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import com.sporty.jackpot.messaging.BetPlacedEventDeserializer;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * What actually runs, read back from the started application: effective Kafka client configuration (T13, T11),
 * topics (T14), the listener container (T21), actuator and OpenAPI endpoints.
 */
@DisplayName("Effective runtime wiring (Embedded Kafka)")
class RuntimeWiringIntegrationTest extends AbstractEndToEndIntegrationTest {

    @Autowired
    private ProducerFactory<?, ?> producerFactory;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private CommonErrorHandler kafkaErrorHandler;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the producer used by the API is idempotent, waits for all replicas and gives up before the API does")
    void producerIsIdempotentAndTimesOutBeforeTheApi() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertThat(kafkaTemplate.getProducerFactory()).isSameAs(producerFactory);
        assertThat(String.valueOf(config.get(ProducerConfig.ACKS_CONFIG))).isEqualTo("all");
        assertThat(String.valueOf(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))).isEqualTo("true");
        assertThat(String.valueOf(config.get(ProducerConfig.COMPRESSION_TYPE_CONFIG))).isEqualTo("lz4");
        long maxBlockMs = Long.parseLong(String.valueOf(config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)));
        long requestTimeoutMs = Long.parseLong(String.valueOf(config.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)));
        long deliveryTimeoutMs = Long.parseLong(String.valueOf(config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)));
        assertThat(List.of(maxBlockMs, requestTimeoutMs, deliveryTimeoutMs)).containsExactly(3_000L, 3_000L, 8_000L);
        assertThat(properties.kafka().publishTimeout())
                .as("the API never gives up on a send the producer may still complete")
                .isGreaterThanOrEqualTo(Duration.ofMillis(maxBlockMs + deliveryTimeoutMs).plusSeconds(1));
    }

    @Test
    @DisplayName("the producer writes bets as plain JSON without type headers and passes raw bytes through")
    void producerSerializesBetsWithoutTypeHeaders() {
        @SuppressWarnings("unchecked")
        Serializer<Object> serializer = (Serializer<Object>) ((DefaultKafkaProducerFactory<?, ?>) producerFactory)
                .getValueSerializerSupplier().get();
        RecordHeaders headers = new RecordHeaders();
        BetPlacedEvent event = new BetPlacedEvent("bet-1", "user-1", "jackpot-1", new BigDecimal("10.50"),
                Instant.parse("2026-01-01T00:00:00.123456Z"));

        String json = new String(serializer.serialize(betsTopic(), headers, event), StandardCharsets.UTF_8);

        assertThat(serializer).isInstanceOf(DelegatingByTypeSerializer.class);
        assertThat(json).isEqualTo("""
                {"betId":"bet-1","userId":"user-1","jackpotId":"jackpot-1","betAmount":10.50,\
                "placedAt":"2026-01-01T00:00:00.123456Z"}""");
        assertThat(headers.toArray()).as("no __TypeId__ headers").isEmpty();
        byte[] raw = {1, 2, 3};
        assertThat(serializer.serialize(betsTopic(), new RecordHeaders(), raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("the consumer reads strict JSON without type headers behind error-handling deserializers")
    void consumerIsConfiguredForPoisonPillSafeJson() {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();

        assertThat(config.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("jackpot-service-local");
        assertThat(config.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(className(config.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)))
                .isEqualTo(ErrorHandlingDeserializer.class.getName());
        assertThat(className(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)))
                .isEqualTo(ErrorHandlingDeserializer.class.getName());
        assertThat(config).containsEntry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                        StringDeserializer.class.getName())
                .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                        BetPlacedEventDeserializer.class.getName())
                .containsEntry(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, BetPlacedEvent.class.getName())
                .containsEntry(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, "false")
                .containsEntry(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.sporty.jackpot.messaging")
                .containsEntry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                        CooperativeStickyAssignor.class.getName())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50")
                .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000");
    }

    @Test
    @DisplayName("one listener container: 3 consumers on platform threads, 12 partitions, dead-letter error handler")
    void listenerContainerRunsThreeConsumersOnPlatformThreads() {
        assertThat(listenerRegistry.getListenerContainers()).hasSize(1);
        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) listenerRegistry.getListenerContainers().iterator().next();
        ContainerProperties containerProperties = container.getContainerProperties();

        assertThat(container.getConcurrency()).isEqualTo(3);
        assertThat(container.getContainers()).hasSize(3).allMatch(MessageListenerContainer::isRunning);
        assertThat(container.getAssignedPartitions()).hasSize(properties.kafka().partitions());
        assertThat(container.getGroupId()).isEqualTo("jackpot-service-local");
        assertThat(containerProperties.getTopics()).containsExactly(betsTopic());
        assertThat(container.getCommonErrorHandler()).isSameAs(kafkaErrorHandler);
        assertThat(containerProperties.isCheckDeserExWhenKeyNull()).isTrue();
        assertThat(containerProperties.isCheckDeserExWhenValueNull()).isTrue();
        assertThat(containerProperties.getShutdownTimeout()).isEqualTo(15_000L);
        assertThat(containerProperties.getListenerTaskExecutor()).isInstanceOf(SimpleAsyncTaskExecutor.class);
        // Thread.getAllStackTraces() lists platform threads only: the consumer loops are among them
        List<String> platformThreads = Thread.getAllStackTraces().keySet().stream().map(Thread::getName).toList();
        assertThat(platformThreads).filteredOn(name -> name.startsWith("jackpot-kafka-"))
                .hasSizeGreaterThanOrEqualTo(container.getConcurrency());
    }

    @Test
    @DisplayName("the topics exist with the configured partitions, min.insync.replicas and dead-letter retention")
    void topicsAreCreatedAsConfigured() throws Exception {
        String betsTopic = betsTopic();
        String deadLetterTopic = properties.kafka().deadLetterTopic();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, TopicDescription> topics = admin.describeTopics(List.of(betsTopic, deadLetterTopic))
                    .allTopicNames().get(10, TimeUnit.SECONDS);
            ConfigResource deadLetterResource = new ConfigResource(ConfigResource.Type.TOPIC, deadLetterTopic);
            ConfigResource betsResource = new ConfigResource(ConfigResource.Type.TOPIC, betsTopic);
            Map<ConfigResource, Config> configs = admin.describeConfigs(List.of(betsResource, deadLetterResource))
                    .all().get(10, TimeUnit.SECONDS);

            assertThat(topics.get(betsTopic).partitions()).hasSize(12);
            assertThat(topics.get(deadLetterTopic).partitions()).hasSize(3);
            assertThat(configs.get(betsResource).get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG).value()).isEqualTo("1");
            assertThat(configs.get(deadLetterResource).get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG).value())
                    .isEqualTo("1");
            assertThat(configs.get(deadLetterResource).get(TopicConfig.RETENTION_MS_CONFIG).value())
                    .isEqualTo(String.valueOf(Duration.ofDays(30).toMillis()));
        }
    }

    @Test
    @DisplayName("open-session-in-view is off: no request keeps a persistence context (T16)")
    void openSessionInViewIsOff() {
        assertThat(applicationContext.getBeansOfType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }

    @Test
    @DisplayName("health is UP, including the Kafka listener containers and the database; probes are UP")
    void healthIsUp() {
        MvcTestResult health = mvc.get().uri("/actuator/health").exchange();

        assertThat(health).hasStatusOk();
        assertThat(text(health, "$.status")).isEqualTo("UP");
        assertThat(text(health, "$.components.kafkaListeners.status")).isEqualTo("UP");
        List<String> containers = json(health).read("$.components.kafkaListeners.details.containers");
        List<String> stopped = json(health).read("$.components.kafkaListeners.details.stopped");
        assertThat(containers).hasSize(1);
        assertThat(stopped).isEmpty();
        assertThat(text(health, "$.components.db.status")).isEqualTo("UP");
        for (String probe : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
            MvcTestResult result = mvc.get().uri(probe).exchange();
            assertThat(result).as(probe).hasStatusOk();
            assertThat(text(result, "$.status")).as(probe).isEqualTo("UP");
        }
    }

    @Test
    @DisplayName("a stopped listener container fails liveness (a restart heals it); readiness stays UP")
    void stoppedListenerContainerFailsLiveness() {
        MessageListenerContainer container = listenerRegistry.getListenerContainers().iterator().next();
        try {
            container.stop();

            MvcTestResult liveness = mvc.get().uri("/actuator/health/liveness").exchange();
            assertThat(liveness).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(text(liveness, "$.status")).isEqualTo("DOWN");
            MvcTestResult readiness = mvc.get().uri("/actuator/health/readiness").exchange();
            assertThat(readiness).as("the HTTP API still works: keep serving it").hasStatusOk();
            assertThat(text(readiness, "$.status")).isEqualTo("UP");
        } finally {
            container.start();
            waitForAssignment();
        }
        assertThat(mvc.get().uri("/actuator/health/liveness").exchange()).hasStatusOk();
    }

    @Test
    @DisplayName("the shipped H2 URL and connection pool are in effect (T17): only the database name is per test")
    void shippedDataSourceSettings() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getConnectionTimeout()).isEqualTo(2_000L);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getJdbcUrl()).startsWith("jdbc:h2:mem:test-")
                .endsWith(";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                        + "LOCK_TIMEOUT=3000");
    }

    @Test
    @DisplayName("Prometheus exposes the business metrics of a processed bet")
    void prometheusExposesTheBusinessMetrics() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");
        assertThat(placeBet(betId, "user-metrics", jackpotId, "10.00")).hasStatus(HttpStatus.ACCEPTED);
        awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);

        MvcTestResult prometheus = mvc.get().uri("/actuator/prometheus").exchange();

        assertThat(prometheus).hasStatusOk();
        assertThat(prometheus).bodyText()
                .containsPattern("jackpot_bets_published_total\\{[^}]*result=\"success\"[^}]*} [1-9]")
                .containsPattern("jackpot_bets_processed_total\\{[^}]*status=\"PROCESSED\"[^}]*} [1-9]")
                .containsPattern("jackpot_evaluations_total\\{[^}]*outcome=\"LOST\"[^}]*} [1-9]")
                .containsPattern("jackpot_bets_processing_latency_seconds_count(\\{[^}]*})? [1-9]")
                .contains("jackpot_rewards_amount_count");
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/actuator/info", "/actuator/metrics/jackpot.bets.published"})
    @DisplayName("the other exposed actuator endpoints answer")
    void actuatorEndpointsAnswer(String endpoint) {
        assertThat(mvc.get().uri(endpoint).exchange()).hasStatusOk();
    }

    @Test
    @DisplayName("OpenAPI describes every endpoint, including the documented error responses")
    void openApiDescribesTheApi() {
        MvcTestResult apiDocs = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(apiDocs).hasStatusOk();
        assertThat(text(apiDocs, "$.info.title")).isEqualTo("Jackpot Service API");
        Map<String, Object> paths = json(apiDocs).read("$.paths");
        Map<String, Object> placeBetResponses = json(apiDocs).read("$.paths['/api/v1/bets'].post.responses");
        Map<String, Object> evaluationResponses =
                json(apiDocs).read("$.paths['/api/v1/bets/{betId}/evaluation'].get.responses");
        assertThat(paths).containsOnlyKeys("/api/v1/bets", "/api/v1/bets/{betId}",
                "/api/v1/bets/{betId}/contribution", "/api/v1/bets/{betId}/evaluation", "/api/v1/jackpots",
                "/api/v1/jackpots/{jackpotId}");
        assertThat(placeBetResponses).containsOnlyKeys("202", "400", "415", "503");
        assertThat(evaluationResponses).containsOnlyKeys("200", "400", "404", "422", "503");
        assertThat(mvc.get().uri("/swagger-ui.html").exchange()).hasStatus3xxRedirection();
    }

    @Test
    @DisplayName("OpenAPI documents every error response as application/problem+json with the Problem schema")
    void openApiDocumentsErrorsAsProblemDetails() {
        DocumentContext apiDocs = json(mvc.get().uri("/v3/api-docs").exchange());
        List<Map<String, Map<String, Object>>> responsesPerOperation = apiDocs.read("$.paths.*.*.responses");
        List<String> errorStatuses = new ArrayList<>();

        assertThat(responsesPerOperation).as("one entry per operation").hasSize(6);
        for (Map<String, Map<String, Object>> responses : responsesPerOperation) {
            responses.forEach((status, response) -> {
                Map<String, Object> content = JsonPath.read(response, "$.content");
                if (status.startsWith("4") || status.startsWith("5")) {
                    errorStatuses.add(status);
                    assertThat(content).as(status).containsOnlyKeys(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    assertThat(JsonPath.<String>read(content, "$['application/problem+json'].schema.$ref"))
                            .as(status).isEqualTo("#/components/schemas/Problem");
                } else {
                    assertThat(content).as(status).doesNotContainKey(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                }
            });
        }
        assertThat(errorStatuses).contains("400", "404", "415", "422", "503");
        assertThat(apiDocs.read("$.components.schemas.Problem.properties.code.enum", List.class))
                .containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat(apiDocs.read("$.components.schemas.Problem.required", List.class))
                .containsExactlyInAnyOrder("title", "status", "detail", "instance", "code", "timestamp");
        assertThat(apiDocs.read("$.components.schemas.Problem.properties.errors.items.$ref", String.class))
                .isEqualTo("#/components/schemas/FieldError");
        assertThat(apiDocs.read("$.paths['/api/v1/bets'].post.responses['202'].headers", Map.class))
                .containsKey(HttpHeaders.LOCATION);
        assertThat(apiDocs.read("$.paths['/api/v1/bets'].post.responses['503'].headers", Map.class))
                .containsKey(HttpHeaders.RETRY_AFTER);
        assertThat(apiDocs.read("$.paths['/api/v1/jackpots'].get.responses['503'].headers", Map.class))
                .containsKey(HttpHeaders.RETRY_AFTER);
        assertThat(apiDocs.read("$.paths['/api/v1/bets/{betId}'].get.responses['404']", Map.class))
                .as("only a 503 carries Retry-After").doesNotContainKey("headers");
        assertThat(apiDocs.read("$.components.schemas.PlaceBetRequest.properties.betAmount.description",
                String.class)).contains("at most 2 decimals");
    }

    private static String className(Object configValue) {
        return configValue instanceof Class<?> type ? type.getName() : String.valueOf(configValue);
    }
}
