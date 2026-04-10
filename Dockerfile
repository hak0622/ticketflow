# Stage 1: Build
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN chmod +x ./gradlew && ./gradlew bootJar -x test

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/blog-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]