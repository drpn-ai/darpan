# ADR 0001 — Darpan is an intentional modular monolith

Date: 2026-07-02 · Status: accepted

## Decision

Darpan ships as **one Moqui deployable** composed of four components — `darpan` (core) plus
the integration plugins `darpan-hotwax`, `shopify-darpan`, `netsuite-darpan` — with a
decoupled Vue SPA in front. We do **not** pursue microservices extraction. Maturity work
targets operational discipline and clean in-process boundaries, not process boundaries.

## Why

- Bitronix XA transactions and shared tenant entities make independent deployment of the
  reconciliation domain a rewrite, not an extraction.
- The scale pressure (per-tenant reconciliation batches) is handled by Spark inside the
  JVM; no component has independent scaling requirements today.
- The MACH audit scored the headless boundary 4/5 — the SPA/facade seam is the boundary
  that pays rent. The costs are operational (CI, observability, config), not topological.

## Boundary rules (enforced)

1. **Plugins depend on core; core never depends on a concrete plugin.**
   Current debt (frozen, ratcheted by `EntityOwnershipGuardTest`): core reads
   `darpan.hotwax.HotWaxOmsRestSourceConfig` and `darpan.shopify.ShopifyAuthConfig` by
   name. New core→plugin references fail the build; remove the debt by publishing
   owner-side read services, then shrink the baseline.
2. **No duplicate entity definitions across components** (H12.1) — build-fails.
3. **Cross-component dispatch goes through the SourceSystemConnector registry**, never
   hardcoded service literals (automation + interactive paths both resolve through it).
4. **The remote surface is the generated facade contract** (`docs/api-contract/`);
   integration components contribute facade services in their own `service/facade/`.

## Extraction trigger (explicit)

Revisit this ADR only if one of these becomes true:

- reconciliation compute requires elastic scale-out that Spark-in-JVM plus vertical
  scaling cannot serve;
- a fault-isolation incident shows a plugin failure taking down the core facade;
- a team boundary forms that needs independent release cadence for one component.

Until then, "extract a service" proposals should instead land as boundary cleanups inside
the monolith (registry entries, SPI ports, owner-side services).
