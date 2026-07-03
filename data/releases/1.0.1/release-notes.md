# Darpan 1.0.1 Release Notes

Release date: `2026-07-03`

## Scope note

Patch release that makes the production image build work from the tag's own tree.
No product code changes: `v1.0.0`'s application content is unchanged; only build
metadata, the release pack, and Docker ref pins moved.

`v1.0.0`'s `docker/prod/Dockerfile` pinned `darpan-hotwax v0.2.0` and
`shopify-darpan v0.2.1`, which predate the JDK 21 / Moqui 4 / Gradle 9 migration and
still declare the removed `:runtime:component:moqui-atomikos` project — the image
build failed at Gradle evaluation. This tag pins the new post-migration companion
tags instead.

## Repo targets

- Backend repo: `drpn-ai/darpan` (`https://github.com/drpn-ai/darpan`)
- Branch: `main`; tag `v1.0.1` on the release-prep commit
- Compare range: `v1.0.0..v1.0.1`
- Companion tags cut with this release: `darpan-hotwax v0.3.0`, `shopify-darpan v0.3.0`
  (both at the component mains that darpan CI proves against this code).
- UI: not included.

## User-visible changes

- None.

## Operator-visible changes

- Production images build directly from this tag with no build-arg overrides:
  `DARPAN_REF=v1.0.1`, `DARPAN_HOTWAX_REF=v0.3.0`, `SHOPIFY_DARPAN_REF=v0.3.0`,
  `NETSUITE_DARPAN_REF=v0.1.0` (unchanged — that tag carries no `build.gradle`, so it
  does not participate in Gradle evaluation; its main is ahead and gets re-pinned at
  its next tag).
- Do not build prod images from the `v1.0.0` tag's Dockerfile; use this tag.

## Upgrade data

- None. No seed changes in `v1.0.0..v1.0.1`; `data/upgrade-data.xml` remains an empty
  candidate. The `1.0.0` current file was archived at
  `data/releases/1.0.0/upgrade-data.xml`. No operator data-load action.

## Verification

- Release pack validation, XML well-formedness, `compileGroovy`, and the
  `checkApiContract` gate passed locally; the full backend test suite ran green in CI
  on the tagged commit before the tag was placed. Live-deploy smoke not run (no
  deployed behavior changes).

## Deferred items

- Unchanged from `v1.0.0`: composite-key matching, idempotency keys, returns
  reconciliation, multi-file-source stitching, `darpan-ui` old-tag decision.
- `netsuite-darpan` re-pin to a post-migration tag when one is cut.

## Rollback or fallback notes

- Rollback target: `v1.0.0` product code is identical; if this tag must be abandoned,
  rebuild with `--build-arg DARPAN_REF=v1.0.0` plus the corrected companion overrides
  (`DARPAN_HOTWAX_REF=v0.3.0`, `SHOPIFY_DARPAN_REF=v0.3.0`).
