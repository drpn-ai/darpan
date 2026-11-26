# Docs Impact For Darpan 3.4.15

Every shipped item needs a decision in each column: the page path you changed,
or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-373 | The approved-orders check's brain gives causes to completed orders and can name a retired SKU or a product missing in NetSuite | none: causes are tenant config; no page lists a check's causes | none: no reference page lists brain questions | none: no new message | none: connections and reads unchanged |
| DAR-CLI-008 (editor-only change proposals) | `get/list#AuditChangeProposal(s)` refuse a view-only user | none: change list/show already documented for editors | none: the CLI page already says proposals are for editors | none: the refusal is the standard no-edit-access message | none |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `get#AuditChangeProposal` and `list#AuditChangeProposals` rows say editors and admins only (3.4.15)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.15
- `api-reference/changelog.mdx`: 3.4.15 named among the releases that added or removed no methods; get/list#AuditChangeProposal(s) need edit access

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine or data change (brain rules are in the pack, not on a page)
- Self-host and deploy pages: self-host/production.mdx (3.4.15 under "Upgrading to 3.4": darpan only, no upgrade data)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.15
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.15
