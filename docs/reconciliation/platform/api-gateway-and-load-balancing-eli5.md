# API Gateway & Load Balancing — Explain Like I'm 5

> A plain-language tour of the "front door" we built for Darpan's **public** API tier (the curated
> read-only GraphQL), and what is / isn't done for spreading traffic across servers. For the precise,
> grown-up version see [api-access-policy.md](api-access-policy.md) and
> [api-contract-testing.md](api-contract-testing.md). Real component names are in the appendix.

## The big picture (a library)

Imagine Darpan's data is a **library**. Lots of companies (tenants) keep their books (reconciliation
results) on their own shelves in the same building. Other companies' computer systems sometimes want to
come and **read** their own books.

We do **not** let strangers wander the shelves. They talk to a **front desk**. That front desk is the
**API gateway**. It checks who you are, what you're allowed to read, makes sure nobody is being greedy,
and writes down what happened.

**Load balancing** is a separate idea: having enough **desk clerks** (server copies) and a **traffic
cop** out front so visitors get served quickly and one rude visitor can't jam the whole desk.

---

## Part 1 — The API Gateway (the front desk). ✅ Built.

Everything below is built, reviewed, and tested. It lives in the `moqui-gql` engine we forked for Darpan.

### 1. A public door, separate from the staff door 🚪
Our own app (the Darpan web app) uses the **staff entrance**. Outside companies use a **different public
door**. Your staff badge does **not** open the public door, and a public key does **not** open the staff
door. Two separate doors so a stolen staff badge can't be used on the public API.

### 2. A library card, not a borrowed badge 💳
Each outside company gets **its own library card** (an API key). We check the card at the door. We only
keep a **fingerprint** of the card (a hash), never the card itself — so even we can't read it back. If a
company misbehaves, we cancel **their** card and nobody else is affected.

### 3. Your card only opens your own shelf 🔒
Every card is stamped with **one company**. With that card you can only ever read **your** company's
books — never another company's. This is enforced by the building, not by politely asking. No stamp =
you get **nothing** (we call this "fail-closed": when in doubt, show zero).

### 4. A menu, not the whole kitchen 📋
Visitors order from a **fixed menu** (the curated schema). They can't invent new requests, can't change
or delete anything (it's **read-only**), and can't reach anything that isn't printed on the menu.

### 5. No single request can be too greedy 🍽️
Before we run a request, we **price** it. If it's too big, too deep, or too expensive, we politely say
"no" before touching the database. And if a request somehow runs too long, we **cut it off**. One
request can't take down the kitchen.

### 6. Everyone gets their own bucket of tokens 🪣
Each card has its **own bucket of points** that slowly refills. Each request spends some points. Run out
→ "please slow down" (this is **rate limiting / throttling**). The important part: **your bucket is
yours.** A greedy visitor draining *their* bucket does **not** slow *you* down.
*(This is the exact bug we fixed in step 4 — before, all public visitors shared one bucket, so one
greedy company could throttle everyone. Now each card has its own.)*

### 7. A logbook and a watchdog 📓🐕
The desk writes down **every** request. A **watchdog** checks the logbook on a timer. If one card
suddenly does way too much — too many requests, too many errors, or too expensive — it **raises a flag**
so a human can slow that card down or cancel it.

---

## Part 2 — Load Balancing (enough clerks + a traffic cop)

Here's the honest part: we have built the things that make load balancing **possible and safe**, but we
have **not** stood up the actual traffic cop yet — because the public tier isn't switched on for the
outside world yet (that's a later, deliberately-deferred step).

### ✅ What's done (the groundwork)

- **An "are you open?" sign** 🟢 — each server copy answers a quick health question (`/status`, checked
  every 30 seconds). A traffic cop (a load balancer or Kubernetes) uses this to send visitors **only to
  clerks who are awake and ready**, and to stop sending to a sick one.
- **Polite shift changes** 🔄 — when a clerk has to go home (a server is being replaced or upgraded), it
  **finishes its current visitor first** instead of slamming the door. So traffic can be moved between
  servers **without dropping anyone's request**.
- **Per-visitor fairness** ⚖️ — because each card has its own token bucket (see #6), one heavy visitor
  can't hog all the clerks. Load is naturally fairer.
- **Clerks are interchangeable** 👥 — public reads are read-only and short (one quick look-up), and a
  visitor is identified by their **card on every request**, not by "which clerk they talked to last."
  That means you can run **many identical clerks** and send a visitor to any of them — no "you must
  always return to the same clerk" stickiness needed.

### ⏳ What's NOT done yet (honest)

- **The actual traffic cop** — we have not yet stood up a real load balancer with multiple server copies
  behind it. That's deployment/infrastructure, not application code.
- **Auto-hiring more clerks** (autoscaling), **multiple buildings** (multi-region), and a **neighborhood
  pickup point** (CDN / edge caching) for the public API.
- These all belong to the **"open the public door to the world"** step, which is **deferred on purpose**
  — the public tier is built and internally tested, but not exposed yet.

---

## Part 3 — One-glance summary

| Front-desk job (ELI5) | Gateway feature | Status |
| --- | --- | --- |
| Public door ≠ staff door | Separate public endpoint; rejects staff (session) tokens | ✅ done |
| Library card | Per-client API key (hashed) | ✅ done |
| Card opens only your shelf | Fail-closed tenant isolation | ✅ done |
| Order off the menu only | Curated, read-only schema | ✅ done |
| No greedy request | Cost governor + time limit | ✅ done |
| Your own token bucket | Per-caller rate limit (per key) | ✅ done |
| Logbook + watchdog | Query log + anomaly alerting | ✅ done |
| "Are you open?" sign | Health/readiness probe (`/status`) | ✅ done |
| Polite shift change | Graceful shutdown (PID-1) | ✅ done |
| Fair sharing | Per-key fairness + stateless reads | ✅ done |
| The traffic cop itself | Real load balancer + multiple copies | ⏳ deferred (with public exposure) |
| Auto-hire clerks / CDN / regions | Autoscaling, edge cache, multi-region | ⏳ deferred |

**In one sentence:** the *front desk* (API gateway) is fully built and guarded; the *traffic-cop-out-front*
(load balancing across many copies) is intentionally left for when we actually open the public door.

---

## Appendix — the same thing, for grown-ups

| ELI5 | Real component | Where |
| --- | --- | --- |
| Public door | `POST /rest/s1/graphql/public` → `gql.QueryServices.execute#PublicQuery` | `moqui-gql` `service/gql.rest.xml`, `QueryServices.xml` |
| Staff door (internal) | `POST /rpc/json` (PWA), `login_key`/cookie session | darpan `webroot` rpc mount |
| Library card + fingerprint | `GqlApiKey` (SHA-256 `keyHash`), `create#ApiKey` / `resolve#ApiKey` | `moqui-gql` `GqlAuthServices.xml`, `ApiKeyHasher.groovy` |
| Card opens only your shelf | `DarpanTenantScopeFilter` (fixed-tenant, fail-closed) | `moqui-gql` `scope/DarpanTenantScopeFilter.groovy` |
| The menu | Curated GraphQL schema (read-only; no mutations) | `moqui-gql` `graphql/DarpanSchema.gql.xml` |
| Request pricing + time cut-off | `GovernorInstrumentation` (depth/cost/first + wall-clock budget) | `moqui-gql` `GovernorInstrumentation.groovy` |
| Your own token bucket | `ThrottleGate` keyed by API-key id (fixed in step 4) | `moqui-gql` `policy/ThrottleGate.groovy`, `GqlEngine.groovy` |
| Logbook | `GqlQueryLog` | `moqui-gql` `entity/GqlEntities.xml` |
| Watchdog | `scan#QueryAnomalies` + `GqlAnomalyAlert`, scheduled `GqlAnomalyScanJob` (~15 min) | `moqui-gql` `GqlMonitorServices.xml` |
| "Are you open?" sign | `HEALTHCHECK curl http://127.0.0.1:8080/status` (every 30s) | `darpan-docker-config` `Dockerfile`, `prod/Dockerfile` |
| Polite shift change | `exec` JVM as PID 1 → receives `SIGTERM` from `docker stop` for graceful drain | `darpan-docker-config` `entrypoint.sh` |
| Staff-door origin lock | `webapp_allow_origins` (first-party only: `*.drpn.ai`, `*.hotwax.io`, Firebase) | `darpan-docker-config` `MoquiProductionConf.xml` |

**Why "load balancing" is mostly groundwork here:** the application's job is to be *load-balancer-ready*
— health-checkable, gracefully drainable, stateless per request, and fair per caller. Putting an actual
load balancer in front of N copies is an infrastructure/deploy decision that lands when the public tier
is exposed (deferred). Everything the app needs to *cooperate* with a load balancer is already in place.
