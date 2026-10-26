# Technical Changelog For Darpan 2.1.0

## Versioning decision

| Repo | From | To | Call |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.0.0` | `v2.1.0` | **Minor**: facade contract additive |
| `drpn-ai/darpan-ui` | `v3.0.0` | `v3.1.0` | **Minor**: new screens, no workflow retired |
| `drpn-ai/darpan-hotwax` | `v0.9.0` | `v0.10.0` | **Minor** (0.x): pairs only with darpan 2.1 |
| `drpn-ai/netsuite-darpan` | `v0.3.0` | `v0.4.0` | **Minor** (0.x): pairs only with darpan 2.1 |
| `drpn-ai/shopify-darpan` | `v0.7.0` | `v0.8.0` | **Minor** (0.x): pairs only with darpan 2.1 |
| `drpn-ai/database-darpan` | `v0.2.1` | not re-cut | no commits; compiles against darpan `99e2620` |
| `drpn-ai/moqui-gql` | untagged | `v0.1.0` (recommended) | darpan 2.1's GraphQL roots need its `main` |
| `drpn-ai/darpan-docker-config` | `v2.0.0` | `v2.1.0` | matches the darpan tag |

**darpan: minor.** `check_contract_compat.py v2.0.0..HEAD`: 26 methods added, all 140 preserved,
contract version stays `3`. No run step code or sequence changed; no `/rpc/json` fence change.

**Connectors: minor.** Each calls `ExcludedRecordsSidecar.discard`, which darpan `v2.0.0` lacks
(present at HEAD; checked by grepping both refs), so no connector of this cut runs on darpan 2.0.0
and no 2.0.0-era connector runs on 2.1.0 (shared-read, by-key and conclusion-lookup contracts).

**moqui-gql.** `v0.1.0` was planned at `907fd15` (DAR-BE-101) and never tagged; `main` has since
gained `findings(query:)`, `category(id:)` / `statement(id:)` and DAR-BE-128's edge cleanup, which
darpan 2.1's audit report and finding documents rely on. Tag `v0.1.0` at `1e0776c` and pin it, or
keep building `main` (the current prod Dockerfile) and accept a moving ref.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.0.0..99e2620` | 94 |
| `drpn-ai/darpan-ui` | `v3.0.0..2520855` | 41 (+ `626b8b1`, unpushed) |
| `drpn-ai/darpan-hotwax` | `v0.9.0..3445bb1` | 26 |
| `drpn-ai/netsuite-darpan` | `v0.3.0..dfdfca2` | 20 |
| `drpn-ai/shopify-darpan` | `v0.7.0..e8b462a` | 4 |
| `drpn-ai/moqui-gql` | `main` since 2026-10-19, to `1e0776c` | 9 |

## Backend (darpan)

### Added

- **Audit run type** (DAR-BE-088..093, 103): statement catalogue, findings ledger (found, open,
  fixed, reopened, events), check health, one report/finding JSON assembler, per-period runs,
  scheduled automations that run an audit's checks; audits created and played from the app
  (DAR-UI-063, 064, 078: `create#Audit`, `add#AuditStatement`, `run#Audit`, `save#AuditCheck`,
  `get#/save#AuditSettings`, `get#AuditCheckOptions`).
- **Gorjana month-close checks** R1, R8, R12, R19, R20 kept (DAR-BE-125); R2-R4, R7, R9, R11,
  R13-R16 built (DAR-BE-094, 095, 097, 111, 113) and catalogued as tenant config.
- **Findings that explain themselves**: conclusion lookups read another system's record only when a
  rule needs it (DAR-BE-118); a finding's fix is its cause's, naming the fact (DAR-BE-120); older
  open findings re-checked by key each run (DAR-BE-119); Resolve re-checks one finding now
  (DAR-BE-123); records keep everything their read gave and list the facts that explain them
  (DAR-UI-080, 081); every id a person navigates by (DAR-BE-117).
- **Audit reads build on the last complete read** (DAR-BE-122); audits read OMS for recent days,
  both sides in parallel (DAR-BE-116).
- **Digests** daily and weekly by chat and email (DAR-BE-102); streaks stored, with GIFs and a
  streaks chat space (DAR-BE-107, 108).
- **Forgot password** and recovery email (DAR-BE-028).
- **Rule operators** `requires` / `required by` (DAR-BE-126), `GT` in conclusions (DAR-BE-094),
  `STARTS_WITH`, `NOT_EMPTY` match rules (DAR-BE-095); lifecycle plan and conclusions (DAR-UI-050,
  052); NetSuite auth delete and diagnostics (DAR-BE-114); returns paired by order + line when no id
  is shared (DAR-BE-085, 086).
- **Extract progress on automation runs** and cancel mid-extract (DAR-BE-134).

### Fixed

- A failed extract closed its run as NO_DATA (DAR-BE-130).
- A scheduled run's second side was refused "User must be logged in" (DAR-BE-133).
- Audit invoice / credit-memo checks failed at compare on `tranId` (DAR-BE-115).
- Run notifications: varied wording, no Result ID line (DAR-BE-121).
- `save#RecoveryEmail` refuses an address already in use instead of a silent fake save (DAR-BE-028).

### Security

- Excluded-records spill files hold customer records: owner-only, discarded on every path
  (DAR-BE-127, security review); mismatch amount parse bounded (DAR-BE-095); password-reset codes
  checked and counted under a row lock, reset routes fenced, recovery email needs the current
  password (DAR-BE-028); audit failure text sanitised before the ledger stores it (DAR-BE-092).
- 12 tenant `EntityFilter` rows for the new audit entities and `AutomationStreak`.

### Removed

- Unused audit services and the audit period extension (DAR-BE-127, 128); the
  `RuleSetCompareScope.objectType` self-repair and dead tests (DAR-BE-131).

## Connectors

- **darpan-hotwax**: every OMS read on GraphQL, one shared cost bucket, parallel day slices, shared
  reads (DAR-BE-116); orders, returns and order items read by key (DAR-BE-119, 120); order notes and
  statuses when asked; returns carry their order's NetSuite id, credit memo, product NetSuite ids,
  dates; findings' order basics (DAR-UI-080, 081). DAR-BE-129: governor rewritten (no THROTTLED),
  one orders read for order and order-line checks, by-id lookup cache, exchange pair lookup on
  GraphQL (REST lookup deleted), keyed reads in parallel, per-call log; a third ship group no longer
  fails the unit read. DAR-BE-134: shared reads followed for live progress.
- **netsuite-darpan**: SuiteQL page retry on timeout (DAR-BE-056); `ACCOUNT_USED`, `LINKED_*`,
  `LINE_ITEMS`, `DEPOSIT_WHEN`, `LINKED_RECORDS`, `LINE_LINKED` columns; read by key and conclusion
  lookup by internal id; query authoring and preview; auth diagnostics; per-call log.
- **shopify-darpan**: orders carry `isFulfilled` (R20); returns rebuilt on unreadable orders with
  refund line ids (DAR-BE-085, 086); spill discarded on every path; per-call log.
- **moqui-gql**: read-only `auditReport`, `auditFinding`, `findings(query:)`; descriptions on every
  SDL item; agent keys (label, expiry, list, disable, rotate).

## UI (darpan-ui)

- Audit view, board and finding panel (DAR-UI-058, 065-076, 079-081); audit authoring and
  play/settings (DAR-UI-063, 064, 078); conclusions editor (`GT`, `WITHIN_HOURS`, causes' fixes);
  rules board `requires` / `required by`, starts-with filter; NetSuite auth details (DAR-UI-062);
  Edit run endpoint by system family (DAR-UI-077); forgot password and recovery email (DAR-BE-028);
  lifecycle builder and run start (DAR-UI-050, 052, 060); cross-tenant result goes home (DAR-UI-061);
  finished runs from storage (DAR-UI-057); Tara (DAR-UI-056).
- Security: none beyond the reset flow's server-side checks.

## Data and configuration

- New entities (add-missing on start): the 11 `darpan.audit.*` entities, `AutomationStreak`,
  `PasswordResetCode`; new columns `NsSuiteQlSourceQuery.omsStatusConfigId` / `omsStatusKeyField`,
  `RuleSetConclusionRule.fixActionTemplate`, `RuleSetCompareScope.conclusionLookupsJson`
  (checked against `git diff v2.0.0..HEAD -- entity/`).
- Job `send_AuditDigests_15m`; email templates for reset and digest; `DARPAN_MAIL_FROM` optional.
- Upgrade data: 98 records (`release-notes.md`); tenant config separate (`release-checklist.md`).

## Validation and rollout notes

Validation: `release-checklist.md` (Verification, Not verified).


- Roll out as a set: darpan, the three connectors, moqui-gql and darpan-ui together; database-darpan
  stays `v0.2.1`.
- Order: image from the release tags → start (add-missing creates tables/columns) →
  `loadDarpanUpgradeData` → `run#PendingMigrations` → tenant config → NetSuite query uploads →
  audit automations → smoke.

## References

- Compare: `https://github.com/drpn-ai/darpan/compare/v2.0.0...v2.1.0`,
  `https://github.com/drpn-ai/darpan-ui/compare/v3.0.0...v3.1.0`,
  `https://github.com/drpn-ai/darpan-hotwax/compare/v0.9.0...v0.10.0`,
  `https://github.com/drpn-ai/netsuite-darpan/compare/v0.3.0...v0.4.0`,
  `https://github.com/drpn-ai/shopify-darpan/compare/v0.7.0...v0.8.0` (valid once tagged).
