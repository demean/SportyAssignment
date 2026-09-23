-- Seeded jackpots. Initial pools are Flyway placeholders (spring.flyway.placeholders.*, overridable by environment:
-- JACKPOT_FIXED_INITIAL_POOL, JACKPOT_VARIABLE_INITIAL_POOL, JACKPOT_MIXED_INITIAL_POOL, JACKPOT_LUCKY_INITIAL_POOL).
INSERT INTO jackpot (id, name, initial_pool_amount, current_pool_amount, contribution_policy, reward_policy,
                     pool_cycle, version, created_at, updated_at)
VALUES ('jackpot-fixed', 'Fixed Classic',
        ${jackpot_fixed_initial_pool}, ${jackpot_fixed_initial_pool},
        '{"type":"FIXED","percentage":5.0}',
        '{"type":"FIXED","chancePercentage":1.0}',
        1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('jackpot-variable', 'Variable Progressive',
        ${jackpot_variable_initial_pool}, ${jackpot_variable_initial_pool},
        '{"type":"VARIABLE","startPercentage":10.0,"minPercentage":1.0,"decayPercentage":0.5,"poolIncreaseStep":1000}',
        '{"type":"VARIABLE","startChancePercentage":0.1,"chanceIncreasePercentage":0.5,"poolIncreaseStep":1000,"poolLimit":25000}',
        1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('jackpot-mixed', 'Mixed Mega',
        ${jackpot_mixed_initial_pool}, ${jackpot_mixed_initial_pool},
        '{"type":"FIXED","percentage":2.0}',
        '{"type":"VARIABLE","startChancePercentage":0.01,"chanceIncreasePercentage":0.1,"poolIncreaseStep":5000,"poolLimit":100000}',
        1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('jackpot-lucky', 'Lucky Demo',
        ${jackpot_lucky_initial_pool}, ${jackpot_lucky_initial_pool},
        '{"type":"VARIABLE","startPercentage":20.0,"minPercentage":5.0,"decayPercentage":1.0,"poolIncreaseStep":100}',
        '{"type":"VARIABLE","startChancePercentage":5.0,"chanceIncreasePercentage":10.0,"poolIncreaseStep":10,"poolLimit":150}',
        1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
