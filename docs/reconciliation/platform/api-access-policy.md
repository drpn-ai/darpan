# API Access Policy — Public vs Internal

> Status: **v1 / proposal.** This document defines the policy and a first-pass classification.
> The *transport* for a public tier and the final public service list need sign-off before any
> endpoint is exposed. **No endpoint is made public by this document.**

## Purpose

Define which Darpan service endpoints are intended for external / 3rd-party consumption versus
internal first-party (PWA) use, the authentication tier for each, and the transport. Today
Darpan has **no public API**; this is the contract for introducing one safely and for keeping
the internal surface internal.

## Principle: two distinct tiers

| | Internal API | Public API |
| --- | --- | --- |
| Audience | The Darpan PWA (first-party SPA) | 3rd-party / customer integration systems |
| Transport | JSON-RPC facade (`/rpc/json`) | Curated, versioned surface (transport TBD — see below) |
| Auth | `login_key` header / HttpOnly cookie session + CSRF | Non-interactive credential (API key or OAuth client-credentials) |
| Origins | CORS locked to first-party (`*.drpn.ai`, Firebase SPA, `*.hotwax.io`) | Server-to-server; no browser-origin assumption; per-client rate limits |
| Surface | All ~57 `allow-remote` facade services | An explicit, narrow allowlist only |
| Docs | This repo's technical docs | Public docs site (`darpan-docs`), versioned |

**Default rule:** every service is **internal** unless explicitly promoted to the public
allowlist after review. Exposure is never implied by transport, `allow-remote`, or being in the
public docs site.

## Current posture (ground truth, 2026-06)

- The JSON-RPC facade is **SPA-private**: `require-authentication="false"` at the `/rpc/json`
  mount (`base-component/webroot/screen/webroot/rpc.xml:20`) but each service authenticates;
  only the 3 auth-bootstrap services are anonymous. Auth is the SPA-shaped `login_key` token
  (`AuthFacadeServices.xml:30`). There is **no API-key / OAuth / signed-request mechanism** for
  non-interactive clients.
- **CORS is locked to first-party origins** (`docker/MoquiProductionConf.xml:3`) — a browser
  3rd party is blocked at preflight.
- **Partner integrations are outbound** (Darpan pulls from NetSuite/Shopify/HotWax/SFTP); no
  partner calls *into* Darpan. There is no inbound public surface today.

Conclusion: there is no public API to govern yet. This policy precedes and gates building one.

## Classification (first pass)

Eligibility criteria for **public**: tenant-scoped; read-only or controlled-trigger; returns no
credentials/secrets; not app-global / super-admin config; rate-limitable; stable shape.

### Public-eligible (read + controlled trigger of reconciliation)
- Read: `list#SavedRuns`, `run#SavedRunDiff`, `run#GenericDiff`, `list#GeneratedOutputs`,
  `get#GeneratedOutput`, `get#GeneratedOutputDifferences`, `list#AutomationExecutions`.
- Trigger (gated, tenant-scoped, rate-limited): `create#RuleSetRun`, `create#CsvRun`,
  `run#AutomationNow`.
- *Rationale:* these are the only operations a customer/partner system would legitimately want
  — fetch my reconciliation results / run status, or kick off a run.

### Internal-only (do not expose)
- Auth / session lifecycle: `login#Session`, `get#SessionInfo`, `logout#*`, `save#ActiveTenant`,
  `save#UserSettings`, `verify/change#OwnPassword`.
- Tenant & platform settings: `get/save#TenantSettings`, `get/save#TenantNotificationSettings`,
  `get/save#LlmSettings`, `list#EnumOptions`.
- Schema authoring: `save#JsonSchemaText`, `save#RefinedSchema`, `infer/flatten#JsonSchema`,
  `delete#JsonSchema`.
- Operational setup: `save#Mapping`, `delete#SavedRun`, `save#DashboardPinned*`,
  `save/delete/pause/resume#Automation`.
- UI helpers: `search#NavigationTargets`.

### Never expose (hard rules)
- Any service handling partner credentials — `*NsAuthConfig`, `*NsRestletConfig`, `*SftpServer`
  (they return/accept encrypted `password`/`privateKey`).
- App-global / super-admin config — `save#LlmSettings`, `list#EnumOptions`.
- The scheduler services in `ReconciliationAutomationServices.xml` (`run#Automation`,
  `run#SftpFileAutomation`, `execute#Automation`, `scan#DueAutomations`,
  `sweep#StuckReconciliationRuns`): they are `authenticate="anonymous-all"` **and**
  `allow-remote="false"` — internal scheduler only, **not** remotely reachable today. They must
  **stay `allow-remote="false"`**; never front them with any public (or internal-remote) route.

## Transport — decided: curated GraphQL via `drpn-ai/moqui-gql` (Phase-0 proven)

Decision (2026-06-30): the public read tier is a **curated, read-only GraphQL** API on the HotWax
`moqui-gql` engine, forked to **`drpn-ai/moqui-gql`** and adapted for Darpan. A Phase-0 spike proved
feasibility end-to-end: the fork compiles against Darpan's framework (Gradle 9 / JDK 21), boots,
builds a curated Darpan schema (`ReconciliationResult`), serves `POST /rest/s1/graphql` + the SDL,
and — critically — enforces **fail-closed tenant isolation** via a custom `DarpanTenantScopeFilter`
(`companyUserGroupId == active tenant`; deny when none). Verified live: GORJANA vs KREWE return
**disjoint** result sets, and a tenant-less caller gets **zero** rows. (moqui-gql's own default is a
fail-OPEN one-DB-per-client model, which does NOT fit Darpan's shared DB — hence the custom filter.)

Two structural safety properties fall out of the engine choice:
- **Read-only** — `Mutations: out of scope`; the public tier literally cannot create or corrupt
  data. All writes stay on the internal authenticated facade (now validation-hardened — see below).
- **The curated graph is the security boundary** — only declared types/edges are reachable, and the
  tenant filter scopes every read; the engine adds a query-cost governor + per-caller throttle.

## Public-tier authentication & anti-fraud model

The fraud question — *"is the call from our app, or a user replaying their token via Postman?"* — is
**unanswerable for a first-party client and must not be a control** (the client is fully
user-controlled; any "prove it's the app" check ships to the browser and is bypassable). The model
assumes a hostile client and defends server-side. For the **public 3rd-party tier** specifically:

1. **Separate credential — never the PWA session token.** 3rd parties authenticate with a
   **per-client API key (or OAuth client-credentials)**, distinct from the `login_key`/cookie
   session, so each client is individually **attributable, scoped, rate-limited, and revocable**
   without affecting interactive users. The public endpoint must **reject** a PWA session token
   (separate auth realm). Map each API key to a `GqlCallerProfile` (the engine's per-caller policy
   record) carrying its tenant scope + limits.
2. **Tenant isolation, enforced server-side.** `DarpanTenantScopeFilter` scopes every read to the
   caller's tenant, fail-closed — identical whether the call comes from the app or a script.
3. **Read-only + cost governance.** No mutations; the query-cost governor + token-bucket throttle
   (`GqlCallerProfile` per-caller overrides) bound abuse/DoS (over-budget → `THROTTLED`).
4. **Detect + attribute, don't pretend to prevent replay.** Every request is logged with
   `darpan.tenant` / `darpan.userId|callerId` / `darpan.correlationId` (structured MDC) and the
   engine's `GqlQueryLog`. Add anomaly alerting on per-caller cost / volume / error spikes; respond
   by throttling or revoking the key. You cannot stop a legitimate user replaying their *own*
   credential — you make it gain them nothing (same authz + validation), bound it, and see it.
5. **Corrupt-data surface closed on the write side.** The internal write path was
   validation-hardened (2026-06-30): caller-supplied `sparkMaster` ignored, `llmBaseUrl`/SFTP-host
   SSRF blocked, `extractServiceName` allowlisted at save **and** the execution sink, and
   payload/window/field caps added — so a token-replay write via Postman cannot persist
   corrupt/dangerous data beyond the caller's authorized, validated scope. See
   [api-contract-testing.md](api-contract-testing.md).

## Public documentation hygiene (issue to remediate)

The public docs site `darpan-docs` (Mintlify) currently has an **API reference tab documenting
the internal JSON-RPC surface** (`api-reference/{overview,json-rpc,service-catalog}.mdx`). That
publishes an internal, first-party API to the world — the opposite of this policy. Until a real
public tier exists, that section should be gated (internal-only) or relabeled as
internal/developer reference, and the *public* API reference should document only the public
allowlist once built.

## Next steps — Phase 1 (gated on sign-off; nothing public until done)

Phase 0 (feasibility + tenant-isolation proof on the `drpn-ai/moqui-gql` fork) is **done**. Phase 1:

1. **[done]** Curated schema scope = `ReconciliationResult` (read-only; result/diff reads added as
   needed); the public read allowlist remains the "public-eligible" set above.
2. **[done]** `drpn-ai/moqui-gql` wired into the build: the darpan-cli `REPO_MANIFEST` + a build step
   (the engine contributes a `GqlToolFactory` loaded at boot, so its jar must be built — not just
   cloned — before load), and the deploy Dockerfiles repointed to `drpn-ai/*` (they were building
   **stale** `hotwax/darpan` + `toaditi/*`) with a moqui-gql clone + build step. `DARPAN_GQL_API`
   authz grant landed. CI intentionally does not load moqui-gql (darpan's own tests don't need it).
   *(Flagged for a Docker-build check: the Dockerfiles still clone `hotwax/moqui-framework`, which
   diverges from the `moqui/*` master CI builds darpan against.)*
3. **[done]** Public auth realm shipped in `drpn-ai/moqui-gql` (commits `c33aa43` + `ba9a7ed`): a
   per-client API key (`GqlApiKey` — SHA-256-hashed, `dgql_`-prefixed, super-admin-minted via
   `create#ApiKey`, never stored raw) maps to a tenant + `GqlCallerProfile`. The public endpoint
   `POST /rest/s1/graphql/public` (`execute#PublicQuery`, `anonymous-all`) authenticates **only** by
   API key (`Authorization: Bearer` / `X-Darpan-Gql-Key`) and **rejects** PWA session tokens
   (separate realm). Tenant scope is **pinned to the key's tenant** via a fail-closed fixed-tenant
   `DarpanTenantScopeFilter` — the session/`TenantAccessSupport` path is never consulted on the public
   realm. Adversarially reviewed (3 findings fixed: a productStore-profile cross-tenant leak, a
   blank-tenant fail-open, an ungated `create#ApiKey`) and **live-verified**: GORJANA vs KREWE keys
   return **disjoint** results, and invalid + session-only requests are rejected. The internal
   JSON-RPC facade is unchanged.
4. **[done — rate limits + anomaly; CORS config deferred to exposure]** Per-caller rate limits +
   anomaly alerting shipped (`drpn-ai/moqui-gql` `cdc6198` + `f1bca83`):
   - **Per-caller throttle fixed** — the token bucket is now keyed by the API-key id on the public
     realm. It was keyed on `ec.user.userId`, which is anonymous for API-key requests, so every public
     caller collapsed into one shared "anonymous" bucket (one noisy key would throttle all callers and
     per-key limits never applied). Live-verified: flooding key A to its bucket limit throttles A while
     key B's independent bucket still serves.
   - **Anomaly detection** — `gql.GqlMonitorServices.scan#QueryAnomalies` (scheduled `GqlAnomalyScanJob`,
     ~15 min) scans `GqlQueryLog` per caller over a rolling window and raises a `GqlAnomalyAlert` row +
     a structured WARN when volume / reject-rate / cost breaches a configurable threshold (deduped per
     window) — the signal for an operator to throttle or revoke the key. Complements the real-time
     per-request throttle above (sustained-abuse detector vs in-request gate).
   - **Origin / CORS** — decided stance: the public endpoint is server-to-server and must NOT inherit
     the first-party CORS lock (no browser-origin assumption). The actual CORS/origin config lands with
     exposure (step 5, deferred), not before.
5. Only then expose publicly; publish versioned public **GraphQL** docs generated from the SDL, and
   relabel the internal JSON-RPC reference in `darpan-docs` (the hygiene issue above).

Remaining internal hardening (separate from the public tier): the 32 MEDIUM + 15 LOW write-path
validation gaps from the 2026-06-30 audit (length caps, enum/cron/tz/PEM parse gates, name
uniqueness) — see the audit follow-up.

Related: [api-contract-testing.md](api-contract-testing.md) (contract enforcement),
[permissions-matrix.md](permissions-matrix.md) (role contract),
[security.md](security.md).
