# Pre-reset release packs (retired numbering)

The repository history was reset on 2026-07-03 (standalone repo, single-commit baseline);
every tag in the `v1.0.0`–`v2.1.3` pre-reset series was deleted and versioning restarted
at `v1.0.0` with the first post-reset cut.

The packs in this directory (`1.1.0`–`2.1.2`, plus `unreleased-post-2.1.3`, the current
upgrade file as it stood on old `main` after the `v2.1.3` cut) belong to that retired
pre-reset series. Their version numbers must not be confused with post-reset releases
under `data/releases/` — post-reset `1.1.0` will be a different release than the pack
archived here.

These packs are organizational only: every record also lives in the generic seed files
under `data/*.xml`, and the loader is type-based (`darpan-seed`) and idempotent. Full
pre-reset git history is backed up in `~/sandbox/repo-backups/` (bundle + mirror).
