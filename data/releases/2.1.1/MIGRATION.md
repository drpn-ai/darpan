# Darpan 2.1.1 — Schema Migration

Companion to `upgrade-data.xml` in this directory. The XML payload handles tenant-scope and authz **data**;
this document covers the **schema** changes that Moqui's entity facade auto-applies to *fresh* installs
(new install of 2.1.1 from the XML) but does **not** apply to *existing* production databases — operators
must run the DDL below.

All statements are MySQL-flavored. Translate column quoting if you target a different RDBMS.

---

## 1. JsonSchema — composite unique (companyUserGroupId, schemaName)

**Audit:** H1.6 / H12.7.

The previous `JSON_SCHEMA_NAME` index was unique on `schemaName` alone, making it a cross-tenant scarce
resource: tenant A creating `orders.json` blocked tenant B from creating their own `orders.json`. The
new entity declaration uses `JSON_SCHEMA_TENANT_NAME` on `(companyUserGroupId, schemaName)`.

```sql
ALTER TABLE JSON_SCHEMA
    DROP INDEX JSON_SCHEMA_NAME;

ALTER TABLE JSON_SCHEMA
    ADD UNIQUE INDEX JSON_SCHEMA_TENANT_NAME (COMPANY_USER_GROUP_ID, SCHEMA_NAME);
```

**Pre-flight check (run first):**
```sql
SELECT COMPANY_USER_GROUP_ID, SCHEMA_NAME, COUNT(*)
FROM JSON_SCHEMA
GROUP BY COMPANY_USER_GROUP_ID, SCHEMA_NAME
HAVING COUNT(*) > 1;
```
Resolve any rows the new constraint would reject before running the ALTER.

---

## 2. ReconciliationMapping — composite unique (companyUserGroupId, mappingName)

**Audit:** H12.5.

```sql
SELECT COMPANY_USER_GROUP_ID, MAPPING_NAME, COUNT(*)
FROM RECONCILIATION_MAPPING
GROUP BY COMPANY_USER_GROUP_ID, MAPPING_NAME
HAVING COUNT(*) > 1;   -- resolve duplicates first

ALTER TABLE RECONCILIATION_MAPPING
    ADD UNIQUE INDEX RECMAP_TENANT_NAME (COMPANY_USER_GROUP_ID, MAPPING_NAME);
```

---

## 3. RuleSet — composite unique (companyUserGroupId, ruleSetName)

**Audit:** H12.6.

```sql
SELECT COMPANY_USER_GROUP_ID, RULE_SET_NAME, COUNT(*)
FROM RULE_SET
GROUP BY COMPANY_USER_GROUP_ID, RULE_SET_NAME
HAVING COUNT(*) > 1;   -- resolve duplicates first

ALTER TABLE RULE_SET
    ADD UNIQUE INDEX RULESET_TENANT_NAME (COMPANY_USER_GROUP_ID, RULE_SET_NAME);
```

---

## 4. ReconciliationRunResult — tenant + created-date index

**Audit:** H9.3. List/typeahead patterns were full-scanning the table.

```sql
ALTER TABLE RECONCILIATION_RUN_RESULT
    ADD INDEX RECRES_TENANT_CREATED (COMPANY_USER_GROUP_ID, CREATED_DATE);
```

---

## 5. ReconciliationAutomationExecution — tenant + created-date index

**Audit:** H9.3.

```sql
ALTER TABLE RECONCILIATION_AUTOMATION_EXECUTION
    ADD INDEX RECAUTEX_TENANT_CREATED (COMPANY_USER_GROUP_ID, CREATED_DATE);
```

---

## 6. SftpServerTenantAccess — index on tenantUserGroupId

**Audit:** H9.5. `tenantUserGroupId` is the non-leftmost half of the composite PK, so lookups by tenant
alone full-scanned this join table.

```sql
ALTER TABLE SFTP_SERVER_TENANT_ACCESS
    ADD INDEX SFTPACCESS_TENANT (TENANT_USER_GROUP_ID);
```

---

## Rollback

All ALTERs above are non-destructive — drop the new indexes to revert. The pre-flight `HAVING COUNT(*) > 1`
queries identify any tenant/name collisions that must be resolved before the unique constraints can apply;
running those alone does not change anything.
