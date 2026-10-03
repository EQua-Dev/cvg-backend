# cvg-backend: Kotlin + Spring Boot on Java 21. Used by Railway and docker compose.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Download dependencies first so code changes don't redo it.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 cvg
COPY --from=build /src/build/libs/cvg-backend-*.jar app.jar
USER cvg
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    TZ=Africa/Lagos
EXPOSE 8080
# PORT is set by Railway; defaults to 8080 (see application.yml).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
