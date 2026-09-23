package com.sporty.jackpot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import com.sporty.jackpot.persistence.entity.JackpotRewardEntity;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Entity factories and constraint assertions shared by the persistence tests.
 *
 * <p>All instants have microsecond precision (the precision of {@code TIMESTAMP WITH TIME ZONE}), so persisted and
 * reloaded values are identical.
 */
public final class PersistenceTestSupport {

    public static final Instant PLACED_AT = Instant.parse("2026-03-01T10:15:30.123456Z");
    public static final Instant PROCESSED_AT = Instant.parse("2026-03-01T10:15:31.654321Z");
    public static final Instant NOW = Instant.parse("2026-03-01T10:15:32.000001Z");

    private PersistenceTestSupport() {
    }

    /** @return a new CONTRIBUTED bet of 10.00 */
    public static BetEntity bet(String betId, String jackpotId) {
        return new BetEntity(betId, "user-" + betId, jackpotId, new BigDecimal("10.00"), BetStatus.CONTRIBUTED,
                PLACED_AT, PROCESSED_AT);
    }

    /** @return a 5 % contribution of a 10.00 stake that took the pool to 1000.50 */
    public static JackpotContributionEntity contribution(String betId, String jackpotId, long cycle) {
        return new JackpotContributionEntity(betId, "user-" + betId, jackpotId, new BigDecimal("10.00"),
                new BigDecimal("0.50"), new BigDecimal("1000.50"), cycle, PROCESSED_AT);
    }

    /** @return a reward of 1000.50 */
    public static JackpotRewardEntity reward(String betId, String jackpotId, long cycle) {
        return new JackpotRewardEntity(betId, "user-" + betId, jackpotId, new BigDecimal("1000.50"), cycle,
                PROCESSED_AT);
    }

    /** @return a LOST evaluation with a 1 % chance */
    public static BetEvaluationEntity evaluation(String betId, String jackpotId, long cycle) {
        return evaluation(betId, jackpotId, cycle, new BigDecimal("1.0000"));
    }

    /** @return a LOST evaluation with the given chance */
    public static BetEvaluationEntity evaluation(String betId, String jackpotId, long cycle, BigDecimal chance) {
        return new BetEvaluationEntity(betId, "user-" + betId, jackpotId, EvaluationOutcome.LOST, chance,
                new BigDecimal("0.00"), cycle, PROCESSED_AT);
    }

    /**
     * Asserts that the call fails with a {@link DataIntegrityViolationException} caused by the named constraint.
     * The constraint name is searched in every message of the cause chain (including chained SQL exceptions of a
     * JDBC batch), case-insensitively.
     *
     * @param constraint constraint (or index) name, e.g. {@code uk_jackpot_reward_cycle}
     * @param call       the failing call
     */
    public static void assertViolates(String constraint, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(allMessages(e)).containsIgnoringCase(constraint));
    }

    private static String allMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = throwable; t != null && seen.put(t, Boolean.TRUE) == null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
            if (t instanceof SQLException sql) {
                for (SQLException next = sql.getNextException(); next != null && seen.put(next, Boolean.TRUE) == null;
                     next = next.getNextException()) {
                    messages.append(next.getMessage()).append('\n');
                }
            }
        }
        return messages.toString();
    }
}
