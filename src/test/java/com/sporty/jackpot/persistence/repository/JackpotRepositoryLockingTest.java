package com.sporty.jackpot.persistence.repository;

import static com.sporty.jackpot.persistence.PersistenceTestSupport.NOW;
import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sporty.jackpot.persistence.entity.JackpotEntity;
import com.sporty.jackpot.support.TestJackpots;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row locking of {@link JackpotRepository#findByIdForUpdate} with real concurrent transactions (the test itself runs
 * without a transaction, every repository call commits). The lock wait is the shipped H2 URL's
 * {@code LOCK_TIMEOUT=3000} (the tests only rename the in-memory database).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("JackpotRepository locking")
class JackpotRepositoryLockingTest {

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(3);

    @Autowired
    private JackpotRepository jackpotRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdJackpots = new ArrayList<>();
    private final CountDownLatch releaseLock = new CountDownLatch(1);
    private ExecutorService executor;
    private TransactionTemplate tx;
    private TestJackpots jackpots;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        tx = new TransactionTemplate(transactionManager);
        jackpots = new TestJackpots(jdbcTemplate);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseLock.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
        // this class commits: remove its rows so they never leak into other tests sharing the context
        createdJackpots.forEach(id -> jdbcTemplate.update("DELETE FROM jackpot WHERE id = ?", id));
    }

    private String createJackpot() {
        String id = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("0"));
        createdJackpots.add(id);
        return id;
    }

    /**
     * Locks the jackpot in a transaction on another thread; the transaction adds {@code contribution} to the pool
     * and commits once {@link #releaseLock} is counted down.
     */
    private Future<?> lockInBackground(String jackpotId, String contribution) throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
            JackpotEntity jackpot = jackpotRepository.findByIdForUpdate(jackpotId).orElseThrow();
            locked.countDown();
            awaitRelease();
            jackpot.addContribution(new BigDecimal(contribution), NOW);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("lock acquired").isTrue();
        return holder;
    }

    private void awaitRelease() {
        try {
            assertThat(releaseLock.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int blockedSessions() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL", Integer.class);
    }

    @Test
    @DisplayName("a second findByIdForUpdate waits for the row lock and fails with CannotAcquireLockException "
            + "after the lock timeout")
    void secondLockerTimesOut() throws Exception {
        String id = createJackpot();
        Future<?> holder = lockInBackground(id, "5.00");

        long start = System.nanoTime();
        Future<?> contender = executor.submit(() -> tx.executeWithoutResult(
                status -> jackpotRepository.findByIdForUpdate(id)));

        assertThatThrownBy(() -> contender.get(LOCK_TIMEOUT.multipliedBy(5).toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(CannotAcquireLockException.class)
                .isInstanceOf(PessimisticLockingFailureException.class);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        assertThat(waited).as("the contender really waited for the lock")
                .isGreaterThanOrEqualTo(LOCK_TIMEOUT.minusMillis(500));

        releaseLock.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(jackpots.pool(id)).as("the lock holder's change is committed").isEqualByComparingTo("1005.00");
    }

    @Test
    @DisplayName("a waiting locker proceeds when the holder commits and sees the committed pool (no lost update)")
    void waitingLockerSeesTheCommittedPool() throws Exception {
        String id = createJackpot();
        Future<?> holder = lockInBackground(id, "5.00");

        Future<BigDecimal> contender = executor.submit(() -> tx.execute(status -> {
            JackpotEntity jackpot = jackpotRepository.findByIdForUpdate(id).orElseThrow();
            BigDecimal seen = jackpot.getCurrentPoolAmount();
            jackpot.addContribution(new BigDecimal("2.50"), NOW);
            return seen;
        }));
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertThat(blockedSessions()).as("contender waits on the row lock").isOne());

        releaseLock.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(contender.get(10, TimeUnit.SECONDS)).isEqualByComparingTo("1005.00");
        assertThat(jackpots.pool(id)).isEqualByComparingTo("1007.50");
        assertThat(jackpots.version(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("the lock is per jackpot row: another jackpot can be locked meanwhile")
    void lockingAnotherJackpotIsNotBlocked() throws Exception {
        String locked = createJackpot();
        String other = createJackpot();
        lockInBackground(locked, "0.00");

        Future<String> contender = executor.submit(() -> tx.execute(
                status -> jackpotRepository.findByIdForUpdate(other).orElseThrow().getId()));

        assertThat(contender.get(LOCK_TIMEOUT.toMillis() / 2, TimeUnit.MILLISECONDS)).isEqualTo(other);
    }

    @Test
    @DisplayName("plain reads (queries) are not blocked by the row lock and see the last committed state")
    void plainReadsAreNotBlocked() throws Exception {
        String id = createJackpot();
        lockInBackground(id, "5.00");

        Future<BigDecimal> reader = executor.submit(() -> tx.execute(
                status -> jackpotRepository.findById(id).orElseThrow().getCurrentPoolAmount()));

        assertThat(reader.get(LOCK_TIMEOUT.toMillis() / 2, TimeUnit.MILLISECONDS)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("@Version rejects a write from a stale copy loaded before a concurrent committed change")
    void versionRejectsStaleWrites() {
        String id = createJackpot();
        JackpotEntity stale = tx.execute(status -> jackpotRepository.findById(id).orElseThrow());
        tx.executeWithoutResult(status -> jackpotRepository.findByIdForUpdate(id).orElseThrow()
                .addContribution(new BigDecimal("10.00"), NOW));

        stale.addContribution(new BigDecimal("5.00"), NOW);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jackpotRepository.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jackpots.pool(id)).isEqualByComparingTo("1010.00");
        assertThat(jackpots.version(id)).isEqualTo(1);
    }
}
