#!/usr/bin/env bash
# <b>The Runyour router route through deploy.sh's step 2 — which key a capability will really boot with.</b>
#   deploy/pilot/runyour-guard.test.sh
#
# SPRING_PROFILES_INCLUDE=runyour + RUNYOUR_API_KEY is a complete model configuration (application-runyour.yml),
# but step 2 used to require every capability's own SELLEROPS_<CAP>_API_KEY and refused it. The rule now lives in
# deploy/pilot/model-keys.sh (shared with preflight.sh) and reads keys the way docker-compose.yml hands them to the
# container (docker-compose.yml + RunyourRouterKeyPostProcessor). This scores step 2 only, exactly like capability-guard.test.sh: `docker` is a stub that refuses,
# --no-pull and --no-backup keep git and the database out of it, and no model is called.
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

ORG='87f57576-7ce7-460d-b93a-579382819fc1'

# The base is everything step 2 needs that is NOT under test, so a case varies exactly one thing.
base() {
  cat <<ENV
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
ENV
}

run() {  # run <extra env lines...>
  local env_file="$WORK/pilot.env"
  { base; printf '%s\n' "$@"; } > "$env_file"
  chmod 600 "$env_file"
  PILOT_ENV_FILE="$env_file" "$HERE/deploy.sh" --no-pull --no-backup 2>&1
}

says()    { case "$2" in *"$3"*) ok "$1" ;; *) no "$1" "expected text absent" ;; esac; }
silent()  { case "$2" in *"$3"*) no "$1" "text present when it should not be" ;; *) ok "$1" ;; esac; }
passes()  { says "$1" "$2" "env: ok"; }
refuses() { silent "$1" "$2" "env: ok"; }


DRAFT_ON=('SELLEROPS_AGENT_DRAFT_ENABLED=true')
echo "Runyour route — what deploy.sh accepts and refuses"

# ── 1. the router configuration passes ───────────────────────────────────────────────────────────
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}")"
passes "runyour profile + RUNYOUR_API_KEY + draft on, own key blank → passes" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=local-seller,runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}")"
passes "…the profile among others (comma list) is still read" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' 'SELLEROPS_AGENT_REPLY_ENABLED=true')"
passes "reply agent on under runyour with only RUNYOUR_API_KEY → passes" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' \
           'SELLEROPS_INQUIRY_DECISION_ENABLED=true' "SELLEROPS_INQUIRY_DECISION_ORG_IDS=$ORG")"
passes "inquiry decision under runyour, named organisation → passes" "$out"

# ── 2. the router configuration without its key is refused ───────────────────────────────────────
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=' "${DRAFT_ON[@]}")"
refuses "runyour profile + draft on + no RUNYOUR_API_KEY → refused" "$out"
says    "…naming RUNYOUR_API_KEY"                                     "$out" "SELLEROPS_AGENT_DRAFT_ENABLED=true but RUNYOUR_API_KEY is blank"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true' "SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS=$ORG" 'SELLEROPS_AI_TRIAGE_API_KEY=sk-not-a-real-key')"
refuses "AI triage under runyour with only its own (unread) key → refused" "$out"

# ── 3. the shapes that would send a key to the wrong party ───────────────────────────────────────
out="$(run 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}" 'SELLEROPS_AGENT_DRAFT_API_KEY=sk-not-a-real-key')"
refuses "RUNYOUR_API_KEY without the profile → refused (a key nothing reads)" "$out"
says    "…naming the profile"                                                                     "$out" "SPRING_PROFILES_INCLUDE does not name runyour"
out="$(run 'SPRING_PROFILES_INCLUDE=myrunyour' 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}")"
refuses "a profile that only CONTAINS the word (myrunyour) is not the profile → refused" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}" 'SELLEROPS_AGENT_DRAFT_API_KEY=sk-not-a-real-key')"
refuses "a direct key beside the profile → refused (it would be sent to the router)" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' 'SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED=true' "SELLEROPS_RESPONSIBILITY_INVESTIGATION_ORG_IDS=$ORG")"
refuses "investigation under runyour without VENDOR=RUNYOUR → refused (compose's OPENAI default wins, no key)" "$out"
says    "…naming the vendor variable" "$out" "SELLEROPS_RESPONSIBILITY_INVESTIGATION_VENDOR is not RUNYOUR"

# ── 4. organisation scope is unchanged under the router ──────────────────────────────────────────
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}" 'SELLEROPS_AGENT_DRAFT_ORG_IDS=*')"
refuses "runyour + draft org list of * → still refused" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' 'SELLEROPS_INQUIRY_DECISION_ENABLED=true' 'SELLEROPS_INQUIRY_DECISION_ORG_IDS=')"
refuses "runyour + inquiry decision with no organisation → still refused" "$out"
out="$(run 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' 'SELLEROPS_KNOWLEDGE_INTENT_ENABLED=true' 'SELLEROPS_KNOWLEDGE_INTENT_ORG_IDS=*')"
refuses "runyour + knowledge org list of * → still refused" "$out"

# ── 5. direct vendors behave exactly as before ───────────────────────────────────────────────────
out="$(run "${DRAFT_ON[@]}" 'SELLEROPS_AGENT_DRAFT_API_KEY=sk-not-a-real-key')"
passes "direct: draft on with its own key → passes" "$out"
out="$(run "${DRAFT_ON[@]}" 'SELLEROPS_AGENT_DRAFT_API_KEY=')"
refuses "direct: draft on with no key → refused" "$out"
says    "…with the same message as before"     "$out" "SELLEROPS_AGENT_DRAFT_ENABLED=true but SELLEROPS_AGENT_DRAFT_API_KEY is blank"
out="$(run 'SELLEROPS_AGENT_REPLY_ENABLED=true' 'SELLEROPS_AGENT_DRAFT_API_KEY=sk-not-a-real-key')"
passes "direct: reply agent borrowing the draft key → passes" "$out"
out="$(run 'SELLEROPS_AGENT_REPLY_ENABLED=true')"
refuses "direct: reply agent with neither key → refused" "$out"

# ── 6. preflight.sh reads the same rule (read-only; DNS / egress / docker are stubs that answer nothing) ─
for c in curl dig getent; do printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/$c"; chmod +x "$WORK/bin/$c"; done
pre() { local env_file="$WORK/pre.env"; { base; printf '%s\n' "$@"; } > "$env_file"; chmod 600 "$env_file"
        PILOT_ENV_FILE="$env_file" bash "$HERE/preflight.sh" 2>&1; }
out="$(pre 'SPRING_PROFILES_INCLUDE=runyour' 'RUNYOUR_API_KEY=router-not-a-real-key' "${DRAFT_ON[@]}")"
says   "preflight: runyour + key → model keys ok"        "$out" "ok    model keys: SPRING_PROFILES_INCLUDE=runyour"
out="$(pre 'SPRING_PROFILES_INCLUDE=runyour' "${DRAFT_ON[@]}")"
says   "preflight: runyour without key → FAIL naming it" "$out" "FAIL  SELLEROPS_AGENT_DRAFT_ENABLED=true but RUNYOUR_API_KEY is blank"
out="$(pre "${DRAFT_ON[@]}" 'SELLEROPS_AGENT_DRAFT_API_KEY=sk-not-a-real-key')"
says   "preflight: direct vendor with own key → ok"      "$out" "ok    model keys: every switched-on capability has its own key"
out="$(pre 'RUNYOUR_API_KEY=router-not-a-real-key')"
says   "preflight: router key without the profile → FAIL" "$out" "FAIL  RUNYOUR_API_KEY is set but SPRING_PROFILES_INCLUDE does not name runyour"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
