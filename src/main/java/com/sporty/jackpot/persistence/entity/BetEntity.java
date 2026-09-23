package com.sporty.jackpot.persistence.entity;

import com.sporty.jackpot.domain.model.BetStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * A processed bet ({@code bet} table). The bet id is the assigned primary key and the idempotency key.
 *
 * <p>Implements {@link Persistable} so that saving a new bet always INSERTs (never merges into a row committed
 * concurrently by another consumer): a concurrent duplicate surfaces as a primary-key violation.
 */
@Entity
@Table(name = "bet")
public class BetEntity implements Persistable<String> {

    @Id
    @Column(name = "bet_id", length = 64, nullable = false)
    private String betId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "jackpot_id", length = 64, nullable = false)
    private String jackpotId;

    @Column(name = "bet_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal betAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private BetStatus status;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** {@code true} only for instances created by the business constructor (not for instances loaded by JPA). */
    @Transient
    private boolean newEntity;

    protected BetEntity() {
    }

    public BetEntity(String betId, String userId, String jackpotId, BigDecimal betAmount, BetStatus status,
                     Instant placedAt, Instant processedAt) {
        this.betId = betId;
        this.userId = userId;
        this.jackpotId = jackpotId;
        this.betAmount = betAmount;
        this.status = status;
        this.placedAt = placedAt;
        this.processedAt = processedAt;
        this.newEntity = true;
    }

    @Override
    public String getId() {
        return betId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getUserId() {
        return userId;
    }

    public String getJackpotId() {
        return jackpotId;
    }

    public BigDecimal getBetAmount() {
        return betAmount;
    }

    public BetStatus getStatus() {
        return status;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
