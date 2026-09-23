package com.sporty.jackpot.persistence.entity;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.persistence.converter.ContributionPolicyConverter;
import com.sporty.jackpot.persistence.converter.RewardPolicyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A jackpot and its pool ({@code jackpot} table). Pool changes happen only through {@link #addContribution} and
 * {@link #award}, under a pessimistic row lock; {@code @Version} is a safety net.
 */
@Entity
@Table(name = "jackpot")
public class JackpotEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "initial_pool_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal initialPoolAmount;

    @Column(name = "current_pool_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal currentPoolAmount;

    @Convert(converter = ContributionPolicyConverter.class)
    @Column(name = "contribution_policy", length = 2000, nullable = false)
    private ContributionPolicy contributionPolicy;

    @Convert(converter = RewardPolicyConverter.class)
    @Column(name = "reward_policy", length = 2000, nullable = false)
    private RewardPolicy rewardPolicy;

    @Column(name = "pool_cycle", nullable = false)
    private long cycle;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JackpotEntity() {
    }

    public JackpotEntity(String id, String name, BigDecimal initialPoolAmount, BigDecimal currentPoolAmount,
                         ContributionPolicy contributionPolicy, RewardPolicy rewardPolicy, long cycle,
                         Instant createdAt) {
        this.id = id;
        this.name = name;
        this.initialPoolAmount = initialPoolAmount;
        this.currentPoolAmount = currentPoolAmount;
        this.contributionPolicy = contributionPolicy;
        this.rewardPolicy = rewardPolicy;
        this.cycle = cycle;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Adds a contribution to the pool.
     *
     * @param amount contribution amount (scale 2)
     * @param now    time of the change
     * @return the pool after the contribution
     */
    public BigDecimal addContribution(BigDecimal amount, Instant now) {
        currentPoolAmount = Money.normalize(currentPoolAmount.add(amount));
        updatedAt = now;
        return currentPoolAmount;
    }

    /**
     * Pays out the pool: resets it to the initial value and starts the next cycle.
     *
     * @param now time of the change
     * @return the pool before the reset (the reward)
     */
    public BigDecimal award(Instant now) {
        BigDecimal reward = currentPoolAmount;
        currentPoolAmount = initialPoolAmount;
        cycle++;
        updatedAt = now;
        return reward;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getInitialPoolAmount() {
        return initialPoolAmount;
    }

    public BigDecimal getCurrentPoolAmount() {
        return currentPoolAmount;
    }

    public ContributionPolicy getContributionPolicy() {
        return contributionPolicy;
    }

    public RewardPolicy getRewardPolicy() {
        return rewardPolicy;
    }

    public long getCycle() {
        return cycle;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
