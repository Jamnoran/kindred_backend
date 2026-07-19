# kindred_backend

Kotlin / Spring Boot API for **Kindred** — an open, transparent dating platform.
No hidden ranking, no feature paywalls, auditable code.

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — full architecture & implementation plan
- **[FEATURES.md](FEATURES.md)** — working task list (phased roadmap for this repo)

The web frontend lives in [kindred_web](https://github.com/Jamnoran/kindred_web)
(React + TypeScript + Vite) and consumes this API via its OpenAPI spec
(`/v3/api-docs`).

## Stack

Kotlin · Spring Boot (Web, Security, Session/Redis, Data JPA) · MySQL 8 (spatial) ·
Flyway · Redis · MinIO/R2 (S3 API) · springdoc-openapi · Docker Compose.

## Run it locally (backend + web)

### 1. Backend

```sh
# everything (MySQL, Redis, MinIO, API):
docker compose up --build

# or just the infrastructure, with the API on the host (needs JDK 21):
docker compose up mysql redis minio
./gradlew bootRun
```

- API: http://localhost:8080 — instance info at `/api/v1/meta`
- Swagger UI: http://localhost:8080/swagger-ui.html
- MinIO console: http://localhost:9001

### 2. Web frontend

With the backend up, clone [kindred_web](https://github.com/Jamnoran/kindred_web)
and start the dev server (needs Node 20+):

```sh
git clone https://github.com/Jamnoran/kindred_web
cd kindred_web
npm install
npm run dev
```

Open http://localhost:5173. The Vite dev server proxies `/api` and `/ws` to
`http://localhost:8080`, keeping the `SESSION` cookie same-origin. If the
backend runs elsewhere: `VITE_BACKEND_URL=http://host:port npm run dev`.

## Develop

```sh
./gradlew build   # compile + tests
./gradlew test
```

Database schema is owned by Flyway (`src/main/resources/db/migration`);
Hibernate only validates it.

## Source layout

Feature-oriented packages under `com.kindred.api` (ARCHITECTURE.md §4):
`auth` · `profile` · `discovery` · `chat` · `media` · `moderation` · `jobs` —
plus `config` (security, OpenAPI) and `meta`. Packages appear as their phase
in FEATURES.md is implemented.
