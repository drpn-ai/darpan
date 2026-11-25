# Upgrade Data Review For Darpan 3.4.6

## Scope

- Backend compare range: `v3.4.5..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=alerted`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="alerted" defaultText="Darpan has been alerted and is working on a fix."/>
```

### Modified in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=notrun`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="notrun" defaultText="${rules} did not run this morning."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=partly.notice`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="partly.notice" defaultText="${rules} partly ran this morning."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=rule.notrun`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="rule.notrun" defaultText="Did not run this morning."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=rule.partly`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="rule.partly" defaultText="${notRun} of ${total} checks did not run this morning."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=subject.unexplained`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="subject.unexplained" defaultText="${tenantName} ${auditName}: ${count} Unexplained findings"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=unexplained.body`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="unexplained.body" defaultText="Each one is a row in the attached CSV."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=unexplained.intro`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="unexplained.intro" defaultText="The ${date} run of ${tenantName} ${auditName} left ${count} findings with no cause. Unsolved, for now."/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=unexplained.next`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="unexplained.next" defaultText="Give one a conclusion and the next run files its lookalikes under it. One less mystery every morning."/>
```

### Added in `data/OpsNoticeSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_NOTICE|blockId=client.notrun`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_NOTICE" blockId="client.notrun" defaultText="${name} did not run this morning. Darpan has been alerted and is working on a fix."/>
```

### Added in `data/OpsNoticeSeedData.xml`

- Record: `moqui.basic.email.EmailServer|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailServer`

```xml
<moqui.basic.email.EmailServer emailServerId="SYSTEM"/>
```

### Added in `data/OpsNoticeSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_ERROR_NOTICE|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_ERROR_NOTICE" description="Darpan run did not run (chat line, not an email)" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Darpan run notice" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/ErrorNotice.xml"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=chat.failed`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="chat.failed" defaultText="Darpan errors today: ${checks} checks, ${logErrors} log errors. The email to ${addresses} could not be sent."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=chat.noemail`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="chat.noemail" defaultText="Darpan errors today: ${checks} checks, ${logErrors} log errors. No email was sent: no address is set."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=chat.sent`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="chat.sent" defaultText="Darpan errors today: ${checks} checks, ${logErrors} log errors. Email sent to ${addresses}."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=footer`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="footer" defaultText="Instance ${instance}. Covers ${since} to ${now}. Generated ${generatedAt}."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=guides`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="guides" defaultText="Automations and alerts|troubleshooting/automations-and-alerts&#10;Audits|troubleshooting/audits"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=intro`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="intro" defaultText="Since the last email, ${checks} checks stumbled and the log complained ${logErrors} times. Each one is below, in its own words."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=log.seen`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="log.seen" defaultText="${count} times on ${day} (UTC) so far, first ${first}, last ${last}."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=log.stack`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="log.stack" defaultText="Stack"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=logs.none`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="logs.none" defaultText="No log errors."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=logs.title`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="logs.title" defaultText="Log errors"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=none`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="none" defaultText="Quiet day. Every run ran, every check is on time and the log has nothing to say."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=run.counts`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="run.counts" defaultText="Read ${read}. Kept ${kept}."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=run.files`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="run.files" defaultText="Files: ${files}"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=run.streak`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="run.streak" defaultText="${days} days in a row"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=runs.none`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="runs.none" defaultText="No run errors."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=runs.title`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="runs.title" defaultText="Run errors"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=watchdog.emptystreak`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="watchdog.emptystreak" defaultText="Its last ${days} scheduled runs read nothing on either side."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=watchdog.never`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="watchdog.never" defaultText="No successful scheduled run yet."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=watchdog.noautomation`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="watchdog.noautomation" defaultText="Its statement is on, but the check has no automation to run it."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=watchdog.none`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="watchdog.none" defaultText="Every check ran in time."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=watchdog.stale`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="watchdog.stale" defaultText="No successful scheduled run since ${last}."/>
```

### Added in `data/OpsSeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_ERROR_DIGEST|blockId=watchdog.title`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_ERROR_DIGEST" blockId="watchdog.title" defaultText="Watchdog"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `moqui.basic.email.EmailServer|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailServer`

```xml
<moqui.basic.email.EmailServer emailServerId="SYSTEM"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_ERROR_DIGEST|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_ERROR_DIGEST" description="Darpan daily error email (Darpan staff only)" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="${digestSubject}" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/ErrorDigest.xml"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=flush_ErrorLog_5m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="flush_ErrorLog_5m" expireLockTime="10" description="Store the ERROR log lines captured in memory and delete stored lines more than 30 days old." serviceName="ops.ErrorDigestServices.flush#ErrorLog" defaultCronExpression="0 0/5 * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
```

### Added in `data/OpsSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=send_ErrorDigest_hourly`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="send_ErrorDigest_hourly" expireLockTime="50" description="Send the daily Darpan error email at errors.digest.hourUtc, once a day, even when there is nothing to report." serviceName="ops.ErrorDigestServices.send#DueErrorDigest" defaultCronExpression="0 5 * * * ?" defaultPaused="N" minIntervalMinutes="30"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
