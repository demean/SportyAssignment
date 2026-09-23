package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Startup check (fail fast): every stored jackpot's policies must be consistent with its initial pool. It runs once
 * all singletons exist (Flyway has migrated, JPA is ready) but before the context starts its lifecycle beans, so a
 * misconfigured jackpot stops the application before the Kafka listener containers join the consumer group and
 * before the web server accepts requests. {@link BetProcessingService} repeats the check for every bet, which covers
 * a jackpot changed while instances are running. Also warns when running on the volatile in-memory H2 database,
 * which supports a single instance only.
 */
@Component
public class JackpotConfigurationValidator implements SmartInitializingSingleton {

    static final String IN_MEMORY_H2_PREFIX = "jdbc:h2:mem:";

    private static final Logger log = LoggerFactory.getLogger(JackpotConfigurationValidator.class);

    private final JackpotQueryService jackpotQueryService;
    private final JdbcConnectionDetails jdbcConnectionDetails;

    public JackpotConfigurationValidator(JackpotQueryService jackpotQueryService,
                                         JdbcConnectionDetails jdbcConnectionDetails) {
        this.jackpotQueryService = jackpotQueryService;
        this.jdbcConnectionDetails = jdbcConnectionDetails;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (jdbcConnectionDetails.getJdbcUrl().startsWith(IN_MEMORY_H2_PREFIX)) {
            log.warn("Running on the in-memory H2 database: data is volatile and only a single instance is supported "
                    + "(use the 'postgres' profile to scale horizontally)");
        }
        List<Jackpot> jackpots = jackpotQueryService.findAll();
        jackpots.forEach(jackpot -> validate(jackpot.id(), jackpot.initialPoolAmount(), jackpot.contributionPolicy(),
                jackpot.rewardPolicy()));
        log.info("Validated the configuration of {} jackpot(s)", jackpots.size());
    }

    /**
     * Cross-field check of a jackpot's policies against its initial pool (e.g. a variable-chance {@code poolLimit}
     * not above the initial pool would make every contributing bet win the whole pool).
     *
     * @throws JackpotConfigurationException naming the jackpot, when a policy is inconsistent
     */
    static void validate(String jackpotId, BigDecimal initialPool, ContributionPolicy contributionPolicy,
                         RewardPolicy rewardPolicy) {
        try {
            contributionPolicy.validateFor(initialPool);
            rewardPolicy.validateFor(initialPool);
        } catch (JackpotConfigurationException e) {
            throw new JackpotConfigurationException("Jackpot '" + jackpotId + "' is misconfigured: " + e.getMessage(), e);
        }
    }
}
