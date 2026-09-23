package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static com.sporty.jackpot.support.TestJackpots.variableContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.sporty.jackpot.messaging.BetPlacedEvent;
import com.sporty.jackpot.support.DeadLetterReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The whole path of a bet: {@code POST /api/v1/bets} → Kafka → consumer → one database transaction → the
 * {@code GET} endpoints (traps T3, T4, T9, T10, T13, T14, T15 and the micro-bet guard of §1.3).
 */
@DisplayName("Bet flow: HTTP -> Kafka -> database -> HTTP (Embedded Kafka)")
class BetFlowIntegrationTest extends AbstractEndToEndIntegrationTest {

    @Test
    @DisplayName("a placed bet is published, processed and exposed consistently by all bet endpoints")
    void placedBetIsExposedByTheBetContributionAndEvaluationEndpoints() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");
        String userId = uniqueId("user");

        MvcTestResult accepted = placeBet(betId, userId, jackpotId, "200.00");

        assertThat(accepted).hasStatus(HttpStatus.ACCEPTED)
                .hasHeader(HttpHeaders.LOCATION, "/api/v1/bets/" + betId);
        assertThat(text(accepted, "$.betId")).isEqualTo(betId);
        assertThat(text(accepted, "$.jackpotId")).isEqualTo(jackpotId);
        assertThat(text(accepted, "$.status")).isEqualTo("ACCEPTED");
        String acceptedAt = text(accepted, "$.acceptedAt");

        MvcTestResult bet = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}", betId);
        assertThat(text(bet, "$.betId")).isEqualTo(betId);
        assertThat(text(bet, "$.userId")).isEqualTo(userId);
        assertThat(text(bet, "$.jackpotId")).isEqualTo(jackpotId);
        assertThat(text(bet, "$.status")).isEqualTo("CONTRIBUTED");
        assertThat(bet).bodyText().contains("\"betAmount\":200.00");
        assertThat(text(bet, "$.placedAt")).as("the acceptance instant survives Kafka and the database unchanged")
                .isEqualTo(acceptedAt);
        String processedAt = text(bet, "$.processedAt");
        assertThat(Instant.parse(processedAt)).isAfterOrEqualTo(Instant.parse(acceptedAt));

        MvcTestResult contribution = mvc.get().uri("/api/v1/bets/{betId}/contribution", betId).exchange();
        assertThat(contribution).hasStatusOk();
        assertThat(text(contribution, "$.userId")).isEqualTo(userId);
        assertThat(text(contribution, "$.jackpotId")).isEqualTo(jackpotId);
        assertThat(contribution).bodyText().contains("\"stakeAmount\":200.00", "\"contributionAmount\":10.00",
                "\"currentJackpotAmount\":1010.00");
        assertThat(text(contribution, "$.createdAt")).as("written in the same transaction").isEqualTo(processedAt);

        MvcTestResult evaluation = mvc.get().uri("/api/v1/bets/{betId}/evaluation", betId).exchange();
        assertThat(evaluation).hasStatusOk();
        assertThat(text(evaluation, "$.outcome")).isEqualTo("LOST");
        assertThat(json(evaluation).read("$.won", Boolean.class)).isFalse();
        assertThat(evaluation).bodyText().contains("\"rewardAmount\":0.00", "\"winChancePercentage\":0.0000");
        assertThat(text(evaluation, "$.evaluatedAt")).as("evaluated in the same transaction").isEqualTo(processedAt);

        MvcTestResult jackpot = mvc.get().uri("/api/v1/jackpots/{jackpotId}", jackpotId).exchange();
        assertThat(jackpot).hasStatusOk();
        assertThat(decimal(jackpot, "$.currentPoolAmount")).isEqualByComparingTo("1010.00");
        assertThat(json(jackpot).read("$.cycle", Integer.class)).isOne();
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isOne();
        assertThat(ledger.rowsOfBet("bet_evaluation", betId)).isOne();
    }

    @Test
    @DisplayName("asking for the evaluation again never re-draws: it is a read-only lookup of the stored result")
    void evaluationLookupIsReadOnly() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");
        assertThat(placeBet(betId, "user-reroll", jackpotId, "100.00")).hasStatus(HttpStatus.ACCEPTED);
        String firstAnswer = body(awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId));
        long versionAfterProcessing = jackpots.version(jackpotId);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(mvc.get().uri("/api/v1/bets/{betId}/evaluation", betId).exchange())
                    .hasStatusOk().bodyText().isEqualTo(firstAnswer);
        }

        assertThat(jackpots.version(jackpotId)).as("no jackpot write").isEqualTo(versionAfterProcessing);
        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("1005.00");
        assertThat(ledger.rowsOfBet("bet_evaluation", betId)).isOne();
    }

    @Test
    @DisplayName("a micro bet whose contribution rounds to 0.00 is recorded but never drawn, even on a 100 % jackpot")
    void microBetIsRecordedButNotDrawn() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("100"));
        String betId = uniqueId("bet");

        assertThat(placeBet(betId, "user-micro", jackpotId, "0.01")).hasStatus(HttpStatus.ACCEPTED);

        MvcTestResult evaluation = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);
        assertThat(text(evaluation, "$.outcome")).isEqualTo("LOST");
        assertThat(evaluation).bodyText().contains("\"winChancePercentage\":0.0000", "\"rewardAmount\":0.00");
        assertThat(mvc.get().uri("/api/v1/bets/{betId}/contribution", betId).exchange()).hasStatusOk()
                .bodyText().contains("\"contributionAmount\":0.00", "\"currentJackpotAmount\":1000.00");
        assertThat(jackpots.cycle(jackpotId)).as("not won").isOne();
        assertThat(jackpots.rewardCount(jackpotId)).isZero();
    }

    @Test
    @DisplayName("the API publishes each bet keyed by its jackpot id (per-jackpot order), as JSON without type headers")
    void apiPublishesBetsKeyedByJackpotId() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");
        MvcTestResult accepted = placeBet(betId, "user-key", jackpotId, "12.34");
        assertThat(accepted).hasStatus(HttpStatus.ACCEPTED);
        awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}", betId);

        List<ConsumerRecord<String, byte[]>> published = KafkaTopicContents.readAll(embeddedKafka, betsTopic()).stream()
                .filter(DeadLetterReader.withValueContaining("\"betId\":\"" + betId + "\""))
                .toList();

        assertThat(published).singleElement().satisfies(bet -> {
            assertThat(bet.key()).isEqualTo(jackpotId);
            assertThat(bet.headers().lastHeader("__TypeId__")).as("no type headers").isNull();
            assertThat(DeadLetterReader.value(bet)).isEqualTo("{\"betId\":\"" + betId + "\",\"userId\":\"user-key\","
                    + "\"jackpotId\":\"" + jackpotId + "\",\"betAmount\":12.34,\"placedAt\":\""
                    + text(accepted, "$.acceptedAt") + "\"}");
        });
    }

    @Test
    @DisplayName("bets on one jackpot are consumed in publish order, each contribution computed on the pool before it")
    void betsOnOneJackpotAreAppliedInPublishOrder() {
        String jackpotId = jackpots.create("1000.00", variableContribution("10.0", "2.0", "1.0", "100"),
                fixedChance("0"));
        List<String> betIds = List.of(uniqueId("bet-1"), uniqueId("bet-2"), uniqueId("bet-3"));

        betIds.forEach(betId -> assertThat(placeBet(betId, "user-order", jackpotId, "500.00"))
                .hasStatus(HttpStatus.ACCEPTED));

        // 10 % of 500 on 1000.00; 9.5 % on 1050.00; 9.025 % on 1097.50 (45.125 rounded half-even)
        List<String> expectedContributions = List.of("50.00", "47.50", "45.12");
        List<String> expectedPools = List.of("1050.00", "1097.50", "1142.62");
        for (int i = 0; i < betIds.size(); i++) {
            MvcTestResult contribution = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/contribution",
                    betIds.get(i));
            assertThat(decimal(contribution, "$.contributionAmount")).as("contribution of bet %d", i + 1)
                    .isEqualByComparingTo(expectedContributions.get(i));
            assertThat(decimal(contribution, "$.currentJackpotAmount")).as("pool after bet %d", i + 1)
                    .isEqualByComparingTo(expectedPools.get(i));
        }
        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("1142.62");
        ledger.assertConsistent(jackpotId);
    }

    @Test
    @DisplayName("a bet for an unknown jackpot is stored as NO_MATCHING_JACKPOT and acked (no DLT); lookups answer 422")
    void betForUnknownJackpotIsStoredWithoutContributionAndAnswers422() {
        String jackpotId = uniqueId("unknown-jackpot");
        String betId = uniqueId("bet");

        assertThat(placeBet(betId, "user-unknown", jackpotId, "25.00"))
                .as("publishing never checks the database").hasStatus(HttpStatus.ACCEPTED);

        MvcTestResult bet = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}", betId);
        assertThat(text(bet, "$.status")).isEqualTo("NO_MATCHING_JACKPOT");
        assertThat(text(bet, "$.jackpotId")).isEqualTo(jackpotId);
        for (String lookup : List.of("/api/v1/bets/{betId}/evaluation", "/api/v1/bets/{betId}/contribution")) {
            MvcTestResult result = mvc.get().uri(lookup, betId).exchange();
            assertThat(result).as(lookup).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(text(result, "$.code")).as(lookup).isEqualTo("BET_NOT_CONTRIBUTING");
            assertThat(json(result).read("$.status", Integer.class)).isEqualTo(422);
        }
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isZero();
        assertThat(ledger.rowsOfBet("bet_evaluation", betId)).isZero();
        assertThat(deadLettersWithKey(jackpotId)).as("an unknown jackpot is not a poison pill").isEmpty();
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/api/v1/bets/{betId}", "/api/v1/bets/{betId}/contribution",
            "/api/v1/bets/{betId}/evaluation"})
    @DisplayName("a bet that was never processed is 404 BET_NOT_FOUND on every lookup (clients keep polling)")
    void unprocessedBetIsNotFound(String lookup) {
        MvcTestResult result = mvc.get().uri(lookup, uniqueId("never-published")).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(text(result, "$.code")).isEqualTo("BET_NOT_FOUND");
    }

    static Stream<Arguments> redeliveredPayloads() {
        return Stream.of(
                arguments("identical payload (at-least-once redelivery)", "user-dup", "250.00"),
                arguments("same bet id re-sent with a higher stake", "user-dup", "999.99"),
                arguments("same bet id re-sent by another user", "someone-else", "900.00"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("redeliveredPayloads")
    @DisplayName("a bet id published twice is processed once: one contribution, one draw, pool counted once")
    void betIdPublishedTwiceIsProcessedOnce(String description, String duplicateUser, String duplicateAmount) {
        String jackpotId = jackpots.createLuckyLike();
        String betId = uniqueId("bet");
        BetPlacedEvent original = new BetPlacedEvent(betId, "user-dup", jackpotId, new BigDecimal("250.00"),
                Instant.parse("2026-01-01T00:00:00Z"));
        BetPlacedEvent duplicate = new BetPlacedEvent(betId, duplicateUser, jackpotId, new BigDecimal(duplicateAmount),
                Instant.parse("2026-01-01T00:00:01Z"));

        sendEvent(original);
        sendEvent(duplicate);
        awaitRecordsWithKeyConsumed(jackpotId);

        // re-processing would contribute again and win a second time (the jackpot pays 100 % at 150.00)
        assertOnlyTheOriginalWasApplied(jackpotId, betId);
        MvcTestResult bet = mvc.get().uri("/api/v1/bets/{betId}", betId).exchange();
        assertThat(text(bet, "$.userId")).isEqualTo("user-dup");
        assertThat(decimal(bet, "$.betAmount")).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("a client retrying POST with the same bet id (e.g. after a 503) gets 202 twice, the bet counts once")
    void clientRetryWithTheSameBetIdIsProcessedOnce() {
        String jackpotId = jackpots.createLuckyLike();
        String betId = uniqueId("bet");

        assertThat(placeBet(betId, "user-dup", jackpotId, "250.00")).hasStatus(HttpStatus.ACCEPTED);
        assertThat(placeBet(betId, "user-dup", jackpotId, "250.00")).hasStatus(HttpStatus.ACCEPTED);
        awaitRecordsWithKeyConsumed(jackpotId);

        assertOnlyTheOriginalWasApplied(jackpotId, betId);
    }

    private void assertOnlyTheOriginalWasApplied(String jackpotId, String betId) {
        MvcTestResult evaluation = mvc.get().uri("/api/v1/bets/{betId}/evaluation", betId).exchange();
        assertThat(evaluation).hasStatusOk();
        assertThat(text(evaluation, "$.outcome")).isEqualTo("WON");
        assertThat(decimal(evaluation, "$.rewardAmount")).isEqualByComparingTo("150.00");
        assertThat(ledger.rowsOfBet("bet", betId)).isOne();
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isOne();
        assertThat(ledger.rowsOfBet("bet_evaluation", betId)).as("a duplicate never re-draws").isOne();
        assertThat(ledger.rowsOfBet("jackpot_reward", betId)).isOne();
        JackpotLedger.Snapshot snapshot = ledger.assertConsistent(jackpotId);
        assertThat(snapshot.contributed()).as("the pool is counted once").isEqualByComparingTo("50.00");
        assertThat(snapshot.jackpot().currentPool()).isEqualByComparingTo("100.00");
        assertThat(snapshot.jackpot().cycle()).isEqualTo(2);
    }
}
