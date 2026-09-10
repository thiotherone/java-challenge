# Build stage: resolve dependencies in their own layer so that editing sources
# does not re-download the world on every rebuild.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && cp target/*.jar application.jar

# Runtime stage: a JRE only, and an unprivileged user, so a compromise inside
# the container does not start as root.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S -G spring spring
COPY --from=build --chown=spring:spring /build/application.jar application.jar
USER spring

EXPOSE 8080

# Uses a real endpoint rather than pulling in Actuator: an unknown type is a
# valid request that answers 200 with an empty array.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8080/transactions/types/healthcheck || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
