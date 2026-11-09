#!/usr/bin/env bash
#
# Build the seller-PC bundle for macOS on Apple Silicon (proposal §18.7 「설치 묶음 진행」, packaging option A):
# one folder a seller runs with no Java, Node or Postgres of their own.
#
#   tools/pc-bundle/build-macos.sh [out-dir]          # default dist/reviewnary-pc-macos-arm64
#
#   <out>/reviewnary              the one command (start · run · stop · status · open · autostart)
#   <out>/bin/node                official Node.js build (nodejs.org) — runs the launcher and the agent runtime
#   <out>/jre/                    a jlink runtime cut from the official Temurin JDK (Adoptium)
#   <out>/pgsql/                  portable PostgreSQL (zonky embedded-postgres-binaries, Maven Central)
#   <out>/app/backend.jar         the backend (local-seller profile at run time)
#   <out>/app/agent-runtime.mjs   the agent runtime, one ESM file
#   <out>/app/screen/             the work screen (VITE_LOCAL_SELLER build), served by the backend itself
#   <out>/app/launcher.mjs
#   <out>/BUILD.txt               versions, commit, and the vendor server this build talks to (blank = none)
#
# The three downloads are pinned by version and SHA-256 and kept in dist/.download-cache/, each in its own folder; a
# file whose checksum differs is deleted and the build stops. Homebrew's Java and Node are not used: they load
# libraries from /opt/homebrew, which a seller's Mac does not have (checked at the end with otool).
#
# What it does NOT do yet: sign, notarize, make a .app or a .dmg, or build for Intel or Windows.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="${1:-$REPO/dist/reviewnary-pc-macos-arm64}"
CACHE="$REPO/dist/.download-cache"

[ "$(uname -s)-$(uname -m)" = "Darwin-arm64" ] || { echo "macOS on Apple Silicon only" >&2; exit 2; }

TEMURIN_VERSION="17.0.21+9"
TEMURIN_FILE="OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.21_9.tar.gz"
TEMURIN_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/$TEMURIN_FILE"
TEMURIN_SHA256="78b08c7f5a0598242d5719c022e19479cb4a3aa45c37c19726a757a469ed1c15"
NODE_VERSION="v22.23.4"
NODE_FILE="node-$NODE_VERSION-darwin-arm64.tar.gz"
NODE_URL="https://nodejs.org/dist/$NODE_VERSION/$NODE_FILE"
NODE_SHA256="c9e1dbc748b33ed2d14f42445191ecaf14c56259c7a9a26d0042c49ec2b66428"
PG_VERSION="16.15.0"
PG_FILE="embedded-postgres-binaries-darwin-arm64v8-$PG_VERSION.jar"
PG_URL="https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-darwin-arm64v8/$PG_VERSION/$PG_FILE"
PG_SHA256="65b953905f0a4d46030767a6b44a52360170d69a7985f1b02aa435f2f6256a83"

# The JDK modules the backend needs (Spring Boot, JDBC, JPA, the HTTP client, TLS, XML for spreadsheets), and no more.
JRE_MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,\
java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,\
java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.naming.dns,jdk.net,\
jdk.unsupported,jdk.zipfs"

fetch() { # name url file sha256 → path of the verified file
  local dir="$CACHE/$1" path="$CACHE/$1/$3"
  mkdir -p "$dir"
  if [[ ! -f "$path" ]]; then
    curl -fsSL --retry 2 -o "$path.part" "$2" && mv "$path.part" "$path"
  fi
  if [[ "$(shasum -a 256 "$path" | cut -d' ' -f1)" != "$4" ]]; then
    rm -f "$path"
    echo "checksum mismatch for $3 — deleted; the build stops" >&2
    exit 1
  fi
  echo "$path"
}

echo "downloads (pinned, checksummed)"
JDK_TGZ="$(fetch temurin "$TEMURIN_URL" "$TEMURIN_FILE" "$TEMURIN_SHA256")"
NODE_TGZ="$(fetch node "$NODE_URL" "$NODE_FILE" "$NODE_SHA256")"
PG_JAR="$(fetch postgres "$PG_URL" "$PG_FILE" "$PG_SHA256")"

WORK="$(mktemp -d "$CACHE/work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT/bin" "$OUT/app"

echo "java runtime (jlink from Temurin $TEMURIN_VERSION)"
tar -xzf "$JDK_TGZ" -C "$WORK"
JDK="$(ls -d "$WORK"/jdk-*/Contents/Home)"
"$JDK/bin/jlink" --add-modules "$JRE_MODULES" --strip-debug --no-man-pages --no-header-files --compress=2 \
  --output "$OUT/jre"

echo "node $NODE_VERSION"
tar -xzf "$NODE_TGZ" -C "$WORK"
cp "$WORK/node-$NODE_VERSION-darwin-arm64/bin/node" "$OUT/bin/node"
cp "$WORK/node-$NODE_VERSION-darwin-arm64/LICENSE" "$OUT/bin/node.LICENSE"

echo "postgres $PG_VERSION"
mkdir -p "$WORK/pg" "$OUT/pgsql"
(cd "$WORK/pg" && unzip -q "$PG_JAR")
tar -xJf "$WORK"/pg/postgres-darwin-*.txz -C "$OUT/pgsql"

echo "backend"
(cd "$REPO/backend" && JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17}" ./gradlew -q bootJar)
cp "$(ls "$REPO"/backend/build/libs/*.jar | grep -v -- '-plain' | head -1)" "$OUT/app/backend.jar"

echo "agent runtime"
# `ws`-style CommonJS dependencies need a real `require` inside an ESM bundle.
BANNER='import { createRequire as __createRequire } from "node:module"; const require = __createRequire(import.meta.url);'
"$REPO/collector/node_modules/.bin/esbuild" "$REPO/agent-runtime/src/http/main.ts" --bundle --platform=node \
  --format=esm --target=node22 --banner:js="$BANNER" --log-level=error --outfile="$OUT/app/agent-runtime.mjs"

echo "work screen"
(cd "$REPO/frontend" && VITE_LOCAL_SELLER=true VITE_AGENT_RUNTIME_URL="http://127.0.0.1:47682" \
  npx vite build --outDir "$OUT/app/screen" --emptyOutDir --logLevel error)

cp "$HERE/launcher.mjs" "$OUT/app/launcher.mjs"
cat > "$OUT/reviewnary" <<'SH'
#!/bin/sh
# Reviewnary — start · run · stop · status · open · autostart on|off|print
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/bin/node" "$DIR/app/launcher.mjs" "$@"
SH
chmod +x "$OUT/reviewnary"

printf 'commit=%s\nbuilt=%s\njre=temurin-%s\nnode=%s\npostgres=%s\nvendor_url=%s\n' \
  "$(git -C "$REPO" rev-parse --short HEAD)$(git -C "$REPO" diff --quiet || echo '+dirty')" \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TEMURIN_VERSION" "$NODE_VERSION" "$PG_VERSION" "${REVIEWNARY_VENDOR_URL:-}" \
  > "$OUT/BUILD.txt"

echo "checking that nothing loads from /opt/homebrew"
if find "$OUT/bin" "$OUT/jre" "$OUT/pgsql" -type f \( -perm -u+x -o -name '*.dylib' \) -print0 \
    | xargs -0 otool -L 2>/dev/null | grep -q '/opt/homebrew\|/usr/local/'; then
  echo "a bundled binary links outside the bundle:" >&2
  find "$OUT/bin" "$OUT/jre" "$OUT/pgsql" -type f \( -perm -u+x -o -name '*.dylib' \) -print0 \
    | xargs -0 otool -L 2>/dev/null | grep -B5 '/opt/homebrew\|/usr/local/' >&2
  exit 1
fi

echo "built $OUT ($(du -sh "$OUT" | cut -f1))"
