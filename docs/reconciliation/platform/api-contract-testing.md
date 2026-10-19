# Facade API Contract Testing

## Purpose

Pin the Darpan facade API contract so that an accidental breaking change — a renamed or
removed parameter, a changed response shape — **fails CI** instead of silently breaking the
SPA at runtime.

Before this work nothing pinned the contract. The backend had only *behavioral* smoke tests
(they assert `ok`/`errors` and a few values, not the full shape), and the SPA cast JSON-RPC
responses straight to TypeScript types (`as T`) with no runtime validation. Net effect: drop or
rename a facade `out-parameter` and every test stayed green while the PWA broke in production.

Three gates now pin it: the backend snapshot test, the generated OpenAPI contract with its drift
and compatibility checks, and the SPA response-shape tests.

## The contract surface

The remote API is JSON-RPC over `POST /rpc/json`. `/rpc/json` dispatches only `facade.*`,
`admin.*` and `gql.*` methods (anything else gets HTTP 403; see
[api-access-policy.md](api-access-policy.md)). The contract of each service is its declared
`<in-parameters>` and `<out-parameters>` (name, type, required) plus `authenticate` /
`allow-remote`.

The generated method list, `docs/api-contract/methods.txt`, holds 140 methods today: 115
`facade.*` (darpan's own facades plus the darpan-hotwax, shopify-darpan and netsuite-darpan
facades) and 25 `admin.*`. `admin.*` is the operator surface and is not part of the public docs.

## Generated contract — OpenAPI + methods list

`src/main/groovy/darpan/contract/ApiContractGenerator.groovy` turns every `allow-remote="true"`
service under `service/facade/` (darpan and the three integration components) and
`service/admin/` into one JSON-RPC operation, and writes:

- `docs/api-contract/openapi.json` — OpenAPI 3.1, one `POST /rpc/json` operation whose request body
  is a union keyed by `method`; it carries `x-darpan-contract-version`.
- `docs/api-contract/methods.txt` — one method name per line.

It has no Moqui imports, so it runs standalone:

- `./gradlew :runtime:component:darpan:generateApiContract` — regenerate both files.
- `./gradlew :runtime:component:darpan:checkApiContract` — fail when the committed files drift
  from the XML. `ApiContractGeneratorTests` enforces the same gate inside `test`.
- Container parameters must declare `type="Map"` or `type="List"`; the generator fails closed on
  ambiguity.

**Compatibility gate.** On pull requests CI runs `scripts/check_contract_compat.py` against the
base branch's `openapi.json` and fails on a breaking change (removed method, removed or
newly-required request param, removed response field, type change) unless
`ApiContractGenerator.CONTRACT_VERSION` was bumped. Additive changes always pass. The version is
3 today (bumped in 1.5.0 when `list#TenantChatSpaces` dropped `googleChatWebhookUrlMasked`).

The generator does not yet cover database-darpan's facade, and it emits `servers: "/"`.

The `darpan-ui` and `darpan-admin-ui` repos keep copies of `methods.txt`; refresh them from this
file when the contract changes.

## Backend enforcement — contract snapshot test

`src/test/groovy/darpan/facade/FacadeContractSnapshotTests.groovy` (commit `8677a3f`).

- A **pure XML-parse** test — it does **not** boot Moqui and needs no database, so it is fast
  and does not contend for the Bitronix `txlog` lock that a running dev stack holds.
- It parses every `<service>` in darpan's own `service/facade/*.xml` (not `admin.*` and not the
  integration components' facades; those are covered by the generated contract above), captures `verb`, `noun`, `type`,
  `authenticate`, `allow-remote`, and the IN/OUT parameters (name, type, required — one level
  of nested params included), renders a deterministic canonical text, and compares it to a
  checked-in snapshot.
- On any drift it fails with a line-level diff and the remediation instruction.

### The contract catalog

`src/test/resources/facade-contract.snapshot.txt` is the committed snapshot. It is also a
**human-readable, self-verifying contract catalog**: every facade service with its full
parameter contract, sorted deterministically (about 2,700 lines today).
Because the test fails when XML and snapshot diverge, the catalog cannot silently drift from
the code.

### Regenerating after an intentional contract change

```
CONTRACT_SNAPSHOT_UPDATE=true \
  ./gradlew :runtime:component:darpan:test \
  --tests "darpan.facade.FacadeContractSnapshotTests"
```

Run it on JDK 21. Then review the diff in `facade-contract.snapshot.txt`, regenerate
`docs/api-contract/` with `generateApiContract`, and commit both **with** the service change. Reviewing that diff is the point: a contract change is now a visible, deliberate act.

## SPA enforcement — response-shape + registry tests

In `darpan-ui` (commit `2a1051a`), purely additive vitest specs:

- `src/lib/api/__tests__/responseContracts.spec.ts` — for the 6 SPA-critical endpoints
  (`login#Session`, `get#SessionInfo`, `list#SavedRuns`, `create#RuleSetRun`,
  `run#SavedRunDiff`, `get#TenantSettings`), build a realistic JSON-RPC body, drive it through
  the **real** client transport + facade wrapper, and assert every documented field and type
  is present. Also pins the client's `ok=false → ApiCallError` throw contract. If the wire
  shape diverges from the TS type, the fixture assertion fails.
- `src/lib/api/__tests__/registryContract.spec.ts` — asserts every method-registry entry is a
  well-formed `facade.*` string, there are no duplicates, and each maps to a wired facade
  wrapper (catches half-finished method additions).

## What is and is not guaranteed

- **Guaranteed:** the *declared* backend contract (XML in/out params) cannot change without a
  red test + a visible snapshot diff; the SPA's view of 6 critical response shapes is pinned;
  the method registry stays internally consistent.
- **Not yet guaranteed:** that a service's *runtime* output always matches its declared
  `<out-parameters>` (services can build ad-hoc result maps); full SPA runtime validation of
  every endpoint (the SPA still casts — only the 6 fixtures are pinned). See deferred work.

## Deferred hardening (tracked, not done)

- Structured, machine-readable error codes in the envelope (today errors are free-text and the
  framework collapses JSON-RPC `code` to 500/403; a discriminator must live in the result body).
- Consolidating the bypassed `FacadeSupport.envelope()` (defined, used 0×) and
  `PaginationSupport` (duplicated inline) helpers.
- Optional runtime boundary validation on the SPA for critical endpoints.
- See [api-access-policy.md](api-access-policy.md) for the public/internal split and versioning,
  which are larger and gated on a transport decision.
