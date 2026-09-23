package com.sporty.jackpot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@DisplayName("JackpotServiceApplication")
class JackpotServiceApplicationTest {

    @Test
    @DisplayName("main delegates to SpringApplication.run with the application class and the arguments")
    void mainRunsTheSpringApplication() {
        String[] args = {"--spring.profiles.active=postgres", "--server.port=0"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            JackpotServiceApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(JackpotServiceApplication.class, args));
            springApplication.verifyNoMoreInteractions();
        }
    }

    @Test
    @DisplayName("is a Spring Boot application that scans @ConfigurationProperties records")
    void isABootApplicationScanningConfigurationProperties() {
        assertThat(JackpotServiceApplication.class)
                .hasAnnotation(SpringBootApplication.class)
                .hasAnnotation(ConfigurationPropertiesScan.class);
    }

    @Test
    @DisplayName("has a public no-arg constructor, so Spring can instantiate (and CGLIB-proxy) the configuration class")
    void instantiableConfigurationClass() throws ReflectiveOperationException {
        Constructor<JackpotServiceApplication> constructor = JackpotServiceApplication.class.getDeclaredConstructor();

        assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
        assertThat(constructor.newInstance()).isInstanceOf(JackpotServiceApplication.class);
    }
}
