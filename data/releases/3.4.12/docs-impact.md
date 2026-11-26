# Docs Impact For Darpan 3.4.12

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-371 | A finding shows only its check's compared systems, named whether or not it ran | guides/audits.mdx (basics: the compared systems only) | none: no status, field or term changed | none: no new message | none: no connection change |
| DAR-BE-372 | Finding card rules: Missing, carried HotWax status, Line total, day from NetSuite, same rows, line names; lookup record kept | guides/audits.mdx (basics, rows per kind, line names) | none: no status, field or term changed | none: no new message | none: no connection change |
| DAR-UI-149 | A statement shows its count on a morning its check did not run | guides/audits.mdx (Statements column) | none: the not checked pill is unchanged | none: no new message | none: no connection change |
| DAR-UI-150 / DAR-UI-151 | Per-system cards removed; same detail rows per kind | guides/audits.mdx (finding paragraph) | none: no status, field or term changed | none: no new message | none: no connection change |
| DAR-CLI-008 part 2 | Methods for proposing an audit change yourself (CLI `0.4.0`) | none: CLI 0.4.0 follows the deploy; its session updates getting-started/set-up-darpan-cli.mdx then | none: CLI 0.4.0 follows the deploy | none: CLI 0.4.0 follows the deploy | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `propose#AuditChange`, `get#AuditChangeProposal`, `list#AuditChangeProposals`, `find#AuditFindingLookalikes`, `apply#AuditChange` (`confirmedIds`), `get#AuditReport` (`systems`)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.12
- `api-reference/changelog.mdx`: a 3.4.12 entry under contract 5 (237 methods)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine or data change
- Self-host and deploy pages: self-host/production.mdx (3.4.12 under "Upgrading to 3.4": darpan only, app v4.4.14, no upgrade data, no migration)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.12 and app v4.4.14, with its items
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.12. "Up next" unchanged.
