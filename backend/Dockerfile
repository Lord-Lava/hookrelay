# Build with the repository-pinned Gradle Wrapper and Java 25 LTS.
FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
RUN groupadd --system gradle && useradd --system --create-home --gid gradle gradle && mkdir /workspace && chown gradle:gradle /workspace
WORKDIR /workspace
USER gradle:gradle

COPY --chown=gradle:gradle gradlew ./gradlew
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon --version | grep -F "Gradle 9.9.1"
RUN ./gradlew --no-daemon dependencies

COPY --chown=gradle:gradle src ./src
RUN ./gradlew --no-daemon clean check bootJar

# Minimal pinned Temurin JRE runtime; no compiler or Gradle in the final image.
FROM eclipse-temurin:25.0.5_8-jre-noble
RUN groupadd --system app && useradd --system --uid 10001 --create-home --gid app appuser
WORKDIR /app
COPY --from=build /workspace/build/libs/hookrelay-api-0.1.0-SNAPSHOT.jar app.jar
USER 10001:app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
