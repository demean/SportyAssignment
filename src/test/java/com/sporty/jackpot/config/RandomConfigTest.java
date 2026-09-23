package com.sporty.jackpot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("RandomConfig")
class RandomConfigTest {

    @Test
    @DisplayName("the reward draw uses an unpredictable SecureRandom within the requested bound")
    void secureRandom() {
        new ApplicationContextRunner().withUserConfiguration(RandomConfig.class).run(context -> {
            assertThat(context).hasSingleBean(RandomGenerator.class);
            RandomGenerator random = context.getBean(RandomGenerator.class);

            assertThat(random).isInstanceOf(SecureRandom.class);
            assertThat(LongStream.range(0, 1_000).map(i -> random.nextLong(1_000_000L)))
                    .allSatisfy(value -> assertThat(value).isBetween(0L, 999_999L));
        });
    }
}
