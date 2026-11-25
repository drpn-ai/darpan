# Upgrade Data Review For Darpan 3.4.9

## Scope

- Backend compare range: `v3.4.8..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_ACKNOWLEDGED|enumTypeId=AuditFindingEvent|enumCode=acknowledged`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_ACKNOWLEDGED" enumTypeId="AuditFindingEvent" enumCode="acknowledged" description="Acknowledged" sequenceNum="5"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_ACK_CHANGED|enumTypeId=AuditFindingEvent|enumCode=acknowledgementChanged`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_ACK_CHANGED" enumTypeId="AuditFindingEvent" enumCode="acknowledgementChanged" description="Acknowledgement changed" sequenceNum="6"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_ACK_REOPENED|enumTypeId=AuditFindingEvent|enumCode=acknowledgementReopened`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_ACK_REOPENED" enumTypeId="AuditFindingEvent" enumCode="acknowledgementReopened" description="Acknowledgement reopened" sequenceNum="7"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_DIGEST|blockId=acknowledged`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_DIGEST" blockId="acknowledged" defaultText="${count} acknowledged, parked until fixed or until the reminder day if one was set."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=acknowledged`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="acknowledged" defaultText="${count} acknowledged. Your team has these in hand until they are fixed, or until the reminder day if one was set."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=answer.intro`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="answer.intro" defaultText="Darpan's answer:"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=body`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="body" defaultText="Darpan has an answer to your note on ${statementCode}. Worth the wait, hopefully."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=greeting`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="greeting" defaultText="Hi ${firstName},"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=guides`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="guides" defaultText="Read the audit page|guides/audits#read-the-audit-page"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=next`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="next" defaultText="The answer lives in your inbox, not in the app. Forward it if your team was asking the same thing."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=note`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="note" defaultText="Still not right? Send a new note from the finding in Darpan."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_ANSWER|blockId=quote`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_ANSWER" blockId="quote" defaultText="You wrote: “${note}”"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=about`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="about" defaultText="On the finding: ${headline}"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=body`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="body" defaultText="Your note on ${statementCode} reached Darpan. Thanks for saying what the records couldn't."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=greeting`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="greeting" defaultText="Hi ${firstName},"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=guides`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="guides" defaultText="Read the audit page|guides/audits#read-the-audit-page"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=next`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="next" defaultText="A person at Darpan reads it and replies by email. Your team sees the note beside the finding for ${shownDays} days, so nobody has to ask twice."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=note`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="note" defaultText="More to add? Send another note from the same finding."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|blockId=quote`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_FEEDBACK_RECEIVED" blockId="quote" defaultText="“${note}”"/>
```

### Added in `data/FeedbackEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_FEEDBACK_ANSWER|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_FEEDBACK_ANSWER" description="Darpan feedback answer" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Darpan answered your note on ${statementCode}" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/FeedbackAnswer.xml"/>
```

### Added in `data/FeedbackEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_FEEDBACK_RECEIVED|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_FEEDBACK_RECEIVED" description="Darpan feedback received" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Darpan got your note on ${statementCode}" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/FeedbackReceived.xml"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_ACK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_ACK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingAcknowledgement" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_FEEDBACK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_FEEDBACK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingFeedback" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

## Final decision

- All 23 candidates ship. The three new `AuditFindingEvent` members change the audit vocabulary, so the pack restates the
  whole vocabulary from `data/AuditSeedData.xml` (10 types, 33 members, each type ahead of its members), as
  `AuditCatalogContractTests.theUpgradePackRestatesTheVocabularyVerbatim` requires: 63 records.
- Load order: vocabulary, the two `EmailTemplate` rows, their `EmailCopyBlock` rows, the two `EntityFilter` rows.
- Not restated: rows unchanged since `v3.4.8` (the digest job, 3.4.8's SHOPIFY connector row), already on prod.

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
