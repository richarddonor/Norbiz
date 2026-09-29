# Stage 1: Build
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

RUN groupadd --system appgroup && useradd --system --gid appgroup --home-dir /home/appuser --create-home appuser

COPY --from=builder /build/target/*.war app.war

RUN chown appuser:appgroup app.war && \
    mkdir -p /home/appuser/norbiz && \
    chown -R appuser:appgroup /home/appuser
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.war"]
