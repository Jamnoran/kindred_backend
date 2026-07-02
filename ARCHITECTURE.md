# Open Dating Platform — Architecture & Implementation Plan

A transparent, open-source dating platform. The core promise: **no hidden ranking, no
feature paywalls, auditable code.** Everything below is built to support that promise
rather than fight it.

> Stack as of this revision: **Kotlin + Spring Boot** backend, **MySQL 8** database,
> **Next.js** frontend, **two separate repositories**, **OpenAPI** as the contract between
> them. Hosting in/near the EU (Sweden → GDPR + DSA apply).

---

## 1. Product principles (turned into hard rules)

These aren't marketing — each one constrains the build.

- **No hidden algorithm.** Matching is a *transparent, explainable scoring function*,
  not a black-box ML ranker. Every card can answer "why am I seeing this person?"
- **No feature paywalls.** Likes, "who liked you", super-likes, filters, read receipts —
  all free. If money ever enters, it cannot gate core dating mechanics (see §10).
- **No dark patterns.** No artificial scarcity, no withholding likes to upsell, no
  infinite-scroll dopamine engineering. Ordering is explainable, not engagement-maximizing.
- **Privacy by default.** Strip location metadata from images, minimize stored data,
  honor deletion. This is also a legal requirement, not just ethics.
- **Self-hostable.** The whole thing runs from `docker compose up`. The software being
  free and runnable by anyone is part of the open-source value, and it's a sustainability
  path (§10).

---

## 2. High-level architecture

```
                    ┌─────────────────────────────────────────┐
                    │              Client (Web)                 │
                    │   Next.js (React, TS) — SSR + SPA-ish     │
                    │   API types generated from OpenAPI spec   │
                    └──────────────┬──────────────┬─────────────┘
                                   │ REST (OpenAPI)│ WebSocket
                                   ▼               ▼
                    ┌──────────────────────────────────────────┐
                    │        Spring Boot API (Kotlin)            │
                    │  Spring Web · Spring Security · WebSocket  │
                    │  springdoc-openapi → /v3/api-docs          │
                    └───────┬───────────┬──────────────┬─────────┘
                            │           │              │
              ┌─────────────┘           │              └───────────┐
              ▼                         ▼                          ▼
   ┌────────────────────┐   ┌──────────────────────┐   ┌────────────────────┐
   │     MySQL 8         │   │   Redis (cache,       │   │  JobRunr worker     │
   │  profiles, likes,   │   │   Spring Session,     │   │  (image pipeline +  │
   │  matches, messages  │   │   pub/sub for chat)   │   │   moderation jobs)  │
   │  + SPATIAL index    │   └──────────────────────┘   └─────────┬──────────┘
   └────────────────────┘                                         │
                                                                  ▼
                              ┌────────────────────────────────────────────┐
                              │  Object storage (S3 API)                     │
                              │  Cloudflare R2 / MinIO                        │
                              │  - public-ish: profile pics                  │
                              │  - private: chat media                       │
                              └────────────────────────────────────────────┘
```

The key split: **the app server never processes or serves images directly.** Uploads go
to object storage via pre-signed URLs; a JobRunr worker processes them off the request
path; clients fetch via CDN/signed URLs. JobRunr can run inside the API process to start
and be split into a separate worker module later.

---

## 3. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Backend language | **Kotlin (JVM)** | Concise, null-safe, first-class Spring support |
| API framework | **Spring Boot** (Spring Web MVC) | Batteries included; best-documented option for codegen |
| Auth | **Spring Security** + **Spring Session (Redis)** | Don't roll your own; session-based, email verify, CSRF handled |
| DB | **MySQL 8** + spatial index | Referential integrity for the like/match/message graph; `ST_Distance_Sphere` for geo |
| Data access | **Spring Data JPA** (Hibernate + hibernate-spatial) | Fast to build; swap hot paths to jOOQ/Exposed later if needed |
| Migrations | **Flyway** | Versioned SQL migrations, plays well with CI |
| Realtime | **Spring WebSocket** (STOMP) + Redis relay | Chat + presence; Redis pub/sub fans out across instances |
| Background jobs | **JobRunr** | JVM-native queue (backs onto MySQL or Redis), has a dashboard |
| Object storage | **Cloudflare R2** (prod) / **MinIO** (self-host & dev) | S3 API, **no egress fees** (huge for images); MinIO keeps it self-hostable |
| S3 client | **AWS SDK for Java v2** (or MinIO Java SDK) | Pre-signed URLs, bucket lifecycle |
| Image processing | **scrimage** (or ImageIO) + **metadata-extractor** | Resize, re-encode, generate sizes; re-encode strips EXIF |
| Frontend | **Next.js** (App Router, TS) | You know it; SSR; hosts the marketing site too |
| API contract | **springdoc-openapi** → **openapi-typescript / orval** | Generate a typed TS client so frontend stays in sync with Kotlin API |
| Deploy | Docker + docker-compose | One-command self-host is a feature, not an afterthought |

---

## 4. Repository structure (two repos)

Since the backend is JVM/Gradle and the frontend is pnpm/Next.js, a shared TS-types
monorepo no longer buys anything — **two repos**, with **OpenAPI as the contract** between
them.

### `open-dating-api` (Kotlin / Spring Boot / Gradle)
```
open-dating-api/
├─ src/main/kotlin/…/
│  ├─ auth/            # Spring Security config, signup, email verify, sessions
│  ├─ profile/         # profile + photo endpoints
│  ├─ discovery/       # transparent matching + scoring
│  ├─ chat/            # WebSocket (STOMP) + conversations/messages
│  ├─ media/           # pre-signed uploads, signed fetch URLs
│  ├─ moderation/      # NSFW + CSAM hooks, reports/blocks, audit log
│  └─ jobs/            # JobRunr image-processing pipeline
├─ src/main/resources/
│  └─ db/migration/    # Flyway V1__init.sql, …
├─ build.gradle.kts
├─ docker-compose.yml  # mysql, redis, minio, api
└─ openapi/            # exported spec (published for the web repo)
```

### `open-dating-web` (Next.js / pnpm)
```
open-dating-web/
├─ app/                # App Router pages
├─ lib/api/            # GENERATED from the API's OpenAPI spec (do not hand-edit)
├─ components/
├─ package.json
└─ README.md           # "pnpm generate:api" pulls the spec → regenerates the client
```

**Contract flow:** Spring exposes the spec at `/v3/api-docs`; the web repo runs a codegen
step (orval/openapi-typescript) against it to produce typed hooks/client. Commit the
generated client so builds are reproducible, regenerate when the API changes.

> Since you have a `FEATURES.md` Claude Code workflow, the roadmap (§11) is written as
> `FEATURES.md` tasks — keep one in each repo (API tasks vs. web tasks).

---

## 5. Data model sketch (MySQL)

Core tables (not exhaustive, enough to start). InnoDB, foreign keys enforced.

- **users** — id, email (unique), password_hash, email_verified, dob (18+ gate),
  created_at, deleted_at (soft delete for GDPR flows).
- **profiles** — user_id FK, display_name, bio, looking_for (JSON), `location POINT SRID 4326`
  with a **SPATIAL INDEX**, location_visibility, last_active_at.
- **photos** — id, profile_id FK, storage_key, sort_order, moderation_status
  (`pending|approved|rejected`), is_primary, blurhash.
- **interests / profile_interests** — tag taxonomy + join table (drives transparent matching).
- **preferences** — user_id FK, distance_km, age_min/max, looking_for, dealbreakers (JSON),
  plus the user's own matching weights if you let people tune ranking (§7).
- **likes** — from_user FK, to_user FK, kind (`like|superlike|pass`), created_at,
  unique(from_user,to_user). "Who liked you" is just a query — **no paywall**.
- **matches** — user_a FK, user_b FK (store ordered to dedupe), created_at. Created in a
  transaction when a like becomes mutual.
- **conversations / messages** — message has nullable media_id FK; only match participants
  can read.
- **media** — chat images: storage_key, owner FK, conversation_id FK, moderation_status,
  expires_at. Access-controlled, never public.
- **reports / blocks** — safety primitives, day-one not phase-five. A block must sever
  visibility + messaging both ways.
- **moderation_events** — audit log of automated + manual actions.

> Geo queries use `ST_Distance_Sphere(location, :point)` against the SPATIAL index; the
> flexible bits (interests, dealbreakers, looking_for) live in JSON columns so the schema
> stays stable as profiles evolve.

---

## 6. Image handling (done properly on the JVM)

Two very different classes of image with different rules.

### A) Profile photos (semi-public)
**Upload flow:**
1. Client requests a **pre-signed upload URL** from the API (AWS SDK for Java) so bytes go
   straight to object storage, not through Spring.
2. Client uploads directly to R2/MinIO into a `quarantine/` prefix.
3. API enqueues a **JobRunr** processing job.
4. Worker:
   - **Validates by magic bytes**, not file extension (reject polyglots/disguised files).
   - **Re-encodes** with scrimage/ImageIO (WebP/AVIF + JPEG fallback). Re-encoding alone
     neutralizes most malicious payloads and normalizes format.
   - **Strips EXIF**, especially GPS (re-encode drops it; verify with metadata-extractor).
     *Non-negotiable* — people unknowingly leak their home location in photo metadata.
   - Generates **responsive sizes** (thumb / card / full) + a **blurhash** placeholder.
   - Runs **moderation** (§9): NSFW classification + CSAM scan.
   - On pass → moves to `profiles/` prefix, sets `moderation_status=approved`.
5. Served via CDN. Use random, non-enumerable storage keys (not user IDs).

### B) Chat images (private, sensitive)
Stricter — this is where intimate content lives.
- Same validate → re-encode → EXIF strip → moderate pipeline.
- Stored in a **private bucket/prefix**; **served only via short-lived signed URLs**
  (e.g. 5-min expiry) and only to the two participants. Authorize on every fetch.
- Random, unguessable keys. No directory listing.
- Optional **"view once" / expiry** as a product feature (you can delete bytes, but can't
  prevent screenshots — say so honestly in the UI).

### The E2E-encryption tension (decided)
True E2E media conflicts with server-side CSAM/NSFW scanning, which is legally/ethically
required. **MVP decision: TLS in transit + encryption at rest + server-side scanning.**
Document it transparently to users; revisit client-side scanning only much later.

### Storage/cost notes
- R2 has **no egress fees** — matters a lot when serving lots of images.
- Keep originals only as long as needed; serve derived sizes.
- Separate prefixes for `quarantine`, `profiles`, `chat-media` with different lifecycle +
  access policies.

---

## 7. Transparent matching (the heart of the product)

An **explainable scoring function** instead of an opaque ML ranker:

```
score(viewer, candidate) =
    w_interests * shared_interest_ratio
  + w_distance  * proximity_score(ST_Distance_Sphere(...))
  + w_active    * recency_score(last_active_at)
  + w_mutualfit * mutual_filter_fit(viewer.prefs, candidate, candidate.prefs, viewer)
  − dealbreaker_penalty
```

Design rules:
- **Hard filters are user-controlled and visible** (distance, age, looking_for,
  dealbreakers). Candidates outside filters never appear.
- **"Why this person?"** panel shows the actual contributing factors ("3 shared interests ·
  4 km away · active today · matches what you're both looking for").
- **Let users tune their own weights** (sliders). Their ranking, their rules.
- **No engagement manipulation:** no reordering for swipe-maximization, no boosted profiles.
- **"Who liked you" is free** and shown plainly.

Honest tradeoff: full transparency can be *gamed*. Be open about *how the system works*
(publish the formula — it's open source anyway) without exposing other users' raw private
scores. Transparency about mechanism ≠ leaking others' data.

---

## 8. Realtime chat

- **Spring WebSocket (STOMP)**; **Redis** as the relay/pub-sub so messages fan out across
  multiple API instances later.
- Messages persisted in MySQL; media via the §6B private pipeline.
- Presence / typing / read receipts — all free, surfaced honestly.
- Rate-limit message + image sends (anti-spam/abuse) via Redis.
- Authorize every message and media fetch against match membership.

---

## 9. Safety, moderation & legal (do NOT defer this)

Where dating platforms get into real trouble. Phase-one, not polish.

- **Age gate (18+).** Collect DOB, enforce. Consider stronger verification later.
- **CSAM scanning is mandatory.** Use a known pipeline:
  - **Cloudflare CSAM Scanning Tool** (free) or **PhotoDNA** (Microsoft, free for qualifying
    platforms) or **Thorn Safer**.
  - In the US, confirmed CSAM must be reported to **NCMEC**. As an EU operator you have your
    own reporting/preservation duties — get this right before launch, ideally with legal input.
- **NSFW classification** for profile photos. Decide policy per surface: a Feeld-adjacent app
  may *allow* more in chat than in public profiles — encode that as per-surface rules.
- **Reporting + blocking** as first-class features from day one. A block fully severs
  visibility and messaging both ways.
- **GDPR (you're in the EU):** lawful basis + consent, **data minimization**, **right to
  erasure** (real deletion of profile + image bytes, not soft-hide), data export, clear
  privacy policy. The EXIF stripping in §6 is part of this.
- **DSA (EU Digital Services Act):** expect notice-and-action, transparency, trusted-flagger
  handling depending on size. Keep a checklist even if small.
- **Anti-abuse:** rate limits, email verification, optional phone verification, manual review
  path for flagged content.

> Not legal advice — treat §9 as engineering requirements plus a flag to get proper legal
> review before public launch, especially on CSAM reporting and GDPR.

---

## 10. Sustainability without paywalls

"Free for everyone" still has to pay for storage and servers (image serving is the
expensive part). Honest options that *don't* gate dating features:

- **Donations / Open Collective / GitHub Sponsors.**
- **Self-host model:** software is free; people/communities run their own instances — the
  open-source-native answer, aligned with the docker-compose setup.
- **Optional cosmetic-only perks** that never affect matching or messaging (tread carefully).
- **Grants** (digital-rights / privacy-focused funders).

Decide direction early-ish: it affects whether you optimize for one hosted instance vs. many
small self-hosted ones.

---

## 11. Implementation roadmap (FEATURES.md-ready)

Phased so each step is shippable. Split tasks across the two repos' `FEATURES.md` files.

**Phase 0 — Foundations**
- [ ] `open-dating-api`: Spring Boot + Kotlin Gradle scaffold, Spring Security, Spring Session (Redis)
- [ ] `open-dating-web`: Next.js scaffold + OpenAPI codegen step (orval/openapi-typescript)
- [ ] docker-compose: mysql 8, redis, minio
- [ ] Flyway baseline migration (V1__init.sql)
- [ ] Auth: signup, email verification, login, sessions, 18+ gate
- [ ] springdoc-openapi exposed; web client generates against it
- [ ] CI in both repos (Gradle build/test; pnpm lint/typecheck/build) via GitHub Actions

**Phase 1 — Profiles + image pipeline**
- [ ] Profile CRUD (bio, looking_for, interests, location POINT + spatial index)
- [ ] Pre-signed upload → quarantine → JobRunr worker (validate, re-encode, EXIF strip, resize, blurhash)
- [ ] NSFW + CSAM scanning hooks (stub providers, real ones before launch)
- [ ] Serve profile photos via CDN with non-enumerable keys

**Phase 2 — Discovery + transparent matching**
- [ ] User-controlled filters (distance/age/looking_for/dealbreakers)
- [ ] Explainable scoring function (ST_Distance_Sphere proximity) + per-user weight tuning
- [ ] "Why this person?" panel
- [ ] Like / pass / superlike; mutual → match (transactional)
- [ ] "Who liked you" (free)

**Phase 3 — Chat**
- [ ] Spring WebSocket (STOMP) + Redis relay
- [ ] Conversations + messages (authz by match membership)
- [ ] Private chat-image pipeline + short-lived signed URLs
- [ ] Presence / typing / read receipts

**Phase 4 — Safety & legal**
- [ ] Report + block (full bidirectional sever)
- [ ] Moderation queue + audit log
- [ ] GDPR: data export + real erasure (incl. image bytes)
- [ ] Rate limiting / anti-abuse

**Phase 5 — Polish & self-host packaging**
- [ ] One-command self-host docs + `.env.example`
- [ ] Public docs on how matching works (surface the transparency feature)
- [ ] Accessibility + responsive pass
- [ ] Optional: cosmetic perks / donation hooks

---

## 12. Settled decisions & remaining minor choices

**Settled:** Kotlin + Spring Boot · MySQL 8 · two repos · OpenAPI contract · R2/MinIO ·
Redis · JobRunr · session-based auth.

**Still open (low stakes, can decide as you go):**
1. **Data access:** start with Spring Data JPA everywhere, or reach for jOOQ/Exposed on the
   matching query? (JPA is fine to start; revisit if the discovery query gets gnarly.)
2. **WebSocket:** STOMP (structured, browser libs exist) vs. raw WebSocket (leaner). STOMP
   recommended.
3. **Distribution model (§10):** self-host-first vs. one hosted instance — affects later
   choices more than initial build.
