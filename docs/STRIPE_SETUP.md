# Stripe setup — premium one-time purchase

How to wire Stripe Checkout to the premium upgrade (`users.premium_since`).
The code side already exists (`premium/Stripe*`); this is the account/config
walkthrough plus how the flow works end to end.

## How the flow works

```
client                        backend                              stripe
  |  POST /api/v1/premium/checkout                                    |
  |----------------------------->|  create Checkout Session           |
  |                              |----------------------------------->|
  |   { checkoutUrl }            |<-- session (url, our userId as     |
  |<-----------------------------|    client_reference_id) -----------|
  |  browser redirect to checkoutUrl ---------------------------------|
  |                              |            user pays on Stripe     |
  |                              |  POST /api/v1/stripe/webhook       |
  |                              |<-- checkout.session.completed -----|
  |                              |  verify Stripe-Signature,          |
  |                              |  payment_status == "paid"          |
  |                              |  → PremiumService.grant(userId)    |
  |  browser lands on STRIPE_SUCCESS_URL                              |
  |  poll GET /api/v1/premium until premium: true                     |
```

Key property: **premium is granted only by the signature-verified webhook** —
never by the success redirect, which anyone can open without paying. The grant
is idempotent, so Stripe's webhook retries are harmless.

## 1. Stripe account & product

1. Create an account at <https://dashboard.stripe.com> (business details can
   wait; test mode works immediately).
2. **Product catalog → Add product**: e.g. "Kindred Premium", description
   "One-time upgrade — unlocks image messaging in your chats".
3. Add a **one-off** price (NOT recurring) in your currency, e.g. €9.99.
4. Copy the price id (`price_…`) → `STRIPE_PRICE_ID`.

## 2. API keys

Dashboard → **Developers → API keys**:

- **Secret key** (`sk_test_…` in test mode, `sk_live_…` live) → `STRIPE_SECRET_KEY`.
  Server-side only — never ship it to the web/mobile client. The publishable key
  (`pk_…`) is not needed: we use Stripe-hosted Checkout via redirect, no Stripe.js.

## 3. Webhook endpoint

Dashboard → **Developers → Webhooks → Add endpoint**:

- URL: `https://<your-api-domain>/api/v1/stripe/webhook`
- Events: `checkout.session.completed` and `checkout.session.async_payment_succeeded`
  (the latter matters only if you enable delayed methods like bank debits;
  harmless to subscribe either way)
- Copy the signing secret (`whsec_…`) → `STRIPE_WEBHOOK_SECRET`.

The endpoint is public and CSRF-exempt by design; authenticity comes from the
`Stripe-Signature` header, which the backend verifies with this secret. A wrong
secret makes every webhook a 400 — premium then never gets granted, so check
this first when a paid checkout doesn't unlock.

## 4. Environment variables

| Variable | Example | Notes |
|---|---|---|
| `STRIPE_SECRET_KEY` | `sk_test_…` | required for checkout |
| `STRIPE_WEBHOOK_SECRET` | `whsec_…` | required for the webhook |
| `STRIPE_PRICE_ID` | `price_…` | the one-off price from step 1 |
| `STRIPE_SUCCESS_URL` | `https://app.example.com/premium/success` | default `http://localhost:3000/premium/success` |
| `STRIPE_CANCEL_URL` | `https://app.example.com/premium/cancelled` | default `http://localhost:3000/premium/cancelled` |

All have empty/localhost defaults (`application.yml`, `kindred.stripe.*`) so the
app boots without Stripe — image gating still enforces, but
`POST /premium/checkout` fails until the keys are set. docker-compose passes
them through to the api container; put them in your `.env`.

## 5. Local development & testing

Install the [Stripe CLI](https://docs.stripe.com/stripe-cli), then:

```bash
stripe login
# forward webhooks to the local API; prints a whsec_… — use IT as
# STRIPE_WEBHOOK_SECRET while this is running (it differs from the dashboard one)
stripe listen --forward-to localhost:8080/api/v1/stripe/webhook
```

Test a full purchase in test mode: log in to the app, `POST /premium/checkout`,
open the returned URL, pay with card `4242 4242 4242 4242` (any future expiry,
any CVC), then `GET /premium` should show `premium: true` and image messaging
unlocks in that user's chats.

Useful shortcuts:

```bash
# fire a synthetic event at the local endpoint (no real checkout);
# note: no client_reference_id, so it logs an error instead of granting — good for wiring checks
stripe trigger checkout.session.completed

# resend a real event from the dashboard: Developers → Webhooks → the event → Resend
```

Declines/edge cases: card `4000 0000 0000 0002` (declined),
`4000 0025 0000 3155` (requires 3DS). Full list: <https://docs.stripe.com/testing>.

## 6. Going live checklist

- [ ] Swap `sk_test_`/test `whsec_` for live-mode key + live webhook endpoint secret
- [ ] Success/cancel URLs point at the production web app (HTTPS)
- [ ] Webhook URL is reachable from the internet (not behind auth/VPN)
- [ ] Activate the account (business + bank details) in the Stripe dashboard
- [ ] Decide refund policy; a refund does NOT auto-revoke premium — if you want
      that, handle `charge.refunded` in `StripeCheckoutService` (deliberately
      not implemented: premium is sold as a permanent one-time unlock)
- [ ] Consider Stripe Tax if you must collect VAT/sales tax

## Operational notes

- Already-premium users get **409** from `POST /premium/checkout` — the client
  should hide the buy button when `GET /premium` says `premium: true`.
- Stripe retries failed webhook deliveries for ~3 days with backoff; grant
  idempotency makes duplicate deliveries safe.
- If a webhook was missed entirely (endpoint down past the retry window), the
  event is still in the dashboard → Resend, or grant manually:
  `UPDATE users SET premium_since = NOW() WHERE id = …`.
- Checkout Sessions expire after 24h if unpaid; the user just starts a new one.
