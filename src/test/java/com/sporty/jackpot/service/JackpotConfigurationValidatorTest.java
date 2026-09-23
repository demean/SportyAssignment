package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

@ExtendWith(MockitoExtension.class)
@DisplayName("JackpotConfigurationValidator")
class JackpotConfigurationValidatorTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/jackpot";
    private static final String H2_WARNING = "Running on the in-memory H2 database: data is volatile and only a single "
            + "instance is supported (use the 'postgres' profile to scale horizontally)";
    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Mock
    private JackpotQueryService jackpotQueryService;
    @Mock
    private JdbcConnectionDetails jdbcConnectionDetails;

    private static Jackpot jackpot(String id, String initialPool, ContributionPolicy contributionPolicy,
                                   RewardPolicy rewardPolicy) {
        return new Jackpot(id, id, new BigDecimal(initialPool), new BigDecimal(initialPool), 1, contributionPolicy,
                rewardPolicy, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Jackpot fixedJackpot(String id) {
        return jackpot(id, "1000.00", new FixedContributionPolicy(new BigDecimal("5.0")),
                new FixedChanceRewardPolicy(new BigDecimal("1.0")));
    }

    private static Jackpot variableJackpot(String id, String initialPool, String poolLimit) {
        return jackpot(id, initialPool, new VariableContributionPolicy(new BigDecimal("10.0"), new BigDecimal("1.0"),
                        new BigDecimal("0.5"), new BigDecimal("1000")),
                new VariableChanceRewardPolicy(new BigDecimal("0.1"), new BigDecimal("0.5"), new BigDecimal("1000"),
                        new BigDecimal(poolLimit)));
    }

    private JackpotConfigurationValidator validator(String jdbcUrl, Jackpot... jackpots) {
        when(jdbcConnectionDetails.getJdbcUrl()).thenReturn(jdbcUrl);
        when(jackpotQueryService.findAll()).thenReturn(List.of(jackpots));
        return new JackpotConfigurationValidator(jackpotQueryService, jdbcConnectionDetails);
    }

    @Test
    @DisplayName("consistent jackpots pass and the number of validated jackpots is logged")
    void validConfigurationPasses() {
        JackpotConfigurationValidator validator = validator(POSTGRES_URL, fixedJackpot("jackpot-fixed"),
                variableJackpot("jackpot-variable", "5000.00", "25000"));

        try (ServiceLogCapture logs = ServiceLogCapture.of(JackpotConfigurationValidator.class)) {
            assertThatNoException().isThrownBy(() -> validator.run(NO_ARGS));

            assertThat(logs.messages(Level.INFO)).containsExactly("Validated the configuration of 2 jackpot(s)");
        }
    }

    @Test
    @DisplayName("no jackpots at all is a valid configuration")
    void noJackpotsPass() {
        JackpotConfigurationValidator validator = validator(POSTGRES_URL);

        try (ServiceLogCapture logs = ServiceLogCapture.of(JackpotConfigurationValidator.class)) {
            assertThatNoException().isThrownBy(() -> validator.run(NO_ARGS));

            assertThat(logs.messages(Level.INFO)).containsExactly("Validated the configuration of 0 jackpot(s)");
        }
    }

    @ParameterizedTest(name = "poolLimit {0} with initial pool 1000.00 fails startup")
    @ValueSource(strings = {"1000", "999.99", "1"})
    @DisplayName("a pool limit not above the initial pool fails startup, naming the jackpot")
    void inconsistentJackpotFailsStartup(String poolLimit) {
        JackpotConfigurationValidator validator = validator(POSTGRES_URL, fixedJackpot("jackpot-ok"),
                variableJackpot("jackpot-bad", "1000.00", poolLimit));

        JackpotConfigurationException exception;
        try (ServiceLogCapture logs = ServiceLogCapture.of(JackpotConfigurationValidator.class)) {
            exception = catchThrowableOfType(JackpotConfigurationException.class, () -> validator.run(NO_ARGS));

            assertThat(logs.messages(Level.INFO)).as("never reports success").isEmpty();
        }

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(exception).hasMessage("Jackpot 'jackpot-bad' is misconfigured: poolLimit (" + poolLimit
                + ") must be greater than the initial pool (1000.00)");
        assertThat(exception.getCause()).isInstanceOf(JackpotConfigurationException.class)
                .hasMessage("poolLimit (" + poolLimit + ") must be greater than the initial pool (1000.00)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "jdbc:h2:mem:jackpot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000",
            "jdbc:h2:mem:test-5b0a8e8e-2b7f-4f7e-9d7e-3c1f4f0f6c1a"
    })
    @DisplayName("in-memory H2 -> single-instance WARN")
    void warnsOnInMemoryH2(String jdbcUrl) {
        JackpotConfigurationValidator validator = validator(jdbcUrl, fixedJackpot("jackpot-fixed"));

        try (ServiceLogCapture logs = ServiceLogCapture.of(JackpotConfigurationValidator.class)) {
            validator.run(NO_ARGS);

            assertThat(logs.messages(Level.WARN)).containsExactly(H2_WARNING);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            POSTGRES_URL,
            "jdbc:h2:file:./data/jackpot;MODE=PostgreSQL",
            "jdbc:postgresql://postgres:5432/jackpot?options=jdbc:h2:mem:"
    })
    @DisplayName("any other database -> no WARN")
    void noWarningOnOtherDatabases(String jdbcUrl) {
        JackpotConfigurationValidator validator = validator(jdbcUrl, fixedJackpot("jackpot-fixed"));

        try (ServiceLogCapture logs = ServiceLogCapture.of(JackpotConfigurationValidator.class)) {
            validator.run(NO_ARGS);

            assertThat(logs.messages(Level.WARN)).isEmpty();
        }
    }
}
