# Darpan 1.5.1 Release Notes

Release date: `2026-08-31`

## Scope note

`1.5.1` is a hotfix on the 1.5 line: 9 backend commits, 4 on the Shopify connector and 3 on the app,
against `v1.5.0`. It exists because `1.5.0` shipped one behaviour switched on and three separate
defects that made it do nothing.

**Scheduled runs now verify for real.** `1.5.0` turned missing-difference verification on by default
for automations. On that path it was inert. Three independent faults stacked: the config id every
verification lookup needs was read from two column shapes a scheduled source row does not have; the
diff document the passes read was written two lines *after* the last reader; and every gate in front
of a pass returned before opening a step, so "switched off", "could not resolve", "unreadable" and
"nothing to check" were indistinguishable from outside. The visible cost was a returns automation
reporting 456 missing-in-Shopify against 2 once verified, and a run publishing raw compare counts
with no indication anything had been skipped. All three are fixed, the exchange-pair and
return-presence passes now run on both paths, and a scheduled run records the same step list a manual
one does — including `NOTIFY`, so "did the alert fire?" is answerable from the timeline.

**The two run paths can no longer drift silently.** Twice in one week the scheduler and the Run
button executed different processes while the suite stayed green, because they were kept in step by
hand-written "mirrors runSavedRunDiff" comments. `RunPathParityGateTest` now fails the build when the
two orchestrators open different stages, when a verification seam is called from one path only, when
they open different numbers of `VERIFY` steps, or when either calls a pass implementation directly.
Each of its four assertions was watched failing under a real perturbation — and its own first version
was fooled by a comment, so it strips comments before matching.

**Return-status exclusion reaches Shopify return references** — and is renamed. Operators can exclude
in-progress returns that have no OMS counterpart yet. The field was called `returnStatus` for a few
hours on 2026-08-27 and collided with Shopify's own `Order.returnStatus`, which holds a different
enum; an operator on production read the docs that name pointed at, excluded on `IN_PROGRESS`, and
dropped nothing. It is now `returnWorkflowStatus`, and the old name is retired. **This retirement
needs one manual step per environment — see Upgrade data.**

**Alerts and counts say true things.** A run's chat alert no longer ends in six audit sentences
joined by a space; those counts were already rendered above it. A returns run's suppression tallies
now sum to the number of rows actually removed — one row qualifying under two rules was counted
twice. And a tenant whose webhook could not be decrypted is now cleaned up in both columns, not just
the legacy one.

**The app says which 11:37 it means.** Every displayed timestamp now names its zone.

## Repo targets

| Repo | Tag | Branch | Content commits since previous tag |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v1.5.1` | `main` | 9, `v1.5.0..HEAD` |
| `drpn-ai/shopify-darpan` | `v0.6.1` | `main` | 4, `v0.6.0..HEAD` |
| `drpn-ai/darpan-ui` | `v2.6.1` | `main` | 3, `v2.6.0..HEAD` |

`darpan-hotwax` (`v0.8.0`), `netsuite-darpan` (`v0.2.0`) and `database-darpan` (`v0.2.0`) have no
commits since their tags and are not re-cut.

`darpan` and `shopify-darpan` MUST deploy together. `shopify-darpan v0.6.1` emits
`returnWorkflowStatus`; `darpan v1.5.1` declares the rules-board pill that selects it. Either one
alone gives a pill with no field, or a field with no pill — and a rule matching nothing reports
`excludedCount 0`, which looks exactly like a feature that was never deployed.

## User-visible changes

- Scheduled runs report verified difference counts rather than raw compare counts. **Counts will
  move**, in both directions: the missing-difference and return-presence passes remove false
  positives, and the exchange pass appends Shopify exchanges absent from OMS.
- A run that skips verification says so, on both paths — a `NO_DATA` `VERIFY` step plus a warning
  naming the reason (the kill switch, the config parameter that would not resolve, or an unreadable
  diff artifact). Deliberately silent when nothing was missing, when rules failed, or when no side
  declares a point lookup at all.
- Shopify return references gain a **Return workflow status** exclusion pill. Exclude
  `REQUESTED,OPEN` to drop in-progress returns with no OMS counterpart.
- Run-completion chat alerts drop the trailing `Notes:` paragraph. The numbers an operator acts on
  are in `*Details*` immediately above. A fully clean run is now just the verdict.
- Every displayed timestamp names its timezone — `Aug 31, 2026, 11:37 AM IST`. DST-correct by
  construction; date-only labels are deliberately left un-annotated.
- The rules board titles its columns with the system, not the endpoint, matching the rule set
  manager. Newly created runs get this too — the create-flow wizard now carries the parent label.
- The tenant-settings Webhook URL field is no longer masked. The same URL renders in clear two lines
  below it, and masking made a truncated paste impossible to catch by eye.

## Operator-visible changes

- A scheduled run's step list is now `RESOLVE / EXTRACT_FILE1 / EXTRACT_FILE2 / COMPARE / VERIFY* /
  WRITE_OUTPUT / NOTIFY` — the same list the interactive path records. Counting steps is how you tell
  a verified run from an unverified one.
- The kill switch keeps its name, `darpan.reconciliation.automation.verifyMissingDiffs`, and now
  gates all three passes. It was not renamed on purpose: renaming would silently disarm it on any
  deployment already setting it. The interactive path deliberately has no kill switch.
- `clear#UndecryptableChatSpaceWebhooks` now checks `webhookUrl` as well as `googleChatWebhookUrl`.
  The two validate differently on purpose — the legacy column keeps its Google host pin, the new one
  goes through `validateWebhookUrl` with the row's own provider, so a working Slack webhook is not
  nulled by a cleanup pass.

## Upgrade data

`data/upgrade-data.xml` is a **delta**, not a cumulative file: 2 records.

- `SourceSystemConnector SHOPIFY_RETURN_REFS` restated with `filterParameterName="sourceFilters"` and
  a `keepFieldsBase` widened by `returnWorkflowStatus`.
- `SourceSystemConnectorField $.records[*].returnWorkflowStatus` — the new pill.

Load with `./gradlew loadDarpanUpgradeData`. An environment that has not yet loaded `1.5.0` must load
`data/releases/1.5.0/upgrade-data.xml` first — this file restates a row that release creates. prod,
UAT and sm-darpan all completed 1.5.0's load and migrations on 2026-08-27, so for them this is the
only outstanding pack.

**ONE MANUAL STEP, once per environment, after the load:**

```
admin.MigrationAdminServices.run#Migration  migrationId=RETIRED_FIELDS  force=true
```

The rename retires the old `returnStatus` pill. A seed load is `createOrUpdate` and never deletes, so
loading this file inserts the new pill and leaves the old one behind — the board would offer both,
and a rule on the dead one matches nothing forever. `RETIRED_FIELDS` deletes it, but that migration
is already marked Applied everywhere 1.5.0's migrations ran, and `run#PendingMigrations` skips applied
migrations. It must be forced. It is idempotent; a second run deletes nothing.

Any exclusion rule already saved against the old path keeps its stored expression and matches nothing
after the rename. Re-add it on the new pill with values `REQUESTED,OPEN`.

## API

`docs/api-contract/openapi.json` regenerates to `version: 1.5.1`. No method, parameter or shape
changes — the diff is one line.

## Rollback or fallback notes

- Setting `darpan.reconciliation.automation.verifyMissingDiffs` to false restores 1.5.0's *effective*
  scheduled behaviour (raw compare counts) without a redeploy, and now leaves a `NO_DATA` `VERIFY`
  step saying so rather than failing silently.
- Rolling `darpan` back to `v1.5.0` while leaving `shopify-darpan` at `v0.6.1` is safe: the extract
  carries an extra field nothing reads. The reverse — `darpan v1.5.1` against `shopify-darpan
  v0.6.0` — is the broken combination.
- The upgrade data is `createOrUpdate` and additive; there is no destructive load step. The forced
  `RETIRED_FIELDS` run deletes one seed row and is the only removal.

## Deferred items

- No live run. Real timing under three verification passes, and the actual count movement on a
  scheduled orders automation, are still owed.
- No browser verification of the rules-board column titles or the timezone codes; both are proven by
  spec and type only.
- No end-to-end test drives the *interactive* skip report; that path has no extractor injection seam
  and is covered at the shared seam and by compilation.
- A true differential test driving one request through both entry points remains blocked on
  `runSavedRunDiff.groovy`'s pipeline steps being script-local closures. `RunPathParityGateTest` is
  structural, not differential, and its class doc says so.
- `SftpAutomationSupport` is out of the parity gate's scope — a third input mode with no interactive
  counterpart.
- `TenantNotificationSetting` still carries `encrypt="true"` on its own `googleChatWebhookUrl`, three
  releases past the "remove after v1.2.0" in its description.
- GitHub release pages for the 1.4 line and this one are outstanding; `drpn-ai/darpan`'s newest
  published page is still `v1.3.0`.

## Verification

Recorded in `release-checklist.md`.
