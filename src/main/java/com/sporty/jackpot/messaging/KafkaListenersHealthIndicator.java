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
 * running, UP otherwise. It is part of the liveness group: a container that stopped itself (a fatal listener error,
 * an authentication failure) leaves the instance accepting bets it never consumes, and only a restart heals that.
 * While the registry itself is not running (before startup, during a graceful shutdown) no container is expected to
 * run, so the indicator stays UP and a shutdown never looks like a failure.
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
        List<String> stopped = registry.isRunning() ? stoppedContainers(listenerContainers) : List.of();
        Health.Builder builder = stopped.isEmpty() ? Health.up() : Health.down();
        return builder.withDetail("containers", containers).withDetail("stopped", stopped).build();
    }

    private static List<String> stoppedContainers(Collection<MessageListenerContainer> listenerContainers) {
        return listenerContainers.stream()
                .filter(container -> container.isAutoStartup() && !container.isRunning())
                .map(MessageListenerContainer::getListenerId)
                .sorted()
                .toList();
    }
}
