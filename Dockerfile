# Stage 1: Build
FROM gradle:8-jdk17 AS builder
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar -x test

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
# Railway 무료 티어 메모리 제한(512MB) 기준으로 JVM 힙 400MB 설정
ENTRYPOINT ["java", "-Xmx400m", "-Xms128m", "-jar", "app.jar"]
