# Crypt-Key Rotation Procedure

Applies to: `runtime/component/darpan` — Moqui 4 / deploys built from the `drpn-ai/darpan-docker-config` Dockerfiles + entrypoints.

## Background

Moqui's `entity-facade` encrypts stored field values with the key in `entity_ds_crypt_pass`.
`MoquiProductionConf.xml` (MACH Security P1, 2026-06-29) now mirrors the framework's dual-key
`<decrypt-alt>` entries so prod can read old-key-encrypted data during a rotation window, then
re-encrypt to the new key, and finally drop the old key.

In steady state (not rotating), `entity_ds_crypt_pass_old` defaults to `entity_ds_crypt_pass` in
both the XML `<default-property>` and the container entrypoint — the decrypt-alt entries are a
harmless no-op.

## Compose-file defaults stance

The upstream `moqui-*-compose.yml` files under `darpan-backend/docker/` (origin: `moqui/moqui-framework`,
push disabled) contain `DEFAULT_CHANGE_ME!!!` placeholders for `entity_ds_crypt_pass`. These are
upstream Moqui example files, not Darpan deploy artifacts. They are not used by the Darpan production
deploy, which uses the Dockerfile and entrypoint from the private `drpn-ai/darpan-docker-config` repo
(see below). No action needed in the Darpan component repo for those files.

The Darpan deploy entrypoints (`entrypoint.sh` and `prod/entrypoint.sh` in `darpan-docker-config`)
fail fast with `:?` on `entity_ds_crypt_pass` — there is no insecure default in the Darpan deploy path.

## Rotation steps on Darpan's AWS (prod)

The window is opened and closed with `darpan-docker-config/aws/deploy.sh` (see `/darpan-aws-deploy`); the
sweep runs from the admin app or the darpan-admin CLI as `admin.SecurityAdminServices.rotate#EncryptedFields`
(below). The full standard, with the snapshot and evidence steps, is the wrapper's
`docs/security/encryption-key-management.md` section 5. Run the whole procedure on UAT first.

Use one shell for steps 2 and 3 and never echo a key: each value goes from Secrets Manager straight back into
Secrets Manager.

1. **Prepare.** Quiet window: scheduler off (`deploy.sh prod --set SchedulerEnabled=false`), and a manual RDS
   snapshot (it holds old-key ciphertext and is the rollback point).
2. **Keep the outgoing key.** Create a secret named `CryptPassOldSecret` holding the current value of
   `CryptPassSecret`. The name matters: the task execution role reads `CryptPassOldSecret-*` and the
   `CryptPassOldSecretArn` parameter accepts only that name.
   ```
   aws secretsmanager create-secret --name CryptPassOldSecret --profile darpan-prod --region us-east-2 \
     --secret-string "$(aws secretsmanager get-secret-value --secret-id <CryptPassSecret arn> \
        --query SecretString --output text --profile darpan-prod --region us-east-2)" \
     --query ARN --output text
   ```
3. **Store the new key** as a new version of `CryptPassSecret` (never replace or regenerate the resource; the
   previous version stays labelled `AWSPREVIOUS`). No punctuation: the entrypoint writes conf values with `sed`.
   ```
   aws secretsmanager put-secret-value --secret-id <CryptPassSecret arn> --profile darpan-prod --region us-east-2 \
     --secret-string "$(aws secretsmanager get-random-password --password-length 48 --exclude-punctuation \
        --query RandomPassword --output text --profile darpan-prod --region us-east-2)"
   ```
4. **Roll the task with both keys:** `aws/deploy.sh prod --ref <current ref> --tag <running image tag> --set
   CryptPassOldSecretArn=<arn from step 2>` (plan), then the same with `--yes`. The new task encrypts with the
   new key and reads old-key values through the `decrypt-alt` entries. Check one connection per tenant.
5. **Dry run:** `rotate#EncryptedFields` with `dryRun` left at true. Expect `oldKeyConfigured: true`,
   `rotationPending: true`, `oldKeyOnlyCount` = the stored values, `undecryptableCount: 0`. A non-zero
   `undecryptableCount` means values neither key reads (stop and find out which entity before going on).
6. **Rotate:** the same command with `dryRun: false` (a new code: a code works once). Save `totalRows` and
   `reencryptedByEntity`. The reply's counts are re-read after the sweep: expect `oldKeyOnlyCount: 0` and
   `rotationPending: false`. Check one connection per tenant again; search the logs for `decrypt`, `crypt`, `PBE`.
7. **Close the window:** `aws/deploy.sh prod ... --set CryptPassOldSecretArn=` (empty) and roll the task. The
   entrypoint then defaults `entity_ds_crypt_pass_old` to the current key and the `decrypt-alt` entries are a no-op.
   A dry run now shows `oldKeyConfigured: false` and every value on the current key.
8. **Retire the old key.** Delete `CryptPassOldSecret` only once every backup and snapshot taken before step 6
   has expired (they hold old-key ciphertext); until then it stays in Secrets Manager, unwired. Scheduler back on.

If something fails before step 6: put the old value back as the current version of `CryptPassSecret`, clear
`CryptPassOldSecretArn`, roll the task; nothing was re-encrypted. After step 6: keep both keys wired and re-run.

## The rotation command

`admin.SecurityAdminServices.rotate#EncryptedFields` (DAR-OPS-019), over `/rpc/json` from the admin app or the
darpan-admin CLI. Super admins only; never on the tenant CLI list. Since DAR-BE-264 closed the Moqui tools
app's sign-in, this is the only way to run the sweep on a deployed instance.

- In: `adminCode` (required: a current code from the acting admin's own authenticator; an admin without
  two-factor is refused), `dryRun` (Boolean, default true).
- Out: `ok`, `oldKeyConfigured`, `rotationPending`, `valueCount`, `currentKeyCount`, `oldKeyOnlyCount`,
  `undecryptableCount`, `entities` (per entity: `entityName`, `fields`, `valueCount`, `currentKey`,
  `oldKeyOnly`, `undecryptable`); on a real run also `reencryptedByEntity` and `totalRows`.
- A real run is refused ("Nothing to rotate") unless an old key different from the current one is wired in.
- Every run writes an `AdminAuditLog` row with counts only; a real run also gets the sweep's own row
  (`rotate#EncryptedFieldValues`).

The sweep underneath, `reconciliation.ReconciliationGenericServices.rotate#EncryptedFieldValues`, stays
internal-only (`allow-remote="false"`). It derives every `encrypt="true"` field from the live entity
definitions, reads each raw column, decrypts it with the current key first and then the old key, and writes the
plaintext back so Moqui encrypts it with the active key. Idempotent, safe to re-run.

Two things it handles that a plain read-and-rewrite would not:

- **A wrong key that seems to work.** The values are AES-CBC with PKCS5 padding, so the new key decrypts about
  one old-key value in 256 without an error, into random bytes; Moqui's read keeps that result and never tries
  the old key. A result counts only when it is text (no replacement or control characters), so the sweep
  writes the real plaintext. During the window, before the sweep, such a value reads as garbage in the app
  (that connection fails until the sweep runs); that includes an admin's own two-factor secret, in which case
  another super admin runs the command.
- **A value no key reads** (Moqui reads it as `_DECRYPT_FAILED_`) is left as it is, not overwritten with the
  placeholder; the dry run counts it as `undecryptable`.

Covered by `CryptRotationSmokeTests` and `CryptRotationAdminSmokeTests` (a real key switch in the test, a
padding collision included).

## Generic steps (any deployment)

1. Start the window: `entity_ds_crypt_pass=<new-key>`, `entity_ds_crypt_pass_old=<current-key>`, both present
   at container start (the entrypoint defaults the old one to the current key when it is unset).
2. Restart every container with both keys.
3. Dry run, then rotate (above).
4. Remove `entity_ds_crypt_pass_old` and restart.
5. Confirm no decrypt errors in the logs (`grep -i "decrypt\|crypt\|PBE"`).

### A note on crypt-salt (determinism)

Moqui's entity crypt uses a **fixed config salt** (`crypt-salt` attribute, default `default1`)
in the PBE key derivation — encryption is deterministic per key, so equal plaintexts produce
equal ciphertext at rest. Do NOT change `crypt-salt` on an existing deployment outside a
rotation window: the salt participates in key derivation, so changing it breaks decryption of
existing data exactly like a key change. If a deployment-unique salt is wanted, rotate it with
the same dual-key procedure (a `<decrypt-alt>` entry carrying the OLD salt + old key), then run
the sweep above. (The dry run's key check compares crypt-pass only, so it reports a salt-only change as
nothing to rotate; extend `CryptRotationSupport.configuredKeys` before doing one.)

## Where the dual-key wiring lives

All in the private `drpn-ai/darpan-docker-config` repo:

- `docker/MoquiProductionConf.xml` — the `<default-property>` for `entity_ds_crypt_pass_old` and
  three `<decrypt-alt>` entries inside `<entity-facade>`, matching the framework's
  `MoquiDefaultConf.xml`. Without them the prod-conf `<entity-facade>` element overrides the
  framework element and loses the fallback decrypt entries. The same file serves both images.
- `docker/entrypoint.sh` (UAT image) — exports `entity_ds_crypt_pass_old` as optional, defaulting to
  `entity_ds_crypt_pass`.
- `docker/prod/entrypoint.sh` (prod image) — same.

The component itself no longer has a `docker/` directory.
