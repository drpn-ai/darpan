# Docs Impact For Darpan 3.4.13

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-372 (fix) | A finding keeps the record its check looked up; a NetSuite line names its ship method | none: guides/audits.mdx already says each compared system's status; this makes R13 match it | none: no status, field or term changed | none: no new message | none: no connection change |
| DAR-BE-375 | Re-check all findings reports its progress again | none: guides/audits.mdx already describes the re-check as it now runs | none: no field changed | none: the error it removes was never documented | none: no connection change |
| DAR-CLI-008 part 3 | Order lookup across every connected system (CLI `0.5.0`) | none: CLI 0.5.0 follows the deploy; its session writes the lookup section then | none: CLI 0.5.0 follows the deploy | none: CLI 0.5.0 follows the deploy | none: reads use the existing connections |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: "Order lookup" section with the six `facade.LookupFacadeServices` methods
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.13
- `api-reference/changelog.mdx`: a 3.4.13 entry under contract 5 (243 methods)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: the lookup engine is documented by the service catalog section and CLI 0.5.0's page
- Self-host and deploy pages: self-host/production.mdx (3.4.13: darpan, darpan-hotwax 1.3.5, shopify-darpan 0.11.3, netsuite-darpan 0.7.2 together; 2 upgrade records; tables created on boot)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.13 with its items
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.13. "Up next" unchanged.
