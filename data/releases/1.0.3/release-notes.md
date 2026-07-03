# Darpan 1.0.3 Release Notes

Release date: `2026-07-03`

## Scope note

`v1.0.3` is a hotfix on the `v1.0.x` line that fixes provisioning of **fresh MySQL
databases**: the darpan security seed load and `NS_RESTLET_CONFIG` table creation both
failed on new environments (surfaced while provisioning the Steve Madden instance,
`sm-darpan-maarg`). Per the hotfix-consolidation rule (2026-07-03) this tag gets no
separate public release page — the public `v1.0.x` story is consolidated onto the
`v1.0.3` release page and the `v1.0.2` page is superseded (git tags remain).

Deferred to later releases (unchanged from v1.0.2): composite-key reconciliation
matching, run idempotency keys, returns reconciliation, and multi-file-source
stitching (see the public roadmap).

## Repo targets

- Backend repo: `drpn-ai/darpan` (`https://github.com/drpn-ai/darpan`)
- Branch: `main`; tag `v1.0.3` (exact commit in `release-checklist.md`)
- Compare range: `v1.0.2..v1.0.3`
- Companion component tags unchanged: `darpan-hotwax v0.3.0`, `shopify-darpan v0.3.0`,
  `netsuite-darpan v0.2.0`.
- UI: not included — `darpan-ui` versions and deploys independently.

## User-visible changes

- None directly. All three fixes affect provisioning and operations of new
  environments; existing running environments see no behavior change.

## Operator-visible changes

- Fresh MySQL databases now provision cleanly:
  - `NS_RESTLET_CONFIG` `CREATE TABLE` no longer exceeds MySQL's 65535-byte InnoDB row
    limit (large fields are now LOB/`MEDIUMTEXT`). No migration is needed on MySQL
    environments: the old `CREATE TABLE` always failed there, so no table exists and
    the fixed definition creates it on next boot. H2 dev databases keep the old
    column shape until recreated (harmless).
  - `types=darpan-seed` loads succeed on databases where FK constraints already exist:
    UserGroups are now defined before the ArtifactAuthz rows that reference them in
    `SecuritySeedData.xml`. Environments whose earlier darpan-seed load rolled back
    mid-file (symptom: `Error creating ArtifactAuthz [DARPAN_SCREEN_UI_SUPER_ADMIN]`)
    should re-run:
    `java -jar moqui-plus-runtime.war load conf=$CONF_FILE types=darpan-seed-initial,darpan-seed`
  - The `DB_LOAD` runbook env in both Dockerfiles now actually includes
    `darpan-seed-initial,darpan-seed` inside `-Ptypes`. The previous value glued them
    onto `-Praw` (a Gradle property no load task reads), so fresh deploys came up with
    no darpan seed data at all. `-Praw` is dropped as a documented no-op; loader flags
    such as `dummy-fks` go as MoquiStart args instead.
- Production images build from `DARPAN_REF=v1.0.3`; all other refs unchanged from
  v1.0.2 (`DARPAN_HOTWAX_REF=v0.3.0`, `SHOPIFY_DARPAN_REF=v0.3.0`,
  `NETSUITE_DARPAN_REF=v0.2.0`).

## Upgrade data

- No upgrade records in range — the seed reorder moves existing records without
  changing them, and the entity change is schema-only (candidate diff empty; see
  `upgrade-data-review.md`).
- Only operator action: environments where a pre-1.0.3 darpan-seed load rolled back
  should re-run the `types=darpan-seed-initial,darpan-seed` load shown above.

## Verification

- `xmllint --noout` on `SecuritySeedData.xml` and `ReconciliationEntities.xml`: passed.
- Programmatic forward-reference check on `SecuritySeedData.xml`: every ArtifactAuthz
  `userGroupId` resolves to an earlier definition or a framework group — passed.
- `AutomationEntityContractTests`: 7/7 passed (forced rerun via `cleanTest`).
- OpenAPI contract snapshot regenerated for the version bump (method set unchanged).
- `release_preflight.py validate --version 1.0.3`: passed (see checklist).
- Full backend test suite: NOT awaited before tagging (same hotfix-speed call as
  v1.0.2); CI runs on the pushed tag and is monitored after the fact.
- Live smoke: `sm-darpan-maarg` re-provisioning with this tag is the real-world
  validation path; not run at authoring time.

## Deferred items

- Composite-key reconciliation matching, run idempotency keys, returns
  reconciliation, multi-file-source stitching (public roadmap order).

## Rollback or fallback notes

- Rolling back to `v1.0.2` restores prior behavior everywhere except fresh-MySQL
  provisioning, which was already broken there — do not roll back environments that
  were provisioned on 1.0.3.
