# Technical Changelog For Darpan 2.1.4

Release date: 2026-10-28

## Versioning decision

- Patch on the 2.1 line. Facade contract unchanged; `component.xml` 2.1.3 -> 2.1.4, shopify-darpan 0.8.2 -> 0.8.3.
- Per the hotfix rule, 2.1.4 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.3..05835c3` | 0 (plus the 2.1.4 version commit) |
| shopify-darpan | `v0.8.2..1a9d701` | 1 |
| darpan-docker-config | `v2.1.3..01b7cb6` | 0 (plus the 2.1.4 pin commit) |

## Backend

### Fixed

- DAR-BE-153 (shopify-darpan `1a9d701`): `ShopifyOrderPaymentSignal.paymentTransactionIds` treats a SUCCESS VOID
  naming an AUTHORIZATION as its parent like a SUCCESS CAPTURE: the authorization is no longer emitted. Test:
  `aVoidedAuthorizationIsNotAPayment` (a failed VOID keeps the authorization).

## UI

- No change; darpan-ui `v3.1.2` stays.

## Data and configuration

- No upgrade data.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.4`, `SHOPIFY_DARPAN_REF=v0.8.3`.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then re-enable the SM transaction rule after a test run; prod with its next deploy.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.3...v2.1.4
- https://github.com/drpn-ai/shopify-darpan/compare/v0.8.2...v0.8.3
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.3...v2.1.4
