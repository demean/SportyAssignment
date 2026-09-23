package com.sporty.jackpot.integration;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Keeps the Kafka listener containers of a test context stopped for good (the test configuration disables their
 * auto-startup).
 *
 * <p>The suite disables context pausing ({@code src/test/resources/spring.properties}:
 * {@code spring.test.context.cache.pause=never}); this post-processor keeps the guarantee when a run overrides that
 * setting. With pausing enabled the Spring TestContext framework stops cached contexts that are not in use and restarts
 * them when a later test class needs them again. On that restart {@link KafkaListenerEndpointRegistry} starts every
 * container regardless of its auto-startup flag ({@code alwaysStartAfterRefresh} defaults to {@code true}), so a
 * context meant to run without consumers would join the consumer group of the embedded broker (whose address
 * {@code @EmbeddedKafka} publishes as a system property).
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoKafkaConsumersTestConfiguration {

    @Bean
    static BeanPostProcessor listenerContainersHonourAutoStartupOnRestart() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaListenerEndpointRegistry registry) {
                    registry.setAlwaysStartAfterRefresh(false);
                }
                return bean;
            }
        };
    }
}
