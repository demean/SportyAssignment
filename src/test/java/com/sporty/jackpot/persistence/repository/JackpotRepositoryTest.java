package com.sporty.jackpot.persistence.repository;

import static com.sporty.jackpot.persistence.PersistenceTestSupport.NOW;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.PLACED_AT;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.assertViolates;
import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.uniqueId;
import static com.sporty.jackpot.support.TestJackpots.variableContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import com.sporty.jackpot.persistence.entity.JackpotEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.support.TestJackpots;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("JackpotRepository")
class JackpotRepositoryTest {

    @Autowired
    private JackpotRepository jackpotRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final EntityMapper mapper = new EntityMapper();

    private TestJackpots jackpots;

    @BeforeEach
    void setUp() {
        jackpots = new TestJackpots(jdbcTemplate);
    }

    @Test
    @DisplayName("findByIdForUpdate returns the jackpot row with both policies converted from JSON")
    void findByIdForUpdateReturnsTheRow() {
        String id = uniqueId("jackpot");
        jackpots.insert(id, "100.00", "123.45", 3, variableContribution("20.0", "5.0", "1.0", "100"),
                fixedChance("2.5"));

        JackpotEntity jackpot = jackpotRepository.findByIdForUpdate(id).orElseThrow();

        assertThat(jackpot.getId()).isEqualTo(id);
        assertThat(jackpot.getName()).isEqualTo("Test " + id);
        assertThat(jackpot.getInitialPoolAmount()).isEqualByComparingTo("100.00");
        assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("123.45");
        assertThat(jackpot.getCycle()).isEqualTo(3);
        assertThat(jackpot.getVersion()).isZero();
        assertThat(jackpot.getContributionPolicy()).isEqualTo(new VariableContributionPolicy(new BigDecimal("20.0"),
                new BigDecimal("5.0"), new BigDecimal("1.0"), new BigDecimal("100")));
        assertThat(jackpot.getRewardPolicy()).isEqualTo(new FixedChanceRewardPolicy(new BigDecimal("2.5")));
        assertThat(jackpot.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByIdForUpdate is empty for an unknown jackpot")
    void findByIdForUpdateOfAnUnknownJackpotIsEmpty() {
        assertThat(jackpotRepository.findByIdForUpdate(uniqueId("jackpot"))).isEmpty();
    }

    @Test
    @DisplayName("a jackpot built by the public constructor is stored with every column and policies as JSON")
    void publicConstructorPersistsEveryColumn() {
        String id = uniqueId("jackpot");
        FixedContributionPolicy contributionPolicy = new FixedContributionPolicy(new BigDecimal("7.5"));
        VariableChanceRewardPolicy rewardPolicy = new VariableChanceRewardPolicy(new BigDecimal("1.0"),
                new BigDecimal("2.0"), new BigDecimal("50"), new BigDecimal("900"));

        entityManager.persistAndFlush(new JackpotEntity(id, "Brand New", new BigDecimal("500.00"),
                new BigDecimal("512.34"), contributionPolicy, rewardPolicy, 1, PLACED_AT));
        entityManager.clear();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM jackpot WHERE id = ?", id);
        assertThat(row)
                .containsEntry("name", "Brand New")
                .containsEntry("contribution_policy", "{\"type\":\"FIXED\",\"percentage\":7.5}")
                .containsEntry("reward_policy", "{\"type\":\"VARIABLE\",\"startChancePercentage\":1.0,"
                        + "\"chanceIncreasePercentage\":2.0,\"poolIncreaseStep\":50,\"poolLimit\":900}")
                .containsEntry("pool_cycle", 1L)
                .containsEntry("version", 0L);
        assertThat((BigDecimal) row.get("initial_pool_amount")).isEqualByComparingTo("500.00");
        assertThat((BigDecimal) row.get("current_pool_amount")).isEqualByComparingTo("512.34");
        assertThat(((OffsetDateTime) row.get("created_at")).toInstant()).isEqualTo(PLACED_AT);
        assertThat(((OffsetDateTime) row.get("updated_at")).toInstant()).isEqualTo(PLACED_AT);

        JackpotEntity loaded = jackpotRepository.findById(id).orElseThrow();
        assertThat(mapper.toJackpot(loaded)).isEqualTo(new Jackpot(id, "Brand New", new BigDecimal("500.00"),
                new BigDecimal("512.34"), 1, contributionPolicy, rewardPolicy, PLACED_AT));
    }

    @Test
    @DisplayName("addContribution and award are flushed to the row and each change bumps the version")
    void poolChangesAreFlushedAndVersioned() {
        String id = jackpots.createLuckyLike();
        JackpotEntity jackpot = jackpotRepository.findByIdForUpdate(id).orElseThrow();

        BigDecimal poolAfter = jackpot.addContribution(new BigDecimal("50.00"), PLACED_AT);
        jackpotRepository.flush();

        assertThat(poolAfter).isEqualByComparingTo("150.00");
        assertThat(jackpots.pool(id)).isEqualByComparingTo("150.00");
        assertThat(jackpots.cycle(id)).isEqualTo(1);
        assertThat(jackpots.version(id)).isEqualTo(1);
        assertThat(jackpot.getVersion()).isEqualTo(1);

        BigDecimal reward = jackpot.award(NOW);
        jackpotRepository.flush();

        assertThat(reward).isEqualByComparingTo("150.00");
        assertThat(jackpots.pool(id)).isEqualByComparingTo("100.00");
        assertThat(jackpots.cycle(id)).isEqualTo(2);
        assertThat(jackpots.version(id)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM jackpot WHERE id = ?", OffsetDateTime.class, id)
                .toInstant()).isEqualTo(NOW);

        entityManager.clear();
        JackpotEntity reloaded = jackpotRepository.findById(id).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(2);
        assertThat(reloaded.getCycle()).isEqualTo(2);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getContributionPolicy())
                .isEqualTo(new VariableContributionPolicy(new BigDecimal("20.0"), new BigDecimal("5.0"),
                        new BigDecimal("1.0"), new BigDecimal("100")));
        assertThat(jdbcTemplate.queryForMap("SELECT contribution_policy, reward_policy FROM jackpot WHERE id = ?", id))
                .as("the policy columns are byte-for-byte unchanged after the pool UPDATEs")
                .containsEntry("contribution_policy", variableContribution("20.0", "5.0", "1.0", "100"))
                .containsEntry("reward_policy", TestJackpots.variableChance("5.0", "10.0", "10", "150"));
    }

    @Test
    @DisplayName("the pool can never be stored negative (ck_jackpot_current_pool)")
    void negativePoolIsRejected() {
        String id = jackpots.create("10.00", TestJackpots.fixedContribution("5.0"), fixedChance("0"));
        JackpotEntity jackpot = jackpotRepository.findByIdForUpdate(id).orElseThrow();

        jackpot.addContribution(new BigDecimal("-10.01"), NOW);

        assertViolates("ck_jackpot_current_pool", () -> jackpotRepository.flush());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            contribution | {"type":"FIXED","percentage":101}  | {"type":"FIXED","chancePercentage":1}  | Invalid contribution policy JSON: | percentage must be within [0, 100] but was 101
            reward       | {"type":"FIXED","percentage":1}    | {"type":"FIXED","chance":1}            | Invalid reward policy JSON:       | chancePercentage must not be null
            JSON null    | {"type":"FIXED","percentage":1}    | null                                   | Invalid reward policy JSON:       | null
            """)
    @DisplayName("loading a jackpot with an unusable stored policy fails non-transiently, caused by the converter's "
            + "JackpotConfigurationException")
    void invalidStoredPolicyFailsOnLoad(String column, String contributionJson, String rewardJson, String prefix,
                                        String detail) {
        String id = uniqueId("jackpot");
        jackpots.insert(id, "100.00", "100.00", 1, contributionJson, rewardJson);

        assertThatThrownBy(() -> jackpotRepository.findByIdForUpdate(id))
                .as("never classified as transient, so the consumer does not retry it forever")
                .isInstanceOf(NonTransientDataAccessException.class)
                .satisfies(e -> assertThat(causeOfType(e, JackpotConfigurationException.class))
                        .hasMessageStartingWith(prefix)
                        .hasMessageContaining(detail));
    }

    private static Throwable causeOfType(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return t;
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in the cause chain of " + throwable);
    }
}
