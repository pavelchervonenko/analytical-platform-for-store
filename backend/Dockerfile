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
ARG SOURCE_COMMIT
LABEL org.opencontainers.image.revision="${SOURCE_COMMIT}"

WORKDIR /app

RUN apk add --no-cache su-exec \
    && addgroup -S app \
    && adduser -S app -G app
COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar
COPY backend/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "-jar", "/app/app.jar"]
