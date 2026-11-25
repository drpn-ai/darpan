# Upgrade Data Review For Darpan 3.4.7

## Scope

- Backend compare range: `v3.4.6..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/OpsNoticeSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_NOTICE|blockId=client.runfailed`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_NOTICE" blockId="client.runfailed" defaultText="${name} did not finish. Darpan has been alerted and is working on a fix."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
