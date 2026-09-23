package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.sporty.jackpot.exception.InvalidBetException;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import com.sporty.jackpot.support.DeadLetterReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Poison pills vs. transient failures (T11, T17) and lenient payload handling, end to end through the embedded
 * broker: deterministic failures go to the dead-letter topic without blocking the partition, transient ones are
 * retried until they succeed, tolerable payload variations are processed.
 */
@DisplayName("Kafka error handling and dead-lettering (Embedded Kafka)")
class KafkaErrorHandlingIntegrationTest extends AbstractEndToEndIntegrationTest {

    private static final String VALID_BET_TEMPLATE = """
            {"betId":"%s","userId":"user-e2e","jackpotId":"%s","betAmount":100.00,\
            "placedAt":"2026-01-01T00:00:00Z"%s}""";

    @Autowired
    private DataSource dataSource;

    static Stream<Arguments> undeserializablePayloads() {
        return Stream.of(
                arguments("truncated JSON", utf8("{\"betId\":\"b-1\",\"userId\":")),
                arguments("binary garbage, not even UTF-8", new byte[] {0x00, (byte) 0xFF, (byte) 0xFE, 0x7B, 0x22}),
                arguments("JSON array instead of an object", utf8("[1,2,3]")),
                arguments("text where the amount is expected", utf8("""
                        {"betId":"b-1","userId":"u","jackpotId":"j","betAmount":"a lot",\
                        "placedAt":"2026-01-01T00:00:00Z"}""")),
                arguments("unparsable timestamp", utf8("""
                        {"betId":"b-1","userId":"u","jackpotId":"j","betAmount":1.00,"placedAt":"yesterday"}""")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undeserializablePayloads")
    @DisplayName("an undeserializable record is dead-lettered with its original bytes; its partition keeps consuming")
    void undeserializableRecordIsDeadLetteredWithItsOriginalBytes(String description, byte[] payload) {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");

        try (DeadLetterReader deadLetters = openDeadLetterReader()) {
            SendResult<String, Object> poison = sendRaw(jackpotId, payload);
            SendResult<String, Object> next = sendEvent(event(betId, jackpotId, "100.00"));
            assertThat(next.getRecordMetadata().partition()).as("same key, same partition")
                    .isEqualTo(poison.getRecordMetadata().partition());

            ConsumerRecord<String, byte[]> dead = deadLetters.await(DeadLetterReader.withKey(jackpotId));

            assertThat(dead.value()).as("the original bytes, untouched").isEqualTo(payload);
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .isEqualTo(DeserializationException.class.getName());
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                    .as("the Jackson parse error is the root cause").contains("Caused by: tools.jackson.");
            assertOriginCoordinates(dead, poison);
        }
        MvcTestResult evaluation = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);
        assertThat(text(evaluation, "$.outcome")).isEqualTo("LOST");
        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("1005.00");
    }

    static Stream<Arguments> invalidBets() {
        return Stream.of(
                arguments("negative amount", "betAmount", "-5.00", "amount must be positive"),
                arguments("zero amount", "betAmount", "0.00", "amount must be positive"),
                arguments("more than two decimals", "betAmount", "10.005", "at most 2 decimals"),
                arguments("amount above the maximum", "betAmount", "1000000000.01", "must not exceed"),
                arguments("missing amount", "betAmount", null, "amount must not be null"),
                arguments("blank user id", "userId", "\"\"", "userId must match"),
                arguments("illegal characters in the jackpot id", "jackpotId", "\"jackpot one!\"",
                        "jackpotId must match"),
                arguments("missing placedAt", "placedAt", null, "placedAt must not be null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBets")
    @DisplayName("a well-formed record breaking a bet invariant is dead-lettered (InvalidBetException), nothing stored")
    void invalidBetIsDeadLettered(String description, String field, String jsonValue, String expectedMessage) {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String invalidBetId = uniqueId("invalid-bet");
        String nextBetId = uniqueId("bet");
        Map<String, Long> callsBefore = listenerCallsByError();

        try (DeadLetterReader deadLetters = openDeadLetterReader()) {
            SendResult<String, Object> invalid = sendJson(jackpotId,
                    betJsonWithField(invalidBetId, jackpotId, field, jsonValue));
            sendEvent(event(nextBetId, jackpotId, "100.00"));

            ConsumerRecord<String, byte[]> dead = deadLetters.await(DeadLetterReader.withKey(jackpotId));

            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN))
                    .isEqualTo(ListenerExecutionFailedException.class.getName());
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                    .isEqualTo(InvalidBetException.class.getName());
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains(expectedMessage);
            assertThat(DeadLetterReader.value(dead)).as("the consumed payload, re-serialized as JSON")
                    .contains("\"betId\":\"" + invalidBetId + "\"");
            assertOriginCoordinates(dead, invalid);
        }
        assertThat(listenerFailuresSince(callsBefore, InvalidBetException.class.getSimpleName()))
                .as("not retried: dead-lettered after the first attempt").isOne();
        awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", nextBetId);
        assertThat(ledger.rowsOfBet("bet", invalidBetId)).isZero();
        assertThat(jackpots.pool(jackpotId)).as("only the valid bet contributed").isEqualByComparingTo("1005.00");
    }

    static Stream<Arguments> tolerablePayloadVariations() {
        Header foreignType = new RecordHeader("__TypeId__",
                "com.example.payments.ForeignBetType".getBytes(StandardCharsets.UTF_8));
        return Stream.of(
                arguments("unknown extra JSON fields", ",\"channel\":\"mobile\",\"promo\":{\"code\":\"X1\"}",
                        new Header[0]),
                arguments("a foreign __TypeId__ header", "", new Header[] {foreignType}),
                arguments("both", ",\"channel\":\"mobile\"", new Header[] {foreignType}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tolerablePayloadVariations")
    @DisplayName("extra JSON fields and foreign type headers are ignored: the bet is processed normally")
    void tolerablePayloadVariationIsProcessed(String description, String extraJson, Header[] headers) {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");

        sendJson(jackpotId, VALID_BET_TEMPLATE.formatted(betId, jackpotId, extraJson), headers);

        MvcTestResult contribution = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/contribution", betId);
        assertThat(decimal(contribution, "$.contributionAmount")).isEqualByComparingTo("5.00");
        assertThat(text(contribution, "$.userId")).isEqualTo("user-e2e");
        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("1005.00");
    }

    @Test
    @DisplayName("a tombstone is skipped without dead-lettering; the next record of its partition is processed")
    void tombstoneIsSkipped() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");

        sendTombstone(jackpotId);
        sendEvent(event(betId, jackpotId, "100.00"));

        awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);
        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("1005.00");
        assertThat(deadLettersWithKey(jackpotId)).isEmpty();
    }

    @Test
    @DisplayName("a non-duplicate integrity violation (double payout of a cycle) is dead-lettered and rolled back")
    void integrityViolationOfAnUnprocessedBetIsDeadLettered() {
        String jackpotId = jackpots.create("100.00", fixedContribution("10.0"), fixedChance("100"));
        ledger.insertPaidOutCycle(jackpotId, 1, uniqueId("fixture-bet"));
        String betId = uniqueId("bet");
        Map<String, Long> callsBefore = listenerCallsByError();

        try (DeadLetterReader deadLetters = openDeadLetterReader()) {
            SendResult<String, Object> sent = sendEvent(event(betId, jackpotId, "50.00"));

            ConsumerRecord<String, byte[]> dead = deadLetters.await(DeadLetterReader.withKey(jackpotId));

            assertThat(causeOf(dead)).isAssignableTo(DataIntegrityViolationException.class);
            assertThat(listenerFailuresSince(callsBefore, causeOf(dead).getSimpleName()))
                    .as("not retried: dead-lettered after the first attempt").isOne();
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                    .containsIgnoringCase("uk_jackpot_reward_cycle");
            assertOriginCoordinates(dead, sent);
        }
        assertThat(mvc.get().uri("/api/v1/bets/{betId}", betId).exchange()).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isZero();
        JackpotLedger.JackpotRow jackpot = ledger.jackpot(jackpotId);
        assertThat(jackpot.currentPool()).isEqualByComparingTo("100.00");
        assertThat(jackpot.cycle()).isEqualTo(1);
    }

    @Test
    @DisplayName("a jackpot with an invalid stored policy dead-letters its bets as JackpotConfigurationException")
    void betOnMisconfiguredJackpotIsDeadLettered() {
        String jackpotId = uniqueId("broken-jackpot");
        jackpots.insert(jackpotId, "1000.00", "1000.00", 1, fixedContribution("150.0"), fixedChance("0"));
        String betId = uniqueId("bet");
        Map<String, Long> callsBefore = listenerCallsByError();
        try (DeadLetterReader deadLetters = openDeadLetterReader()) {
            sendEvent(event(betId, jackpotId, "100.00"));

            ConsumerRecord<String, byte[]> dead = deadLetters.await(DeadLetterReader.withKey(jackpotId));

            assertThat(listenerFailuresSince(callsBefore, causeOf(dead).getSimpleName()))
                    .as("not retried: dead-lettered after the first attempt").isOne();
            // Hibernate wraps the converter's exception; the error handler finds it in the cause chain
            assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                    .contains(JackpotConfigurationException.class.getName() + ": Invalid contribution policy JSON");
        } finally {
            // the broken row must not outlive the test: listing all jackpots would fail for every other test
            jdbcTemplate.update("DELETE FROM jackpot WHERE id = ?", jackpotId);
        }
        assertThat(ledger.rowsOfBet("bet", betId)).isZero();
    }

    @Test
    @DisplayName("a transient failure (jackpot row locked past the lock timeout) is retried until the bet is processed")
    void lockTimeoutIsRetriedUntilTheBetIsProcessed() throws SQLException {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");
        Map<String, Long> callsBefore = listenerCallsByError();

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT id FROM jackpot WHERE id = ? FOR UPDATE")) {
                lock.setString(1, jackpotId);
                lock.executeQuery().close();
            }
            int blockerSession = sessionId(blocker);

            sendEvent(event(betId, jackpotId, "100.00"));

            OffsetDateTime firstAttempt = await().atMost(AWAIT_AT_MOST).pollInterval(POLL_INTERVAL)
                    .until(() -> statementBlockedBy(blockerSession), start -> start != null);
            await("the lock wait timed out and the record was retried in a new transaction")
                    .atMost(AWAIT_AT_MOST).pollInterval(POLL_INTERVAL)
                    .until(() -> statementBlockedBy(blockerSession),
                            start -> start != null && start.isAfter(firstAttempt));
            assertThat(ledger.rowsOfBet("bet", betId)).as("nothing written while the row is locked").isZero();
            blocker.rollback();
        }

        MvcTestResult evaluation = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);
        assertThat(text(evaluation, "$.outcome")).isEqualTo("LOST");
        assertThat(jackpots.pool(jackpotId)).as("processed exactly once").isEqualByComparingTo("1005.00");
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isOne();
        assertThat(listenerFailuresSince(callsBefore, CannotAcquireLockException.class.getSimpleName()))
                .as("the lock timeout surfaced as a transient CannotAcquireLockException").isPositive();
        assertThat(deadLettersWithKey(jackpotId)).as("transient failures are never dead-lettered").isEmpty();
    }

    private void assertOriginCoordinates(ConsumerRecord<String, byte[]> dead, SendResult<String, Object> original) {
        assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(betsTopic());
        assertThat(KafkaTopicContents.intHeader(dead, KafkaHeaders.DLT_ORIGINAL_PARTITION))
                .isEqualTo(original.getRecordMetadata().partition());
        assertThat(KafkaTopicContents.longHeader(dead, KafkaHeaders.DLT_ORIGINAL_OFFSET))
                .isEqualTo(original.getRecordMetadata().offset());
        assertThat(DeadLetterReader.header(dead, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo(consumerGroup());
    }

    private String consumerGroup() {
        MessageListenerContainer container = listenerRegistry.getListenerContainers().iterator().next();
        return container.getGroupId();
    }

    private static Class<?> causeOf(ConsumerRecord<String, byte[]> dead) {
        try {
            return Class.forName(DeadLetterReader.header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN));
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Unknown exception class in the dead-letter headers", e);
        }
    }

    /** H2: start time of the statement currently waiting for a lock held by {@code blockerSession}, or null. */
    private OffsetDateTime statementBlockedBy(int blockerSession) {
        List<OffsetDateTime> starts = jdbcTemplate.queryForList(
                "SELECT executing_statement_start FROM information_schema.sessions WHERE blocker_id = ?",
                OffsetDateTime.class, blockerSession);
        return starts.isEmpty() ? null : starts.getFirst();
    }

    private static int sessionId(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT SESSION_ID()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** A bet JSON whose {@code field} is replaced by the raw JSON {@code value}, or left out when it is null. */
    private static String betJsonWithField(String betId, String jackpotId, String field, String value) {
        StringBuilder json = new StringBuilder("{");
        appendField(json, "betId", field, "\"" + betId + "\"", value);
        appendField(json, "userId", field, "\"user-e2e\"", value);
        appendField(json, "jackpotId", field, "\"" + jackpotId + "\"", value);
        appendField(json, "betAmount", field, "100.00", value);
        appendField(json, "placedAt", field, "\"2026-01-01T00:00:00Z\"", value);
        return json.append('}').toString();
    }

    private static void appendField(StringBuilder json, String name, String replacedField, String validValue,
                                    String replacement) {
        String value = name.equals(replacedField) ? replacement : validValue;
        if (value != null) {
            json.append(json.length() > 1 ? "," : "").append('"').append(name).append("\":").append(value);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
