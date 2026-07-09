# Kindred API — Client Integration Guide

Drop this file into the client repo. It explains how to implement a client against
the Kindred backend as it exists today (Phases 0–3: auth, profiles, photos,
discovery/matching, chat + realtime).

Two sources of truth:

1. **`openapi/kindred-api.json`** (in the backend repo, regenerated on every build)
   — the REST contract. Generate your typed client/models from it; this guide does
   not duplicate every field.
2. **This document** — everything the OpenAPI spec *cannot* express: session and
   CSRF mechanics, multi-step flows (photo upload, matching), and the STOMP
   WebSocket protocol for realtime chat.

Base URL: `http://localhost:8080` in dev (`docker compose up` in the backend repo).
All REST paths below are relative to `/api/v1`.

---

## 1. Sessions, cookies, CSRF — read this first

The API is **session-based, not token-based**. There are no JWTs and no
`Authorization` header.

- On login the server sets a `SESSION` cookie (HttpOnly, `SameSite=Lax`,
  7-day timeout). Send it on every request: `fetch(..., { credentials: "include" })`,
  or automatic cookie handling on mobile.
- Because the cookie is `SameSite=Lax`, serve the web app and the API on the
  **same site** in production (e.g. `app.example.com` + `api.example.com`, or a
  reverse-proxy path). A completely cross-site frontend will not get the cookie back.
- **CSRF (double-submit cookie):** the server sets a JS-readable `XSRF-TOKEN`
  cookie. Echo its value back in the **`X-XSRF-TOKEN`** header on every mutating
  request (`POST`/`PUT`/`DELETE`). `GET`s don't need it. Missing/wrong header → 403.

```js
// Bootstrapping: any GET (e.g. the public /api/v1/meta) makes the server set XSRF-TOKEN.
await fetch(`${API}/api/v1/meta`, { credentials: "include" });

function csrfToken() {
  return document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)?.[1] ?? "";
}

async function api(path, { method = "GET", body } = {}) {
  const res = await fetch(`${API}/api/v1${path}`, {
    method,
    credentials: "include",
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(method !== "GET" ? { "X-XSRF-TOKEN": decodeURIComponent(csrfToken()) } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw await res.json(); // RFC 7807 problem detail — see §10
  return res.status === 204 ? null : res.json();
}
```

## 2. Auth flow

| Step | Call | Notes |
|---|---|---|
| Sign up | `POST /auth/signup` `{email, password, dob}` | `dob` is `YYYY-MM-DD`; under-18 → 400. Password 8–72 chars. |
| Verify | `POST /auth/verify-email` `{token}` | Token arrives by email as a link to the web app — your verify page extracts `token` and posts it. One-shot, expiring. |
| Resend | `POST /auth/resend-verification` `{email}` | Always 204 (no account enumeration). |
| Log in | `POST /auth/login` `{email, password}` | Sets the `SESSION` cookie. 401 = bad credentials; **403 = correct password but email not verified** → show "check your inbox". |
| Who am I | `GET /auth/me` | Returns `{id, email, emailVerified}`; 401 = not logged in. Use it on app boot to restore the session. |
| Log out | `POST /auth/logout` | 204, clears the session. CSRF header required. |

Everything except signup/login/verify/resend/`/meta` returns **401** without a
session — treat any 401 as "redirect to login".

## 3. Onboarding: profile, interests, location

- `GET /interests` — the fixed interest taxonomy `[{slug, label}]`. Send slugs;
  unknown slugs → 400.
- `PUT /profile` — **full replace** (send the whole profile every time):
  `{displayName, bio, lookingFor: ["relationship" | ...], interests: [slug...]}`.
  `GET /profile` reads it back.
- `PUT /profile/location` — `{lat, lng, visibility}` where visibility is
  `hidden | approximate | precise`. `approximate` rounds displayed distances to
  5 km steps for others; `hidden` excludes the user from nearby/distance features.
- `GET /profiles/nearby?radiusKm=25` — nearby browse (distances rounded to whole
  km, max 100 results). Discovery (§5) is the main feed; this is auxiliary.

## 4. Profile photos (async pipeline — poll for approval)

Photos are **not** uploaded to the API. The flow is presign → direct upload →
register → poll:

1. `POST /media/profile-photo-uploads` `{contentType}` (jpeg/png/webp only, else
   415) → `{uploadUrl, storageKey, expiresAt}`.
2. `PUT` the raw bytes to `uploadUrl` (expires in 10 min) with the same
   `Content-Type` header. This goes to object storage, not the API — no cookies/CSRF.
3. `POST /photos` `{storageKey}` → a photo row in `pending` state. Max 6 photos.
4. `GET /photos` and poll (a few seconds apart) until `status` is `approved`
   (or `rejected` — bad bytes/failed scan; tell the user to try another).

Approved photos have `urls: {thumb, card, full}` (256/800/1600 px CDN URLs) and a
`blurhash`. **Render the blurhash as the placeholder** while images load and for
pending photos. `DELETE /photos/{id}` removes one (re-sorts, re-picks primary).

## 5. Discovery, preferences, likes, matches

- `GET /discovery?limit=20` → `DiscoveryCard[]`:
  `{userId, displayName, age, bio, lookingFor, interests, photo, distanceKm, score, whyThisPerson}`.
  `whyThisPerson` is the transparent per-factor score breakdown (shared interests,
  proximity, recency, mutual fit + the user's own weights). **Show it** — it's a
  core product principle, not debug data. `distanceKm` is null when either side
  hides location.
- `GET /preferences` / `PUT /preferences` — hard filters
  (`distanceKm`, `ageMin`/`ageMax`, `lookingFor`, `dealbreakers`) and the scoring
  `weights` map (values 0–5). The PUT is a full replace with server defaults for
  omitted fields, so read-modify-write.
- `POST /likes` `{toUserId, kind}` with kind `like | superlike | pass` →
  `{matched, matchId, conversationId}`. When `matched` is true, **the conversation
  already exists** — go straight to chat with `conversationId` ("It's a match!"
  screen). Reacting removes that card from future decks; superlike is a visible
  signal to the other person, not a ranking boost.
- `GET /likes/received` — who liked you (free, ungated): `[{userId, displayName, kind, likedAt, photo}]`.

## 6. Chat — REST

- `GET /conversations` → newest-activity-first list:
  `{id, matchId, matchedAt, imageMessagingEnabled, otherUser: {userId, displayName, photo, online}, lastMessage, unreadCount}`.
  `otherUser.online` is live presence (see §7 for the realtime updates).
  `imageMessagingEnabled` is the premium image gate — see below.
- `GET /conversations/{id}/messages?limit=50[&before={messageId}]` — **newest
  first, keyset paginated**: first call without `before`, then pass the smallest
  `id` you have to load older history. `limit` 1–100.
- `POST /conversations/{id}/messages` `{body?, mediaStorageKey?}` → 201 with the
  created message. At least one of the two is required (neither → 400). Body:
  ≤2000 chars; blank counts as absent. The sender's own send also arrives
  as a WebSocket event — de-duplicate by message `id`.
- `POST /conversations/{id}/read` → `{markedRead: n}`. Call it when the user
  actually views the conversation; it marks all of the *other* participant's
  messages as read.
- Any conversation you're not a member of is a **404** (indistinguishable from
  nonexistent — by design). Treat 404 here as "conversation gone".

### Chat images (private — signed URLs only)

**Premium gate:** sending images in a conversation requires that **at least one
of the two participants** has the one-time premium upgrade — then **both** can
send. Per conversation, read `imageMessagingEnabled` from `GET /conversations`:
when `false`, hide/disable the attach-image affordance and offer the upgrade
instead. The server enforces it too: the upload presign and any message with a
`mediaStorageKey` return **402** in a free/free chat. Text messages are never
gated, and viewing already-sent images is never gated.

Buying premium (Stripe Checkout — backend setup in `docs/STRIPE_SETUP.md`):

1. `GET /premium` → `{premium, premiumSince}` — the caller's own status. Hide
   the buy button when already `premium: true`.
2. `POST /premium/checkout` (no body) → 201 `{checkoutUrl}`; **409** if already
   premium. Redirect the browser to `checkoutUrl` (Stripe-hosted page — no
   Stripe.js needed).
3. Stripe sends the user back to the configured success/cancel URL in the web
   app. The upgrade is granted **asynchronously by webhook**, usually within a
   couple of seconds — on the success page, poll `GET /premium` until
   `premium: true`, then refetch `GET /conversations` so `imageMessagingEnabled`
   flips. Never treat merely landing on the success URL as proof of payment.

Same three-step shape as profile photos, but scoped to a conversation and never
served from a public URL:

1. `POST /conversations/{id}/media-uploads` `{contentType}` (jpeg/png/webp) →
   `{uploadUrl, storageKey, expiresAt}`. `PUT` the raw bytes to `uploadUrl` with
   that same `Content-Type` within 10 minutes.
2. `POST /conversations/{id}/messages` `{mediaStorageKey: storageKey}` (with or
   without a `body`). The message's `media` field starts as
   `{id, status: "pending", nsfw: false, blurhash: null}` — render a placeholder.
   When the server has validated/re-encoded/scanned the image you get a `media`
   WebSocket event (below) with `status: "approved"` (+ `nsfw` + blurhash) or
   `"rejected"`; a rejected image should be shown as removed. Each `storageKey`
   is single-use.
3. To display an approved image: `GET /conversations/{id}/media/{mediaId}` →
   `{mediaId, urls: {thumb, card, full}, expiresAt}`. **These are signed URLs that
   expire after 5 minutes** — fetch them when the image scrolls into view, don't
   persist them, and refetch after `expiresAt` (an expired URL returns 403 from
   storage). While still processing the endpoint returns **409**; rejected or
   foreign media is a **404**.
- Message objects everywhere (`lastMessage`, the messages page, `message` events)
  carry `media: {id, status, nsfw, blurhash} | null` — the bytes always go through
  the signed-URL endpoint above, per participant, authorized on every fetch.

#### NSFW chat images — blur until tapped (client REQUIREMENT)

Adult-but-legal images are **allowed in chat** but come back with `nsfw: true`
(per-surface policy — the same image would be rejected as a profile photo). The
client is the enforcement point for the reveal, so this is a requirement, not a
suggestion:

- When `media.nsfw` is true, render **only the blurhash placeholder** with a
  "tap to reveal" affordance. Do **not** call the signed-URL endpoint — and
  therefore never load the actual bytes — until the viewer explicitly taps.
  (The blurhash is a 32×32 gradient, so nothing recognizable leaks.)
- The reveal is per-viewer and should not be sticky across messages: each
  `nsfw` image gets its own tap. Remembering the choice per *conversation* is an
  acceptable UX refinement; auto-revealing globally is not.
- Apply the same rule to the conversation list: if `lastMessage.media.nsfw` is
  true, show a generic "Photo" label/placeholder there, never a thumbnail.
- `nsfw` can be true the moment the `media` event arrives — check it before
  swapping the placeholder for the real image on that event, too.
- Note: until a real classifier replaces the backend's stub scanner, `nsfw` is
  always false in practice. Build the UI against the field now so it lights up
  when the real provider lands (backend `ImageContentScanner` — §9).

## 7. Chat — realtime (STOMP over WebSocket)

This part is not in the OpenAPI spec. The endpoint is **`/ws`** (so
`ws://localhost:8080/ws`, `wss://…/ws` in prod) — plain WebSocket with STOMP,
**no SockJS**. Use `@stomp/stompjs` on web.

- **Auth:** the handshake is authenticated by the `SESSION` cookie the browser
  attaches automatically (same-site requirement from §1 applies). No CSRF token
  and no headers needed on the socket. Unauthenticated handshakes fail.
- **Subscribe:** `/topic/conversations/{id}` — only for conversations returned by
  `GET /conversations`. Subscribing to someone else's conversation is rejected:
  the server sends a STOMP ERROR frame and **closes the connection**, so never
  guess ids.
- **Events** on that topic (one JSON `ChatEvent` per frame; fields a type doesn't
  use are null on the wire — omitted below for brevity):

```jsonc
{ "type": "message",  "conversationId": 7, "message": { "id": 101, "senderId": 2, "body": "hey", "media": null, "createdAt": "2026-07-03T07:00:00Z", "readAt": null } }
{ "type": "read",     "conversationId": 7, "readerId": 2 }                                        // user 2 read your messages
{ "type": "typing",   "conversationId": 7, "typingUserId": 2 }                                    // user 2 is typing
{ "type": "media",    "conversationId": 7, "media": { "id": 30, "status": "approved", "nsfw": false, "blurhash": "LKO2…" } } // image 30 finished processing (or "rejected"); nsfw:true → blur until tapped (§6)
{ "type": "presence", "conversationId": 7, "presenceUserId": 2, "online": true }                  // user 2 came online (or false: went offline)
```

- **Presence:** `online` in `GET /conversations` is the initial state; `presence`
  events keep it live while you're subscribed. A user is "online" while they have
  a WebSocket connected (sessions on a dead instance age out within ~5 minutes,
  so a dropped connection may read online briefly). Presence is free and honest —
  there is no "appear offline" tier.

- **Typing:** publish an empty frame to `/app/conversations/{id}/typing` while the
  user types. Throttle to ~1 frame per 3 s, and expire the "is typing…" indicator
  client-side after ~5 s of silence — the server sends no "stopped typing" event.
- Events are produced for *both* participants, including your own actions
  (your sends and your read receipts echo back) — filter by
  `senderId`/`readerId`/`typingUserId` !== own user id where needed.

```js
import { Client } from "@stomp/stompjs";

const stomp = new Client({
  brokerURL: `${API.replace(/^http/, "ws")}/ws`,
  reconnectDelay: 3000, // built-in auto-reconnect
});
stomp.onConnect = () => {
  for (const convo of openConversations) {
    stomp.subscribe(`/topic/conversations/${convo.id}`, (frame) => {
      const event = JSON.parse(frame.body);
      // event.type: "message" | "read" | "typing"
    });
  }
};
stomp.activate();

// typing signal (throttled by you):
stomp.publish({ destination: `/app/conversations/${id}/typing` });
```

### Delivery semantics — important

The relay is **fire-and-forget with no replay**: events are only delivered to
sockets connected at that moment. Whenever you (re)connect, (re)subscribe, or
return from background, **re-sync over REST** (`GET /conversations` for unread
counts + `GET .../messages` for anything newer than your last seen id), then rely
on the socket for updates. Never treat the socket as the source of truth for
history. Sending messages goes through REST (§6), not the socket.

## 8. Offline notifications & preferences

When a user is **not connected over WebSocket** (per presence, §7), the backend
notifies them out-of-band about new matches and new messages — email today, more
channels (push, …) later. This is fully server-side; clients only need the
preferences UI:

- `GET /api/v1/notification-preferences` → `{ preferences: [{ type, channel,
  enabled }] }` — always the **complete type × channel grid** with defaults
  filled in. Types: `new_match`, `new_message`; channels: `email` (more will
  appear — render whatever the grid contains rather than hardcoding).
- `PUT /api/v1/notification-preferences` with the same shape — **full replace**:
  any type/channel pair missing from the body resets to its default (enabled).
  Repeating a pair → 400. Returns the updated grid.

Semantics worth knowing (for support/UX copy): everything defaults to **on**;
message notifications are skipped if the recipient reads the conversation first
and are throttled to at most one per conversation per 15 minutes; emails name
the sender but **never include message content**. Notification emails deep-link
to `{web-base-url}/conversations/{conversationId}` — the web app must route
that path.

## 9. Not implemented yet (don't build against these)

Report/block, rate limiting, and GDPR export are pending backend phases
(Phase 4+). The `type` field on `ChatEvent` is open-ended — **ignore unknown
event types** instead of erroring, so new event kinds can ship without breaking
older clients.

## 10. Errors

Errors are RFC 7807 problem details (`application/problem+json`):

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "detail": "..." }
```

Handle globally: 401 → login screen; **402 → premium required** (image
messaging in a free/free chat — show the upgrade prompt, §6); 403 on login →
unverified email; 403 elsewhere → missing CSRF header (§1); 404 on
conversations → treat as gone; 400 → show `detail`.
