# Upgrade Data Review For Darpan 3.2.0

## Scope

- Backend compare range: `v3.1.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_TENANT_USER|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_TENANT_USER" description="Can view tenant pages, upload files, run audits, saved runs and reconciliations, resolve findings, and add, edit or delete connections for associated tenants" groupTypeEnumId="UgtDarpanPermission"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `darpan.auth.TwoFactorRolePolicy|permissionGroupId=EVERYONE`
- Element: `darpan.auth.TwoFactorRolePolicy`

```xml
<darpan.auth.TwoFactorRolePolicy permissionGroupId="EVERYONE"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_2FA_REMINDER|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_2FA_REMINDER" description="Darpan two-factor setup reminder" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="${subjectLine}" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TwoFactorReminder.xml"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=send_TwoFactorReminders_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="send_TwoFactorReminders_daily" description="Remind everyone two-factor is required for, who has not set up a method, on the 7/3/1/0-days-before then weekly cadence." serviceName="auth.TwoFactorServices.send#TwoFactorReminders" cronExpression="0 0 15 * * ?" paused="N"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
