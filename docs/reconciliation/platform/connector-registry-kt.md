# KT: Source Connector Registry (`SourceSystemConnector`)

> **Audience:** a new agent/engineer picking up the connector-registry build.
> **Status:** design + implementation KT. Greenfield — no registry entity exists yet.
> **Owning surface:** `darpan-backend/runtime/component/darpan/**` (backend, Moqui/Groovy/XML).
> **Prepared:** 2026-07-01, from a MACH-audit follow-up + live-code verification.

---

## 0. How to use this doc

1. Read it end to end before touching code. Every file:line here was verified against `drpn-ai/darpan@main` on 2026-07-01, but **re-grep line numbers before editing** — they drift.
2. This is a `feature` per the repo workflow. Do **Linear-first intake** (see §10) before any edits.
3. Follow the phased plan in §7 (strangler-fig). Each phase lands behind a green `:runtime:component:darpan:test` suite and preserves behavior.
4. The **acceptance test** for the whole effort (§8): *a brand-new fake `systemEnumId`, seeded only as a registry data row, resolves and dispatches an extraction with **zero** core-Groovy change.* That is the definition of done for "config over code."

---

## 1. Mission (TL;DR)

Replace Darpan's **hardcoded, per-system source-dispatch branching** with a **seed-data-backed registry entity** (`darpan.reconciliation.SourceSystemConnector`) + one resolver, so onboarding a new source system (or a customer's own system) is a **data row**, not edits to core Groovy in several files.

Today, adding a source means editing core in ≥3 support classes. After this work, core knows *nothing* system-specific; each integration component seeds its own connector row.

This is the internal plumbing that unblocks the biggest cluster of MACH-roadmap debt (microservices 5% → , integrations 8% → , codequality 16% → ) and is the foundation under the **customer-facing connector API design in [DAR-283]** (In Review — *"connect any system without a custom Darpan integration component for every system"*). Build the registry so DAR-283's customer API can sit directly on top of it.

---

## 2. Why (MACH context)

The MACH audit's single highest-leverage item. Core/facade code is coupled to specific integrations via literal `==` comparisons and per-class duplicated `static final` constants (OMS / SHOPIFY / HOTWAX_OMS_REST / NETSUITE_AUTH). Because the same source constants are re-declared across three support classes, one new source = edits in multiple core files — the inverse of MACH "config over code" decoupling.

**A metadata-first escape hatch already half-exists** (`metadata.extractServiceName`, see §4) and works generically — the job is to make that the **only** path by moving per-system facts into data.

Landing this unblocks (roughly): data-driven source dispatch, unify saved-run + automation dispatch, collapse the ~12–15 per-system switch sites, metadata-driven source resolution, and — adjacently — retry/dead-letter and the dynamic-dispatch security fence.

---

## 3. Vocabulary

| Term | Meaning |
|---|---|
| **source** | One file-side input of a reconciliation (`FILE_1` / `FILE_2`). Persisted as `RuleSetCompareSource` (`entity/RuleEntities.xml:69`). |
| **systemEnumId** | The source system id: `OMS`, `SHOPIFY`, `NETSUITE`, `SAPI`, or `null`/upload for plain files. |
| **sourceConfigType** | Which auth/config kind a source needs: `SHOPIFY_AUTH`, `HOTWAX_OMS_REST`, `NETSUITE_AUTH`. |
| **extract service** | The Moqui service that pulls data for a system, e.g. `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders`. Lives in the **integration component** (`darpan-hotwax`, `shopify-darpan`, …), invoked by name. |
| **automation path** | Scheduled/automated extraction. `AutomationExecutionSupport`. |
| **saved-run / interactive path** | User-driven Create-Run extraction. `ReconciliationSavedRunSupport`. **Two divergent dispatch implementations — unifying them is the whole point.** |
| **virtual remote** | A `moqui.service.message.SystemMessageRemote` row (`ensureVirtual*`) whose `sendServiceName` is the extract service; the mechanism the saved-run path uses to invoke extractors. |

---

## 4. Current architecture (verified — RE-GREP before editing)

### 4a. Automation dispatch — `src/main/groovy/darpan/reconciliation/automation/AutomationExecutionSupport.groovy`

| Line | What |
|---|---|
| `:68-71` | `OMS_SYSTEM_ENUM_ID="OMS"`, `SHOPIFY_SYSTEM_ENUM_ID="SHOPIFY"`, extract-service constants (re-imported from `ReconciliationSavedRunSupport`). |
| `:76` | `ALLOWED_*` extract-service **allow-list** `Set` (already exists — the security fence is *partly* done). |
| `:600` | `callConfiguredSourceExtractor(...)` — the automation entry to extraction. |
| `:604` | `serviceName = metadata.extractServiceName ?: metadata.serviceName` — **the metadata-first seam.** |
| `:608` | `throw IllegalStateException("API source extractor is not configured for ${systemEnumId} ${fileSide}. Add extractServiceName ... after DAR-240/DAR-241 source contracts are available.")` — **the stub the registry replaces.** (DAR-240/241 are Done but delivered extractors, not this contract layer.) |
| `:617` | allow-list check: rejects a `serviceName` not in the `ALLOWED_*` set. |
| `:634` | `if (serviceName == SHOPIFY_ORDERS_EXTRACT_SERVICE) serviceParams.preserveWindowInstants = true` — a per-system behavior branch → should be a registry flag. |
| `:637` | `call.disableAuthz()` on the dynamic dispatch — **privilege-escalation risk once service names become config-driven (see §7 Phase 4).** |
| `:657-658` | `needsOmsRestConfig` / `needsShopifyAuthConfig` — config-requirement branches on the two constants. |
| `:668` | `resolveSourceExtractorMetadata(...)` — **the resolver to rewrite.** |
| `:670` | `if (metadata.extractServiceName…) return metadata` — early-return already supports registry-populated metadata. |
| `:677`, `:687` | `if (systemEnumId == OMS_SYSTEM_ENUM_ID)` → sets `HOTWAX_OMS_ORDERS_EXTRACT_SERVICE`; `if (== SHOPIFY_…)` → `SHOPIFY_ORDERS_EXTRACT_SERVICE`. **The core switch.** |
| `:700+` | `defaultDateFromParameterName` / `defaultDateToParameterName` — `switch(serviceName)` over the two constants. |

### 4b. Saved-run / interactive dispatch — `src/main/groovy/darpan/facade/reconciliation/ReconciliationSavedRunSupport.groovy`

| Line | What |
|---|---|
| `:27-46` | The de-facto registry-as-constants: `SYSTEM_SHOPIFY/HOTWAX_OMS/NETSUITE/SAPI`, `SOURCE_CONFIG_TYPE_*`, `*_ORDERS_REMOTE_ID`, `*_ENDPOINT_LABEL`, and the extract-service string literals (`:42`, `:46`). |
| `:660-668` | `expectedSourceConfigType(systemEnumId)` — `switch` returning `SHOPIFY_AUTH` / `HOTWAX_OMS_REST` / `NETSUITE_AUTH`. |
| `canonicalSystemEnumId(...)` | alias table (used at `:531-532`, `:621`). |
| `validateSourceConfig(...)` | per-system `validateShopifyAuthConfig` / `validateHotWaxOmsConfig` / `validateNetSuiteAuthConfig`. |
| `ensureVirtualHotWaxOrdersRemote` / `ensureVirtualShopifyOrdersRemote` | build a `SystemMessageRemote` (sendUrl / sendServiceName / label) from the `*` constants — two near-identical methods to collapse onto one registry-driven builder. |

> **Note on NetSuite:** it is core-coupled *here* (auth-config validation + `expectedSourceConfigType`) but **not** in the automation extractor switch (§4a). This class is the right place to neutralize the NetSuite coupling.

### 4c. Duplicated constants (drift risk)

The same source constants are re-declared in **three** classes — collapse into the registry + one holder (§7 Phase 6):
- `AutomationExecutionSupport.groovy:68-76`
- `AutomationFacadeSupport.groovy:30-50`
- `ReconciliationSavedRunSupport.groovy:27-46`

### 4d. Data model already present

- `RuleSetCompareSource` (`entity/RuleEntities.xml:69`): `systemEnumId`, `sourceTypeEnumId` (`AUT_SRC_API`), `fileTypeEnumId`, `schemaFileName`, `recordRootExpression`, `primaryIdExpression`, `idValueNormalizer`, `systemMessageRemoteId`, `nsRestletConfigId`, `sourceConfigId`, `sourceConfigType`. **The registry keys off `systemEnumId`; the source row already carries `sourceConfigId`/`sourceConfigType`.**
- `ReconciliationAutomationExecution` (`entity/ReconciliationEntities.xml:260`): has `statusId`, `errorMessage`, `parentAutomationExecutionId` (`:270`), unique `RECAUTEX_IDEMP` index (`:321`). **No** `retryCount`/`nextRetryDate`/dead-letter — the retry gap (§7 Phase 5).
- Extract services are seeded as `SystemMessageRemote` rows (`data/SystemMessageRemoteSeedData.xml`, `data/releases/*/upgrade-data.xml`) with `sendServiceName` = the extract service.
- Statuses in `data/AutomationSeedData.xml` (`AUT_STAT_FAILED` is terminal at `:51` — no requeue).

---

## 5. Target design

### 5a. Entity (sketch — refine against real field types)

Add to `entity/ReconciliationEntities.xml`:

```xml
<entity entity-name="SourceSystemConnector" package="darpan.reconciliation" use="configuration">
    <description>Registry row that makes a source system dispatch data-driven (config over code).</description>
    <field name="systemEnumId" type="id" is-pk="true"/>
    <field name="extractServiceName" type="text-medium"/>        <!-- e.g. reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders -->
    <field name="dateFromParameterName" type="text-short"/>
    <field name="dateToParameterName" type="text-short"/>
    <field name="expectedSourceConfigType" type="text-short"/>   <!-- SHOPIFY_AUTH / HOTWAX_OMS_REST / NETSUITE_AUTH -->
    <field name="configIdResolverServiceName" type="text-medium"/> <!-- findSingleActive*ConfigId equivalent -->
    <field name="validationServiceName" type="text-medium"/>      <!-- per-connector validateSourceConfig -->
    <field name="remoteId" type="id"/>                           <!-- HOTWAX_ORDERS_API / SHOPIFY_REMOTE -->
    <field name="endpointLabel" type="text-medium"/>
    <field name="sendUrlTemplate" type="text-medium"/>
    <field name="systemAliases" type="text-medium"/>             <!-- for canonicalSystemEnumId -->
    <field name="preserveWindowInstants" type="text-indicator"/> <!-- replaces the Shopify special-case at :634 -->
    <field name="enabled" type="text-indicator"/>
</entity>
```

### 5b. Seed data

New `data/SourceSystemConnectorSeedData.xml` (or, better, each integration component seeds **its own** row so it registers itself without core edits):
- `OMS` and `SHOPIFY` rows that reproduce the current switch **byte-identically**.
- `NETSUITE` added later as a pure data row.

### 5c. Resolver

One `SourceSystemConnectorSupport.resolve(ec, systemEnumId)` (tenant-safe via `TenantScopedFinder` where applicable) that both dispatch paths call. `resolveSourceExtractorMetadata` populates `metadata.extractServiceName` (+ date params, config type) from the row, so the existing metadata-first early-returns (`:604`, `:670`) become the single path.

---

## 6. Switch-site inventory (the collapse list)

| # | Site | Switches on | Becomes |
|---|---|---|---|
| 1 | `AutomationExecutionSupport:677/687` `resolveSourceExtractorMetadata` | `systemEnumId ==` OMS/SHOPIFY | registry lookup |
| 2 | `AutomationExecutionSupport:700+` date-param `switch` | `serviceName` | registry `date*ParameterName` |
| 3 | `AutomationExecutionSupport:634` `preserveWindowInstants` | `serviceName == SHOPIFY` | registry `preserveWindowInstants` flag |
| 4 | `AutomationExecutionSupport:657-658` config-requirement | `systemEnumId ==` | registry `expectedSourceConfigType` + null-check |
| 5 | `ReconciliationSavedRunSupport:660-668` `expectedSourceConfigType` | `systemEnumId` `switch` | registry read |
| 6 | `ReconciliationSavedRunSupport` `canonicalSystemEnumId` | alias table | registry `systemAliases` |
| 7 | `ReconciliationSavedRunSupport` `validateSourceConfig` | per-system validators | registry `validationServiceName` |
| 8 | `ReconciliationSavedRunSupport` `ensureVirtual*` (×2) | per-system constants | one registry-driven remote builder |
| 9 | config-default discovery `findSingleActiveOmsRestSourceConfigId` / `findSingleActiveShopifyAuthConfigId` | per-system | registry `configIdResolverServiceName` |
| 10 | duplicated constants ×3 classes (§4c) | — | registry seed + one holder |

---

## 7. Build plan (strangler-fig, phased)

**Phase 0 — prereqs.** Green `:runtime:component:darpan:test` (390 baseline). Read this doc. Linear intake (§10).

**Phase 1 — entity + seed + resolver, no behavior change.** Add `SourceSystemConnector` entity + `OMS`/`SHOPIFY` seed rows (byte-identical) + `SourceSystemConnectorSupport.resolve`. Wire nothing yet. Test: resolver returns the same service/params the switch would.

**Phase 2 — automation path onto the registry.** Rewrite `resolveSourceExtractorMetadata` (`:668`), the date-param switches (`:700+`), `preserveWindowInstants` (`:634`), and config-requirement branches (`:657-658`) to read the registry. Reword the `IllegalStateException` (`:608`) to *"no connector registered for systemEnumId X"* (drop the DAR-240/241 reference). Keep the metadata-first early-return.

**Phase 3 — unify the saved-run path.** Replace `expectedSourceConfigType` (`:660-668`), `canonicalSystemEnumId`, `validateSourceConfig`, and the two `ensureVirtual*` builders with registry reads. **Add a test that drives the automation path and the interactive saved-run path for the same source and asserts identical resolved service name + parameters** — this proves the two implementations are truly unified.

**Phase 4 — security fence (do NOT skip; must precede tenant-configurable service names).** The `ALLOWED_*` allow-list (`:76`/`:617`) already exists — extend it so **only registry-registered service names** may be dispatched, add a verb/noun naming guard (must match `reconciliation.*ExtractionServices.extract#*`), and replace the blanket `disableAuthz()` (`:637`) with a narrowly-scoped service-user context. Negative test: a registry row / metadata pointing `extractServiceName` at a non-extractor internal service is rejected and never dispatched.

**Phase 5 — retry + dead-letter (adjacent P1, can be a separate ticket).** Add `retryCount`/`maxRetryCount`/`nextRetryDate` + `AUT_STAT_DEAD_LETTER` to `ReconciliationAutomationExecution` + `AutomationSeedData.xml`. At the FAILED transition, classify transient vs permanent (in `callConfiguredSourceExtractor`), backoff-requeue transients to `PENDING`, dead-letter at max retries. Extend the existing 5-min scanner `ServiceJob` (`data/ReconciliationJobSeedData.xml`) to pick up due `nextRetryDate` rows — no new cron. Add a `reprocess#AutomationExecution` (allow-remote, authenticated) for operator re-drive.

**Phase 6 — constants cleanup.** Delete the now-dead per-source constants across the three classes; keep one holder for genuinely cross-cutting non-source constants (`windowStart`/`windowEnd`, normalizer aliases, side ids). Add a `verifyOrganization` check (`build.gradle`) that flags new duplicate source-constant declarations.

**Acceptance (gates the whole effort):** a brand-new fake `systemEnumId`, seeded only as a `SourceSystemConnector` row, resolves + dispatches with **zero** core-Groovy change. Write this test.

---

## 8. Testing & validation

Run from the wrapper root, JDK 21:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
cd darpan-backend
./gradlew :runtime:component:darpan:test --tests <FQCN>      # focused
./gradlew :runtime:component:darpan:test                     # full (390 baseline; ~4m)
```
- Existing coverage that guards these refactors: `AutomationExecutionSupportTests`, `AutomationExecutionServiceSmokeTests`, `AutomationEntityContractTests`, `SavedRunsFacadeSmokeTests`, `RuleSetCompareScopeServiceSmokeTests`, `GenericReconciliationServiceSmokeTests` (`src/test/groovy/darpan/reconciliation/**`).
- **Gotchas:** each test class runs in its **own JVM** (`forkEvery=1`), so `System.setProperty` is per-class-scoped. A **running dev-stack holds the Bitronix `runtime/txlog/btm*.tlog` lock** → backend tests can't init while it's up. Local `java` may be 17 — always set `JAVA_HOME` to openjdk@21.
- `./gradlew :runtime:component:darpan:build` runs the `verifyOrganization` static-analysis gate.

---

## 9. Boundaries, constraints & pitfalls

- **Moqui XML-first.** Prefer entity/service/seed-data + declarative patterns; keep Groovy only where it removes real complexity (see `.claude/rules/darpan-backend.md`).
- **Tenant scoping.** Route any tenant-scoped reads through `TenantScopedFinder`; bare `disableAuthz` is CI-banned. The connector registry itself is `use="configuration"` (platform config), but source/config lookups it triggers are tenant-scoped.
- **Preserve contracts.** Service contracts, `allow-remote`/`authenticate` exposure, active-tenant scoping, entity naming — don't change without cause.
- **Each integration component seeds its own row** (`darpan-hotwax`, `shopify-darpan`, `netsuite-darpan`) — that's how a new source "registers itself" without core edits. Keep partner-specific extract *services* in their component; the registry references them **by name** only.
- **Byte-identical first.** Phase 1 seed must reproduce today's behavior exactly before you delete any branch.
- **Groovy 4 Map gotcha:** on a `Map`, both `.properties` and `['properties']` resolve to bean `getProperties()` — use `map.get('properties')`.
- **NetSuite** is core-coupled in validation/config-type but not extraction — neutralize it in `ReconciliationSavedRunSupport` (Phase 3), don't assume symmetry with OMS/SHOPIFY.
- **Security ordering is load-bearing:** the dynamic-dispatch `disableAuthz` (`:637`) + tenant-configurable `extractServiceName` = privilege escalation. **Phase 4 must land before** any path lets a tenant/config choose the service name.

---

## 10. Intake & workflow rules (do this first)

1. Classify: **`feature`**. Do Linear-first prework (`AGENTS.md`, `LINEAR_WORKFLOW.md`).
2. **Create a Linear issue** in team `Darpan`, project **"Q2 2026: Connections & Integrations"**, assignee **Aditi Patel**, labels `type: core-feature` + `area: integrations` + `Backend`. **Relate it to [DAR-283]** (the customer-facing connector API design this implements). Consider splitting the phases (registry / unify / security fence / retry) into sibling issues.
3. **Branching:** work directly on `main` in `drpn-ai/darpan` (current policy overrides the topic-branch text in `LINEAR_WORKFLOW.md`). Use the Linear id in commit messages.
4. **Linear comments** (progress/verification/closeout) must end with `LLM time this update: <minutes> minutes` (add `(<h>h)` at ≥60).
5. State runtime-verification gaps separately from compile/test proof.

---

## 11. Key files

| File | Role |
|---|---|
| `src/main/groovy/darpan/reconciliation/automation/AutomationExecutionSupport.groovy` | Automation dispatch + the core switch + allow-list + `disableAuthz` |
| `src/main/groovy/darpan/facade/reconciliation/ReconciliationSavedRunSupport.groovy` | Saved-run dispatch, config-type/alias/validation switches, virtual remotes, constants |
| `src/main/groovy/darpan/facade/reconciliation/AutomationFacadeSupport.groovy` | Duplicated source constants |
| `entity/ReconciliationEntities.xml` | `ReconciliationAutomationExecution`; add `SourceSystemConnector` here |
| `entity/RuleEntities.xml` | `RuleSetCompareSource` (`:69`) — the source row keyed by `systemEnumId` |
| `data/AutomationSeedData.xml` | Automation statuses (`AUT_STAT_FAILED` `:51`) |
| `data/ReconciliationJobSeedData.xml` | The 5-min scanner `ServiceJob` (extend for retry) |
| `data/SystemMessageRemoteSeedData.xml` | Extract services as `SystemMessageRemote` `sendServiceName` |
| `docs/reconciliation/automation/order-reconciliation-automation.md` | How `extractServiceName`/metadata is used today (background) |

**Related Linear:** DAR-283 (design, In Review), DAR-237 (parent automation epic — done), DAR-240/241/242 (extractors — done), DAR-250 (file-or-API source per side), DAR-244 (automation facade APIs).
