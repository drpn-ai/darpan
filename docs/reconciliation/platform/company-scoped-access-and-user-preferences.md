# Company-Scoped Access and User-Level Preferences

## Goal

Introduce company-scoped shared-tenant access for company-sensitive Darpan data while preserving personal user-level preferences across devices and sessions.

## Overview

Darpan should separate two different concerns:

- Personal preferences that belong to an individual user
- Shared company-sensitive data that belongs to the company the user is actively working in

Pins are personal and should follow the user everywhere they log in. Company-sensitive records should be visible to all users in the same company and hidden from users outside that company. A user may belong to multiple companies and switch the active company from the user menu without logging out.

## Phase 1 Scope

### In Scope

- Runs
- Schemas
- Results
- SFTP settings
- NetSuite settings

### Out of Scope

- LLM and other global AI settings
- `HcReadDbConfig`
- enum/global admin settings
- an end-user "all companies" data view
- broader global-vs-company policy beyond the surfaces listed above

## Current Repo State

The current repo already has pieces of the desired behavior, but not the full model:

- dashboard pins are already persisted per user through `UserPreference`
- session scope for normal users is still effectively `customerScopeId = userId`
- schema access currently relies on `ownerUserId`
- generated outputs are stored in per-user runtime locations for non-admin users
- SFTP and NetSuite settings are currently treated as global/admin-only settings

This means user-level persistence exists, but company-level shared visibility does not.

## Core Principles

- Pins are always user-level
- Company-sensitive records are always company-level
- The active company controls both read visibility and write ownership
- A user can belong to multiple companies
- `ALL_USERS` must not be used as a company data-view scope
- Generic security groups such as `ADMIN` must not appear in the company switcher

## Company Model

Darpan should use `moqui.security.UserGroup` as the company model.

- Each company is represented by a `UserGroup`
- Users can belong to multiple company groups through `UserGroupMember`
- Company groups should be identified with a Darpan-specific `UserGroup.groupTypeEnumId`
- The active company should be stored as a user preference so it persists across sessions and devices

This avoids inventing a separate account model while still allowing explicit company selection.

## User-Level Preferences

Pins should remain user-scoped and should not depend on the active company.

This applies to:

- pinned runs
- pinned AI items
- any future pin-capable Darpan surfaces

Implementation rules:

- continue using `UserPreference`
- do not move pins onto company records
- do not clear or rewrite pins when the user switches company
- only render pinned items if the pinned target is visible in the current active company

## Company-Sensitive Ownership and Visibility

Company-sensitive records must be stamped with the active company when created and filtered by the active company when read.

Top-level records in scope should carry:

- `companyUserGroupId`
- `createdByUserId`

Phase 1 top-level records:

- `JsonSchema`
- `ReconciliationMapping`
- `SftpServer`
- `NsAuthConfig`
- `NsRestletConfig`

Child rows should inherit company scope from their parent rather than duplicating company ownership fields.

Visibility rules for normal users:

- list/get/update/delete only within the active company
- records from other companies are not visible
- new records are created in the active company

Admin behavior:

- retain operational admin access
- do not expose an end-user all-company selector in this phase

## Auth and Session Contract

The auth/session contract should shift from user-owned scope to active-company scope for company-sensitive data.

`login#Session` and `get#SessionInfo` should return:

- `scopeType`
- `activeCompanyUserGroupId`
- `activeCompanyLabel`
- `availableCompanies[]`

A new authenticated facade service should allow the frontend to switch active company and return refreshed session metadata.

The frontend should stop using `customerScopeId` as the source of data visibility for company-sensitive surfaces.

## Runtime Storage and Results

Results must become company-scoped, not user-scoped.

Required changes:

- stop using per-user generated-output folders for company-sensitive results
- write generated outputs to a company-scoped runtime location
- include `companyUserGroupId` in generated output metadata
- filter result list/get/delete operations by active company

This ensures users in the same company can see shared results while preserving company isolation.

## UI Behavior

The user popup should become the control point for active company switching.

Required behavior:

- show the current active company
- show all switchable company groups for the current user
- exclude generic security groups from the list
- switching company should refresh company-sensitive surfaces immediately

Surfaces that should refresh on company switch:

- dashboard runs
- run history
- run results
- schema pages
- SFTP pages
- NetSuite pages

Global settings that are out of scope for Phase 1 should remain unchanged by company switching.

## Migration Strategy

Migration should be controlled and conservative.

Recommended sequence:

1. Add company ownership fields and session/company-switch contract
2. Backfill legacy company-sensitive records to real company groups
3. Keep unmatched legacy records admin-only until assigned
4. Enforce active-company filtering after ownership is present
5. Move generated outputs to company-scoped storage after run ownership is stable

Do not introduce `ALL_USERS` as a fallback shared data scope.

## Acceptance Criteria

- A user with one company gets a valid default active company
- A user with multiple companies can switch active company without logging out
- The active company persists across devices and sessions
- Pins remain personal and persist across devices and sessions
- Users in the same company can see shared runs, schemas, results, SFTP settings, and NetSuite settings
- Users outside that company cannot access those records
- New company-sensitive records are stamped with the active company
- `ALL_USERS` never appears as a selectable company or data-view scope

## Assumptions

- Company groups are identified with a Darpan-specific `UserGroup.groupTypeEnumId`
- The last active company is stored in `UserPreference`
- Phase 1 company-sensitive scope is limited to `RUN`, `SCHEMA`, `RESULTS`, `SFTP`, and `NETSUITE`
- Global settings outside that scope are intentionally deferred
