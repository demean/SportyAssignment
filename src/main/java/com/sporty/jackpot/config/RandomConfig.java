package com.sporty.jackpot.config;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unpredictable random source for reward draws.
 */
@Configuration(proxyBeanMethods = false)
public class RandomConfig {

    @Bean
    RandomGenerator randomGenerator() {
        return new SecureRandom();
    }
}
