# Release Checklist For Darpan 1.5.1

## Scope

Hotfix on the 1.5 line. Three repos re-cut; three left alone.

| Repo | From | To | Content commits |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v1.5.0` | `v1.5.1` | 9 |
| `drpn-ai/shopify-darpan` | `v0.6.0` | `v0.6.1` | 4 |
| `drpn-ai/darpan-ui` | `v2.6.0` | `v2.6.1` | 3 |
| `drpn-ai/darpan-hotwax` | `v0.8.0` | — | 0 |
| `drpn-ai/netsuite-darpan` | `v0.2.0` | — | 0 |
| `drpn-ai/database-darpan` | `v0.2.0` | — | 0 |

All content commits were already pushed to `main` in each repo before the cut began; nothing was
authored for this release beyond the release metadata below.

## Release metadata changed

- `runtime/component/darpan/component.xml` — `1.5.0` → `1.5.1`
- `runtime/component/darpan/docs/api-contract/openapi.json` — regenerated; diff is the version string only
- `runtime/component/darpan/data/upgrade-data.xml` — rebuilt as a 2-record delta
- `runtime/component/darpan/data/releases/1.5.1/` — new pack (this file, notes, changelog, review, upgrade data)
- `runtime/component/shopify-darpan/component.xml` — `0.6.0` → `0.6.1`
- `darpan-ui/package.json`, `darpan-ui/package-lock.json` — `2.6.0` → `2.6.1`
- `darpan-docs/releases/updates.mdx` — 1.5 line consolidated into one section, renamed to `v1.5.1`
- `darpan-docs/releases/roadmap.mdx` — recently-shipped card rotated to the 1.5 line

## Upgrade data

- Rebuilt as a **delta**, not appended to. The file inherited from `main` was `1.5.0`'s 54-record
  cumulative pack with `1.5.1`'s records appended and its header count edited to 55, which would have
  told three already-migrated environments to re-load 54 records they had applied on 2026-08-27.
- `data/releases/1.5.0/upgrade-data.xml` confirmed byte-identical to `git show v1.5.0:data/upgrade-data.xml`
  before rebuilding, so the delta's baseline is the real tagged file.
- Both records confirmed byte-identical to their generic source declarations in
  `data/SourceSystemConnectorSeedData.xml` and `data/SourceSystemConnectorFieldSeedData.xml`. One
  divergence was found and corrected: the appended field record had dropped the
  `SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS` pointer from its `description`, so the
  default deploy's `darpan-seed` self-load and the upgrade load would have written different help
  text to the same column depending on which ran last.
- Ordering hand-checked: `SourceSystemConnector` before `SourceSystemConnectorField`. No
  `Enumeration` chain applies — the parents shipped in `1.5.0`.
- `xml.dom.minidom` parse — OK. Record count asserted at 2.

## Verification

Run 2026-08-31 from the wrapper root against `main` at the content HEADs above, JDK 21.0.11.

**Backend** — `./gradlew :runtime:component:darpan:unitTest --rerun :runtime:component:darpan:smokeTest --rerun :runtime:component:shopify-darpan:test --rerun --continue`

`BUILD SUCCESSFUL in 3m 56s`, 8 tasks executed, exit 0.

| Pool | Classes | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| `darpan:unitTest` | 81 | 908 | 0 | 0 | 0 |
| `darpan:smokeTest` | 48 | 393 | 0 | 0 | 0 |
| `shopify-darpan:test` | 15 | 143 | 0 | 0 | 0 |

**App** — `cd darpan-ui && npm run check`, exit 0. Ran `eslint . --max-warnings=0`, the stylelint
design-system gate at `--max-warnings=0`, `vue-tsc --build --force`, and `vitest run --coverage`:
113 files, 1191 tests, 0 failures. Executed after the `2.6.1` bump — the npm lifecycle banner reports
`darpan-ui@2.6.1`.

Counts read from the JUnit result XMLs, not from console summaries, and every result file was checked
for zero length and parse failure (0 of 144 unparseable).

### Two false readings caught during this cut, recorded so the next one expects them

1. **A hollow green.** The first backend invocation returned `BUILD SUCCESSFUL in 14s` with
   `unitTest UP-TO-DATE`. `component.xml` and `data/upgrade-data.xml` are not declared inputs of the
   test tasks, so Gradle skipped execution — and result XMLs from 10:45 that morning made the counts
   read as freshly green. The re-run used `--rerun` per task. On a release cut, exit 0 means nothing
   until the tasks are confirmed *executed*.
2. **A stale failure.** `build/test-results/test/` reports 215 classes / 1303 tests / **116 failures**
   and is dated **2026-08-27**. The unsplit `test` task was not invoked by this cut; `testAll`
   (`unitTest` + `smokeTest`) has the same coverage per `build.gradle`. Those 116 are a four-day-old
   artifact and are not a current result. The directory was left in place rather than deleted.

Also noted: the first re-run reported exit 0 while Gradle had actually failed, because the command
appended `; echo "EXIT=$?"`, which returns the echo's status. The failure it hid was real — the API
contract had gone stale against the bumped version — and was fixed by regenerating it.

## Not verified

- **No live run.** Real timing under three verification passes, and the actual count movement on a
  scheduled orders automation, are still owed. This is the same gap `1.5.0` carried.
- **No browser verification.** The rules-board column titles and the timezone codes are proven by
  spec and type only.
- **No upgrade-data load** against any database, local or remote. Ordering is argued, not observed.
- **The forced `RETIRED_FIELDS` re-run** has not been exercised on an environment where the migration
  is already Applied — which is every environment that will receive this release.
- **`darpan:mysqlTest` not run** — 4 tests in `LoginDeadlockRegressionTests`, tagged `mysql` and
  excluded from both pools. It reproduces InnoDB FK lock behaviour H2 cannot and needs a reachable
  `darpan_test` MySQL. This is why the smoke pool announces 49 classes and 48 report.
- **No `darpan-docs` build.** `updates.mdx` and `roadmap.mdx` were edited as text; Mintlify was not
  run over them. (Mintlify's CLI needs the node@20 keg.)

## Known gaps carried forward

- A true differential test driving one request through both run entry points remains blocked on
  `runSavedRunDiff.groovy`'s pipeline steps being script-local closures. `RunPathParityGateTest` is
  structural, and its class doc says so.
- `SftpAutomationSupport` is outside the parity gate — a third input mode with no interactive
  counterpart.
- `TenantNotificationSetting` still carries `encrypt="true"` on its own `googleChatWebhookUrl`, three
  releases past the "remove after v1.2.0" in its description.
- GitHub release pages are outstanding for the whole 1.4 and 1.5 lines: `drpn-ai/darpan`'s newest
  published page is `v1.3.0`, `darpan-ui`'s is `v2.4.0`, and `shopify-darpan`'s is `v0.2.0`.

## Approval

Prepared and verified; **not tagged, not committed, not published**. Awaiting review.
