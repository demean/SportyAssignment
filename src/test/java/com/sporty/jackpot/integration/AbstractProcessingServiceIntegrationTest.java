package com.sporty.jackpot.integration;

import com.sporty.jackpot.messaging.BetEventListener;
import com.sporty.jackpot.service.BetProcessingService;
import com.sporty.jackpot.support.TestJackpots;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base of the service-level integration tests: the whole application on its real in-memory H2 database, but without
 * Kafka consumers (the test configuration disables their auto-startup and topic creation, and
 * {@link NoKafkaConsumersTestConfiguration} keeps it that way when the cached context is resumed), and with a
 * {@link ScriptedRandomGenerator} for deterministic draws. Tests call the services directly, never through Kafka.
 * Subclasses share one cached context: no context-changing annotations, not {@code @Transactional}, unique ids only.
 *
 * <p>The concurrency scenarios run {@value ConcurrencyScenarios#THREADS} threads against the connection pool of 10,
 * and every transaction on the shared jackpot serializes on its row lock, so threads legitimately queue for a pooled
 * connection for as long as the whole scenario takes. The pool-acquire timeout is therefore raised from the
 * production 2 s (sized for 3 consumer threads per instance) so that a slow machine cannot turn that queueing into a
 * {@code CannotCreateTransactionException}; the row-lock wait stays bounded by the unchanged 3 s lock timeout.
 */
@SpringBootTest(properties = ConcurrencyScenarios.POOL_ACQUIRE_TIMEOUT_PROPERTY)
@Import({ScriptedRandomTestConfiguration.class, NoKafkaConsumersTestConfiguration.class})
abstract class AbstractProcessingServiceIntegrationTest {

    @Autowired
    protected BetProcessingService processingService;

    @Autowired
    protected BetEventListener betEventListener;

    @Autowired
    protected ScriptedRandomGenerator random;

    @Autowired
    protected MeterRegistry meterRegistry;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected Clock clock;

    protected TestJackpots jackpots;
    protected JackpotLedger ledger;
    protected ConcurrencyScenarios scenarios;

    @BeforeEach
    void setUpProcessingServiceIntegrationTest() {
        jackpots = new TestJackpots(jdbcTemplate);
        ledger = new JackpotLedger(jdbcTemplate);
        scenarios = new ConcurrencyScenarios(processingService, betEventListener, jackpots, ledger, random,
                meterRegistry, clock);
        random.reset();
    }
}
