package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Startup check (fail fast): every stored jackpot's policies must be consistent with its initial pool. Also warns
 * when running on the volatile in-memory H2 database, which supports a single instance only.
 */
@Component
public class JackpotConfigurationValidator implements ApplicationRunner {

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
    public void run(ApplicationArguments args) {
        if (jdbcConnectionDetails.getJdbcUrl().startsWith(IN_MEMORY_H2_PREFIX)) {
            log.warn("Running on the in-memory H2 database: data is volatile and only a single instance is supported "
                    + "(use the 'postgres' profile to scale horizontally)");
        }
        List<Jackpot> jackpots = jackpotQueryService.findAll();
        jackpots.forEach(JackpotConfigurationValidator::validate);
        log.info("Validated the configuration of {} jackpot(s)", jackpots.size());
    }

    private static void validate(Jackpot jackpot) {
        try {
            jackpot.contributionPolicy().validateFor(jackpot.initialPoolAmount());
            jackpot.rewardPolicy().validateFor(jackpot.initialPoolAmount());
        } catch (JackpotConfigurationException e) {
            throw new JackpotConfigurationException("Jackpot '" + jackpot.id() + "' is misconfigured: "
                    + e.getMessage(), e);
        }
    }
}
