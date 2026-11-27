# Upgrade Data Review For Darpan 3.5.9

## Scope

- Backend compare range: `v3.5.8..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=logs.ignored`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="logs.ignored" defaultText="Left out ${count} log lines that match the ignore patterns (Errors settings)."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=logs.ignored.more`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="logs.ignored.more" defaultText="${count} more not shown."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
