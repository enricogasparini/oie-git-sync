#!/usr/bin/env bash
#
# scripts/dev-setup.sh — one-time local development environment setup.
#
# Gets your local machine into a state where you can build the gitsync plugin
# and open OIE's admin console via OpenWebStart to test it end-to-end.
# Everything this script does is scoped to your own user account and dev
# containers; nothing touches system-wide state or the public distribution.
#
# Run once per machine. Idempotent — safe to re-run. After that, the daily
# loop is scripts/dev-deploy.sh.
#
# Usage:
#   ./scripts/dev-setup.sh                  # use values from dev-signing.properties
#   ./scripts/dev-setup.sh engine-oie-1     # override container list
#
# What it does (each step is independently skippable with an env var):
#
#   1. generate-keystore    Generate a self-signed dev keystore if missing.
#                           SKIP_KEYSTORE=1 to skip.
#   2. sign-base-jars       Sign the ~223 OIE base + extension JARs inside
#                           the running container. Only needed when your OIE
#                           was self-built with -DdisableSigning=true.
#                           A container is skipped only when its JARs are
#                           already signed by the *current* keystore cert
#                           (compared by SHA-256 fingerprint, not alias name) —
#                           so a regenerated keystore correctly triggers a
#                           re-sign instead of leaving a mixed-signature
#                           classpath that OpenWebStart rejects.
#                           SKIP_BASE_SIGNING=1 to skip.
#
#   Recovery: the keystore (scripts/dev-keystore.jks) is gitignored and easily
#   lost. If you lose it, just re-run this script: it generates a fresh
#   keystore and the fingerprint checks below re-sign every base JAR and
#   refresh the trusted cert so the classpath ends up single-signed again.
#   FORCE_RESIGN=1 forces a re-sign + trust refresh even when fingerprints
#   already match.
#   3. patch-reflections    Replace /opt/oie/client-lib/reflections-0.9.10.jar
#                           with Reflections 0.9.12 content (same filename so
#                           the JNLP reference resolves). Works around the
#                           "zip file closed" bug on JDK 17+ that prevents
#                           admin console login.
#                           SKIP_REFLECTIONS=1 to skip.
#   4. trust-signing-cert   Import the dev keystore cert into OpenWebStart's
#                           trusted.certs so signed JARs from this keystore
#                           are accepted.
#                           SKIP_TRUST_CERT=1 to skip.
#   5. trust-tls            Fetch each target OIE's TLS cert and import into
#                           OpenWebStart's trusted.jssecerts so HTTPS to the
#                           JNLP URL works without a manual browser approval.
#                           SKIP_TLS_TRUST=1 to skip.
#   6. pin-zulu-jvm         Point OpenWebStart at your local Zulu 17 JDK
#                           instead of its bundled Adoptium 21 (which hits
#                           additional Reflections issues).
#                           SKIP_JVM_PIN=1 to skip.
#
# This script is intended for local contributor testing only, not for
# production installation. See docs/local-testing.md for the full explanation
# of why each step is needed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

CONFIG="$REPO_ROOT/scripts/dev-signing.properties"
EXAMPLE="$REPO_ROOT/scripts/dev-signing.properties.example"

if [[ ! -f "$CONFIG" ]]; then
  if [[ -f "$EXAMPLE" ]]; then
    cp "$EXAMPLE" "$CONFIG"
    echo "Seeded $CONFIG from the example. Edit it if the defaults don't match"
    echo "your container names or URLs, then re-run this script."
  else
    echo "ERROR: neither $CONFIG nor $EXAMPLE exists."
    exit 1
  fi
fi

prop() {
  local key="$1" default="${2:-}"
  local val
  val="$(grep -E "^\\s*${key}\\s*=" "$CONFIG" | head -1 | sed -E "s/^\\s*${key}\\s*=\\s*//;s/\\s*\$//")"
  echo "${val:-$default}"
}

KEYSTORE_PATH="$(prop keystore.path scripts/dev-keystore.jks)"
KEYSTORE_ALIAS="$(prop keystore.alias gitsync-dev)"
KEYSTORE_PASS="$(prop keystore.password gitsync-dev)"
KEYSTORE_DNAME="$(prop keystore.dname 'CN=gitsync-dev, OU=Local Development, O=Local Development, C=GB')"
KEYSTORE_VALIDITY="$(prop keystore.validity 3650)"

if [[ $# -gt 0 ]]; then
  CONTAINERS=("$@")
else
  IFS=' ' read -r -a CONTAINERS <<< "$(prop containers engine-oie-1)"
fi
IFS=' ' read -r -a URLS <<< "$(prop urls https://localhost:8443)"

OWS_SECURITY_DIR="$HOME/.config/icedtea-web/security"
OWS_CACHE_FILE="$HOME/.cache/icedtea-web/jvm-cache/cache.json"

require() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: required command '$1' not found on PATH."
    exit 1
  fi
}
require keytool
require jarsigner
require docker
require openssl
require curl
require tar

CONTAINER_KEYTOOL=/usr/lib/jvm/zulu17/bin/keytool

# Extract the first SHA-256 fingerprint from keytool output, normalised to
# uppercase colon-separated hex (e.g. 26:66:4C:...:78). Empty if none.
extract_sha256() {
  grep -oE 'SHA256: [0-9A-Fa-f:]+' | head -1 | sed 's/SHA256: //' | tr 'a-f' 'A-F'
}

# SHA-256 fingerprint of the current dev keystore's signing cert.
keystore_cert_fp() {
  keytool -list -v -keystore "$KEYSTORE_PATH" -storepass "$KEYSTORE_PASS" \
    -alias "$KEYSTORE_ALIAS" 2>/dev/null | extract_sha256
}

# True if any signer of the given in-container JAR matches fingerprint $3.
container_jar_signed_by() {  # container jarpath wantfp
  [[ -n "$3" ]] || return 1
  docker exec "$1" "$CONTAINER_KEYTOOL" -printcert -jarfile "$2" 2>/dev/null \
    | grep -oE 'SHA256: [0-9A-Fa-f:]+' | sed 's/SHA256: //' | tr 'a-f' 'A-F' \
    | grep -qx "$3"
}

# SHA-256 fingerprint of an alias already in a local trust store. Empty if absent.
truststore_alias_fp() {  # truststore alias
  keytool -list -v -keystore "$1" -storepass changeit -alias "$2" 2>/dev/null \
    | extract_sha256
}

# -----------------------------------------------------------------------------
# 1. Generate the dev keystore if missing.
# -----------------------------------------------------------------------------
if [[ "${SKIP_KEYSTORE:-}" != "1" ]]; then
  echo "=== 1. dev keystore ==="
  if [[ -f "$KEYSTORE_PATH" ]]; then
    echo "    keystore already exists at $KEYSTORE_PATH — reusing."
  else
    mkdir -p "$(dirname "$KEYSTORE_PATH")"
    keytool -genkeypair \
      -alias "$KEYSTORE_ALIAS" \
      -keyalg RSA -keysize 2048 -validity "$KEYSTORE_VALIDITY" \
      -dname "$KEYSTORE_DNAME" \
      -keystore "$KEYSTORE_PATH" \
      -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" >/dev/null 2>&1
    echo "    generated $KEYSTORE_PATH (alias=$KEYSTORE_ALIAS)"
  fi
  # Always (re-)export the public cert so the downstream steps use the right copy.
  CERT_FILE="$(dirname "$KEYSTORE_PATH")/dev-keystore.cer"
  keytool -exportcert -alias "$KEYSTORE_ALIAS" \
    -keystore "$KEYSTORE_PATH" -storepass "$KEYSTORE_PASS" \
    -file "$CERT_FILE" >/dev/null 2>&1
  keytool -printcert -file "$CERT_FILE" | grep -E 'SHA256|Owner' | sed 's/^/    /'
fi

# -----------------------------------------------------------------------------
# 2. Sign the ~223 OIE base + extension JARs inside each container.
#    Required because self-built OIE (ant -DdisableSigning=true) ships every
#    JAR unsigned and OpenWebStart refuses to grant all-permissions to them.
#    Does nothing if the container is already running signed JARs.
# -----------------------------------------------------------------------------
if [[ "${SKIP_BASE_SIGNING:-}" != "1" ]]; then
  echo "=== 2. sign base OIE JARs in each container ==="
  echo "    note: JREs inside OIE containers don't ship jarsigner, so we tar"
  echo "          the JARs out to the host, sign them here, and tar them back."
  STAGE="$(mktemp -d)"
  trap 'rm -rf "$STAGE"' EXIT
  WANT_FP="$(keystore_cert_fp)"
  if [[ -z "$WANT_FP" ]]; then
    echo "    ERROR: could not read the keystore signing cert fingerprint."
    exit 1
  fi
  echo "    current keystore cert SHA-256: $WANT_FP"
  for container in "${CONTAINERS[@]}"; do
    if ! docker inspect "$container" >/dev/null 2>&1; then
      echo "    WARN: container '$container' not running; skipping"
      continue
    fi
    # Skip only when the base JARs are already signed by THIS keystore's cert
    # (compared by fingerprint, not alias name). A regenerated keystore reuses
    # the alias but has a different cert, so the fingerprint will not match and
    # the container is correctly re-signed — avoiding a mixed-signature
    # classpath that OpenWebStart rejects. FORCE_RESIGN=1 always re-signs.
    if [[ "${FORCE_RESIGN:-}" != "1" ]] \
       && container_jar_signed_by "$container" \
            /opt/oie/client-lib/mirth-client.jar "$WANT_FP"; then
      echo "    $container: base JARs already signed by current cert — skipping"
      continue
    fi

    CSTAGE="$STAGE/$container"
    mkdir -p "$CSTAGE"
    docker exec "$container" sh -c 'cd / && tar cf - opt/oie/client-lib opt/oie/extensions 2>/dev/null' \
      | tar xf - -C "$CSTAGE"
    jar_count="$(find "$CSTAGE" -name '*.jar' | wc -l)"
    echo "    $container: exported $jar_count JARs, signing..."
    ok=0
    while IFS= read -r j; do
      if jarsigner -keystore "$KEYSTORE_PATH" \
                   -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
                   "$j" "$KEYSTORE_ALIAS" >/dev/null 2>&1; then
        ok=$((ok + 1))
      fi
    done < <(find "$CSTAGE" -name '*.jar')
    echo "    $container: signed $ok/$jar_count JARs"
    ( cd "$CSTAGE" && tar cf - opt/oie/client-lib opt/oie/extensions ) \
      | docker exec -i "$container" tar xf - -C /
    echo "    $container: pushed signed JARs back"
  done
fi

# -----------------------------------------------------------------------------
# 3. Patch Reflections 0.9.10 -> 0.9.12 in each container.
#    OIE 4.5.2 bundles Reflections 0.9.10 which has a known
#    `java.lang.IllegalStateException: zip file closed` bug on JDK 17+.
#    That manifests as "error connecting to the server" on admin console
#    login, because Client.<init> scans packages via Reflections on a
#    background SwingWorker and the exception is mapped to a generic dialog.
#    0.9.12 is API-compatible with 0.9.10 so this is a safe drop-in.
#    TODO: remove this step once OIE upstream bumps its Reflections version.
# -----------------------------------------------------------------------------
if [[ "${SKIP_REFLECTIONS:-}" != "1" ]]; then
  echo "=== 3. patch Reflections 0.9.10 in each container ==="
  REFLECTIONS_URL='https://repo1.maven.org/maven2/org/reflections/reflections/0.9.12/reflections-0.9.12.jar'
  REFLECTIONS_JAR="$STAGE/reflections-0.9.12.jar"
  curl -fsSL -o "$REFLECTIONS_JAR" "$REFLECTIONS_URL"
  jarsigner -keystore "$KEYSTORE_PATH" \
    -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    "$REFLECTIONS_JAR" "$KEYSTORE_ALIAS" >/dev/null 2>&1
  for container in "${CONTAINERS[@]}"; do
    docker inspect "$container" >/dev/null 2>&1 || continue
    docker cp "$REFLECTIONS_JAR" "$container":/opt/oie/client-lib/reflections-0.9.10.jar
    echo "    $container: /opt/oie/client-lib/reflections-0.9.10.jar now contains 0.9.12"
  done
fi

# -----------------------------------------------------------------------------
# 4. Trust our dev signing cert in OpenWebStart.
# -----------------------------------------------------------------------------
if [[ "${SKIP_TRUST_CERT:-}" != "1" ]]; then
  echo "=== 4. import dev cert into OpenWebStart trusted.certs ==="
  mkdir -p "$OWS_SECURITY_DIR"
  TRUST_CERTS="$OWS_SECURITY_DIR/trusted.certs"
  CERT_FILE="$(dirname "$KEYSTORE_PATH")/dev-keystore.cer"
  WANT_FP="$(keystore_cert_fp)"
  HAVE_FP="$(truststore_alias_fp "$TRUST_CERTS" "$KEYSTORE_ALIAS")"
  if [[ "${FORCE_RESIGN:-}" != "1" && -n "$HAVE_FP" && "$HAVE_FP" == "$WANT_FP" ]]; then
    echo "    $KEYSTORE_ALIAS already trusts the current cert — skipping"
  else
    # A stale entry under our alias (e.g. from a previous, now-lost keystore)
    # would leave OpenWebStart trusting the wrong cert, so delete it first.
    # If the trust store doesn't exist yet, keytool creates it with the given
    # password. OpenWebStart uses "changeit" by convention.
    if [[ -n "$HAVE_FP" ]]; then
      echo "    replacing stale $KEYSTORE_ALIAS cert ($HAVE_FP)"
      keytool -delete -alias "$KEYSTORE_ALIAS" \
        -keystore "$TRUST_CERTS" -storepass changeit >/dev/null 2>&1 || true
    fi
    keytool -importcert -noprompt \
      -alias "$KEYSTORE_ALIAS" \
      -file "$CERT_FILE" \
      -keystore "$TRUST_CERTS" \
      -storepass changeit 2>&1 | tail -1 | sed 's/^/    /'
  fi
fi

# -----------------------------------------------------------------------------
# 5. Import each OIE's TLS cert so OpenWebStart can HTTPS the JNLP URL.
# -----------------------------------------------------------------------------
if [[ "${SKIP_TLS_TRUST:-}" != "1" ]]; then
  echo "=== 5. import OIE server TLS certs into trusted.jssecerts ==="
  JSSE_STORE="$OWS_SECURITY_DIR/trusted.jssecerts"
  for url in "${URLS[@]}"; do
    host="$(echo "$url" | sed -E 's|https?://||;s|/.*$||')"
    alias_name="oie-${host//[:.]/-}"
    pem="$STAGE/${alias_name}.pem"
    if ! echo | openssl s_client -connect "$host" -servername "${host%:*}" 2>/dev/null \
         | openssl x509 > "$pem" 2>/dev/null; then
      echo "    WARN: could not fetch TLS cert from $url (is the container up?); skipping"
      continue
    fi
    if [[ -f "$JSSE_STORE" ]] && \
       keytool -list -keystore "$JSSE_STORE" -storepass changeit 2>/dev/null \
         | grep -q "$alias_name"; then
      echo "    $alias_name already trusted — skipping"
      continue
    fi
    keytool -importcert -noprompt \
      -alias "$alias_name" -file "$pem" \
      -keystore "$JSSE_STORE" -storepass changeit 2>&1 | tail -1 | sed 's/^/    /'
  done
fi

# -----------------------------------------------------------------------------
# 6. Pin OpenWebStart to a local Zulu 17 JVM.
#    OIE 4.5.2's JNLP declares `version="1.9+"` which lets OpenWebStart pick
#    the newest JRE it knows about — normally Adoptium 21, which triggers
#    additional Reflections 0.9.x issues beyond the 0.9.12 patch above.
#    TODO: remove once OIE's JNLP or bundled libraries catch up with JDK 21+.
# -----------------------------------------------------------------------------
if [[ "${SKIP_JVM_PIN:-}" != "1" ]]; then
  echo "=== 6. pin OpenWebStart to local Zulu 17 ==="
  # Find a local Zulu 17 JDK. Try SDKMAN first, then a few common locations.
  ZULU_HOME=""
  for candidate in \
      "$HOME/.sdkman/candidates/java/17.0.18.fx-zulu" \
      "$HOME/.sdkman/candidates/java/17"* \
      /usr/lib/jvm/zulu17* \
      /usr/lib/jvm/zulu-17* \
      /opt/zulu17*; do
    if [[ -d "$candidate" && -x "$candidate/bin/java" ]]; then
      ZULU_HOME="$candidate"
      break
    fi
  done
  if [[ -z "$ZULU_HOME" ]]; then
    echo "    WARN: no local Zulu 17 JDK found. Install one and re-run (e.g.:"
    echo "          sdk install java 17.0.18.fx-zulu), or set SKIP_JVM_PIN=1 to skip."
  else
    mkdir -p "$(dirname "$OWS_CACHE_FILE")"
    cat > "$OWS_CACHE_FILE" <<EOF
{
  "runtimes": [
    {
      "version": "17.0.18",
      "vendor": "Azul Systems, Inc.",
      "javaHome": "file://$ZULU_HOME/",
      "active": true,
      "os": "LINUX64",
      "managed": false,
      "lastUsage": "$(date -u +%Y-%m-%dT%H:%M:%S)"
    }
  ]
}
EOF
    echo "    OpenWebStart will now use $ZULU_HOME"
  fi
fi

echo
echo "=== dev-setup complete ==="
echo "Daily loop: edit code, then run ./scripts/dev-deploy.sh"
echo "Open admin console: javaws ${URLS[0]}/webstart.jnlp"
