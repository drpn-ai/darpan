# Technical Changelog For Darpan 1.5.1

## Versioning decision

**Patch, not minor**, and the call is deliberate. Three of the sixteen commits are `feat` — the
return-status exclusion on the Shopify returns connector (`darpan 53285d8`, `shopify-darpan e4bb3bd`
/ `21bafed` / `4aa4df0`) and `darpan-ui 59b12aa`. Under strict semver that argues for `1.6.0`.

It is cut as a patch because the release's reason for existing is that `1.5.0` shipped
scheduled-run verification switched on and inert. Everything else rides along with a fix that cannot
wait for a minor cut. Per the hotfix consolidation rule (set 2026-07-03), `1.5.1` gets no separate
public release page: the 1.5 line is told as one story on the newest tag, so the feature is not
hidden from operators by the patch numbering.

The Shopify exclusion feature is itself a completion of `1.3.0`'s exclusion filters extended to one
more connector, not a new capability — zero UI changes were required for it.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| `drpn-ai/darpan` | `v1.5.0..HEAD` | 9 |
| `drpn-ai/shopify-darpan` | `v0.6.0..HEAD` | 4 |
| `drpn-ai/darpan-ui` | `v2.6.0..HEAD` | 3 |

## Backend

### `drpn-ai/darpan` — 9 commits

#### Fixed

- `d40082d` — a scheduled run now verifies exactly like a manual one. `RunVerificationSupport.buildVerificationLookup`
  resolved a source's config id from two *column* shapes; a scheduled run passes a raw
  `ReconciliationAutomationSource` row that declares neither for API connectors, which store the id
  in `safeMetadataJson.parameters`. `configId` came back null, no lookup was built, and
  `verifyMissingDiffsIfEnabled` returned false without opening a `VERIFY` step — indistinguishable
  from the switch being off, on every OMS and Shopify automation since `1.5.0` flipped the default on.
  Only `DATABASE` sources, whose `databaseSourceQueryId` *is* a column, ever verified.
  `resolveSourceConfigId` now walks the same chain the extractor walks: row column →
  `safeMetadataJson.parameters` → the run's resolved `sourceExtractorConfigDefaults`.
  The exchange-pair and return-presence passes, which had one caller each in `runSavedRunDiff.groovy`,
  now run on both paths through shared seams. `runSavedRunDiff.groovy` 1234 → 1108 lines; its
  `buildFencedLookup` closure is deleted — it read `source?.sourceConfigId` directly, which *raises*
  an `EntityException` on an automation row rather than answering null. `NOTIFY` becomes a stage on
  the scheduled path.
- `6543bbb` — a run that skips verification now says why, on both paths. Every gate returned before
  the step was opened, so "switched off", "no config id", "unreadable diff artifact" and "nothing to
  check" looked identical from outside. A declining pass now records a `NO_DATA` `VERIFY` step and a
  processing warning naming the reason. `NO_DATA` rather than `FAILED`: nothing broke in the run, and
  a red step on every run of a deployment that switched the pass off trains operators to ignore the
  colour. `runMissingDiffPass` becomes the whole phase, called identically by both orchestrators
  (`runSavedRunDiff.groovy` 60 → 14 lines, `AutomationExecutionSupport` 86 → 37).
- `c72b266` — a scheduled run's verification can now find its diff document. All three passes read
  the diff off disk and rewrite it in place; they ran at lines 404–410 while
  `ensureAutomationResultArtifact` — the only thing that sets `reconcileResult.diffLocation` — ran at
  412. `reconcile#RuleSetCompareScope` declares no `diffLocation` out-parameter, so `resolveDiffFile`
  returned null deterministically on every scheduled run. The missing-diff pass skipped loudly; the
  return-presence and exchange passes skipped in silence, so returns runs published without
  grace-window conversion, pre-window gating, cancelled-order refund suppression, superseded-sibling
  suppression or the by-id recheck. Fixed by writing the artifact before the passes — the order
  `runSavedRunDiff.groovy` has always used.
- `71d559a` — stop counting one suppressed return row under two reasons. A row whose line never
  shipped *and* whose order turns out cancelled takes two paths; `addAll` into a `LinkedHashSet` was
  a no-op while `cancelledRefundCount += rowIds.size()` counted it again. `removedCount` was never
  wrong (derived from the de-duplicated id map); only the per-reason tallies over-counted, by exactly
  the overlap. The regression test asserts the invariant (`pending + preWindow + cancellationRefund +
  superseded + cancelledItem == removedCount`), not the number.
- `ce5f790` — rename the return-status pill and retire the colliding name. Companion to
  `shopify-darpan 773c1ad`. Renames the pill row, `keepFieldsBase` and the upgrade-data copies, and
  declares the old PK in `RETIRED_FIELDS`.
- `d55e097` — drop the `Notes:` paragraph from run-completion alerts. Three independent producers
  each appended a sentence to `processingWarnings` and the renderer `join(' ')`-ed whatever arrived.
  `RunNotificationVoice.detailsBlock` already renders the actionable numbers from structured
  in-parameters immediately above. `partitionAuditNotes` stays and is still called — the split is what
  keeps an always-emitted audit sentence out of `warningList` and so out of the "finished, but not
  cleanly" header. The stored artifact keeps every sentence verbatim.
- `f8ffc4a` — clear undecryptable webhooks in the new column too. `clear#UndecryptableChatSpaceWebhooks`
  only validated `googleChatWebhookUrl`, but `migrate#ChatSpaceWebhookUrls` copies that column into
  `webhookUrl` without validating, and `resolveWebhookUrl` reads `webhookUrl` first. Nothing orders
  two one-time services and the two orders are not equivalent: migrate-then-clear leaves ciphertext
  unreachable forever. Both columns are now checked and validate differently on purpose — reusing the
  Google-only validator on the provider-agnostic column would have nulled every working Slack webhook.

#### Added

- `53285d8` — return-status exclusion offered on the Shopify returns connector.

#### Tests

- `e2d30b7` — `RunPathParityGateTest`, four source-structural checks in the unit pool: both
  orchestrators open the same stage set and that set equals a canonical list; every
  `RunVerificationSupport` pass seam is called from both files; both open the same number of `VERIFY`
  steps; neither calls a pass implementation directly. Each assertion was watched failing under a
  real perturbation. The gate's own first version was satisfied by a leftover javadoc mentioning a
  seam that was no longer called, so it strips block and line comments before matching. Structural,
  not differential, and the class doc says so.

### `drpn-ai/shopify-darpan` — 4 commits

- `e4bb3bd` — select `returns.status` for return-status exclusion.
- `21bafed` — emit the status on return rows.
- `4aa4df0` — apply source exclusion filters in the returns getter.
- `773c1ad` — name the key for the enum it actually holds. `returnStatus` collided with Shopify's
  `Order.returnStatus` (`OrderReturnStatus`: `IN_PROGRESS`, `INSPECTION_COMPLETE`, `NO_RETURN`,
  `RETURN_FAILED`, `RETURN_REQUESTED`, `RETURNED`) while the selection is `Return.status`
  (`ReturnStatus`: `REQUESTED`, `OPEN`, `CLOSED`, `DECLINED`, `CANCELED`). Renamed to
  `returnWorkflowStatus`; the GraphQL selection is unchanged. Both enums are spelled out at each site.

## UI

### `drpn-ai/darpan-ui` — 3 commits

- `3f5ca66` — title the rules board's columns with the system, not the endpoint. Since endpoint-level
  systems exist (`OMS_RETURNS`, `SHOPIFY_RETURN_REFS`), a side's own `systemLabel` names the endpoint.
  The rule set manager had already fixed this with a documented helper; `RuleSetBoard` carried its own
  naive `file1SystemLabel || file1SystemEnumId` one-liner. The helper is now
  `reconciliationSystemTitle()` in the module owning the draft type, called by both. Second defect:
  `file{n}SystemParentLabel` was populated by `savedRunEditorRoute` and `RunsSettingsWorkflowPage` but
  not by the create-flow wizard — the only producer of a draft for a new run — so a newly created run
  had no family name to fall back to. The wizard now carries it.
- `3746a59` — stop masking a webhook URL the step prints in clear below it. `type="password"` on a
  field whose value renders in full two lines down. Not a secret by decision: `encrypt="true"` was
  removed from `TenantChatSpace.googleChatWebhookUrl` on 2026-08-14 and the entity description still
  carries the reasoning. Now `type="text"`, not `type="url"` — native constraint validation would gate
  submission ahead of `validateWebhookUrl`, which owns the host pin that is the actual SSRF control.
- `59b12aa` — name the timezone on every displayed time. `Intl` alone cannot produce codes:
  `timeZoneName:'short'` is locale-dependent (`en-US` gives `PDT` for Los Angeles but `GMT+5:30` for
  Kolkata). `timeZoneCode()` resolves in three steps pinned to `en-US`: CLDR short name when it is a
  real code → initials of the long name (`India Standard Time` → `IST`) → the offset, so nothing
  renders blank. DST-correct by construction; pinned in tests in both halves of the year. Three
  overrides only, where initialising is provably wrong (`Central European Standard Time` → `CEST`
  asserts summer time in January). Appended by `formatDateTime`, reaching every timestamp surface,
  because `dateStyle`/`timeStyle` cannot be combined with `timeZoneName`.

## Data

- `data/SourceSystemConnectorSeedData.xml` — `SHOPIFY_RETURN_REFS` gains `filterParameterName`, widened
  `keepFieldsBase`.
- `data/SourceSystemConnectorFieldSeedData.xml` — new `returnWorkflowStatus` pill.
- `data/upgrade-data.xml` — rebuilt as a 2-record 1.5.1 delta (was a 55-record cumulative file
  produced by appending to 1.5.0's).
- `data/releases/1.5.1/` — new pack.
- `docs/api-contract/openapi.json` — regenerated, `version` 1.5.0 → 1.5.1, one line.
