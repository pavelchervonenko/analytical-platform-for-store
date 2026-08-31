# syntax=docker/dockerfile:1.7
FROM node:22.22.0-alpine3.23 AS build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --ignore-scripts

COPY frontend ./
COPY scripts/check-generated-openapi-types.mjs /workspace/scripts/check-generated-openapi-types.mjs
COPY contracts/openapi /workspace/contracts/openapi
COPY docs/schemas /workspace/docs/schemas
RUN npm run contracts:check \
    && npm run lint \
    && npm run test \
    && npm run build

FROM caddy:2.10.2-alpine
ARG SOURCE_COMMIT
LABEL org.opencontainers.image.revision="${SOURCE_COMMIT}"

COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/frontend/dist /srv

EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
