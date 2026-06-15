#!/usr/bin/env bash
#
# scripts/dev-deploy.sh — per-rebuild deploy loop.
#
# Builds the gitsync plugin, signs the JARs with your local dev keystore,
# pushes the result into one or more running OIE containers, and optionally
# restarts them and clears OpenWebStart's cache so the next admin console
# launch sees the new classes.
#
# Usage:
#   ./scripts/dev-deploy.sh                      # reads containers= from config
#   ./scripts/dev-deploy.sh engine-oie-1         # override container
#   ./scripts/dev-deploy.sh engine-oie-1 engine-oie-prod-1
#   NO_RESTART=1 ./scripts/dev-deploy.sh         # skip docker restart
#   NO_CACHE_PURGE=1 ./scripts/dev-deploy.sh     # skip OpenWebStart cache purge
#
# Prerequisites (run `./scripts/dev-setup.sh` once to handle these):
#   * jarsigner and keytool on PATH (JDK, not JRE)
#   * ant on PATH
#   * docker daemon accessible
#   * scripts/dev-signing.properties created from .example and edited
#   * scripts/dev-keystore.jks exists (created by dev-setup.sh)
#
# This script is intended for local contributor testing only, not for
# production installation. See docs/local-testing.md for the full story.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

CONFIG="$REPO_ROOT/scripts/dev-signing.properties"
if [[ ! -f "$CONFIG" ]]; then
  echo "ERROR: $CONFIG not found."
  echo "       Copy scripts/dev-signing.properties.example to that path and edit it,"
  echo "       or run ./scripts/dev-setup.sh to create it interactively."
  exit 1
fi

# Tolerant properties reader: strips comments + whitespace, keeps key=value.
prop() {
  local key="$1" default="${2:-}"
  local val
  val="$(grep -E "^\\s*${key}\\s*=" "$CONFIG" | head -1 | sed -E "s/^\\s*${key}\\s*=\\s*//;s/\\s*\$//")"
  echo "${val:-$default}"
}

KEYSTORE_PATH="$(prop keystore.path scripts/dev-keystore.jks)"
KEYSTORE_ALIAS="$(prop keystore.alias gitsync-dev)"
KEYSTORE_PASS="$(prop keystore.password gitsync-dev)"
RESTART_AFTER_DEPLOY="$(prop restart.after.deploy true)"
CLEAR_OWS_CACHE="$(prop clear.openwebstart.cache true)"

# Container list: CLI args win, otherwise fall back to config.
if [[ $# -gt 0 ]]; then
  CONTAINERS=("$@")
else
  IFS=' ' read -r -a CONTAINERS <<< "$(prop containers engine-oie-1)"
fi
if [[ ${#CONTAINERS[@]} -eq 0 ]]; then
  echo "ERROR: no target containers. Pass as args or set containers= in $CONFIG."
  exit 1
fi

# Env var overrides for one-off invocations.
[[ "${NO_RESTART:-}" = "1" ]] && RESTART_AFTER_DEPLOY=false
[[ "${NO_CACHE_PURGE:-}" = "1" ]] && CLEAR_OWS_CACHE=false

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "ERROR: keystore $KEYSTORE_PATH not found. Run ./scripts/dev-setup.sh first."
  exit 1
fi

require() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: required command '$1' not found on PATH."
    exit 1
  fi
}
require ant
require jarsigner
require docker

echo "=== 1. ant clean build ==="
ant -noinput clean build >/dev/null

ZIP="$(ls -1 dist/gitsync-*.zip 2>/dev/null | head -1 || true)"
if [[ -z "$ZIP" ]]; then
  echo "ERROR: no dist zip produced by ant build."
  exit 1
fi
echo "    built: $ZIP"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
unzip -q "$ZIP" -d "$STAGE"
PLUGIN_DIR="$STAGE/gitsync"

echo "=== 2. sign plugin JARs ==="
SIGNED=0
for j in "$PLUGIN_DIR"/*.jar "$PLUGIN_DIR"/lib/*.jar; do
  [[ -f "$j" ]] || continue
  jarsigner -keystore "$KEYSTORE_PATH" \
            -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
            "$j" "$KEYSTORE_ALIAS" >/dev/null
  SIGNED=$((SIGNED + 1))
done
echo "    signed $SIGNED JARs with alias '$KEYSTORE_ALIAS'"

echo "=== 3. push into containers ==="
for container in "${CONTAINERS[@]}"; do
  if ! docker inspect "$container" >/dev/null 2>&1; then
    echo "    WARN: container '$container' not running; skipping"
    continue
  fi
  docker exec "$container" rm -rf /opt/oie/extensions/gitsync
  ( cd "$STAGE" && docker cp gitsync "$container":/opt/oie/extensions/gitsync )
  echo "    pushed to $container"
done

if [[ "$RESTART_AFTER_DEPLOY" = "true" ]]; then
  echo "=== 4. restart containers ==="
  for container in "${CONTAINERS[@]}"; do
    docker inspect "$container" >/dev/null 2>&1 || continue
    docker restart "$container" >/dev/null
    echo "    restarted $container"
  done
else
  echo "=== 4. skipping container restart (NO_RESTART / restart.after.deploy=false) ==="
  echo "    note: server-side changes won't take effect until the container restarts."
fi

if [[ "$CLEAR_OWS_CACHE" = "true" ]]; then
  echo "=== 5. clear OpenWebStart cache ==="
  pkill -9 -f 'javaws|icedtea-web.*Boot' 2>/dev/null || true
  rm -rf "$HOME/.cache/icedtea-web/cache"
  echo "    purged ~/.cache/icedtea-web/cache and killed any running javaws"
else
  echo "=== 5. skipping OpenWebStart cache purge ==="
fi

echo
echo "=== done ==="
echo "Re-launch the admin console with:"
IFS=' ' read -r -a DONE_URLS <<< "$(prop urls https://localhost:8443)"
for u in "${DONE_URLS[@]}"; do
  echo "    javaws ${u}/webstart.jnlp"
done
echo
echo "Then navigate to Settings -> Git Sync to test the new build."
