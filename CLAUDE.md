# CLAUDE.md — working notes for this repo

Kindred backend: an open, transparent dating-platform API. Kotlin + Spring Boot 3,
Gradle (Kotlin DSL), MySQL 8 (Flyway), Redis (sessions + chat relay + presence),
MinIO/S3 (images), JobRunr (async image pipeline). The web frontend lives in a
separate repo (`kindred_web`) and codegens its client from `openapi/kindred-api.json`.

## Build & test — read this first

- **The gradle wrapper (`./gradlew`) fails in Claude Code remote sessions**: the
  distribution download redirects to github.com/gradle releases, which the egress
  proxy blocks with 403. Use the pre-installed **`/opt/gradle/bin/gradle`** instead
  (8.14.3, same as the wrapper pin). Java 21 is on PATH.
- Run tests: `/opt/gradle/bin/gradle test`; full build: `... gradle build`.
- **CI fails on OpenAPI spec drift.** After any controller/DTO change, regenerate
  with `/opt/gradle/bin/gradle generateOpenApiDocs` (boots the app on H2 with the
  `openapi` Spring profile — no infra needed) and commit `openapi/kindred-api.json`.
- Tests need no infrastructure: unit tests + `@WebMvcTest` slices + HTTP smoke on H2.
  The real-Redis tests (`*RedisTest`, presence) self-skip via JUnit assumptions when
  localhost:6379 is down — "skipped" there is normal, not a failure.
- H2 can't run the MySQL-specific migrations (POINT SRID etc.), so tests don't run
  Flyway; new migrations only get exercised against real MySQL (`docker compose up`).

## Layout & conventions

- Packages under `src/main/kotlin/com/kindred/api/`: `auth`, `profile`, `media`
  (presigned uploads, image processing), `photo` (profile photos), `discovery`
  (scoring/likes/matches), `chat`, `premium`, `common`, `config`, `meta`.
  One package per domain; DTOs + domain exceptions live in `<Domain>Dtos.kt`.
- Errors: throw a domain exception, map it in `common/ApiExceptionHandler.kt` to an
  RFC 9457 problem detail. Existing status choices: 404 for non-membership (never
  reveal existence), 409 for conflicts/pending, 402 for premium-gated, 415 bad image type.
- Auth is **session-based** (Spring Session in Redis), not JWT. CSRF is
  double-submit cookie (`XSRF-TOKEN` → `X-XSRF-TOKEN` header). Principal is
  `KindredUserDetails` (id, email, emailVerified) — created at login, so anything
  that can change mid-session must be read from the DB, not the principal.
- Flyway migrations in `src/main/resources/db/migration/` (`V7__...` is next).
  MySQL 8 dialect, InnoDB, `TINYINT(1)` booleans, FKs enforced.
- Time comes from the injected `Clock` bean (`config/TimeConfig`); tests use
  `Clock.fixed`. Mockito-kotlin + kotlin.test assertions; controller tests are
  `@WebMvcTest` + `@Import(SecurityConfig::class)` + `@MockitoBean`.
- `ChatService.requireMembership(userId, conversationId)` is the chat authz
  primitive — call it on every read/send; it returns the `Match` (participants =
  `userA`/`userB`). Non-membership throws `ConversationNotFoundException` (404).
- Optional beans (`ChatEventRelay`, `PresenceService`) are injected as
  `ObjectProvider` because the `openapi` profile and slice tests boot without Redis.

## Process / docs contract

- **FEATURES.md is the working task file**: check off tasks and add a dated entry
  under *Work log* describing what shipped, how it was verified, and what's still
  open. Follow the existing entries' style (dense, honest about gaps).
- **docs/CLIENT_INTEGRATION.md** is the hand-written client contract (everything
  the OpenAPI spec can't express: CSRF, multi-step flows, STOMP protocol, gating
  rules). Update it whenever client-visible behavior changes.
- ARCHITECTURE.md is the design doc (numbered §s referenced throughout the code).
  Note: it promises "no feature paywalls" — the premium feature below is a
  deliberate 2026-07-05 product decision that departs from §10; README/§10 wording
  is still to be reconciled before launch.

## Domain facts worth knowing

- Images are never uploaded to the API: presigned PUT to a `quarantine/` prefix →
  register the storage key → JobRunr worker validates (magic bytes), re-encodes
  (EXIF strip), makes thumb/card/full + blurhash, runs the NSFW/CSAM scan hook
  (stub until launch), then promotes. Profile photos go to a public CDN prefix;
  chat media to a **private** `chat-media/` prefix served only via 5-minute
  presigned GETs, membership-checked per fetch. Storage keys are single-use across
  both `photos` and `media` tables.
- Chat realtime is STOMP over `/ws` with a Redis pub/sub relay so events reach
  clients on any instance; sends go through REST, the socket is notification-only.
- NSFW policy is per-surface: rejected on profiles, allowed-but-flagged in chat
  (`nsfw: true` → client blurs until tap). DISALLOWED (CSAM) rejected everywhere.
- **Premium** (`premium/`): one-time upgrade, `users.premium_since` (NULL = free).
  Image messaging in a conversation requires ≥1 premium participant (then both can
  send); enforced at the media-upload presign and at send (`mediaStorageKey`) →
  402. `GET /conversations` exposes `imageMessagingEnabled`; `GET /premium` gives
  own status. Text chat and viewing received images are never gated.
- **Stripe** (`premium/Stripe*`, setup: docs/STRIPE_SETUP.md): `POST
  /premium/checkout` → Checkout Session (redirect; user id in
  `client_reference_id`); premium is granted **only** by the signature-verified
  `POST /api/v1/stripe/webhook` (public + CSRF-exempt in SecurityConfig, never by
  the success redirect). Config `kindred.stripe.*` from `STRIPE_*` env vars —
  empty defaults keep boots/tests working without Stripe. `grant()` is
  idempotent (webhook retries safe); refunds don't auto-revoke. Webhook tests
  compute real signatures via `Webhook.Util.computeHmacSha256`.
- Matches are stored ordered (`user_a < user_b`, DB CHECK). "Who liked you" is
  free by design. Discovery scoring is transparent and user-weighted (§7).
