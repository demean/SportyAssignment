package com.sporty.jackpot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the Logback events of one class's logger for the service-area unit tests. The logger is forced to
 * {@code DEBUG} while capturing (independent of whatever logging configuration an earlier Spring context in the same
 * JVM installed) and restored on {@link #close()}.
 *
 * <pre>{@code
 * try (ServiceLogCapture logs = ServiceLogCapture.of(BetProcessingService.class)) {
 *     service.process(bet);
 *     assertThat(logs.messages(Level.WARN)).singleElement().asString().contains("payload differs");
 * }
 * }</pre>
 */
public final class ServiceLogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private ServiceLogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * Starts capturing the logger named after {@code type}.
     *
     * @param type class whose logger is captured
     * @return the running capture (close it)
     */
    public static ServiceLogCapture of(Class<?> type) {
        return new ServiceLogCapture(type);
    }

    /**
     * @param level log level
     * @return the formatted messages logged at exactly {@code level}, in order
     */
    public List<String> messages(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel().equals(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
