package com.sporty.jackpot.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A bet's contribution to a jackpot pool ({@code jackpot_contribution} table, one row per contributing bet).
 */
@Entity
@Table(name = "jackpot_contribution")
public class JackpotContributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "jackpot_contribution_seq")
    @SequenceGenerator(name = "jackpot_contribution_seq", sequenceName = "jackpot_contribution_seq", allocationSize = 50)
    private Long id;

    @Column(name = "bet_id", length = 64, nullable = false, unique = true)
    private String betId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "jackpot_id", length = 64, nullable = false)
    private String jackpotId;

    @Column(name = "stake_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal stakeAmount;

    @Column(name = "contribution_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal contributionAmount;

    @Column(name = "current_jackpot_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal currentJackpotAmount;

    @Column(name = "pool_cycle", nullable = false)
    private long poolCycle;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JackpotContributionEntity() {
    }

    public JackpotContributionEntity(String betId, String userId, String jackpotId, BigDecimal stakeAmount,
                                     BigDecimal contributionAmount, BigDecimal currentJackpotAmount, long poolCycle,
                                     Instant createdAt) {
        this.betId = betId;
        this.userId = userId;
        this.jackpotId = jackpotId;
        this.stakeAmount = stakeAmount;
        this.contributionAmount = contributionAmount;
        this.currentJackpotAmount = currentJackpotAmount;
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

    public BigDecimal getStakeAmount() {
        return stakeAmount;
    }

    public BigDecimal getContributionAmount() {
        return contributionAmount;
    }

    public BigDecimal getCurrentJackpotAmount() {
        return currentJackpotAmount;
    }

    public long getPoolCycle() {
        return poolCycle;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
