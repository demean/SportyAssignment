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
