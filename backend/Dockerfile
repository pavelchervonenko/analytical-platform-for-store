FROM eclipse-temurin:21-jdk-alpine-3.23 AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY docs ./docs
COPY backend ./backend

RUN --mount=type=cache,target=/root/.gradle ./gradlew :backend:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine-3.23
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
