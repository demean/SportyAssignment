# Jackpot Service — Design Contract (v2, post adversarial review)

This document is the **binding contract** for the implementation. Package names, class names, method
signatures, table/column names, endpoints, error codes and config keys below are normative. Deviate only
when a technical constraint forces it, and record the deviation in §12 of this file.

---

## 1. Requirements

### 1.1 From the assignment
1. **API endpoint to publish a bet to Kafka.** A bet = Bet ID, User ID, Jackpot ID, Bet Amount. Topic: `jackpot-bets`.
2. **Kafka consumer** listening to `jackpot-bets`.
3. **Each bet contributes to a matching jackpot** (matched by Jackpot ID; no such jackpot → no contribution).
   - Each jackpot starts with a **configurable initial pool value**.
   - Contribution configuration per jackpot, two options now, extensible later:
     **Fixed** % of bet amount; **Variable** % that starts bigger and decreases at a fixed rate as the pool increases.
   - Jackpot Contribution record: Bet ID, User ID, Jackpot ID, Stake Amount, Contribution Amount, Current Jackpot Amount, Created At.
4. **Each bet is evaluated for the jackpot reward** + **API endpoint that returns whether a (contributing) bet won and the reward.**
   - Reward configuration per jackpot, two options now, extensible later:
     **Fixed chance** %; **Variable chance** % that starts smaller and grows as the pool increases; pool ≥ **limit** → 100 %.
   - When a jackpot is rewarded it is **reset to its initial pool value**.
   - Jackpot Reward record: Bet ID, User ID, Jackpot ID, Jackpot Reward Amount, Created At.
5. Conditions: **in-memory DB** for bets and jackpots (H2 = default profile; PostgreSQL = extra production-like profile). Real Kafka (Docker). README.

### 1.2 From the user (non-negotiable)
- Spring Boot **4.1.1** (latest GA), Java **21**, Maven + wrapper, dependencies from Maven Central only (no private mirrors,
  nothing is ever deployed/pushed as an artifact).
- Enterprise-grade: RFC 9457 `ProblemDetail` errors, validation, observability, graceful shutdown.
- **All schema/data changes via Flyway** (`ddl-auto=validate`).
- **100 % test coverage** — JaCoCo BUNDLE INSTRUCTION/BRANCH/LINE/METHOD/CLASS/COMPLEXITY = 1.00, **no excludes**; tests run on
  in-memory H2 + Embedded Kafka (no Docker needed for `./mvnw verify`).
- **Controllers work with DTOs only; persistence works with entities only**; entities never leave `service`/`persistence`.
  Mapping in dedicated mapper classes. Enforced by ArchUnit.
- **Everything transactional**: every DB-touching service method is `@Transactional` (`readOnly = true` for queries,
  `isolation = READ_COMMITTED` for writes). Each Kafka record is applied in exactly one DB transaction.
  Kafka side: idempotent (non-transactional) producer + idempotent consumer (bet id = idempotency key) → effectively-once
  processing without 2PC. This decision is documented in the README.
- High load & horizontal scaling considered and documented. Docker Compose: Kafka (KRaft) + PostgreSQL + Kafka UI + app.
- Public GitHub repo `demean/SportyAssignment`, pushed to `main` at the end.

### 1.3 Interpretations (documented in README "Assumptions & interpretations")
- **Evaluation is eager**: every bet that contributes is evaluated exactly once, inside the same DB transaction as its
  contribution, under the same jackpot row lock ("receive a bet and process it for (1) contribution and (2) evaluation";
  "each bet must be evaluated"). The evaluation API endpoint returns the stored result (`GET /api/v1/bets/{betId}/evaluation`).
  This makes re-rolling, front-running and timing exploits impossible by construction.
- **Win chance** is computed from the pool **right after this bet's contribution** (= the contribution's
  "Current Jackpot Amount"). The bet that pushes the pool to/over the variable-chance limit wins with 100 %.
- **Reward** = the whole pool at the moment of winning (after this bet's contribution); the pool is reset to its initial
  value and the jackpot's `cycle` is incremented.
- **Contributing bet** = contribution amount > 0.00. A bet whose contribution rounds to 0.00 is recorded (contribution row
  with 0.00) but is not drawn: evaluation LOST, win chance 0.0000 (micro-bet exploit guard).
- **Unknown jackpot**: the bet is stored with status `NO_MATCHING_JACKPOT`, no contribution, no evaluation, record acked.
- Bet id is globally unique; a re-delivered / re-published bet id is a no-op (`DUPLICATE`), even with a different payload (WARN log).
- Publishing does not touch the DB (the publish path scales independently of the DB); jackpot existence is checked by the consumer.

---

## 2. Traps / tricky points and how we handle them (all implemented + tested)

| # | Trap | Handling |
|---|------|----------|
| T1 | Money as floating point | `BigDecimal` only; `NUMERIC(19,2)` amounts, `NUMERIC(7,4)` percentages; scale 2, `HALF_EVEN`; compare with `compareTo`. |
| T2 | Lost updates on the pool | Jackpot row locked `PESSIMISTIC_WRITE` (`SELECT … FOR UPDATE`) for every contribution+evaluation; `@Version` as safety net; `READ_COMMITTED` pinned. |
| T3 | Kafka at-least-once → duplicate contributions / draws | `bet.bet_id` PK is the idempotency key, checked after taking the jackpot lock; a PK race (same bet id to two jackpots / rebalance) surfaces as `DataIntegrityViolationException`, the listener confirms via `isProcessed` and treats it as a duplicate. A duplicate never re-draws. |
| T4 | Re-rolling via the evaluate endpoint | Impossible: evaluation happens once during processing; the endpoint is a read-only lookup (no lock, no write). |
| T5 | Front-running / waiting-game (watch the pool, evaluate later) | Impossible: the draw happens atomically with the contribution in Kafka order. |
| T6 | Double payout | One transaction per bet under the jackpot lock; reward resets the pool and bumps `cycle`; DB backstop `UNIQUE(jackpot_id, pool_cycle)` on `jackpot_reward` and `UNIQUE(bet_id)`. |
| T7 | Reset | Pool := initial, cycle++ in the same transaction as the reward + evaluation inserts. |
| T8 | Formula edge cases | Variable contribution floored at `minPercentage`; chance capped at 100; pool growth below initial clamped to 0; parameters validated in record constructors (percentages at most 4 decimals; contribution percentages > 0, because a 0 % contribution is never drawn and would freeze the jackpot, §12 item 12); cross-field `poolLimit > initialPool` validated at startup, before the consumers start (fail fast), and again for every bet (§12 item 11). |
| T9 | Async flow / eventual consistency | `POST /bets` → `202 Accepted` + `Location: /api/v1/bets/{betId}`. Clients poll `GET /bets/{betId}`: `404 BET_NOT_FOUND` = not processed (yet); `422 BET_NOT_CONTRIBUTING` = processed, no matching jackpot (decided from the bet row, read first: §6.3). |
| T10 | Bet for unknown jackpot | Stored as `NO_MATCHING_JACKPOT`, metric, WARN, acked (not DLT). |
| T11 | Poison pills vs transient failures | `ErrorHandlingDeserializer` (+ `checkDeserExWhenKeyNull/ValueNull`) around a strict JSON delegate (duplicate keys and strings coerced into numbers fail, as in the API: §12 item 12); deterministic failures (`DeserializationException`, `InvalidBetException`, `JackpotConfigurationException`, non-duplicate `DataIntegrityViolationException`) → DLT immediately; **transient** DB/Kafka failures → retried **indefinitely** with exponential back-off (the consumer thread waits, and with it every partition assigned to it; order preserved, no money event lost); unknown exceptions → bounded retries then DLT. |
| T12 | Predictable randomness | `SecureRandom` as `RandomGenerator` bean; exact integer draw `nextLong(1_000_000) < chance×10_000`. |
| T13 | Publish reliability | `acks=all`, `enable.idempotence=true`; API waits for the broker ack; timeouts ordered `publish-timeout ≥ max.block.ms + delivery.timeout.ms + 1s` (validated at startup); `503 BET_PUBLISH_FAILED` says "outcome unknown — retry with the same betId". |
| T14 | Ordering / hot rows | Record key = `jackpotId` → per-jackpot ordering and single consumer writer per jackpot; 12 partitions (seeded ids land on distinct partitions). |
| T15 | Time precision | `Instant` + `TIMESTAMP WITH TIME ZONE`, UTC; `Clock.tick(systemUTC, 1µs)` so persisted and returned instants are identical. |
| T16 | OSIV | `spring.jpa.open-in-view=false`. |
| T17 | Lock waits | Single lock timeout: H2 `LOCK_TIMEOUT=3000` in URL; PostgreSQL Hikari `connection-init-sql: SET lock_timeout = '3s'`; no JPA lock-timeout query hint (ignored by H2, costly on PG). Hikari `connection-timeout: 2s`. |
| T18 | Metrics/logs claiming things that rolled back | Business metrics and "processed/won" logs are emitted from a `@TransactionalEventListener(phase = AFTER_COMMIT)`. |
| T19 | Hibernate insert ordering with plain FK columns | `saveAndFlush` in FK order (bet → contribution → reward → evaluation); `order_inserts` not enabled. |
| T20 | Converted policy attributes deep-copied on every load | Policy converters annotated `@org.hibernate.annotations.Immutable` (policy records are immutable); the converter tests assert the annotation. |
| T21 | Virtual-thread pinning on JDK 21 in Kafka poll loops | HTTP on virtual threads; Kafka listener containers on platform threads (`SimpleAsyncTaskExecutor` with virtual threads off) via `ContainerCustomizer`. |
| T22 | H2 profile + multiple instances | H2 = single instance only (WARN at startup); horizontal scaling = postgres profile; different default consumer groups per profile. |

Deadlock freedom: each write transaction locks at most one jackpot row, first, then inserts child rows.

---

## 3. Architecture & packages

Base package `com.sporty.jackpot`; groupId `com.sporty`, artifactId `jackpot-service`, version `1.0.0`.

```
com.sporty.jackpot
├── JackpotServiceApplication                 // @SpringBootApplication @ConfigurationPropertiesScan
├── config
│   ├── JackpotProperties                     // @ConfigurationProperties("jackpot") @Validated record
│   ├── KafkaConfig                           // NewTopics, producer customizer, error handler, DLT, container customizer
│   ├── ClockConfig                           // Clock.tick(Clock.systemUTC(), 1µs)
│   ├── RandomConfig                          // RandomGenerator (SecureRandom)
│   └── OpenApiConfig                         // OpenAPI metadata bean
├── domain
│   ├── Money
│   ├── model
│   │   ├── Bet                               // command/value object (betId, userId, jackpotId, amount, placedAt)
│   │   ├── BetStatus                         // CONTRIBUTED, NO_MATCHING_JACKPOT
│   │   ├── ProcessedBet                      // read model of a stored bet
│   │   ├── Contribution                      // read model
│   │   ├── BetEvaluation                     // read model
│   │   ├── EvaluationOutcome                 // WON, LOST
│   │   ├── ProcessingStatus                  // PROCESSED, DUPLICATE, NO_MATCHING_JACKPOT
│   │   ├── ProcessingResult                  // result of processing one bet
│   │   └── Jackpot                           // read model
│   └── policy
│       ├── ContributionPolicy / FixedContributionPolicy / VariableContributionPolicy
│       └── RewardPolicy / FixedChanceRewardPolicy / VariableChanceRewardPolicy
├── exception                                 // framework-free
│   ├── ErrorCode
│   ├── JackpotServiceException               // abstract, carries ErrorCode
│   ├── InvalidBetException
│   ├── BetNotFoundException
│   ├── BetNotContributingException
│   ├── JackpotNotFoundException
│   ├── BetPublishingException                // carries betId
│   └── JackpotConfigurationException
├── service
│   ├── BetPlacementService                   // place-bet use case: Bet stamped with the clock -> BetEventPublisher (no DB)
│   ├── BetEventPublisher                     // outbound port, implemented by messaging.BetPublisher
│   ├── BetProcessingService                  // @Transactional process(Bet): contribution + evaluation
│   ├── BetQueryService                       // @Transactional(readOnly = true)
│   ├── JackpotQueryService                   // @Transactional(readOnly = true)
│   ├── JackpotConfigurationValidator         // SmartInitializingSingleton: cross-field validation, fail fast; H2 WARN
│   ├── RewardDraw                            // @Component
│   └── event
│       ├── BetProcessedEvent                 // record published inside the tx
│       └── BetProcessingMetrics              // @TransactionalEventListener(AFTER_COMMIT): metrics + INFO log
├── messaging
│   ├── BetPlacedEvent                        // Kafka payload DTO
│   ├── BetPlacedEventDeserializer            // consumer value delegate: strict JSON (duplicate keys, no coercion)
│   ├── BetEventMapper                        // Bet <-> BetPlacedEvent
│   ├── BetPublisher                          // Bet -> Kafka (no DB): producer adapter implementing service.BetEventPublisher
│   ├── BetEventListener                      // @KafkaListener (NOT @Transactional)
│   └── KafkaListenersHealthIndicator         // DOWN if a listener container is not running
├── persistence
│   ├── entity      BetEntity, JackpotEntity, JackpotContributionEntity, JackpotRewardEntity, BetEvaluationEntity
│   ├── repository  BetRepository, JackpotRepository, JackpotContributionRepository, JackpotRewardRepository, BetEvaluationRepository
│   ├── converter   PolicyJson (package-private), ContributionPolicyConverter, RewardPolicyConverter
│   └── mapper      EntityMapper
└── api
    ├── controller  BetController, JackpotController
    ├── dto         PlaceBetRequest, BetAcceptedResponse, BetResponse, ContributionResponse, BetEvaluationResponse,
    │               JackpotResponse, PolicyResponse
    ├── mapper      ApiMapper
    └── error       GlobalExceptionHandler, ErrorHttpStatus (ErrorCode -> HttpStatus, exhaustive switch),
                    ProblemDetailOpenApiCustomizer (documents every 4xx/5xx as application/problem+json)
```

Layer rules (ArchUnit test `ArchitectureTest`, all must hold):
1. `domain..` and `exception..` depend on no `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `tools.jackson..`, `com.fasterxml..`.
2. `api..` and `messaging..` do not depend on `persistence..`.
3. Public methods of controller classes (meta-annotated `@Controller`) take/return only `api.dto..` types, `String`, primitives, `ResponseEntity`, `List` (of DTOs).
   Controllers map the domain objects the services return to DTOs through `ApiMapper`.
4. `persistence.entity..` classes are only accessed from `persistence..` and `service..`.
5. `@Transactional` is only used in `service..` (no `@Transactional` on listeners/controllers); every public method of a
   service class owning a repository is transactional (method or class), and read-write ones pin `READ_COMMITTED`.
6. No field injection (`@Autowired` fields) in main code.
7. Layers: `api` → `service`, `domain`, `exception` (never another adapter); `messaging` → `service`, `domain`, `exception`, `config`
   (properties); `service` → `persistence`, `domain`, `exception`; `persistence` → `domain`, `exception`; `domain` →
   `exception`; `exception` → nothing. `config` is the composition root and may use anything. No package cycles
   (ignoring dependencies from `config`).

Other rules: no Lombok; records for DTOs/value objects; constructor injection; exhaustive pattern-matching `switch`
without `default` over sealed policy hierarchies (a new policy fails compilation until mapped).

---

## 4. Domain

### 4.1 `Money`
```java
public final class Money {
    public static final int SCALE = 2;
    public static final int PERCENT_SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    public static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(PERCENT_SCALE);
    private Money() {}
    public static BigDecimal normalize(BigDecimal amount)                    // setScale(SCALE, ROUNDING); requireNonNull
    public static BigDecimal percentageOf(BigDecimal amount, BigDecimal pct) // amount × pct / 100 → normalize
    public static BigDecimal normalizePercentage(BigDecimal pct)             // setScale(PERCENT_SCALE, ROUNDING)
}
```

### 4.2 `Bet`
`record Bet(String betId, String userId, String jackpotId, BigDecimal amount, Instant placedAt)`;
`public static final String ID_REGEX = "^(?!\\.{1,2}$)[A-Za-z0-9._:-]{1,64}$"` (§12 item 11: `.` and `..` are URL dot
segments); `MAX_AMOUNT = 1_000_000_000.00`.
Compact constructor → `InvalidBetException` when an id is null/blank/not matching, amount null / ≤ 0 / scale > 2 (after
`stripTrailingZeros`) / > MAX_AMOUNT, or `placedAt` null. Amount stored normalized to scale 2. Messages quote untrusted
input bounded: an id cut to 80 characters (plus its length), an amount with `BigDecimal.toString()`.

### 4.3 Contribution policies
```java
public sealed interface ContributionPolicy permits FixedContributionPolicy, VariableContributionPolicy {
    /** Percentage (scale 4) of the stake to contribute, given the pool BEFORE this contribution. */
    BigDecimal contributionPercentage(BigDecimal currentPool, BigDecimal initialPool);
    default BigDecimal contributionAmount(BigDecimal stake, BigDecimal currentPool, BigDecimal initialPool) {
        return Money.percentageOf(stake, contributionPercentage(currentPool, initialPool));
    }
    /** Cross-field validation against the jackpot's initial pool; throws JackpotConfigurationException. */
    default void validateFor(BigDecimal initialPool) { }
}
record FixedContributionPolicy(BigDecimal percentage)                       // 0 < p ≤ 100
record VariableContributionPolicy(BigDecimal startPercentage, BigDecimal minPercentage,
                                  BigDecimal decayPercentage, BigDecimal poolIncreaseStep)
// 0 < min ≤ start ≤ 100; decay ≥ 0; step > 0
// pct = max(min, start − decay × max(0, pool − initial) / step)   (MathContext.DECIMAL64) → normalizePercentage
```
Constructor violations → `JackpotConfigurationException` (non-retryable); nulls → same exception with the parameter name.
A contribution percentage of 0 is rejected: a bet contributing 0.00 is never drawn (§1.3), so such a jackpot could never
grow or be won again (§12 item 12). Rejected values are quoted with `BigDecimal.toString()` (bounded, §12 item 12).

### 4.4 Reward policies
```java
public sealed interface RewardPolicy permits FixedChanceRewardPolicy, VariableChanceRewardPolicy {
    /** Win chance in percent [0,100], scale 4, for the pool right after the bet's contribution. */
    BigDecimal winChancePercentage(BigDecimal poolAmount, BigDecimal initialPool);
    default void validateFor(BigDecimal initialPool) { }
}
record FixedChanceRewardPolicy(BigDecimal chancePercentage)                  // 0..100
record VariableChanceRewardPolicy(BigDecimal startChancePercentage, BigDecimal chanceIncreasePercentage,
                                  BigDecimal poolIncreaseStep, BigDecimal poolLimit)
// 0 ≤ start ≤ 100; increase ≥ 0; step > 0; limit > 0; validateFor: limit > initialPool
// pool ≥ limit → 100.0000; else min(100, start + increase × max(0, pool − initial) / step) → normalizePercentage
```

### 4.5 Read models / results
```java
enum BetStatus { CONTRIBUTED, NO_MATCHING_JACKPOT }
record ProcessedBet(String betId, String userId, String jackpotId, BigDecimal betAmount, BetStatus status,
                    Instant placedAt, Instant processedAt)
record Contribution(String betId, String userId, String jackpotId, BigDecimal stakeAmount, BigDecimal contributionAmount,
                    BigDecimal currentJackpotAmount, long jackpotCycle, Instant createdAt)
enum EvaluationOutcome { WON, LOST }
record BetEvaluation(String betId, String userId, String jackpotId, EvaluationOutcome outcome,
                     BigDecimal winChancePercentage, BigDecimal rewardAmount, long jackpotCycle, Instant evaluatedAt)
    boolean won()
enum ProcessingStatus { PROCESSED, DUPLICATE, NO_MATCHING_JACKPOT }
record ProcessingResult(ProcessingStatus status, String betId, Contribution contribution, BetEvaluation evaluation)
    // factories: processed(Contribution, BetEvaluation), duplicate(String betId), noMatchingJackpot(String betId)
    // contribution/evaluation are non-null only for PROCESSED
record Jackpot(String id, String name, BigDecimal initialPoolAmount, BigDecimal currentPoolAmount, long cycle,
               ContributionPolicy contributionPolicy, RewardPolicy rewardPolicy, Instant updatedAt)
```

---

## 5. Persistence

### 5.1 Flyway (`src/main/resources/db/migration`) — one script set, valid on H2 2.4 (MODE=PostgreSQL) **and** PostgreSQL 17

`V1__create_jackpot_schema.sql`
```sql
CREATE TABLE jackpot (
    id                  VARCHAR(64)              NOT NULL,
    name                VARCHAR(128)             NOT NULL,
    initial_pool_amount NUMERIC(19, 2)           NOT NULL,
    current_pool_amount NUMERIC(19, 2)           NOT NULL,
    contribution_policy VARCHAR(2000)            NOT NULL,
    reward_policy       VARCHAR(2000)            NOT NULL,
    pool_cycle          BIGINT                   NOT NULL DEFAULT 1,
    version             BIGINT                   NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_jackpot PRIMARY KEY (id),
    CONSTRAINT ck_jackpot_initial_pool CHECK (initial_pool_amount >= 0),
    CONSTRAINT ck_jackpot_current_pool CHECK (current_pool_amount >= 0),
    CONSTRAINT ck_jackpot_pool_cycle   CHECK (pool_cycle >= 1)
);

CREATE TABLE bet (
    bet_id       VARCHAR(64)              NOT NULL,
    user_id      VARCHAR(64)              NOT NULL,
    jackpot_id   VARCHAR(64)              NOT NULL,   -- no FK: bets for unknown jackpots are stored too
    bet_amount   NUMERIC(19, 2)           NOT NULL,
    status       VARCHAR(32)              NOT NULL,
    placed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_bet PRIMARY KEY (bet_id),
    CONSTRAINT ck_bet_amount CHECK (bet_amount > 0),
    CONSTRAINT ck_bet_status CHECK (status IN ('CONTRIBUTED', 'NO_MATCHING_JACKPOT'))
);
CREATE INDEX ix_bet_user ON bet (user_id);

CREATE SEQUENCE jackpot_contribution_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE jackpot_contribution (
    id                     BIGINT                   NOT NULL,
    bet_id                 VARCHAR(64)              NOT NULL,
    user_id                VARCHAR(64)              NOT NULL,
    jackpot_id             VARCHAR(64)              NOT NULL,
    stake_amount           NUMERIC(19, 2)           NOT NULL,
    contribution_amount    NUMERIC(19, 2)           NOT NULL,
    current_jackpot_amount NUMERIC(19, 2)           NOT NULL,
    pool_cycle             BIGINT                   NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_jackpot_contribution PRIMARY KEY (id),
    CONSTRAINT uk_jackpot_contribution_bet UNIQUE (bet_id),
    CONSTRAINT fk_jackpot_contribution_bet FOREIGN KEY (bet_id) REFERENCES bet (bet_id),
    CONSTRAINT fk_jackpot_contribution_jackpot FOREIGN KEY (jackpot_id) REFERENCES jackpot (id),
    CONSTRAINT ck_jackpot_contribution_stake  CHECK (stake_amount > 0),
    CONSTRAINT ck_jackpot_contribution_amount CHECK (contribution_amount >= 0),
    CONSTRAINT ck_jackpot_contribution_pool   CHECK (current_jackpot_amount >= 0)
);
CREATE INDEX ix_jackpot_contribution_jackpot_created ON jackpot_contribution (jackpot_id, created_at);

CREATE SEQUENCE jackpot_reward_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE jackpot_reward (
    id                    BIGINT                   NOT NULL,
    bet_id                VARCHAR(64)              NOT NULL,
    user_id               VARCHAR(64)              NOT NULL,
    jackpot_id            VARCHAR(64)              NOT NULL,
    jackpot_reward_amount NUMERIC(19, 2)           NOT NULL,
    pool_cycle            BIGINT                   NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_jackpot_reward PRIMARY KEY (id),
    CONSTRAINT uk_jackpot_reward_bet UNIQUE (bet_id),
    CONSTRAINT uk_jackpot_reward_cycle UNIQUE (jackpot_id, pool_cycle),
    CONSTRAINT fk_jackpot_reward_contribution FOREIGN KEY (bet_id) REFERENCES jackpot_contribution (bet_id),
    CONSTRAINT fk_jackpot_reward_jackpot FOREIGN KEY (jackpot_id) REFERENCES jackpot (id),
    CONSTRAINT ck_jackpot_reward_amount CHECK (jackpot_reward_amount >= 0)
);
CREATE INDEX ix_jackpot_reward_user ON jackpot_reward (user_id);

CREATE SEQUENCE bet_evaluation_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE bet_evaluation (
    id                    BIGINT                   NOT NULL,
    bet_id                VARCHAR(64)              NOT NULL,
    user_id               VARCHAR(64)              NOT NULL,
    jackpot_id            VARCHAR(64)              NOT NULL,
    outcome               VARCHAR(16)              NOT NULL,
    win_chance_percentage NUMERIC(7, 4)            NOT NULL,
    reward_amount         NUMERIC(19, 2)           NOT NULL,
    pool_cycle            BIGINT                   NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_bet_evaluation PRIMARY KEY (id),
    CONSTRAINT uk_bet_evaluation_bet UNIQUE (bet_id),
    CONSTRAINT fk_bet_evaluation_contribution FOREIGN KEY (bet_id) REFERENCES jackpot_contribution (bet_id),
    CONSTRAINT fk_bet_evaluation_jackpot FOREIGN KEY (jackpot_id) REFERENCES jackpot (id),
    CONSTRAINT ck_bet_evaluation_outcome CHECK (outcome IN ('WON', 'LOST')),
    CONSTRAINT ck_bet_evaluation_chance CHECK (win_chance_percentage >= 0 AND win_chance_percentage <= 100),
    CONSTRAINT ck_bet_evaluation_reward CHECK (reward_amount >= 0)
);
```

`V2__seed_jackpots.sql` — initial pools are **Flyway placeholders** (`spring.flyway.placeholders.*`, overridable by env):

| id | name | initial (placeholder, default) | contribution_policy | reward_policy |
|----|------|--------------------------------|---------------------|---------------|
| `jackpot-fixed` | Fixed Classic | `${jackpot_fixed_initial_pool}` = 1000.00 | `{"type":"FIXED","percentage":5.0}` | `{"type":"FIXED","chancePercentage":1.0}` |
| `jackpot-variable` | Variable Progressive | `${jackpot_variable_initial_pool}` = 5000.00 | `{"type":"VARIABLE","startPercentage":10.0,"minPercentage":1.0,"decayPercentage":0.5,"poolIncreaseStep":1000}` | `{"type":"VARIABLE","startChancePercentage":0.1,"chanceIncreasePercentage":0.5,"poolIncreaseStep":1000,"poolLimit":25000}` |
| `jackpot-mixed` | Mixed Mega | `${jackpot_mixed_initial_pool}` = 10000.00 | `{"type":"FIXED","percentage":2.0}` | `{"type":"VARIABLE","startChancePercentage":0.01,"chanceIncreasePercentage":0.1,"poolIncreaseStep":5000,"poolLimit":100000}` |
| `jackpot-lucky` | Lucky Demo | `${jackpot_lucky_initial_pool}` = 100.00 | `{"type":"VARIABLE","startPercentage":20.0,"minPercentage":5.0,"decayPercentage":1.0,"poolIncreaseStep":100}` | `{"type":"VARIABLE","startChancePercentage":5.0,"chanceIncreasePercentage":10.0,"poolIncreaseStep":10,"poolLimit":150}` |

Deterministic demo (README): a 250.00 bet on a fresh `jackpot-lucky` contributes 20 % = 50.00 → pool 150.00 ≥ limit →
100 % → WON 150.00, pool reset to 100.00, cycle 2.

### 5.2 Entities
- No setters; `protected` no-arg constructor; public all-business-fields constructor.
- Sequence ids: `@GeneratedValue(strategy = SEQUENCE, generator = "…")` + `@SequenceGenerator(sequenceName = "<table>_seq", allocationSize = 50)`.
- `BetEntity` (`bet`, assigned String PK `betId`, `status` `@Enumerated(STRING)` of `BetStatus`).
- `JackpotEntity` (`jackpot`): `id`, `name`, `initialPoolAmount`, `currentPoolAmount`, `contributionPolicy` / `rewardPolicy` (`@Convert`),
  `cycle` (`pool_cycle`), `@Version long version`, `createdAt`, `updatedAt`. Behaviour:
  `BigDecimal addContribution(BigDecimal amount, Instant now)` (returns new pool), `BigDecimal award(Instant now)` (returns the pool
  before reset; pool := initial; cycle++).
- `JackpotContributionEntity`, `JackpotRewardEntity`, `BetEvaluationEntity` (outcome `@Enumerated(STRING)`), mirroring columns;
  `jackpotId`/`betId` are plain columns (no associations).

### 5.3 Repositories
```java
interface BetRepository extends JpaRepository<BetEntity, String> { }
interface JackpotRepository extends JpaRepository<JackpotEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JackpotEntity j where j.id = :id")
    Optional<JackpotEntity> findByIdForUpdate(@Param("id") String id);
}
interface JackpotContributionRepository extends JpaRepository<JackpotContributionEntity, Long> { Optional<…> findByBetId(String betId); }
interface JackpotRewardRepository     extends JpaRepository<JackpotRewardEntity, Long>     { Optional<…> findByBetId(String betId); }
interface BetEvaluationRepository     extends JpaRepository<BetEvaluationEntity, Long>     { Optional<…> findByBetId(String betId); }
```

### 5.4 Converters
`PolicyJson` (package-private): one static Jackson 3 `JsonMapper` with **mix-ins** (`@JsonTypeInfo(use = NAME, property = "type")`,
`@JsonSubTypes`) for both sealed hierarchies; domain stays annotation-free. Converters are `@Converter` +
`@org.hibernate.annotations.Immutable`; `null ↔ null`; malformed JSON / unknown type / invalid params →
`JackpotConfigurationException("Invalid contribution|reward policy JSON: …", cause)`.
Test: every `getPermittedSubclasses()` of both sealed interfaces has a registered subtype and round-trips.

### 5.5 `EntityMapper`
`ProcessedBet toProcessedBet(BetEntity)`, `Contribution toContribution(JackpotContributionEntity)`,
`BetEvaluation toEvaluation(BetEvaluationEntity)`, `Jackpot toJackpot(JackpotEntity)`.

---

## 6. Services

### 6.1 `service.BetPlacementService` + port `service.BetEventPublisher` → `messaging.BetPublisher` (no DB; §12 items 11, 12)
`BetPlacementService.place(String betId, String userId, String jackpotId, BigDecimal amount)` builds
`new Bet(…, clock.instant())` (domain invariants → `InvalidBetException`, nothing published), calls
`BetEventPublisher.publish(bet)` and returns the bet (its `placedAt` is the `acceptedAt` of the 202). It owns no repository
and is not transactional. The controller calls only this service.
`messaging.BetPublisher implements BetEventPublisher`: `public void publish(Bet bet)` — `BetEventMapper.toEvent(bet)`;
`kafkaTemplate.send(betsTopic, bet.jackpotId(), event).get(publishTimeout)`; `ExecutionException` / `TimeoutException` /
`KafkaException` → `BetPublishingException(betId, cause)`; `InterruptedException` → restore the interrupt flag +
`BetPublishingException`. Counter `jackpot.bets.published{result=success|failure}`.

### 6.2 `BetProcessingService`
```java
@Transactional(isolation = Isolation.READ_COMMITTED) public ProcessingResult process(Bet bet)
@Transactional(readOnly = true)                      public boolean isProcessed(String betId)
```
`process`:
1. `jackpot = jackpotRepository.findByIdForUpdate(bet.jackpotId())` (row lock).
2. `betRepository.findById(bet.betId())` present → WARN if payload differs (userId, jackpotId, amount via compareTo) → `ProcessingResult.duplicate`. No draw.
3. Jackpot absent → `betRepository.saveAndFlush(BetEntity(…, NO_MATCHING_JACKPOT, placedAt, now))`, publish `BetProcessedEvent`, return `noMatchingJackpot`.
4. Both policies' `validateFor(initial)` (the startup check does not cover a jackpot changed while running;
   `JackpotConfigurationException` → rollback → DLT), then `amount = policy.contributionAmount(stake, poolBefore, initial)`;
   `poolAfter = jackpot.addContribution(amount, now)`; `cycle = jackpot.getCycle()`.
5. `saveAndFlush` bet (CONTRIBUTED) then contribution (`currentJackpotAmount = poolAfter`, `poolCycle = cycle`).
6. `amount > 0` ? `chance = rewardPolicy.winChancePercentage(poolAfter, initial)`, `won = rewardDraw.isWinning(chance)` : `chance = 0.0000`, `won = false`.
7. WON → `reward = jackpot.award(now)`; `saveAndFlush(JackpotRewardEntity(…, reward, cycle, now))`. LOST → reward 0.00.
8. `saveAndFlush(BetEvaluationEntity(…, outcome, chance, reward, cycle, now))`.
9. `eventPublisher.publishEvent(BetProcessedEvent(...))`; return `processed(contribution, evaluation)`.

### 6.3 `BetQueryService` — class-level `@Transactional(readOnly = true)`
`ProcessedBet getBet(String betId)` → `BetNotFoundException`.
`Contribution getContribution(String betId)` / `BetEvaluation getEvaluation(String betId)`: the bet row is read FIRST —
absent → `BetNotFoundException`, `NO_MATCHING_JACKPOT` → `BetNotContributingException`, `CONTRIBUTED` → the child row,
which was committed in the same transaction (missing = broken invariant, `IllegalStateException`). §12 item 11.

### 6.4 `JackpotQueryService` — class-level `@Transactional(readOnly = true)`
`List<Jackpot> findAll()` (`Sort.by("id")`), `Jackpot getJackpot(String id)` → `JackpotNotFoundException`.

### 6.5 `JackpotConfigurationValidator implements SmartInitializingSingleton` (§12 item 11)
Loads all jackpots via `JackpotQueryService`, calls `contributionPolicy.validateFor(initial)` and `rewardPolicy.validateFor(initial)` → throws
(startup fails) on misconfiguration, before the lifecycle beans (Kafka listener containers, web server) start. The same
static check runs in `BetProcessingService` for every bet. Logs a WARN when the datasource URL starts with `jdbc:h2:mem:`
("volatile, single-instance only").

### 6.6 `RewardDraw`
`boolean isWinning(BigDecimal chancePercentage)`: reject < 0 or > 100 (`IllegalArgumentException`);
`threshold = chance.setScale(4, HALF_EVEN).movePointRight(4).longValueExact()`; `return random.nextLong(1_000_000L) < threshold`.

### 6.7 `event.BetProcessedEvent` / `event.BetProcessingMetrics`
`record BetProcessedEvent(ProcessingStatus status, String betId, String jackpotId, EvaluationOutcome outcome /*nullable*/,
BigDecimal contributionAmount /*nullable*/, BigDecimal rewardAmount /*nullable*/, Instant placedAt, Instant processedAt)`.
`BetProcessingMetrics.onProcessed` (`@TransactionalEventListener(phase = AFTER_COMMIT)`): counters
`jackpot.bets.processed{status}`, `jackpot.evaluations{outcome}` (PROCESSED only), `jackpot.rewards.amount` (DistributionSummary, WON only),
INFO/WARN log, then timer `jackpot.bets.processing.latency` (processedAt − placedAt). `placedAt` is producer-supplied, so a
span that is negative or longer than 30 days (the DLT retention) is not recorded (§12 item 12).

---

## 7. Messaging

- Topics: `jackpot-bets` (partitions **12**, RF, `min.insync.replicas`), `jackpot-bets.DLT` (own partitions, retention 30 d). `NewTopic` beans.
- `BetPlacedEvent(String betId, String userId, String jackpotId, BigDecimal betAmount, Instant placedAt)`; key = `jackpotId`; JSON, no type headers.
- Keep Boot's auto-configured producer/consumer/container factories. Producer value serializer is set through a
  `DefaultKafkaProducerFactoryCustomizer` → `DelegatingByTypeSerializer({byte[] → ByteArraySerializer, BetPlacedEvent → Jackson 3 JSON serializer (no type headers)}, assignable=true)`.
  The same customizer validates `publish-timeout ≥ max.block.ms + delivery.timeout.ms + 1s` (fail fast).
- Consumer via YAML: `ErrorHandlingDeserializer` → Jackson 3 JSON deserializer delegate `messaging.BetPlacedEventDeserializer`
  (strict like the API: duplicate keys and string-to-number coercion fail, §12 item 12), default type `BetPlacedEvent`,
  `spring.json.use.type.headers=false`, trusted package `com.sporty.jackpot.messaging`, unknown JSON properties ignored,
  `CooperativeStickyAssignor`, `max.poll.records=50`, `max.poll.interval.ms=300000`, `auto-offset-reset=earliest`.
- `ContainerCustomizer`: platform-thread `listenerTaskExecutor`, `shutdownTimeout=15s`, `checkDeserExWhenKeyNull/ValueNull=true`.
- `BetEventListener.onBetPlaced(ConsumerRecord<String, BetPlacedEvent> record)` (not transactional):
  null value → WARN "tombstone", skip; `bet = mapper.toBet(value)`; `processingService.process(bet)`;
  `catch (DataIntegrityViolationException e)` → `isProcessed(betId)` ? WARN duplicate race : rethrow. MDC `betId`/`jackpotId` during processing.
- `CommonErrorHandler` bean = `DefaultErrorHandler(recoverer, ExponentialBackOffWithMaxRetries(maxRetries, initial, multiplier, maxInterval))`:
  - `addNotRetryableExceptions(InvalidBetException, JackpotConfigurationException, DataIntegrityViolationException)` (+ Spring defaults).
  - `setBackOffFunction((rec, ex) -> isTransient(ex) ? unboundedExponentialBackOff : null)` where transient =
    cause chain contains `TransientDataAccessException`, `RecoverableDataAccessException`, `CannotCreateTransactionException`,
    `DataAccessResourceFailureException`, `java.sql.SQLTransientException`, `org.apache.kafka.common.errors.RetriableException`.
  - recoverer = counter `jackpot.bets.dead-lettered{exception}` + `DeadLetterPublishingRecoverer` with resolver → `(deadLetterTopic, -1)`.
    A dead-letter record too large to publish (`RecordTooLargeException`) is logged, counted as
    `jackpot.bets.dead-letter-failed{exception}` and acknowledged instead of being retried forever (§12 item 11).
- `KafkaListenersHealthIndicator` (`HealthIndicator`): UP when every container in `KafkaListenerEndpointRegistry` is running
  (or auto-startup is disabled, or the registry itself is not running: startup/shutdown), DOWN otherwise, with container
  ids in details. Member of the liveness group.

---

## 8. REST API (`/api/v1`, JSON; OpenAPI at `/v3/api-docs`, Swagger UI `/swagger-ui.html`)

| Method | Path | Success | Errors |
|--------|------|---------|--------|
| POST | `/api/v1/bets` (`PlaceBetRequest`) | `202` `BetAcceptedResponse`, `Location: /api/v1/bets/{betId}` | 400 VALIDATION_FAILED / MALFORMED_REQUEST / INVALID_BET, 415, 503 BET_PUBLISH_FAILED |
| GET | `/api/v1/bets/{betId}` | `200` `BetResponse` | 400, 404 BET_NOT_FOUND |
| GET | `/api/v1/bets/{betId}/contribution` | `200` `ContributionResponse` | 400, 404 BET_NOT_FOUND, 422 BET_NOT_CONTRIBUTING |
| GET | `/api/v1/bets/{betId}/evaluation` | `200` `BetEvaluationResponse` (did the bet win + reward) | 400, 404 BET_NOT_FOUND, 422 BET_NOT_CONTRIBUTING |
| GET | `/api/v1/jackpots` | `200` `List<JackpotResponse>` | – |
| GET | `/api/v1/jackpots/{jackpotId}` | `200` `JackpotResponse` | 400, 404 JACKPOT_NOT_FOUND |

DTOs (records; Jakarta Validation; `@Schema`):
```java
record PlaceBetRequest(@NotBlank @Pattern(regexp = Bet.ID_REGEX) String betId,
                       @NotBlank @Pattern(regexp = Bet.ID_REGEX) String userId,
                       @NotBlank @Pattern(regexp = Bet.ID_REGEX) String jackpotId,
                       @NotNull @DecimalMin("0.01") @DecimalMax("1000000000.00") @Digits(integer = 10, fraction = 2) BigDecimal betAmount)
record BetAcceptedResponse(String betId, String jackpotId, String status /* "ACCEPTED" */, Instant acceptedAt)
record BetResponse(String betId, String userId, String jackpotId, BigDecimal betAmount, String status, Instant placedAt, Instant processedAt)
record ContributionResponse(String betId, String userId, String jackpotId, BigDecimal stakeAmount, BigDecimal contributionAmount,
                            BigDecimal currentJackpotAmount, Instant createdAt)
record BetEvaluationResponse(String betId, String userId, String jackpotId, String outcome, boolean won, BigDecimal rewardAmount,
                             BigDecimal winChancePercentage, Instant evaluatedAt)
record JackpotResponse(String id, String name, BigDecimal initialPoolAmount, BigDecimal currentPoolAmount, long cycle,
                       PolicyResponse contributionPolicy, PolicyResponse rewardPolicy, Instant updatedAt)
record PolicyResponse(String type, Map<String, BigDecimal> parameters)   // LinkedHashMap, deterministic order
```
Path variables: `@Pattern(regexp = Bet.ID_REGEX)` on `@PathVariable` parameters. **Do NOT put `@Validated` on controller classes**
(verified: the AOP proxy then throws `ConstraintViolationException` → 500). Spring MVC 7 built-in method validation raises
`HandlerMethodValidationException`, handled by `handleHandlerMethodValidationException`.

### 8.1 Errors — RFC 9457 `ProblemDetail`
Properties: `code`, `timestamp`; `errors: [{field, message}]` for validation — a `record FieldErrorDto(String field, String message)`
list sorted by field then message (never a `Map`, iteration order is random); `betId` for BET_PUBLISH_FAILED.

| ErrorCode | HTTP |
|-----------|------|
| VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_BET | 400 |
| BET_NOT_FOUND, JACKPOT_NOT_FOUND, RESOURCE_NOT_FOUND | 404 |
| METHOD_NOT_ALLOWED | 405 |
| NOT_ACCEPTABLE | 406 |
| CONFLICT | 409 |
| UNSUPPORTED_MEDIA_TYPE | 415 |
| BET_NOT_CONTRIBUTING | 422 |
| INTERNAL_ERROR (incl. JackpotConfigurationException) | 500 |
| BET_PUBLISH_FAILED, TEMPORARILY_UNAVAILABLE | 503 (+ `Retry-After: 1`) |

`GlobalExceptionHandler extends ResponseEntityExceptionHandler`: `JackpotServiceException` → its code; validation exceptions →
VALIDATION_FAILED; `HttpMessageNotReadableException` → MALFORMED_REQUEST; `ConcurrencyFailureException`,
`TransientDataAccessException`, `CannotCreateTransactionException`, `DataAccessResourceFailureException` → TEMPORARILY_UNAVAILABLE;
`DataIntegrityViolationException` → CONFLICT; base-class handled exceptions get `code` via `handleExceptionInternal`
(by status: 404→RESOURCE_NOT_FOUND, 405, 406, 415, other 4xx→MALFORMED_REQUEST, 5xx→INTERNAL_ERROR); `Exception` → INTERNAL_ERROR with
generic detail (logged at ERROR, never leaks internals). `ErrorHttpStatus.of(ErrorCode)` = exhaustive switch.

---

## 9. Configuration

`application.yml` (default profile: H2 in-memory, single instance):
```yaml
spring:
  application.name: jackpot-service
  threads.virtual.enabled: true
  lifecycle.timeout-per-shutdown-phase: 20s
  datasource:
    url: jdbc:h2:mem:${jackpot.h2.database:jackpot};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000
    username: sa
    password: ""
    hikari: { maximum-pool-size: 10, minimum-idle: 2, connection-timeout: 2000, pool-name: jackpot-hikari }
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate
    properties.hibernate: { jdbc.time_zone: UTC, jdbc.batch_size: 50 }
  jackson: { read.strict-duplicate-detection: true, mapper.allow-coercion-of-scalars: false }
  flyway:
    locations: classpath:db/migration
    placeholders:
      jackpot_fixed_initial_pool: ${JACKPOT_FIXED_INITIAL_POOL:1000.00}
      jackpot_variable_initial_pool: ${JACKPOT_VARIABLE_INITIAL_POOL:5000.00}
      jackpot_mixed_initial_pool: ${JACKPOT_MIXED_INITIAL_POOL:10000.00}
      jackpot_lucky_initial_pool: ${JACKPOT_LUCKY_INITIAL_POOL:100.00}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    admin.fail-fast: true
    template.observation-enabled: true
    producer:
      acks: all
      properties: { enable.idempotence: true, linger.ms: 5, compression.type: lz4, max.block.ms: 3000, request.timeout.ms: 3000, delivery.timeout.ms: 8000 }
    consumer:
      group-id: ${JACKPOT_CONSUMER_GROUP:jackpot-service-local}
      auto-offset-reset: earliest
      properties: { partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor, max.poll.records: 50, max.poll.interval.ms: 300000 }
    listener: { concurrency: 3, observation-enabled: true }
server.shutdown: graceful
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  endpoint.health: { probes.enabled: true, show-details: always, group.liveness.include: "livenessState,kafkaListeners" }
jackpot:
  kafka:
    bets-topic: jackpot-bets
    dead-letter-topic: jackpot-bets.DLT
    partitions: 12
    dead-letter-partitions: 3
    replication-factor: 1
    min-insync-replicas: 1
    dead-letter-retention: 30d
    publish-timeout: 12s
    consumer.retry: { max-retries: 5, initial-interval: 500ms, multiplier: 2.0, max-interval: 30s }
```
`application-postgres.yml`: `url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/jackpot}`, `POSTGRES_USER`/`POSTGRES_PASSWORD`
(default `jackpot`), `hikari.connection-init-sql: SET lock_timeout = '3s'`, consumer group `${JACKPOT_CONSUMER_GROUP:jackpot-service}`,
`management.server.port: 8081`.

`JackpotProperties(@Valid @NotNull Kafka kafka)`; `Kafka(@NotBlank betsTopic, @NotBlank deadLetterTopic, @Min(1) int partitions,
@Min(1) int deadLetterPartitions, @Min(1) short replicationFactor, @Min(1) int minInsyncReplicas, @NotNull Duration deadLetterRetention,
@NotNull Duration publishTimeout, @Valid @NotNull Consumer consumer)` — also asserts `minInsyncReplicas ≤ replicationFactor`;
`Consumer(@Valid @NotNull Retry retry)`; `Retry(@Min(0) int maxRetries, @NotNull Duration initialInterval, @DecimalMin("1.0") double multiplier, @NotNull Duration maxInterval)`.

---

## 10. Build, run, test

- Maven wrapper 3.3.4 (`mvn -N wrapper:wrapper -Dmaven=3.9.9`), Boot parent 4.1.1, Java 21 (`$JAVA_HOME` pointing to a
  JDK 21).
- Dependencies/APIs: see §13 (verified by the probe project).
- JaCoCo: `check` in `verify`, BUNDLE rules INSTRUCTION/BRANCH/LINE/METHOD/CLASS/COMPLEXITY = 1.00, **no excludes**.
- Surefire: `**/*Test.java` (H2 + Embedded Kafka, no Docker). Failsafe: `**/*IT.java` only with `-Pit` (Testcontainers PostgreSQL + Kafka).
- `Dockerfile`: multi-stage (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`), non-root, Boot layered jar extraction
  (`java -Djarmode=tools -jar app.jar extract --layers --launcher`), `-XX:MaxRAMPercentage=75`, exec-form `ENTRYPOINT`,
  `HEALTHCHECK` on the management port. **No BuildKit-only syntax** (`RUN --mount`, `# syntax=`): the file must build with
  the legacy builder (`DOCKER_BUILDKIT=0`) on Docker CLIs without buildx.
  `.dockerignore` excludes `target/`, `.git/`, IDE files.
- `docker-compose.yml` (`docker compose up --build` runs everything):
  - `kafka`: `apache/kafka:4.2.1` (matches kafka-clients 4.2.1), env `KAFKA_NODE_ID=1`, `KAFKA_PROCESS_ROLES=broker,controller`,
    `KAFKA_LISTENERS=INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093`,
    `KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka:29092,EXTERNAL://localhost:9092`,
    `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT`,
    `KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL`, `KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER`, `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093`,
    `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1`,
    `KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0`, `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`; port 9092; healthcheck
    `kafka-broker-api-versions.sh --bootstrap-server kafka:29092`.
  - `postgres`: `postgres:17-alpine`, db/user/password `jackpot`, `command: postgres -c max_connections=200`, `pg_isready` healthcheck, port 5432.
  - Named volumes `postgres-data` (`/var/lib/postgresql/data`) and `kafka-data` (`/var/lib/kafka/data`, with
    `KAFKA_LOG_DIRS` pointing there): `docker compose down` keeps the data, `down -v` deletes it. The Kafka image's
    other, unused volumes (`/etc/kafka/secrets`, `/mnt/shared/config`) are `tmpfs` mounts, so no `down` leaves
    anonymous volumes behind (§12 item 12).
  - `kafka-ui`: `kafbat/kafka-ui:v1.5.0` → `kafka:29092`, port 8090.
  - `jackpot-service`: build `.`, `SPRING_PROFILES_ACTIVE=postgres`, `KAFKA_BOOTSTRAP_SERVERS=kafka:29092`,
    `POSTGRES_URL=jdbc:postgresql://postgres:5432/jackpot`, ports 8080 + 8081, `depends_on: condition: service_healthy`, `stop_grace_period: 30s`.

### 10.1 Test plan (all required)
- Test config in `src/test/resources/config/application.yml` (classpath:/config/ overrides classpath:/application.yml for **every**
  test context, no profile needed): `jackpot.h2.database: test-${random.uuid}` (one DB per context; the rest of the shipped
  H2 URL is kept, §12 item 11), retry intervals 10–50 ms, `spring.kafka.listener.auto-startup: false` by default (the Embedded-Kafka
  base class turns it on), quiet Kafka/embedded-broker loggers (`kafka`, `org.apache.kafka`, `org.apache.zookeeper` → WARN;
  `kafka.server.ReplicaManager`, `org.apache.kafka.storage.internals.log.LogDirFailureChannel` → OFF).
  Tests create their own jackpots with unique ids (JdbcTemplate/repository); seeds are only asserted read-only.
- One abstract `AbstractKafkaIntegrationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@EmbeddedKafka(partitions = 12,
  brokerProperties = "auto.create.topics.enable=false")`, no topic list → `NewTopic` beans create them), single cached context:
  no per-class `@MockitoBean`, no `@DirtiesContext`, not `@Transactional`; `ContainerTestUtils.waitForAssignment` before producing;
  Awaitility `atMost(30s)`, `pollInterval(100ms)`.
- `@DataJpaTest` always with `@AutoConfigureTestDatabase(replace = NONE)` (keep MODE=PostgreSQL URL).
- Domain unit tests: every formula + boundaries, floors/caps, validation, `validateFor`; `Money`; `Bet` invariants; `ProcessingResult` factories.
- `RewardDraw`: chance 0 never wins, 100 always wins, exact threshold boundary, invalid chance rejected (stub `RandomGenerator`).
- Service unit tests (Mockito): every branch of process / queries / publish (success, ExecutionException, TimeoutException,
  KafkaException, InterruptedException incl. interrupt flag), configuration validator, metrics listener.
- Persistence tests: seeds + placeholders applied; converters round-trip for every permitted subclass; unique/FK/check constraints;
  lock query; `award`/`addContribution`.
- Web: `@WebMvcTest` for both controllers + every `GlobalExceptionHandler` branch/error code; `ErrorHttpStatus` exhaustive.
- Messaging: listener branches (tombstone, processed, duplicate, no match, DIV duplicate race, DIV rethrow); mapper; error handler
  classification (transient → unbounded back-off, deterministic → no retry, unknown → bounded); DLT resolver; health indicator.
- Integration (Embedded Kafka): HTTP → Kafka → processing → `GET` bet/contribution/evaluation; deterministic WON + reset on a
  100 %-at-limit jackpot; duplicate record → single contribution and a single draw; unknown jackpot → 422 on evaluation;
  malformed JSON → DLT with original raw bytes + `kafka_dlt-*` headers; invalid bet → DLT; extra JSON field and foreign `__TypeId__`
  header still processed; effective producer config (acks, idempotence, timeouts) and container concurrency asserted.
- **Concurrency (service level, no Kafka)**: ≥ 32 threads behind a `CountDownLatch`/`CyclicBarrier` against the real H2:
  (a) N contributions to one jackpot → pool = initial + Σ contributions − Σ rewards, exactly one reward per cycle, version consistent;
  (b) the same betId concurrently to two jackpots → exactly one bet row, the loser resolves as duplicate through the DIV path,
  the loser jackpot's pool unchanged; (c) 100 %-chance jackpot with parallel bets → N WONs with distinct cycles, final pool = initial.
- Transactional rollback: failure after the pool update (e.g. evaluation insert fails) → pool, bet, contribution all rolled back.
- `ArchitectureTest` (ArchUnit, §3 rules). `JackpotProperties` binding/validation. `JackpotServiceApplication.main` via `mockStatic(SpringApplication.class)`.
- `-Pit` `JackpotFlowIT` (Testcontainers PostgreSQL 17 + Kafka): Flyway on real PostgreSQL + full HTTP flow + concurrency (a)/(b) on PostgreSQL.

---

## 11. README contract
Sections: Overview & architecture (mermaid); Prerequisites; Run (compose all-in-one; host run with `docker compose up -d kafka postgres`
+ `./mvnw spring-boot:run` (H2) or `-Dspring-boot.run.profiles=postgres`); API walkthrough with curl for every endpoint incl. the
deterministic WON demo; seeded jackpots table with all parameters; error model with sample bodies; Assumptions & interpretations (§1.3);
Traps table (§2); Transactions & delivery semantics; Scaling (partitions, limits: effective parallelism =
min(partitions, active jackpots, instances × concurrency), per-jackpot ceiling = one consumer thread, next step = batch listener grouped by
jackpot; H2 = single instance; pool sizing formula instances × pool ≤ max_connections − reserved); Observability (metrics, health, DLT
replay procedure); Testing & coverage (`./mvnw verify`, report path, `-Pit` with colima env vars); Extending (how to add a policy:
record + `permits` + mix-in subtype + ApiMapper case + tests); Project structure.

## 12. Deviations log
_(implementation notes any forced deviation here)_

1. **`BetEntity implements Persistable<String>`** (§5.2): the id accessor is `getId()` (no `getBetId()`), `isNew()` is
   `true` only for instances built by the business constructor. Forced by T3: with an assigned String PK and no
   `@Version`, Spring Data's `save` would `merge`, and a concurrent duplicate committed in between would be turned into a
   silent `UPDATE` of the other consumer's bet row (e.g. overwritten with `NO_MATCHING_JACKPOT`) instead of the expected
   PK violation. With `Persistable` every new bet is an `INSERT`, so the race always surfaces as
   `DataIntegrityViolationException`.
2. **`GlobalExceptionHandler`** (§8.1): `code`/`timestamp` for base-class-handled exceptions are added in the
   `createResponseEntity` override (the last step of `handleExceptionInternal`, after the committed-response check),
   not in `handleExceptionInternal` itself, and the two validation overrides build their `VALIDATION_FAILED` response
   directly. This keeps every branch reachable (`handleExceptionInternal` may return `null` only for committed responses).
3. **`JackpotConfigurationValidator`** (§6.5) reads the JDBC URL from Boot's `JdbcConnectionDetails` instead of the raw
   `spring.datasource.url` property, so the H2 warning is correct when a Testcontainers `@ServiceConnection` replaces
   the datasource.
4. **Flyway placeholders** (§9): the YAML keys use Boot's bracket notation (`"[jackpot_fixed_initial_pool]": …`);
   without brackets Boot's map binding strips the underscores and the placeholders are never resolved.
5. **Kafka details** (§7): the consumer key deserializer is also an `ErrorHandlingDeserializer` (delegate
   `StringDeserializer`), so `checkDeserExWhenKeyNull` is meaningful; the DLT also gets `min.insync.replicas`;
   `PolicyJson` additionally enables `FAIL_ON_UNKNOWN_PROPERTIES` (typos in stored policies fail loudly) and
   `WRITE_BIGDECIMAL_AS_PLAIN`. Policy validation helpers live in a package-private `domain.policy.PolicyParameters`.
6. **Test configuration** (§10.1): `src/test/resources/config/application.yml` also sets
   `spring.kafka.admin.auto-create: false` (contexts without a broker never try to create topics; the Embedded-Kafka base
   class turns it back on together with `listener.auto-startup`) and silences further embedded-broker shutdown noise
   (`state.change.logger` WARN, `kafka.log.LogManager` OFF, `org.apache.kafka.common.utils.Utils` ERROR).
   `AbstractKafkaIntegrationTest` additionally waits until the 12 partitions are spread over all 3 consumers
   (cooperative-sticky assigns incrementally) so no rebalance happens in the middle of a test; the DLT helper is a
   buffering `support.DeadLetterReader`.
7. **Docker** (§10): the image sets `ENV MANAGEMENT_SERVER_PORT=8081` so the `HEALTHCHECK`
   (`/actuator/health/readiness`) hits the same port under every profile; the build stage uses the base image's `mvn`.
   In `docker-compose.yml` the PostgreSQL host port is `${POSTGRES_HOST_PORT:-5432}` (a host PostgreSQL already
   listens on 5432 on the dev machine), and `jackpot-service` also declares a compose-level healthcheck. PostgreSQL and
   Kafka keep their data in named volumes (item 12).
8. **Production fixes found by the test suites** (integration pass):
   - **Policy converters** (§5.4): the JSON literal `null` (valid JSON, and not rejected by the `NOT NULL` column) is
     rejected with `JackpotConfigurationException("Invalid contribution|reward policy JSON: null")` (no cause) instead
     of silently becoming a `null` policy. That null previously surfaced as an NPE: at startup instead of a clear
     configuration error, and in the listener as an "unknown" exception with bounded retries instead of the immediate
     DLT required by T11. `null ↔ null` applies to SQL `NULL` only.
   - **Controllers** (§8): `BetController` and `JackpotController` declare `produces = application/json` at class
     level, so an unsatisfiable `Accept` header is rejected with `406 NOT_ACCEPTABLE` during handler mapping, before
     the handler runs. Previously `POST /api/v1/bets` published the bet and only then failed with 406 while writing
     the response: the client was told "rejected" although the bet was processed. Error bodies are still
     `application/problem+json`.
   - **`jackpot.bets.dead-lettered`** (§7): the counting recoverer logs before delegating but counts only after the
     `DeadLetterPublishingRecoverer` has published the record. A failed DLT publication propagates (the record is
     re-seeked) and is not counted, so the metric cannot climb for records that were never dead-lettered. Exception: a
     dead-letter record too large to publish (item 11).
   - **`application.yml`** (§9): `springdoc.api-docs.enabled: true` and `springdoc.swagger-ui.enabled: true` are set
     explicitly. Both endpoints are part of the contract; springdoc only enables them by default and logs a WARN at
     every startup when they are left implicit.
9. **Observed behaviour, no change needed**:
   - Hibernate wraps a converter's `JackpotConfigurationException` as `JpaSystemException` → `PersistenceException`
     → `JackpotConfigurationException`. The Kafka `DefaultErrorHandler` classifies by traversing the cause chain, so
     such a record is still dead-lettered on the first delivery (T11). Only the listener's error tag and the DLT
     exception headers name the wrapper. Over REST the generic handler answers `500 INTERNAL_ERROR`, the status and
     code §8.1 requires.
   - The jackpot `version` grows by contributions plus payouts, not by bets: a winning bet flushes the jackpot row twice
     in its one transaction (contribution, then award).
10. **Test plan corrections** (§10.1):
    - The concurrency invariant (a) `pool = initial + Σ contributions − Σ rewards` only holds while nothing is won. A
      payout takes the whole pool and the reset re-seeds `initial`, so the asserted invariant (`JackpotLedger`) is
      `pool = initial × (1 + number of rewards) + Σ contributions − Σ rewards`.
    - `src/test/resources/spring.properties` sets `spring.test.context.cache.pause=never`. Spring Framework 7 pauses a
      cached context on every context switch by default. The shared Embedded-Kafka context's consumers then left and
      re-joined the group, a cooperative rebalance each time. On restart `KafkaListenerEndpointRegistry` starts every
      container even when `spring.kafka.listener.auto-startup=false` (`alwaysStartAfterRefresh`), so a consumer-less
      context would join the embedded broker's group. The service-level context also imports
      `NoKafkaConsumersTestConfiguration` (`alwaysStartAfterRefresh=false`) as a safeguard.
    - Besides the Embedded-Kafka context, a second full context (`AbstractProcessingServiceIntegrationTest`: no
      Kafka consumers, scripted `RandomGenerator`) runs the service-level concurrency and rollback tests.
    - The contexts that run the concurrency scenarios (`AbstractProcessingServiceIntegrationTest`, `JackpotFlowIT`)
      raise `spring.datasource.hikari.connection-timeout` to 60 s. 32 threads share the pool of 10 and serialize on
      one jackpot row lock, so waiting for a pooled connection legitimately takes as long as the scenario. On
      PostgreSQL in a 2-CPU Docker VM the production 2 s timeout produced spurious `CannotCreateTransactionException`s.
      Production keeps 2 s (at most 3 consumer threads per instance share the pool, and an acquire timeout is
      transient: the listener retries it, and the API answers 503).
    - `JackpotFlowIT` is `@DirtiesContext`, so its context (and Kafka clients) is closed before `@Testcontainers`
      stops the broker and the database, instead of reconnecting to stopped containers until JVM exit.
    - `ArchitectureTest` uses plain JUnit tests over `ClassFileImporter` with `ImportOption.DoNotIncludeTests` (not the
      ArchUnit JUnit engine), so rule 6 does not flag `@Autowired` fields in test classes.

11. **Review round 1** (fixes of confirmed findings):
    - **Bounded error messages** (§4.2): `Bet` quoted a rejected amount with `toPlainString()`. An 11-byte JSON amount
      such as `1e999999999` has a plain form of a billion digits: the consumer thread died with an `OutOfMemoryError`,
      the container stopped every consumer and the record was never committed. Amounts are now quoted with
      `toString()` (scientific notation for extreme exponents), and a rejected id is quoted cut to 80 characters plus
      its length. The counting recoverer cuts the logged key and failure to 500 characters.
    - **Oversized dead-letter records** (§7): the dead-letter copy of a record adds exception headers and can exceed
      the producer's `max.request.size` (1 MiB). That failure is deterministic, and re-seeking the record retried it
      forever with no back-off, blocking the consumer thread. A `RecordTooLargeException` is now logged once with the
      record's coordinates, counted as `jackpot.bets.dead-letter-failed{exception}` and acknowledged (the original
      stays in `jackpot-bets` for a replay within its retention). `KafkaConfig.DeadLetterPublisher` overrides
      `DeadLetterPublishingRecoverer.publish` because the base class drops the cause of a send that fails
      synchronously (the producer rejects an oversized record before sending it).
    - **Lookups race the commit** (§6.3, T9): `getContribution`/`getEvaluation` read the child table first and, on a
      miss, checked whether the bet row existed. Each READ_COMMITTED statement has its own snapshot, so a commit
      between the two statements answered the terminal `422 BET_NOT_CONTRIBUTING` for a contributing (possibly
      winning) bet. The bet row is now read first and its status decides 404 / 422.
    - **Jackpot configuration check** (§6.2, §6.5, T8): the `ApplicationRunner` ran after the context refresh, when the
      Kafka listener containers were already consuming, and it never protected running instances (e.g. a migration of
      a rolling deploy on the shared PostgreSQL). With `poolLimit <= initialPool` every contributing bet wins the whole
      pool. The validator is now a `SmartInitializingSingleton` (runs before any lifecycle bean starts), and
      `BetProcessingService` repeats the check for every bet, under the lock and before any write
      (`JackpotConfigurationException` → rollback → DLT).
    - **Package structure** (§3): the Kafka producer adapter moved from `service.BetPublishingService` to
      `messaging.BetPublisher`. It used `messaging.BetEventMapper` and `config.JackpotProperties`, while
      `messaging.BetEventListener` uses `service`: two package cycles. ArchUnit now checks the layer dependencies and
      the absence of cycles (rule 7), controllers by `@Controller` meta-annotation, and that every public method of a
      repository-owning service is transactional with `READ_COMMITTED` writes (rule 5).
    - **Liveness** (§7, §9): `kafkaListeners` joins the liveness group, so an orchestrator restarts an instance whose
      consumers stopped (fatal listener error). Readiness keeps its default: the HTTP API still works. The indicator
      reports UP while the registry itself is stopped (graceful shutdown). The Docker/compose healthchecks still poll
      readiness (Docker does not restart unhealthy containers).
    - **API**: ids `.` and `..` are rejected (URL dot segments: `Location: /api/v1/bets/..` resolves elsewhere);
      request bodies are parsed strictly (duplicate keys and string-to-number coercion → `400 MALFORMED_REQUEST`);
      OpenAPI documents every 4xx/5xx as `application/problem+json` with a `Problem` schema (`api.error.
      ProblemDetailOpenApiCustomizer`, which also adds `Retry-After` to every 503), plus `Location` on 202, 400 on the
      id lookups, 415 on POST and 503 `TEMPORARILY_UNAVAILABLE` on the lookups.
    - **Percentages** (§4.3): policy percentages have at most 4 decimals (the precision of the formulas and of the
      draw); a finer value was displayed as configured but silently rounded when used.
    - **Tests run the shipped H2 settings** (§9, §10.1): the test configuration overrode the whole H2 URL, so the
      shipped `MODE`/`LOCK_TIMEOUT` were never exercised. The URL now takes the database name from
      `jackpot.h2.database` (default `jackpot`), and the tests only set that name.
    - **Docker**: the image build skips test compilation (`-Dmaven.test.skip=true`).

12. **Review round 2** (fixes of confirmed findings):
    - **Place-bet use case in a service** (§3, §6.1): after round 1 moved the producer to `messaging`, `BetController`
      stamped the bet with its own `Clock` and called the Kafka adapter directly (`api` → `messaging`), so the only
      write use case had no service and a second entry point would have duplicated it. `service.BetPlacementService`
      now owns it and publishes through the port `service.BetEventPublisher`, which `messaging.BetPublisher`
      implements (`messaging` → `service`, no cycle). The controller depends on services and `ApiMapper` only, and
      ArchUnit rule 7 no longer lets `api` use `messaging`. `ApiMapper.toBet` is gone; `toAcceptedResponse(Bet)` takes
      the `acceptedAt` from `placedAt`.
    - **Contribution percentages > 0** (§4.3, T8): a fixed 0 % contribution, or a variable one whose `minPercentage` is
      0, makes every bet (eventually) contribute 0.00. Such a bet is never drawn (§1.3), so the pool could neither grow
      nor be won again, and the players' contributions stayed locked. `PolicyParameters.requirePositivePercentage`
      rejects it when the policy is built (startup check, and a runtime-changed jackpot's bets go to the DLT). A fixed
      0 % reward chance stays allowed: it is an explicit, visible choice.
    - **Strict consumer JSON** (§7, T11): request bodies were strict since round 1, but the consumer, where the stake
      is applied, used Spring Kafka's lenient default mapper: a duplicate `betAmount` was applied last-wins and a string
      amount was coerced. The value delegate `messaging.BetPlacedEventDeserializer` (Spring Kafka's mapper with
      `STRICT_DUPLICATE_DETECTION` on and `ALLOW_COERCION_OF_SCALARS` off) dead-letters both with their original
      bytes. Unknown properties are still ignored. It is named in `spring.deserializer.value.delegate.class`, so the
      `spring.json.*` properties still configure it.
    - **Latency of producer-supplied `placedAt`** (§6.7): `Timer.record(Duration)` overflowed for a span beyond about
      292 years (`ArithmeticException` in `Duration.toNanos`). The AFTER_COMMIT listener then failed after a
      committed payout, before the `WON` counter, the reward summary and the business log line. The listener now
      records the meters and the log line first and the latency last, and only for a span within [0, 30 days].
    - **Bounded policy messages** (§4.3): like `Bet` in round 1, policy and draw validation messages quoted rejected
      values with `toPlainString()`; a stored `1e-999999999` then raised an `OutOfMemoryError` at startup (or killed a
      consumer thread) instead of a `JackpotConfigurationException`. They now use `toString()`.
    - **Compose volumes** (§10): PostgreSQL and Kafka used anonymous volumes, so a plain `docker compose down` lost
      all data (the README implied only `down -v` did) and left the old volumes dangling. The broker also wrote to
      `/tmp/kafka-logs`, outside its image volume. Both now use named volumes, with `KAFKA_LOG_DIRS=/var/lib/kafka/data`,
      and the Kafka image's two other (empty, unused) volume paths are `tmpfs` mounts. Verified on the live stack: after
      a plain `down` and `up`, the pools, cycles, bets, topics and consumer offsets were unchanged, and only the two
      named volumes existed; `down -v` removed them.

## 13. Verified APIs (Spring Boot 4.1.1 probe — reference implementation compiled and tested)

The APIs below were verified in a throw-away probe project during design.

Key facts:
- Starters: `spring-boot-starter-webmvc`, `-validation`, `-data-jpa`, `-flyway` (+ `org.flywaydb:flyway-database-postgresql`), `-kafka`,
  `-actuator`, `micrometer-registry-prometheus` (runtime), `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`, `h2`, `postgresql` (runtime).
  Test: `spring-boot-starter-test`, `-webmvc-test`, `-data-jpa-test`, `-flyway-test`, `-kafka-test`, `awaitility`, `spring-boot-testcontainers`,
  `org.testcontainers:testcontainers-junit-jupiter|testcontainers-postgresql|testcontainers-kafka` (2.0.5 via BOM),
  `com.tngtech.archunit:archunit-junit5:1.4.2`.
- JaCoCo 0.8.15; surefire `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>` with
  `maven-dependency-plugin:properties` and an empty `<argLine/>` property; failsafe only in profile `it`.
- `@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`; `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`;
  `@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure` (**replace = NONE always**); `TestEntityManager` →
  `org.springframework.boot.jpa.test.autoconfigure`; `@MockitoBean` → `org.springframework.test.context.bean.override.mockito`.
  Slices don't load component-scanned `@Configuration` (import `ClockConfig` etc. explicitly).
- Kafka (Jackson 3): `JacksonJsonSerializer<T>().noTypeInfo()`, `JacksonJsonDeserializer`, `ErrorHandlingDeserializer`,
  `DelegatingByTypeSerializer`, all in `org.springframework.kafka.support.serializer`. Hook into Boot's producer factory with
  `org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer` (`factory.setValueSerializerSupplier(...)`, raw cast).
  A single `CommonErrorHandler` `@Bean` is applied automatically. `@KafkaListener` must **not** set `id` (it would become the group id).
  `EmbeddedKafkaBroker.consumeFromAnEmbeddedTopic` only works for topics listed in `@EmbeddedKafka(topics=…)`; otherwise `consumer.subscribe`.
  `KafkaTestUtils.consumerProps(broker, group, autoCommit)`.
- Jackson 3: `tools.jackson.databind.json.JsonMapper`, annotations still `com.fasterxml.jackson.annotation.*`; all failures are
  `tools.jackson.core.JacksonException` (unchecked; record-constructor exceptions arrive wrapped in `ValueInstantiationException`).
  `FAIL_ON_UNKNOWN_PROPERTIES` is false by default; BigDecimal keeps scale; Instants ISO strings.
- Hibernate 7 maps `PESSIMISTIC_WRITE` to `FOR NO KEY UPDATE` on PostgreSQL (does not block child FK inserts); `FOR UPDATE` on H2.
  Lock timeout → `CannotAcquireLockException` (a `TransientDataAccessException`).
- `ResponseEntityExceptionHandler` overrides take `HttpStatusCode`. `ProblemDetail.setProperty`. `NoResourceFoundException` for unknown URLs.
- Testcontainers with colima: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (+ optional
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`); `org.testcontainers.postgresql.PostgreSQLContainer`,
  `org.testcontainers.kafka.KafkaContainer("apache/kafka:4.2.1")` with `@ServiceConnection`.
- Docker CLIs without buildx: if BuildKit is unavailable, build with `DOCKER_BUILDKIT=0 docker build` (the Dockerfile must not
  need BuildKit).
