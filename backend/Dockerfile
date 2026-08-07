FROM eclipse-temurin:21-jdk-alpine-3.23 AS build
WORKDIR /workspace

RUN apk add --no-cache bash curl git jq python3

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY .dockerignore docker-compose.prod.yml ./
COPY scripts ./scripts
COPY docs ./docs
COPY backend ./backend

RUN --mount=type=cache,target=/root/.gradle ./gradlew :backend:bootJar --no-daemon

FROM build AS test
RUN --mount=type=cache,target=/root/.gradle ./gradlew :backend:test -x operatorScriptSecurityTest --no-daemon --console=plain

FROM eclipse-temurin:21-jre-alpine-3.23
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
