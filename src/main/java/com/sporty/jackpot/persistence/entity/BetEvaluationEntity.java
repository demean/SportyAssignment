package com.sporty.jackpot.persistence.entity;

import com.sporty.jackpot.domain.model.EvaluationOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The (single) reward evaluation of a contributing bet ({@code bet_evaluation} table).
 */
@Entity
@Table(name = "bet_evaluation")
public class BetEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bet_evaluation_seq")
    @SequenceGenerator(name = "bet_evaluation_seq", sequenceName = "bet_evaluation_seq", allocationSize = 50)
    private Long id;

    @Column(name = "bet_id", length = 64, nullable = false, unique = true)
    private String betId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "jackpot_id", length = 64, nullable = false)
    private String jackpotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16, nullable = false)
    private EvaluationOutcome outcome;

    @Column(name = "win_chance_percentage", precision = 7, scale = 4, nullable = false)
    private BigDecimal winChancePercentage;

    @Column(name = "reward_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal rewardAmount;

    @Column(name = "pool_cycle", nullable = false)
    private long poolCycle;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BetEvaluationEntity() {
    }

    public BetEvaluationEntity(String betId, String userId, String jackpotId, EvaluationOutcome outcome,
                               BigDecimal winChancePercentage, BigDecimal rewardAmount, long poolCycle,
                               Instant createdAt) {
        this.betId = betId;
        this.userId = userId;
        this.jackpotId = jackpotId;
        this.outcome = outcome;
        this.winChancePercentage = winChancePercentage;
        this.rewardAmount = rewardAmount;
        this.poolCycle = poolCycle;
        this.createdAt = createdAt;
    }

    public String getBetId() {
        return betId;
    }

    public String getUserId() {
        return userId;
    }

    public String getJackpotId() {
        return jackpotId;
    }

    public EvaluationOutcome getOutcome() {
        return outcome;
    }

    public BigDecimal getWinChancePercentage() {
        return winChancePercentage;
    }

    public BigDecimal getRewardAmount() {
        return rewardAmount;
    }

    public long getPoolCycle() {
        return poolCycle;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
