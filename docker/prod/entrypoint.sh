#!/bin/bash

set -e
sed -i 's/name="entity_ds_host" value="127.0.0.1"/name="entity_ds_host" value="'$Moqui_DB_HOST'"/g' $CONF_FILE
sed -i 's/name="webapp_http_host" value=""/name="webapp_http_host" value="'$Moqui_HOST'"/g' $CONF_FILE

sed -i 's/name="entity_ds_user" value="moqui"/name="entity_ds_user" value="'$Moqui_DB_USER'"/g' $CONF_FILE
sed -i 's/name="entity_ds_password" value="moqui"/name="entity_ds_password" value="'$Moqui_DB_PASSWORD'"/g' $CONF_FILE
sed -i 's/name="entity_ds_database" value="moqui"/name="entity_ds_database" value="'$Moqui_DB_NAME'"/g' $CONF_FILE

WEBAPP_ALLOW_ORIGINS_OVERRIDE="${Moqui_WEBAPP_ALLOW_ORIGINS:-${WEBAPP_ALLOW_ORIGINS:-}}"
if [ -n "$WEBAPP_ALLOW_ORIGINS_OVERRIDE" ]; then
  sed -i 's|name="webapp_allow_origins" value="[^"]*"|name="webapp_allow_origins" value="'"$WEBAPP_ALLOW_ORIGINS_OVERRIDE"'"|g' "$CONF_FILE"
fi

# Timezone setting
sed -i 's|name="default_time_zone" value=""|name="default_time_zone" value="'$TIME_ZONE'"|g' $CONF_FILE
sed -i 's|name="database_time_zone" value=""|name="database_time_zone" value="'$TIME_ZONE'"|g' $CONF_FILE

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }--add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"

$SLEEP

if [ "${DARPAN_LOAD_UPGRADE_DATA:-Y}" != "N" ]; then
  echo "Loading Darpan upgrade data"
  DARPAN_MOQUI_FRAMEWORK_DIR="${DARPAN_MOQUI_FRAMEWORK_DIR:-/moqui-framework}"
  DARPAN_COMPONENT_GRADLE_FILE="${DARPAN_COMPONENT_GRADLE_FILE:-$DARPAN_MOQUI_FRAMEWORK_DIR/runtime/component/darpan/build.gradle}"
  DARPAN_GRADLEW="${DARPAN_GRADLEW:-$DARPAN_MOQUI_FRAMEWORK_DIR/gradlew}"
  DARPAN_UPGRADE_DATA_TYPES="${DARPAN_UPGRADE_DATA_TYPES:-darpan-seed}"
  DARPAN_UPGRADE_DATA_LOCATION="${DARPAN_UPGRADE_DATA_LOCATION:-component://darpan/data/upgrade-data.xml}"
  load_args=(
    --no-daemon
    -b "$DARPAN_COMPONENT_GRADLE_FILE"
    loadDarpanUpgradeData
    "-PmoquiConf=$CONF_FILE"
    "-PmoquiRuntime=$DARPAN_MOQUI_FRAMEWORK_DIR/runtime"
    "-PmoquiWar=$DARPAN_MOQUI_FRAMEWORK_DIR/moqui-plus-runtime.war"
    "-Ptypes=$DARPAN_UPGRADE_DATA_TYPES"
    "-PupgradeDataLocation=$DARPAN_UPGRADE_DATA_LOCATION"
  )
  if [ -n "${DARPAN_UPGRADE_DATA_LOAD_ARGS:-}" ]; then
    load_args+=("-PextraLoadArgs=$DARPAN_UPGRADE_DATA_LOAD_ARGS")
  fi
  (cd "$DARPAN_MOQUI_FRAMEWORK_DIR" && "$DARPAN_GRADLEW" "${load_args[@]}")
fi

screen -dmS Moqui java $JAVA_OPTS -cp . MoquiStart port=8080 conf=$CONF_FILE
tail -F runtime/log/moqui.log
