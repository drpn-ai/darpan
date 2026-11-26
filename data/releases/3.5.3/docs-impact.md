# Docs Impact For Darpan 3.5.3

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-379 | A sync pulse runs on its own lane, never behind the audit | guides/peak-periods-and-sync-pulse.mdx (Today row) | none: no field changed | none: no new message | none: no connection change |
| DAR-BE-320 | A daily NetSuite-only audit check reads what changed since its last complete read | guides/audits.mdx (what a morning check reads) | none: the two new check fields are not settable in the app yet | none: no new message | none: the NetSuite connection is unchanged |
| DAR-UI-152 (migration fix) | The note migration runs outside the caller's transaction | none: the audit guide already says the note is optional (changed in 3.4.16) | none: no field changed | none: no user message | none: no connection change |
| DAR-BE-374 (ratchet) | Authz ratchet re-baselined after review | none: test-only | none: test-only | none: test-only | none: test-only |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no facade method changed (the darpan-hotwax service is internal, not remote)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.3
- `api-reference/changelog.mdx`: 3.5.3 under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: backend/operations-and-validation.mdx (run_SyncPulse: the light slot)
- Self-host and deploy pages: self-host/production.mdx (3.5.3: three pins, load order, 5 upgrade records, the new setting)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.3
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.3. "Up next" unchanged.
