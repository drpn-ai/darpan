#!/usr/bin/env bash
# Places every component the darpan suite needs under darpan-backend/runtime/component, from the workspace root, after
# the framework is cloned and getRuntime has run. Both ci.yml jobs call it, so the test shards and the coverage job
# build the same assembly. darpan itself comes from the checkout at component-darpan, moved last.
set -euo pipefail
git clone --depth 1 --branch master https://github.com/moqui/moqui-sftp.git \
  darpan-backend/runtime/component/moqui-sftp
# darpan's main code and test suite reference sibling-component entities: darpan-hotwax
# owns darpan.hotwax.HotWaxOmsRestSourceConfig and shopify-darpan owns
# darpan.shopify.ShopifyAuthConfig (the Ns* configs are canonical in darpan itself, so
# netsuite-darpan is a consumer placed only to assemble the full, proven platform). All
# three are public drpn-ai repos on `main` and part of the canonical darpan-cli
# REPO_MANIFEST. Without darpan-hotwax/shopify-darpan those entity definitions never load
# and ~24 darpan tests fail with EntityNotFoundException in their shared @BeforeEach.
# database-darpan owns darpan.database.DatabaseSourceConfig / DatabaseSourceQuery, which
# darpan's saved-run, automation and tenant-filter tests read; without it those tests fail
# with EntityNotFoundException exactly like the hotwax/shopify case above.
# DAR-OPS-015: moqui-gql serves the GraphQL API that darpan's GraphQL tests call; without it they
# failed in CI only. The production image clones it the same way (darpan-docker-config docker/Dockerfile).
# darpan-agent: its facade services are part of the API contract (ApiContractGenerator.SERVICE_DIRS), which fails
# without it; the production image carries it too.
for repo in darpan-hotwax netsuite-darpan shopify-darpan database-darpan moqui-gql darpan-agent; do
  git clone --depth 1 --branch main "https://github.com/drpn-ai/${repo}.git" \
    "darpan-backend/runtime/component/${repo}"
done
mv component-darpan darpan-backend/runtime/component/darpan
