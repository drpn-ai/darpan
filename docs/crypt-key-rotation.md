# Crypt-Key Rotation Procedure

Applies to: `runtime/component/darpan` — Moqui 4 / prod deploy via component Dockerfile + entrypoint.

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

## Rotation Steps

### 1. Prepare

Verify the current key is set and all pods are healthy:

```
entity_ds_crypt_pass=<current-key>    # already required; pods running normally
entity_ds_crypt_pass_old=<unset>      # defaulting to current key — no rotation in progress
```

### 2. Start the rotation window

Set the old key to the current key and the new key in your secrets manager / deployment config:

```
entity_ds_crypt_pass=<new-key>
entity_ds_crypt_pass_old=<current-key>   # the key being retired
```

Both env vars must be available to the container at start time. The entrypoint exports
`entity_ds_crypt_pass_old` as a shell default — if it is already in the environment it is used
as-is; if not, it falls back to `entity_ds_crypt_pass` (safe no-op, but in the rotation case you
must set it explicitly).

### 3. Rolling deploy with both keys

Deploy the new image. Each new container boots with the new key as the active encryption key and
the old key wired into three `<decrypt-alt>` entries:

- PBEWithMD5AndDES + current key (backward-compat for pre-AES values)
- PBEWithMD5AndDES + old key (rotation-window DES values)
- PBEWithHmacSHA256AndAES_128 + old key (rotation-window AES-128 values)

Reads against data written under the old key succeed via decrypt-alt. New writes use the new key.
Old pods still running during a rolling deploy continue to read and write with the old key — they
can also decrypt new-key values if they have both `<decrypt-alt>` entries, but the standard Moqui
framework conf already contains those entries; only this prod-conf override needed to be fixed.

### 4. Re-encrypt existing data

Run the one-shot rotation sweep (added 2026-07-02, MACH P2):

```
reconciliation.ReconciliationGenericServices.rotate#EncryptedFieldValues
```

It derives every `encrypt="true"` field from the live entity definitions (no hardcoded list to
drift), loads each row via an unscoped system read, and forces the encrypted columns back through
an UPDATE — Moqui re-encrypts at write time with the ACTIVE key. Internal-only
(`allow-remote="false"`), super-admin gated when called by a user, idempotent, safe to re-run.
Returns per-entity re-encrypted row counts. Covered by `CryptRotationSmokeTests` (at-rest
ciphertext + decrypted round-trip verified against H2).

If the sweep cannot be run, records also re-encrypt naturally on next write — keep
`entity_ds_crypt_pass_old` set until all rows have been rewritten.

### A note on crypt-salt (determinism)

Moqui's entity crypt uses a **fixed config salt** (`crypt-salt` attribute, default `default1`)
in the PBE key derivation — encryption is deterministic per key, so equal plaintexts produce
equal ciphertext at rest. Do NOT change `crypt-salt` on an existing deployment outside a
rotation window: the salt participates in key derivation, so changing it breaks decryption of
existing data exactly like a key change. If a deployment-unique salt is wanted, rotate it with
the same dual-key procedure (a `<decrypt-alt>` entry carrying the OLD salt + old key), then run
the sweep above.

### 5. Drop the old key

Once all encrypted-field data has been re-written under the new key:

```
entity_ds_crypt_pass=<new-key>
# Remove entity_ds_crypt_pass_old from your secrets manager / deployment config.
# The entrypoint will default it to entity_ds_crypt_pass — decrypt-alt becomes a no-op again.
```

Deploy (rolling). The rotation is complete.

### 6. Verify

After step 5, confirm that no decrypt failures appear in the application logs. Search for:

```
grep -i "decrypt\|crypt\|PBE" <log-file>
```

Any decryption error against a live record would indicate a record that was not yet re-encrypted
in step 4. If found, temporarily re-add `entity_ds_crypt_pass_old` and complete the re-encrypt pass.

## Files modified (MACH Security P1, 2026-06-29)

- `runtime/component/darpan/docker/MoquiProductionConf.xml` — added `<default-property>` for
  `entity_ds_crypt_pass_old` and three `<decrypt-alt>` entries inside `<entity-facade>`, matching
  the framework's `MoquiDefaultConf.xml` lines 433-436. Previously the prod-conf `<entity-facade>`
  element overrode the framework element and lost the fallback decrypt entries entirely.
- `runtime/component/darpan/docker/entrypoint.sh` — exports `entity_ds_crypt_pass_old` as optional,
  defaulting to `entity_ds_crypt_pass`.
- `runtime/component/darpan/docker/prod/entrypoint.sh` — same.

(Paths above are as of 2026-06-29. The component `docker/` directory has since moved to the private
`drpn-ai/darpan-docker-config` repo, where these files live at the repo root.)
