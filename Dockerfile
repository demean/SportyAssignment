# Multi-stage build of the Jackpot Service.
# Works with the legacy builder (DOCKER_BUILDKIT=0): no BuildKit-only syntax (no RUN --mount, no # syntax=).

# ---- build: compile, package and extract the layered Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# dependencies first (own layer, reused while pom.xml is unchanged)
COPY pom.xml ./
RUN mvn -B -q -DskipTests -Djacoco.skip=true dependency:go-offline

COPY src/ src/
RUN mvn -B -q -DskipTests -Djacoco.skip=true package \
    && java -Djarmode=tools -jar target/jackpot-service-1.0.0.jar extract --layers --launcher --destination target/extracted

# ---- runtime: JRE only, non-root, one layer per Boot layer (dependencies change rarely) ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 jackpot \
    && useradd --system --uid 10001 --gid jackpot --no-create-home --shell /usr/sbin/nologin jackpot
WORKDIR /app

COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER 10001:10001

# Actuator on a dedicated management port in the container (whatever the active profile).
ENV MANAGEMENT_SERVER_PORT=8081
EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS "http://localhost:${MANAGEMENT_SERVER_PORT}/actuator/health/readiness" > /dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
