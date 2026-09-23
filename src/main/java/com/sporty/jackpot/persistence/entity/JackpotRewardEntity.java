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
 * A jackpot payout ({@code jackpot_reward} table): at most one per bet and one per jackpot cycle.
 */
@Entity
@Table(name = "jackpot_reward")
public class JackpotRewardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "jackpot_reward_seq")
    @SequenceGenerator(name = "jackpot_reward_seq", sequenceName = "jackpot_reward_seq", allocationSize = 50)
    private Long id;

    @Column(name = "bet_id", length = 64, nullable = false, unique = true)
    private String betId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "jackpot_id", length = 64, nullable = false)
    private String jackpotId;

    @Column(name = "jackpot_reward_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal jackpotRewardAmount;

    @Column(name = "pool_cycle", nullable = false)
    private long poolCycle;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JackpotRewardEntity() {
    }

    public JackpotRewardEntity(String betId, String userId, String jackpotId, BigDecimal jackpotRewardAmount,
                               long poolCycle, Instant createdAt) {
        this.betId = betId;
        this.userId = userId;
        this.jackpotId = jackpotId;
        this.jackpotRewardAmount = jackpotRewardAmount;
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

    public BigDecimal getJackpotRewardAmount() {
        return jackpotRewardAmount;
    }

    public long getPoolCycle() {
        return poolCycle;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
