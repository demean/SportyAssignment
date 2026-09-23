package com.sporty.jackpot.messaging;

import java.util.Collection;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Health of the Kafka listener containers: DOWN when a container that should run (auto-startup enabled) is not
 * running, UP otherwise.
 */
@Component
public class KafkaListenersHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    public KafkaListenersHealthIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Collection<MessageListenerContainer> listenerContainers = registry.getListenerContainers();
        List<String> containers = listenerContainers.stream()
                .map(MessageListenerContainer::getListenerId)
                .sorted()
                .toList();
        List<String> stopped = listenerContainers.stream()
                .filter(container -> container.isAutoStartup() && !container.isRunning())
                .map(MessageListenerContainer::getListenerId)
                .sorted()
                .toList();
        Health.Builder builder = stopped.isEmpty() ? Health.up() : Health.down();
        return builder.withDetail("containers", containers).withDetail("stopped", stopped).build();
    }
}
