package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotNotFoundException;
import com.sporty.jackpot.persistence.entity.JackpotEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.JackpotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("JackpotQueryService")
class JackpotQueryServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-23T10:15:30.123456Z");

    private static final JackpotEntity FIXED = new JackpotEntity("jackpot-a", "Fixed", new BigDecimal("1000.00"),
            new BigDecimal("1012.50"), new FixedContributionPolicy(new BigDecimal("5.0")),
            new FixedChanceRewardPolicy(new BigDecimal("1.0")), 2, CREATED_AT);
    private static final JackpotEntity VARIABLE = new JackpotEntity("jackpot-b", "Variable",
            new BigDecimal("5000.00"), new BigDecimal("5000.00"),
            new VariableContributionPolicy(new BigDecimal("10.0"), new BigDecimal("1.0"), new BigDecimal("0.5"),
                    new BigDecimal("1000")),
            new VariableChanceRewardPolicy(new BigDecimal("0.1"), new BigDecimal("0.5"), new BigDecimal("1000"),
                    new BigDecimal("25000")), 1, CREATED_AT);

    @Mock
    private JackpotRepository jackpotRepository;

    private JackpotQueryService service;

    @BeforeEach
    void setUp() {
        service = new JackpotQueryService(jackpotRepository, new EntityMapper());
    }

    @Test
    @DisplayName("is read-only transactional at class level")
    void isReadOnlyTransactional() {
        Transactional transactional = JackpotQueryService.class.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    @DisplayName("findAll asks the repository for ascending id order and maps every jackpot in that order")
    void findAllSortedById() {
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        when(jackpotRepository.findAll(any(Sort.class))).thenReturn(List.of(FIXED, VARIABLE));

        List<Jackpot> jackpots = service.findAll();

        verify(jackpotRepository).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
        assertThat(jackpots).extracting(Jackpot::id).containsExactly("jackpot-a", "jackpot-b");
        assertThat(jackpots.get(0)).isEqualTo(new Jackpot("jackpot-a", "Fixed", new BigDecimal("1000.00"),
                new BigDecimal("1012.50"), 2, FIXED.getContributionPolicy(), FIXED.getRewardPolicy(), CREATED_AT));
        assertThat(jackpots.get(1).contributionPolicy()).isSameAs(VARIABLE.getContributionPolicy());
        assertThat(jackpots.get(1).rewardPolicy()).isSameAs(VARIABLE.getRewardPolicy());
    }

    @Test
    @DisplayName("findAll without jackpots returns an empty list")
    void findAllEmpty() {
        when(jackpotRepository.findAll(any(Sort.class))).thenReturn(List.of());

        assertThat(service.findAll()).isEmpty();
    }

    @Test
    @DisplayName("getJackpot maps the stored jackpot")
    void getJackpotFound() {
        when(jackpotRepository.findById("jackpot-b")).thenReturn(Optional.of(VARIABLE));

        Jackpot jackpot = service.getJackpot("jackpot-b");

        assertThat(jackpot.id()).isEqualTo("jackpot-b");
        assertThat(jackpot.name()).isEqualTo("Variable");
        assertThat(jackpot.initialPoolAmount()).isEqualByComparingTo("5000.00");
        assertThat(jackpot.currentPoolAmount()).isEqualByComparingTo("5000.00");
        assertThat(jackpot.cycle()).isEqualTo(1);
        assertThat(jackpot.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("getJackpot of an unknown id -> JACKPOT_NOT_FOUND")
    void getJackpotNotFound() {
        when(jackpotRepository.findById("nope")).thenReturn(Optional.empty());

        JackpotNotFoundException exception = catchThrowableOfType(JackpotNotFoundException.class,
                () -> service.getJackpot("nope"));

        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.JACKPOT_NOT_FOUND);
        assertThat(exception).hasMessage("Jackpot 'nope' was not found");
    }
}
