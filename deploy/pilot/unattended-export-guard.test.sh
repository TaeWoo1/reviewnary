#!/usr/bin/env bash
# <b>The unattended review export: on, with nobody named.</b>
#   deploy/pilot/unattended-export-guard.test.sh
#
# The failure this pins is not a crash. SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED=true with a blank org
# or device list boots fine and then refuses every call the agent makes — which reads as «the agent is
# broken» while the truth is «this deployment named nobody». The second case is the opposite mistake: a
# `*` in either list would authorize an unattended browser session against organisations and devices
# that nobody chose.
#
# Scored on what step 2 (env validation) printed: `env: ok`, or the guard's own message. Nothing is
# deployed — `docker` is a stub that refuses, so the script ends a line after the verdict we read.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

mkdir -p "$WORK/bin"
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/docker"; chmod +x "$WORK/bin/docker"
printf '#!/usr/bin/env bash\necho aws-cli/2.0.0\n' > "$WORK/bin/aws"; chmod +x "$WORK/bin/aws"
export PATH="$WORK/bin:$PATH"

BLANK_GUARD='name the organisation and the paired agent device explicitly'
STAR_GUARD='contains * '
ORG='87f57576-7ce7-460d-b93a-579382819fc1'
DEV='2f0b4c61-9f2a-4c3e-8a7d-6b5e4d3c2b1a'

run() {  # run <enabled> <org-ids> <device-ids>
  local env_file="$WORK/pilot.env"
  cat > "$env_file" <<ENV
PILOT_PUBLIC_HOST=pilot.example.com
PILOT_ACME_EMAIL=ops@example.com
POSTGRES_PASSWORD=not-a-real-password
SELLEROPS_JWT_SECRET=0123456789abcdef0123456789abcdef0123
SELLEROPS_AGENT_ACCESS_SCOPE=CONNECTED_SELLERS
SELLEROPS_BACKUP_S3_ENABLED=true
SELLEROPS_BACKUP_S3_BUCKET=reviewnary-pilot-backups
SELLEROPS_BACKUP_S3_REGION=ap-northeast-2
SELLEROPS_BACKUP_S3_ACCESS_KEY_ID=AKIAEXAMPLEEXAMPLE
SELLEROPS_BACKUP_S3_SECRET_ACCESS_KEY=not-a-real-secret
SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED=$1
SELLEROPS_REVIEW_IMPORT_UNATTENDED_ORG_IDS="$2"
SELLEROPS_REVIEW_IMPORT_UNATTENDED_DEVICE_IDS="$3"
ENV
  chmod 600 "$env_file"
  PILOT_ENV_FILE="$env_file" "$HERE/deploy.sh" --no-pull --no-backup 2>&1
}

echo "unattended review export — enabled × named"

# 1. The shipped posture: off, both lists blank. Must keep deploying.
out="$(run false "" "")"
case "$out" in
  *"env: ok"*) ok "off + blank → passes (the shipped posture)" ;;
  *) no "off + blank → passes (the shipped posture)" "env validation did not report ok" ;;
esac

# 2. On with nothing named — the gap this guard exists for.
out="$(run true "" "")"
case "$out" in
  *"$BLANK_GUARD"*) ok "on + both lists blank → refused" ;;
  *) no "on + both lists blank → refused" "guard message absent" ;;
esac

# 3. Half-named is still not named. An org with no device is an authority nobody can exercise; a device
#    with no org is an authority over nothing.
out="$(run true "$ORG" "")"
case "$out" in
  *"$BLANK_GUARD"*) ok "on + org named, device blank → refused" ;;
  *) no "on + org named, device blank → refused" "guard message absent" ;;
esac
out="$(run true "" "$DEV")"
case "$out" in
  *"$BLANK_GUARD"*) ok "on + device named, org blank → refused" ;;
  *) no "on + device named, org blank → refused" "guard message absent" ;;
esac

# 4. A wildcard in either list.
out="$(run true "*" "$DEV")"
case "$out" in
  *"$STAR_GUARD"*|*"contains *"*) ok "on + org list is * → refused" ;;
  *) no "on + org list is * → refused" "guard message absent" ;;
esac
out="$(run true "$ORG" "*")"
case "$out" in
  *"$STAR_GUARD"*|*"contains *"*) ok "on + device list is * → refused" ;;
  *) no "on + device list is * → refused" "guard message absent" ;;
esac

# 5. The working shadow-run posture.
out="$(run true "$ORG" "$DEV")"
case "$out" in
  *"env: ok"*) ok "on + both named → passes" ;;
  *) no "on + both named → passes" "env validation did not report ok" ;;
esac
case "$out" in
  *"acquisition only"*) ok "on + both named → says what it authorized" ;;
  *) no "on + both named → says what it authorized" "note absent" ;;
esac

# 6. Blank is blank however it is spelled — separators and padding name nobody, and the guard must read
#    them the way the backend's own parser does.
out="$(run true " , " "$DEV")"
case "$out" in
  *"$BLANK_GUARD"*) ok "org list of separators only → treated as blank" ;;
  *) no "org list of separators only → treated as blank" "guard read whitespace/commas as an organisation" ;;
esac

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
