# Darpan 3.4.9 Technical Changelog

## Versioning decision

- Patch on the 3.4 line: 9 facade/admin methods added, none removed or changed (contract stays 5), GraphQL unchanged.

## Source ranges

- darpan `v3.4.8..v3.4.9`, darpan-ui `v4.4.10..v4.4.11`, darpan-docker-config `v3.4.8..v3.4.9`.

## Backend

### drpn-ai/darpan `v3.4.8..v3.4.9`

- DAR-BE-361 (`3efa49ab`..`fa5a737c`, 16 commits): entities `AuditFindingAcknowledgement` (one per finding: note,
  remind date, who and when) and `AuditFindingFeedback` (note, answer); events `AUDFE_ACKNOWLEDGED`, `AUDFE_ACK_CHANGED`, `AUDFE_ACK_REOPENED`; settings
  `audit.acknowledge.remindDays`, `audit.feedback.shownDays`, `feedback.chat.webhookUrl`; retention of acknowledgement and
  feedback rows; `acknowledge#AuditFinding` / `update#AuditFindingAcknowledgement` / `delete#AuditFindingAcknowledgement`
  (a report row: every open line of its order under its statement plus the waiting lines); the report and
  findingsByOrder split open into open and acknowledged; digest and run emails count acknowledged apart;
  `send#AuditFindingFeedback` posts to the Darpan chat space and sends `DARPAN_FEEDBACK_RECEIVED`;
  `admin.FeedbackAdminServices.get#FeedbackList` / `reply#Feedback` send `DARPAN_FEEDBACK_ANSWER`; tenant filters
  `DARPAN_SCOPE_AUD_FINDING_ACK`, `DARPAN_SCOPE_AUD_FINDING_FEEDBACK`.
- DAR-BE-369 (`487225f3`): `AuditFindingsVerifySupport` (plan: open findings grouped by their check's automation, one
  verify run per automation with every record key, batches split on the first key field at
  `AuditRecheckSupport.MAX_KEYS`; checks with no automation skipped); `verify#AuditFindings` (run access) and
  `get#AuditRecheckProgress` (view); both CLI-excluded.
- Release commit: component version 3.4.9, `openapi.json` version 3.4.9, `facade-contract.snapshot.txt` (+2 services,
  owed by `487225f3`), upgrade data (63 records), this pack.

## UI

### drpn-ai/darpan-ui `v4.4.10..v4.4.11`

- DAR-UI-144: Acknowledge popup (note, remind toggle), Acknowledged pill, feedback bubble on a finding; the findings
  column and counts follow the pill.
- DAR-UI-147: Re-check all icons (report header, Findings column), progress wait, closed/open message.
- Release commit: version 4.4.11.

### drpn-ai/darpan-docker-config `v3.4.8..v3.4.9`

- `docker/prod/Dockerfile`: `DARPAN_REF v3.4.9`.

## Data and configuration

- Upgrade data: 63 records (43 audit vocabulary rows restated whole, 2 email templates, 16 copy blocks, 2 entity filters).

## Validation and rollout notes

- Focused tests and gates: `release-checklist.md`. Rollout: docker-config `v3.4.9` prod image via the deployer, then app
  `v4.4.11` to Firebase hosting.

## References

- Backlog: DAR-BE-361, DAR-UI-144, DAR-BE-369, DAR-UI-147.
