# Release Checklist For Darpan 3.3.0

Prepared 2026-11-18, 20:30 to 21:05 IST. Nothing pushed, tagged, deployed or published.

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-303, DAR-BE-304, DAR-BE-305, DAR-BE-306, DAR-BE-307,
  DAR-BE-313 (all ready-to-ship, pushed by 20:24 IST), DAR-BE-315 (added by Aditi the same evening; committed and
  approved as `dc80d9e9`, under the release commits), and
  the app riders on `main`: DAR-UI-120 to 129 and 131, with DAR-UI-123's backend methods (darpan 25af1bc2).
- Open release issues: 1
  1. The darpan-docs guides for the reworked settings pages name labels the app no longer shows (`docs-impact.md`,
     "Owed"); Aditi decides: a docs pass before the cut, or a named follow-up after it.
- Scope changes recorded: Aditi chose one 3.3.0 for the six DAR-BE items (2026-11-18 19:35 IST) and added DAR-BE-315
  (20:3x IST).
- Deferred items: `release-notes.md` "Deferred items".

## Release notes

- User-facing release notes: `release-notes.md` (outcome and operator steps, no mistake narratives, no bold). Public
  notes: darpan-docs `releases/updates.mdx`, one `## v3.3.0` section, and `releases/roadmap.mdx` (Recently shipped =
  v3.3.0, v3.2.1; Up next unchanged), commit `1e05972`.
- Operator-visible changes reviewed: yes, including the deploy order (backend, darpan-ui, darpan-admin-ui), the
  migration, the retention dry run, the agent price rule, and every prod-owed step of every item ("Operator actions
  after deploy").

## Technical changelog

- Curated from the compare ranges: yes, `technical-changelog.md`. Compare URLs captured, one per tagged repo.

## Upgrade data

- Generated with `release_preflight.py generate` in a clean detached worktree of darpan `6cc1a6ac`
  (`v3.2.1..HEAD`): 1,948 records, 0 lines of the uncommitted DAR-ADMIN-012 work.
- Tooling defect found and fixed first: the generator collapsed the mascot seed (500 entries -> 69, 1,289 anchors ->
  813) because their keys do not end in "id"; darpan-workflows `cbae375` (local commit) adds the key names.
- Candidate diff reviewed: yes, `upgrade-data-review.md`. One FK reorder by hand (the job parameter after its job,
  ORDERING NOTE in the file); the `apply#JobDefaults` call is last; the `RETIRED_JOB_PARAMETERS` row is present; no job
  row states `cronExpression`, `paused` or `parameterValue`.
- Previous upgrade data archived: `data/releases/3.2.1/upgrade-data.xml`, byte-identical to `v3.2.1`'s current file
  (unchanged).
- Final load path: first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y` (every record is also
  in a boot-loaded `darpan-seed` file); otherwise `./gradlew loadDarpanUpgradeData` once.
- The working copy's `data/upgrade-data.xml` = the release file + the DAR-ADMIN-012 block before the closing tag; the
  release commit carries only the release file.
- `release_preflight.py validate` (`v3.2.1..6cc1a6ac` and `v3.2.1..dc80d9e9`): every check passes except the
  open-issues line above (proved by validating a copy with that line at 0: "passed validation").
- Re-generated at 21:2x IST in a clean worktree of the release commit `d9e41958` (on top of DAR-BE-315's
  `dc80d9e9`): the same 1,948 records; the only difference from the committed file is the hand-moved job parameter
  row and its ORDERING NOTE. DAR-BE-315 changed no `data/` file. After DAR-BE-315, re-run in a
  clean worktree of the final head: `release_preflight.py validate --version 3.3.0 --backend-repo <wt>
  --backend-previous-ref v3.2.1 --backend-current-ref HEAD`; it compares the pack's records with the seed diff, so it
  fails if 315 changed seed data.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes; DAR-UI-121 to 128's guide pages are "Owed".
- darpan-docs `1e05972`: updates, roadmap, and the three sign-in pages (DAR-UI-120). `mint broken-links` and
  `mint validate` pass under Node 20.
- darpan-developers `4edb896` (+ `e1a16b6`): service catalog (33 methods; 301 of 301 documented), API changelog,
  public OpenAPI at 3.3.0 (208 public methods), self-host retention batch size. `mint broken-links` passes.
- `verify_release_docs.py --version 3.3.0 --offline` (20:58 IST): exit 1. PASS release notes, graphql log, public
  openapi (3.3.0), runtime stack (Spark 4.2.1, Drools 10.2.0). FAIL service catalog: only the 28 uncommitted
  DAR-ADMIN-012 admin methods in the working-tree `methods.txt` (against the release `methods.txt`:
  301 of 301). FAIL docs impact: only the owed guide pages. FAIL published: both docs repos are unpushed (the cut
  pushes them).
- GraphQL pages vs the local stack (`generate_graphql_reference.py --check`, stack up since 18:59 IST from the darpan
  working tree): "deployed SDL matches the snapshot; 55 generated pages and the navigation are current".
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`): owed.

## Verification

- Contract: `check_contract_compat.py` v3.2.1 -> release contract (HEAD + version 3.3.0): compatible, 33 added, 268
  preserved, version 4. Version line moved to 3.3.0 in darpan, darpan-ui (`2a7348b`) and darpan-admin-ui
  (`3694444`), committed without the DAR-ADMIN-012 methods. darpan-ui's contract copy is byte-identical to the
  release contract; darpan-admin-ui's lacks DAR-UI-123's three facade methods, which it does not call.
- Backend, JDK 21, `--rerun`, under the gradle lock (taken 20:53, released 20:55 IST), result XMLs 20:55:02:
  ApiContractGeneratorTests 8/8, FacadeContractSnapshotTests 1/1, DisableAuthzRatchetTest 2/2,
  TenantOwnedEntityFilterContractTests 5/5, CliMethodAllowlistTests 4/6. Both failures come from the working-tree
  contract's 28 uncommitted DAR-ADMIN-012 admin methods (`everyMethodInTheContractIsInExactlyOneList` names only those;
  `noAdminMethodIsAllowed` asserts every admin method is excluded); all 301 release-contract methods are placed in
  the allowlist (checked). The test classes were compiled from the shared working tree, which holds DAR-BE-315's
  in-progress email files.
- Email and sign-in, JDK 21, `--rerun`, under the gradle lock (21:28 to 21:29 IST, released), on the final tree
  (`d9e41958` + the uncommitted DAR-ADMIN-012 files, none of them email or auth code), result XMLs 21:29:12:
  PasswordResetServicesSmokeTests 34/34, TwoFactorReminderSmokeTests 9/9, EmailTemplatesRenderTests 26/26. The
  transient "fixture password refused" failure an earlier run saw did not reproduce.
- darpan-ui: `npm run type-check` exit 0; vitest `contractDrift` + `registryContract` 20/20; `npm run
  mascot:seed:check` pass.
- darpan-admin-ui, on a clean worktree of the committed tree + the version line: `npm run type-check` exit 0;
  vitest `contractDrift` 5/5.
- Deploy pins: `verify_release_pins.py` (pre-tag, 20:46 IST): exit 1 only because `v3.3.0` (darpan), `v0.4.0`
  (darpan-agent) and docker-config `v3.3.0` are not on the remotes yet; every other pin resolves, and `DARPAN_REF`
  equals `component.xml`'s 3.3.0. It must re-run after the tags are pushed (the cut script does).
- Not run: the full suites (focused-tests rule); loading the upgrade file
  into an empty database; any browser check; the SDL against prod.

## Approval

- Release owner sign-off: pending Aditi.
- Cut/tag blocked until this checklist is fully complete: yes. Expected heads: darpan = the pack-results commit on top of `d9e41958` (the
  cut script names it);
  darpan-agent `d7ad806`, darpan-ui `2a7348b`, darpan-docker-config `81c3551`, darpan-admin-ui `3694444`, darpan-docs
  `1e05972`, darpan-developers `4edb896`. Cut script:
  `/private/tmp/claude-501/-Users-aditipatel-sandbox-darpan-master/c9cb16fa-b46a-4600-99fb-9fc3d0fa68f8/scratchpad/cut-3.3.0.sh`.
