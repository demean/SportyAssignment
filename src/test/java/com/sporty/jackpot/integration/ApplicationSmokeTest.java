package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.support.AbstractKafkaIntegrationTest;
import java.math.BigDecimal;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * End-to-end smoke test: HTTP -> Kafka -> processing -> HTTP lookups.
 */
class ApplicationSmokeTest extends AbstractKafkaIntegrationTest {

    @Test
    void betReachingThePoolLimitWinsThePoolAndResetsTheJackpot() {
        String jackpotId = jackpots.createLuckyLike();
        String betId = uniqueId("bet");

        assertThat(placeBet(betId, "user-1", jackpotId, "250.00"))
                .hasStatus(HttpStatus.ACCEPTED)
                .hasHeader(HttpHeaders.LOCATION, "/api/v1/bets/" + betId)
                .bodyJson().extractingPath("$.status").isEqualTo("ACCEPTED");

        MvcTestResult evaluation = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);
        assertThat(evaluation).bodyJson().extractingPath("$.outcome").isEqualTo("WON");
        assertThat(evaluation).bodyJson().extractingPath("$.won").isEqualTo(true);
        assertThat(evaluation).bodyJson().extractingPath("$.rewardAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("150.00");
        assertThat(evaluation).bodyJson().extractingPath("$.winChancePercentage")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("100");

        MvcTestResult contribution = mvc.get().uri("/api/v1/bets/{betId}/contribution", betId).exchange();
        assertThat(contribution).hasStatusOk();
        assertThat(contribution).bodyJson().extractingPath("$.contributionAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("50.00");
        assertThat(contribution).bodyJson().extractingPath("$.currentJackpotAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("150.00");

        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("100.00");
        assertThat(jackpots.cycle(jackpotId)).isEqualTo(2);
        MvcTestResult jackpot = mvc.get().uri("/api/v1/jackpots/{jackpotId}", jackpotId).exchange();
        assertThat(jackpot).hasStatusOk();
        assertThat(jackpot).bodyJson().extractingPath("$.cycle").isEqualTo(2);
        assertThat(jackpot).bodyJson().extractingPath("$.currentPoolAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("100.00");
    }

    @Test
    void losingBetContributesToThePool() {
        String jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        String betId = uniqueId("bet");

        assertThat(placeBet(betId, "user-2", jackpotId, "100.00")).hasStatus(HttpStatus.ACCEPTED);

        MvcTestResult evaluation = awaitStatus(HttpStatus.OK, "/api/v1/bets/{betId}/evaluation", betId);
        assertThat(evaluation).bodyJson().extractingPath("$.outcome").isEqualTo("LOST");
        assertThat(evaluation).bodyJson().extractingPath("$.won").isEqualTo(false);
        assertThat(evaluation).bodyJson().extractingPath("$.rewardAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo(BigDecimal.ZERO);

        MvcTestResult bet = mvc.get().uri("/api/v1/bets/{betId}", betId).exchange();
        assertThat(bet).hasStatusOk();
        assertThat(bet).bodyJson().extractingPath("$.status").isEqualTo("CONTRIBUTED");
        assertThat(bet).bodyJson().extractingPath("$.userId").isEqualTo("user-2");

        MvcTestResult contribution = mvc.get().uri("/api/v1/bets/{betId}/contribution", betId).exchange();
        assertThat(contribution).bodyJson().extractingPath("$.contributionAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("5.00");
        assertThat(contribution).bodyJson().extractingPath("$.currentJackpotAmount")
                .convertTo(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("1005.00");

        assertThat(jackpots.pool(jackpotId)).isEqualByComparingTo("1005.00");
        assertThat(jackpots.cycle(jackpotId)).isEqualTo(1);
    }
}
