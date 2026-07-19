# Store Analytics

Closed analytics cabinet for store managers. The backend stores external data from LiveSklad in PostgreSQL first, then serves calculated KPI, employee analytics, salary reports, sync history, and audit data through protected APIs.

## Stack

- Java 21
- Spring Boot 3
- Gradle Kotlin DSL
- PostgreSQL
- Flyway
- Docker Compose

## Repository Layout

```text
backend/                 Spring Boot backend
frontend/                Reserved for the future React/Vite cabinet
docs/                    Architecture and operational notes
docker/                  Docker and deployment helper files
```

## Local Development

1. Copy environment variables:

   ```bash
   cp .env.example .env
   ```

2. Start PostgreSQL:

   ```bash
   docker compose -f docker-compose.dev.yml up -d postgres
   ```

3. Run the backend with Java 21 and Gradle:

   ```bash
   cd backend
   gradle bootRun
   ```

The backend uses the `dev` profile by default. Swagger UI is available at `/swagger-ui.html` after the app starts.
