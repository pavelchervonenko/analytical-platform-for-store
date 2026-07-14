FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts ./
COPY backend ./backend

RUN gradle :backend:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
