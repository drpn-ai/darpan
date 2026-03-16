# Standalone Reconciliation Platform - Service Overview

## Goal

Move reconciliation out of OMS into a standalone Moqui platform while keeping integrations stable and data isolated per client.

## Core Services and Purpose

- Ingestion Service: accept POS and OMS extracts (SFTP/API) and normalize into canonical records
- Reconciliation Service: run rules against canonical records and produce match, missing, and mismatch outcomes
- Reporting Service: assemble outputs (CSV or queryable views) and publish to configured destinations
- Integration Service: manage outbound deliveries (SFTP push, API callbacks) and status tracking
- Configuration Service: store tenant-specific rules, schedules, and mapping configurations
- Audit and Monitoring Service: record runs, rule versions used, and operational metrics

## Current Repo Footprint

- Configuration entities (Reconciliation, Run, RuleSet, etc): `runtime/component/darpan/entity/ReconciliationEntities.xml`
- Sample reconciliation compare service: `runtime/component/darpan/service/debug/ReconciliationDebugServices.xml`
- Inventory retrieval services (NS Restlet + read-only DB): `runtime/component/darpan/service/reconciliation/ReconciliationInventoryServices.xml`
- Spark-based compare implementation: `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/compareSampleOrderIds.groovy`
- Sample input data: `runtime/component/darpan/data/sample/omsData.json`, `runtime/component/darpan/data/sample/shopifyData.json`

## UI Facade Contracts (Wave 1)

- Wave 1 authenticated facade APIs for `darpan-ui` (Settings + JSON Schema):
  `runtime/component/darpan/docs/reconciliation/platform/facade-wave1-services.md`

## Platform Boundaries

- No direct reads from OMS or POS databases
- All heavy computation occurs inside the platform database and services
- Tenant isolation enforced at storage and rule execution levels
