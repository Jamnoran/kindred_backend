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
- [ ] Auth: signup (18+ gate), email verification, login/logout, session hardening
- [ ] Export OpenAPI spec to `openapi/` as part of the build (contract for the web repo)
- [ ] CI: GitHub Actions — Gradle build + test

## Phase 1 — Profiles + image pipeline

- [ ] Profile CRUD (bio, looking_for, interests, location POINT + spatial queries)
- [ ] Pre-signed upload → `quarantine/` prefix (AWS SDK v2 against R2/MinIO)
- [ ] JobRunr worker: magic-byte validation, re-encode (scrimage), EXIF strip, responsive sizes, blurhash
- [ ] NSFW + CSAM scanning hooks (stub providers; real ones before launch — see §9)
- [ ] Serve profile photos via CDN with non-enumerable keys

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

- **2026-07-02** — Repo bootstrapped. Added ARCHITECTURE.md; scaffolded Gradle/Kotlin
  Spring Boot project (`com.kindred.api`), Flyway `V1__init.sql` with the full §5
  schema (users, profiles + SPATIAL index, photos, interests, preferences, likes,
  matches, conversations, messages, media, reports, blocks, moderation_events),
  docker-compose with MySQL 8 / Redis / MinIO / api, springdoc, session-based
  security shell, `/api/v1/meta` endpoint + test. `gradlew build` green.
  Next up: auth (signup / email verify / login).
