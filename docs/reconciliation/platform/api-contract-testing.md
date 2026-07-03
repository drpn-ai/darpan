# Facade API Contract Testing

## Purpose

Pin the Darpan facade API contract so that an accidental breaking change — a renamed or
removed parameter, a changed response shape — **fails CI** instead of silently breaking the
SPA at runtime.

Before this work nothing pinned the contract. The backend had only *behavioral* smoke tests
(they assert `ok`/`errors` and a few values, not the full shape), and the SPA cast JSON-RPC
responses straight to TypeScript types (`as T`) with no runtime validation. Net effect: drop or
rename a facade `out-parameter` and every test stayed green while the PWA broke in production.

This is the MACH "API-first / contracts" P1 hardening. It is purely additive — no production
code or behavior changed; only tests and a snapshot artifact were added.

## The contract surface

The product API is the JSON-RPC facade: `facade.*FacadeServices.*` over `POST /rpc/json`
(and `/qapps/darpan/rpc/json`), defined in `service/facade/*.xml`
(Auth / JsonSchema / Reconciliation / Settings / Search). ~57 services carry
`allow-remote="true"`. The contract of each service is its declared `<in-parameters>` and
`<out-parameters>` (name, type, required) plus `authenticate` / `allow-remote`.

## Backend enforcement — contract snapshot test

`src/test/groovy/darpan/facade/FacadeContractSnapshotTests.groovy` (commit `8677a3f`).

- A **pure XML-parse** test — it does **not** boot Moqui and needs no database, so it is fast
  and does not contend for the Bitronix `txlog` lock that a running dev stack holds.
- It parses every `<service>` in `service/facade/*.xml`, captures `verb`, `noun`, `type`,
  `authenticate`, `allow-remote`, and the IN/OUT parameters (name, type, required — one level
  of nested params included), renders a deterministic canonical text, and compares it to a
  checked-in snapshot.
- On any drift it fails with a line-level diff and the remediation instruction.

### The contract catalog

`src/test/resources/facade-contract.snapshot.txt` is the committed snapshot. It is also a
**human-readable, self-verifying contract catalog**: every facade service with its full
parameter contract, sorted deterministically (~1,669 lines covering all captured services).
Because the test fails when XML and snapshot diverge, the catalog cannot silently drift from
the code.

### Regenerating after an intentional contract change

```
CONTRACT_SNAPSHOT_UPDATE=true \
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :runtime:component:darpan:test \
  --tests "darpan.facade.FacadeContractSnapshotTests"
```

Then review the diff in `facade-contract.snapshot.txt` and commit it **with** the service
change. Reviewing that diff is the point: a contract change is now a visible, deliberate act.

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
