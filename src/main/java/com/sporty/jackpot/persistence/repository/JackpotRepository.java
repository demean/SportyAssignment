package com.sporty.jackpot.persistence.repository;

import com.sporty.jackpot.persistence.entity.JackpotEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Jackpots. Every pool change goes through {@link #findByIdForUpdate(String)}.
 */
public interface JackpotRepository extends JpaRepository<JackpotEntity, String> {

    /**
     * Loads a jackpot and locks its row ({@code SELECT ... FOR UPDATE}; {@code FOR NO KEY UPDATE} on PostgreSQL)
     * until the surrounding transaction ends. The lock wait is bounded by the connection-level lock timeout.
     *
     * @param id jackpot id
     * @return the locked jackpot, or empty when it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JackpotEntity j where j.id = :id")
    Optional<JackpotEntity> findByIdForUpdate(@Param("id") String id);
}
