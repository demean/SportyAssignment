package com.sporty.jackpot.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

@DisplayName("KafkaListenersHealthIndicator")
class KafkaListenersHealthIndicatorTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final KafkaListenersHealthIndicator indicator = new KafkaListenersHealthIndicator(registry);

    @Test
    @DisplayName("UP when every listener container is running; container ids reported sorted")
    void upWhenAllRunning() {
        givenContainers(container("listener-b", true, true), container("listener-a", true, true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("containers", List.of("listener-a", "listener-b"))
                .containsEntry("stopped", List.of());
    }

    @Test
    @DisplayName("DOWN when a container that should run is stopped; the stopped ids are in the details")
    void downWhenOneStopped() {
        givenContainers(container("listener-c", true, false), container("listener-a", true, true),
                container("listener-b", true, false));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("containers", List.of("listener-a", "listener-b", "listener-c"))
                .containsEntry("stopped", List.of("listener-b", "listener-c"));
    }

    @Test
    @DisplayName("UP when a stopped container has auto-startup disabled (it is not expected to run)")
    void upWhenStoppedContainerHasAutoStartupDisabled() {
        givenContainers(container("manual-listener", false, false), container("listener-a", true, true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("containers", List.of("listener-a", "manual-listener"))
                .containsEntry("stopped", List.of());
    }

    @Test
    @DisplayName("UP with empty details when no listener container is registered")
    void upWithoutContainers() {
        givenContainers();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("containers", List.of())
                .containsEntry("stopped", List.of());
    }

    private void givenContainers(MessageListenerContainer... containers) {
        when(registry.isRunning()).thenReturn(true);
        when(registry.getListenerContainers()).thenReturn(Arrays.asList(containers));
    }

    @Test
    @DisplayName("UP while the registry itself is not running (before startup, during a graceful shutdown)")
    void upWhileTheRegistryIsStopped() {
        MessageListenerContainer stoppedForShutdown = mock(MessageListenerContainer.class);
        when(stoppedForShutdown.getListenerId()).thenReturn("listener-a");
        when(registry.getListenerContainers()).thenReturn(List.of(stoppedForShutdown));
        when(registry.isRunning()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("containers", List.of("listener-a"))
                .containsEntry("stopped", List.of());
    }

    private static MessageListenerContainer container(String id, boolean autoStartup, boolean running) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getListenerId()).thenReturn(id);
        when(container.isAutoStartup()).thenReturn(autoStartup);
        when(container.isRunning()).thenReturn(running);
        return container;
    }
}
