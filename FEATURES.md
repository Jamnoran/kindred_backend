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

- [x] User-controlled hard filters (distance / age / looking_for / dealbreakers)
- [x] Inclusivity: optional self-identified gender + mutual "show me" filter; relationship
      styles (monogamy / ENM umbrella / open / polyamory) on profiles + preferences
- [x] Explainable scoring function (§7) with `ST_Distance_Sphere` proximity
- [x] Per-user weight tuning (persisted in `preferences.weights`)
- [x] "Why this person?" factors in the discovery response
- [x] Like / pass / superlike; mutual like → match (transactional)
- [x] "Who liked you" endpoint (free, no gating)

## Phase 3 — Chat

- [x] Spring WebSocket (STOMP) + Redis relay (STOMP live: /ws endpoint, subscribe authz by membership, message/read/typing events on /topic/conversations/{id}; Redis pub/sub relay fans events out across instances)
- [x] Conversations + messages (authz by match membership on every read/send)
- [x] Private chat-image pipeline + short-lived signed URLs (5-min expiry)
- [x] Presence / typing / read receipts

## Phase 4 — Safety & legal

- [ ] Report + block (block fully severs visibility + messaging both ways)
- [ ] Moderation queue + `moderation_events` audit log wiring
- [ ] GDPR: data export + real erasure (rows **and** image bytes)
- [ ] Rate limiting / anti-abuse (Redis)

## Phase 5 — Polish & self-host packaging

- [ ] One-command self-host docs + `.env.example` walkthrough
- [ ] Public docs on how matching works
- [ ] Optional: donation hooks / cosmetic-only perks

## Premium — one-time paid upgrade (product decision 2026-07-05)

> Deliberate departure from ARCHITECTURE.md §10 "no feature paywalls" for the
> gated features listed here; core dating features (matching, text chat,
> "who liked you") stay free. Revisit §10/README wording before launch.

- [x] `users.premium_since` + `PremiumService` entitlement layer + `GET /premium` status
- [x] Gate image messaging in chat: enabled when **either** participant is premium (402 otherwise)
- [x] Stripe Checkout integration (one-off payment + signature-verified webhook → `grant`; docs/STRIPE_SETUP.md)
- [ ] Compose smoke test of a real test-mode purchase (`stripe listen` + 4242 card)

## Notifications — offline match/message alerts

- [x] Channel abstraction (`notification/`): `NotificationChannel` beans fanned out per user prefs
- [x] Email channel (logging mailer stub — real SMTP is the same launch blocker as VerificationMailer)
- [x] Per-user preferences (type × channel grid, default on) + `GET/PUT /notification-preferences`
- [x] Async dispatch via JobRunr with at-dispatch re-checks (offline, unread, 15-min per-conversation throttle)
- [ ] Real SMTP mailer (shared with email verification)
- [ ] More channels (web push?) as product wants them

---

## Work log

- **2026-07-09** — LGBTQ+ / non-monogamy support (V8 migration, `profile/` +
  `discovery/`): the platform was gender-blind (no gender/orientation fields at
  all — nothing excluded queer users, but a gay man couldn't say "show me men").
  Added optional self-identified `profiles.gender` (`woman|man|nonbinary`, null =
  prefer not to say; deliberately no separate trans categories, and orientation is
  never stored as a label) plus `preferences.genders` ("show me", multi-select →
  bi/pan is just picking several). Gender is the one **mutually enforced** hard
  filter in DiscoveryService: both sides' filters must accept, and a set filter
  hides undeclared-gender profiles in both directions. Non-monogamy:
  `relationship_styles` JSON on profiles + preferences with fixed vocab
  (`monogamy|non_monogamy|open|polyamory`); profile writes are umbrella-normalized
  (open/poly ⇒ + non_monogamy) while preference filters stay verbatim, so
  filtering `non_monogamy` finds all ENM but `polyamory` stays specific. Filter
  semantics mirror looking_for (undeclared candidates pass); style overlap also
  feeds the mutualFit factor (now age+lookingFor+style ÷ 3). Nothing structural
  ever blocked multiple partners (unlimited concurrent matches/chats). Verified:
  full suite green (new DiscoveryServiceTest for the mutual/JSON filters,
  PreferencesServiceTest, scoring/profile test updates); OpenAPI regenerated
  (enums land in the spec for the web client). Open: migration only exercised on
  H2-less unit path — needs the usual `docker compose up` MySQL smoke; frontend
  onboarding/filters UI; README/§10-style public copy about inclusivity.
- **2026-07-09** — Offline notifications (`notification/` package): users who are
  **not online** (no live WS session per PresenceService) get notified about new
  matches and new messages. Pluggable by design: `NotificationChannel` is a Spring
  bean interface (`channelType` + `send`), `NotificationService.dispatch` fans out
  to every bean whose channel the recipient has enabled — adding push/sms later =
  one new bean + one enum entry, no dispatch changes. First channel is **email**
  via `NotificationMailer` (logging stub, same launch-blocker deal as
  VerificationMailer; emails name the other person + deep-link
  `{web-base-url}/conversations/{id}` but deliberately **never carry message
  content**). Producers: `LikeService.react` (mutual like → notify the participant
  who *didn't* just react) and `ChatService.send` (notify the recipient). Both just
  enqueue a JobRunr `SendNotificationRequest`; the worker re-checks everything at
  dispatch time — recipient still offline, message still unread, recipient not
  deleted — so a user who opens the site (or reads) in between gets no email.
  Message notifications are additionally throttled to one per
  recipient+conversation per 15 min (`NotificationThrottle`, Redis key with TTL,
  `kindred.notifications.message-throttle`; marked *after* a successful send so
  JobRunr retries aren't swallowed; `@Profile("!openapi")` + ObjectProvider like
  PresenceService). Preferences: V7 `notification_preferences` (one row per
  user/type/channel, missing row = enabled), `GET /notification-preferences`
  returns the full type×channel grid with defaults filled in, `PUT` is a full
  replace (missing combos reset to enabled, duplicate pair → 400). Verified: unit
  tests for dispatch gating (online/read/throttle/opt-out/deleted/absent-presence),
  prefs matrix + replace semantics, email copy, producers' enqueue calls, MockMvc
  slice for the endpoints (401, grid, replace, 400s); spec regenerated;
  CLIENT_INTEGRATION.md §8 added. Open: real SMTP (shared with verification
  emails), V7 only exercised against H2-less unit tests — needs the compose
  smoke, and no digest/batching beyond the 15-min throttle.
- **2026-07-05** — Stripe Checkout for the premium purchase (`premium/Stripe*`,
  stripe-java 33.1.0): `POST /premium/checkout` (409 if already premium) creates
  a one-off-payment Checkout Session — our user id rides along as
  `client_reference_id`, price/success/cancel come from `kindred.stripe.*`
  (`STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` / `STRIPE_PRICE_ID` env; empty
  defaults keep the app bootable without Stripe) — and the client just redirects
  to the returned URL, no Stripe.js. Granting happens **only** in
  `POST /api/v1/stripe/webhook` (public + CSRF-exempt in SecurityConfig;
  authenticity = `Stripe-Signature` verified against the webhook secret, bad
  signature → 400): `checkout.session.completed` / `…async_payment_succeeded`
  with `payment_status: "paid"` → `grant(client_reference_id)`; the session JSON
  is read raw (Jackson) so a dashboard API-version bump can't break parsing, and
  grant idempotency makes Stripe's retries safe. The success redirect
  deliberately grants nothing — clients poll `GET /premium` after returning.
  Refunds do NOT auto-revoke (documented decision). Verified: unit tests with
  **real signature computation** (valid/invalid/wrong-secret, unpaid ignored,
  async variant grants, missing client_reference_id logged not granted) +
  MockMvc (checkout 201/409, webhook public/CSRF-exempt, bad sig 400); spec
  regenerated; setup walkthrough in docs/STRIPE_SETUP.md (CLI forwarding, test
  cards, go-live checklist); CLIENT_INTEGRATION.md purchase flow added. Open:
  a real test-mode purchase end-to-end (needs compose + `stripe listen`).
- **2026-07-05** — Premium image messaging (`premium/` package): premium is a
  one-time upgrade recorded as `users.premium_since` (V6; NULL = free, never
  expires). Sending images in a conversation now requires that **at least one
  participant** is premium — one upgrade unlocks the chat for *both* sides.
  Enforced server-side at both entry points: `POST .../media-uploads` (presign)
  and `POST .../messages` with a `mediaStorageKey` throw `PremiumRequiredException`
  → **402** problem detail; text messages and viewing already-received images are
  never gated. `GET /conversations` gained `imageMessagingEnabled` (batch premium
  lookup, one query) so the client can hide the attach button up front, and
  `GET /premium` returns the caller's own `{premium, premiumSince}`.
  `PremiumService.grant` is idempotent and deliberately has **no public
  endpoint** — it's reserved for the payment provider's verified completion
  webhook (checkout integration is the open task above; until then, premium can
  only be set directly in the DB). Verified: unit tests for the gate (free/free
  blocked at send + presign, either-side-premium allowed, text ungated,
  conversation flag) and grant idempotency; OpenAPI spec regenerated;
  CLIENT_INTEGRATION.md §6/§9 updated.
- **2026-07-03** — Per-surface NSFW policy (§9): the scanner hook now returns a
  verdict (`CLEAN | NSFW | DISALLOWED`) instead of a boolean. Profile photos
  reject anything non-clean (unchanged behavior); chat **approves NSFW flagged** —
  V5 adds `media.is_nsfw`, exposed as `nsfw` on `ChatMediaSummary` everywhere a
  message travels (REST + `media` events). The client contract (documented as a
  hard requirement in CLIENT_INTEGRATION.md §6): `nsfw: true` → render only the
  blurhash, don't fetch signed URLs until the viewer explicitly taps. DISALLOWED
  (CSAM) is still rejected + deleted on every surface, and the stub scanner still
  returns CLEAN for everything, so nothing is flagged until a real classifier
  lands — the launch-blocker warning stands. Verified: NSFW-flagged-approved in
  chat, NSFW-rejected on profiles, DISALLOWED rejected in both (unit tests).
- **2026-07-03** — Phase 3 complete: chat images + presence. Chat images (§6B,
  `chat/ChatMedia*`): `POST /conversations/{id}/media-uploads` presigns a PUT into
  the same `quarantine/` prefix (membership-checked, conversation id recorded as
  object metadata); `POST .../messages` now takes `{body?, mediaStorageKey?}` (at
  least one; blank body counts as absent) and records a pending `media` row +
  message in one transaction, enqueuing a JobRunr job that reuses the §6A pipeline
  (magic bytes → re-encode/EXIF strip → sizes → blurhash → scan hook) but promotes
  to the **private `chat-media/` prefix** — never a public URL. Outcome is pushed
  as a `media` ChatEvent so both clients swap the blurhash placeholder live.
  Fetching bytes: `GET .../media/{mediaId}` mints **5-min presigned GET URLs**
  per size, membership-authorized on every call; pending → 409, rejected/foreign →
  404 (same as nonexistent). Storage keys are single-use across both photos and
  media tables (cross-checked both ways). V4 adds `media.blurhash`. Presence
  (`chat/Presence*`): Redis ZSET per user of live WS sessions scored by last-seen;
  connect/disconnect listeners + a 2-min scheduled sweep of this instance's
  SimpUserRegistry keep scores fresh, so a crashed instance's sessions age past
  the 5-min window and read offline with no cleanup job. Connect also touches
  `profiles.last_active_at` (feeds discovery's activity score). `otherUser.online`
  in `GET /conversations`; offline↔online transitions broadcast `presence`
  ChatEvents to all the user's conversation topics via the existing relay. Both
  ChatEvent additions are backward-compatible (new nullable fields). Verified:
  unit tests throughout (processing happy/reject/idempotent paths with real image
  bytes, signed-URL shape with the real presigner, tracker transitions) **plus
  real-Redis presence tests** (multi-session transitions, crash aging via a
  movable clock) — run against a live local Redis here, self-skipping when Redis
  is down. OpenAPI spec regenerated; CLIENT_INTEGRATION.md §6–8 updated. Still
  open: the full compose smoke test (MySQL spatial + MinIO end-to-end) and rate
  limiting on sends/likes (Phase 4). Next: Phase 4 — report + block.
- **2026-07-03** — Phase 3 Redis relay (`chat/ChatEventRelay.kt`): chat events no
  longer go straight to the local STOMP broker — `ChatService.broadcast` publishes
  the `ChatEvent` as JSON on one Redis pub/sub channel (`kindred:chat:events`), and
  a `RedisMessageListenerContainer` on every API instance (publisher included)
  rebroadcasts received events to its own in-memory broker. So a WebSocket client
  can sit on any instance and still see messages/read-receipts/typing produced on
  another. One channel for everything: per-conversation filtering already happens
  at the STOMP subscription. Malformed relay payloads are logged + dropped, never
  thrown. The relay is `@Profile("!openapi")` (the spec-export boot excludes Redis);
  `broadcast` no-ops when it's absent. Verified: relay unit tests (JSON round-trip,
  malformed drop) **plus a real-Redis integration test** — two relay instances with
  separate listener containers against a live Redis, publish on one, assert delivery
  on both; it self-skips (JUnit assumption) when localhost:6379 is down, so plain CI
  stays green. Still not client-driven end-to-end over an actual WebSocket — that
  smoke test remains open alongside the compose one. Next in Phase 3: private
  chat-image pipeline, then presence.
- **2026-07-02** — Phase 2 complete + Phase 3 REST chat (`discovery/` + `chat/`).
  Discovery: SQL hard filters (age via `TIMESTAMPDIFF`, viewer's distance limit via
  `ST_Distance_Sphere`, no repeats, blocks pre-severed both ways, deleted/unverified
  invisible) + JSON hard filters (looking_for overlap, dealbreaker interests) →
  `DiscoveryScoring` — a pure function of jaccard interests / linear distance decay /
  activity steps / mutual-fit, weighted by the user's own `preferences.weights`
  (0..5, clamped) — every card returns the full `whyThisPerson` breakdown; unknowns
  are neutral 0.5, never penalties; displayed distance respects location_visibility
  (approximate → 5 km steps). `GET/PUT /preferences`, `POST /likes`
  (like/superlike/pass; superlike signals, never boosts), mutual like → match +
  conversation in one transaction, `GET /likes/received` free. Chat: `GET
  /conversations` (other participant, last message, unread count), keyset-paginated
  messages, `POST .../messages`, `POST .../read` receipts — membership checked on
  every read/send, non-membership = 404 (no probing). Verified: scoring/matching/
  authz unit tests + full HTTP smoke on H2 (two users → mutual like → match → chat →
  read receipts → outsider 404). **The discovery SQL itself needs the MySQL smoke
  test** (H2 lacks `ST_Distance_Sphere`). STOMP added after: `/ws` handshake (session
  cookie), SUBSCRIBE to `/topic/conversations/{id}` gated by membership, send/read/
  typing broadcast as `ChatEvent`s, typing via `/app/conversations/{id}/typing` —
  in-memory simple broker only (Redis relay pending), and the WS path is
  compile/boot-verified, not driven by a client yet. Still open in Phase 3: Redis
  relay, presence, private chat images. Likes have no rate limit yet (Phase 4).
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
