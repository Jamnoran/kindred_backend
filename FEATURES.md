# FEATURES.md — Kindred backend work file

Working task list for this repo (the API side of [ARCHITECTURE.md](ARCHITECTURE.md)).
The web repo keeps its own `FEATURES.md` for frontend tasks.

**How to use this file:** pick the topmost unchecked task in the lowest unfinished
phase, implement it, check it off, and add a dated note under *Work log*. Tasks map
1:1 to the roadmap in ARCHITECTURE.md §11 (backend items only).

Legend: `[ ]` todo · `[x]` done · `[~]` in progress / partially done

---

## Phase 0 — Foundations

- [x] Spring Boot + Kotlin Gradle scaffold (web, security, JPA, validation, actuator)
- [x] docker-compose: mysql 8, redis, minio, api
- [x] Flyway baseline migration (`V1__init.sql` — full §5 schema incl. spatial index)
- [x] springdoc-openapi exposed at `/v3/api-docs` (+ Swagger UI)
- [x] Security shell: session-based (Spring Session/Redis), public docs + health, everything else 401
- [x] Auth: signup (18+ gate), email verification, login/logout, session hardening
- [x] Export OpenAPI spec to `openapi/` as part of the build (contract for the web repo)
- [x] CI: GitHub Actions — Gradle build + test

## Phase 1 — Profiles + image pipeline

- [x] Profile CRUD (bio, looking_for, interests, location POINT + spatial queries)
- [x] Pre-signed upload → `quarantine/` prefix (AWS SDK v2 against R2/MinIO)
- [x] JobRunr worker: magic-byte validation, re-encode (scrimage), EXIF strip, responsive sizes, blurhash
- [x] NSFW + CSAM scanning hooks (stub providers; real ones before launch — see §9)
- [x] Serve profile photos via CDN with non-enumerable keys

## Phase 2 — Discovery + transparent matching

- [ ] User-controlled hard filters (distance / age / looking_for / dealbreakers)
- [ ] Explainable scoring function (§7) with `ST_Distance_Sphere` proximity
- [ ] Per-user weight tuning (persisted in `preferences.weights`)
- [ ] "Why this person?" factors in the discovery response
- [ ] Like / pass / superlike; mutual like → match (transactional)
- [ ] "Who liked you" endpoint (free, no gating)

## Phase 3 — Chat

- [ ] Spring WebSocket (STOMP) + Redis relay
- [ ] Conversations + messages (authz by match membership on every read/send)
- [ ] Private chat-image pipeline + short-lived signed URLs (5-min expiry)
- [ ] Presence / typing / read receipts

## Phase 4 — Safety & legal

- [ ] Report + block (block fully severs visibility + messaging both ways)
- [ ] Moderation queue + `moderation_events` audit log wiring
- [ ] GDPR: data export + real erasure (rows **and** image bytes)
- [ ] Rate limiting / anti-abuse (Redis)

## Phase 5 — Polish & self-host packaging

- [ ] One-command self-host docs + `.env.example` walkthrough
- [ ] Public docs on how matching works
- [ ] Optional: donation hooks / cosmetic-only perks

---

## Work log

- **2026-07-02** — Phase 1 image pipeline (`photo/` + `media/` additions): JobRunr
  worker (in-process, MySQL-backed via the main DataSource; JobRequest pattern,
  idempotent on retry). `POST /photos {storageKey}` records a pending row and
  enqueues §6A processing: magic-byte validation (never extension) → scrimage
  re-encode to JPEG (drops all metadata — EXIF strip proven by test with a spliced
  EXIF segment) → bounded thumb/256, card/800, full/1600 sizes → blurhash (own
  ~90-line encoder, no dep) → §9 scan hook (`ImageContentScanner`; stub allows all
  and logs a launch-blocker warning — replace with PhotoDNA/Thorn/NSFW classifier)
  → promote to `profiles/<random-hex>/{thumb,card,full}.jpg`, delete quarantine,
  approve. `GET /photos` (CDN URLs from `kindred.media.public-base-url` only when
  approved, blurhash for placeholders), `DELETE /photos/{id}` (re-primaries/re-sorts,
  best-effort object deletion). Max 6 photos. Verified: pipeline unit-tested with
  real images; HTTP smoke on H2 (submit/list/delete + all validation paths).
  **Not verified end-to-end with MinIO/MySQL** — the compose smoke test below now
  also covers upload → worker → approved URL. Self-host note: the MinIO bucket
  needs creating + a public-read policy on `profiles/` (Phase 5 packaging).
- **2026-07-02** — Phase 1 pre-signed uploads (`media/` package): AWS SDK v2
  `S3Presigner` (path-style for MinIO, endpoint/creds from `kindred.s3.*` /
  `S3_*` env, already wired in docker-compose). `POST /media/profile-photo-uploads`
  returns a 10-min presigned PUT into `quarantine/` with a random 16-byte hex key
  (never derived from user id; uploader recorded as object metadata server-side).
  Content type allowlist: jpeg/png/webp → else 415. Presigning is offline, so the
  service tests exercise the real presigner. No photos row is created yet — that
  happens when the JobRunr worker (next task) promotes quarantine → `profiles/`.
- **2026-07-02** — Phase 1 profile CRUD (`profile/` package): own-profile
  GET/PUT (PUT = full replace) with bio, looking_for (JSON column via Hibernate
  `@JdbcTypeCode(JSON)`), interests (V3 seeds a ~36-slug taxonomy; unknown slugs → 400;
  `GET /interests` lists it), and location: `PUT /profile/location` writes the POINT
  via native `ST_SRID(POINT(lng, lat), 4326)` (no hibernate-spatial needed) with
  per-profile visibility, `GET /profiles/nearby?radiusKm=` runs `ST_Distance_Sphere`
  against the caller's stored location (hidden profiles excluded, distances rounded to
  whole km to resist trilateration, capped at 100 results). Verified over HTTP on H2
  except the two native spatial queries — **those need the MySQL smoke test** noted
  below. Nearby does not yet apply blocks (Phase 4) or preference filters (Phase 2).
- **2026-07-02** — Phase 0 finished: full auth slice (`auth/` package) — signup with
  18+ gate + email normalization, email verification via one-shot expiring tokens
  (`V2__email_verification_tokens.sql`, logging mailer stub until SMTP), JSON
  login/logout with session-fixation protection, `GET /auth/me`. Session hardening:
  HttpOnly/SameSite=Lax/Secure-flag cookies, 7d timeout, double-submit-cookie CSRF
  (SPA pattern from the Spring Security docs). Problem-detail error responses.
  Unverified accounts get 403 only *after* the password checks out (no enumeration);
  login is blocked until verified. OpenAPI spec now exported to
  `openapi/kindred-api.json` via `./gradlew generateOpenApiDocs` (boots on H2 with the
  `openapi` profile, no infra needed); CI (GitHub Actions) builds, tests, and fails on
  spec drift. Verified end-to-end over HTTP on H2 (signup → verify → login → me →
  logout, CSRF included); **not yet run against real MySQL/Redis** — do a
  `docker compose up` smoke test (JPA schema validation, Spring Session) before
  building on top. Real SMTP mailer + signup rate limiting still TODO (Phase 4).
- **2026-07-02** — Repo bootstrapped. Added ARCHITECTURE.md; scaffolded Gradle/Kotlin
  Spring Boot project (`com.kindred.api`), Flyway `V1__init.sql` with the full §5
  schema (users, profiles + SPATIAL index, photos, interests, preferences, likes,
  matches, conversations, messages, media, reports, blocks, moderation_events),
  docker-compose with MySQL 8 / Redis / MinIO / api, springdoc, session-based
  security shell, `/api/v1/meta` endpoint + test. `gradlew build` green.
  Next up: auth (signup / email verify / login).
