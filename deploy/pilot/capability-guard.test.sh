#!/usr/bin/env bash
# <b>A capability that is switched on and reaches nobody — and the first deploy that cannot say so.</b>
#   deploy/pilot/capability-guard.test.sh
#
# Two properties, both about step 2 of deploy.sh and neither about a running stack:
#
#   1. The SHIPPED template deploys. It used to turn the three knowledge-retrieval capabilities on
#      with blank organisation lists, which deploy.sh refuses — correctly — and which no operator
#      could satisfy on a first deploy, because the UUID belongs to an organisation that cannot be
#      created until the stack is serving. On + blank was unreachable and unrefusable at once.
#   2. The refusals are still refusals. Turning one on without its organisation, or with `*`, or
#      turning AI triage on with no key, still stops the deploy — and the knowledge message now names
#      the ORDER, because the order is the trap.
#
# Scored the same way responsibility-guard.test.sh is: on what step 2 printed (`env: ok`, or the
# guard's own text). `docker` is a stub that refuses, so the script dies at step 4 one line after the
# verdict; --no-pull and --no-backup keep git and the database out of it.
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

echo "AI capability wiring — what deploy.sh refuses, and what the shipped template does"

# ── 1. The shipped template, as copied, with only the host-specific values filled in ─────────────
#    This is the case the audit found broken: the template's own values could not get past step 2.
TEMPLATE="$WORK/from-template.env"
{
  # Take the template verbatim, then override only what is host-specific (a placeholder host name and
  # a placeholder secret are refused by other guards, and neither is what this test is about).
  grep -v -E '^(PILOT_PUBLIC_HOST|PILOT_ACME_EMAIL|POSTGRES_PASSWORD|SELLEROPS_JWT_SECRET|SELLEROPS_BACKUP_S3_)' \
    "$HERE/pilot.env.example"
  base
} > "$TEMPLATE"
chmod 600 "$TEMPLATE"
out="$(PILOT_ENV_FILE="$TEMPLATE" "$HERE/deploy.sh" --no-pull --no-backup 2>&1)"
passes "shipped pilot.env.example completes step 2 on a host with no organisation yet" "$out"

# ── 2. The refusal is intact, and it now names the order ─────────────────────────────────────────
out="$(run 'SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED=true' 'SELLEROPS_KNOWLEDGE_EMBEDDING_API_KEY=sk-not-a-real-key' 'SELLEROPS_KNOWLEDGE_EMBEDDING_ORG_IDS=')"
refuses "knowledge on + key + blank org list → still refused" "$out"
says    "…and the message names the two-deploy order"        "$out" "deploy once with SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED=false"

out="$(run 'SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED=true' 'SELLEROPS_KNOWLEDGE_EMBEDDING_API_KEY=sk-not-a-real-key' "SELLEROPS_KNOWLEDGE_EMBEDDING_ORG_IDS=$ORG")"
passes "knowledge on + key + named org → passes (the step-3 posture)" "$out"

out="$(run 'SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED=true' 'SELLEROPS_KNOWLEDGE_EMBEDDING_API_KEY=sk-not-a-real-key' 'SELLEROPS_KNOWLEDGE_EMBEDDING_ORG_IDS=*')"
refuses "knowledge org list of * → refused" "$out"

# ── 3. The three inquiry capabilities: the same rules, newly reachable ───────────────────────────
out="$(run 'SELLEROPS_INQUIRY_GOAL_ENABLED=true' 'SELLEROPS_INQUIRY_GOAL_API_KEY=')"
refuses "inquiry goal on with no key → refused"  "$out"
says    "…naming the key variable"               "$out" "SELLEROPS_INQUIRY_GOAL_API_KEY is blank"

out="$(run 'SELLEROPS_INQUIRY_DECISION_ENABLED=true' 'SELLEROPS_INQUIRY_DECISION_API_KEY=sk-not-a-real-key' 'SELLEROPS_INQUIRY_DECISION_ORG_IDS=')"
refuses "inquiry decision on with no organisation → refused (it declines scope widening)" "$out"

out="$(run 'SELLEROPS_INQUIRY_SIGNATURE_ENABLED=true' 'SELLEROPS_INQUIRY_SIGNATURE_API_KEY=sk-not-a-real-key' 'SELLEROPS_INQUIRY_SIGNATURE_ORG_IDS=*')"
refuses "inquiry signature org list of * → refused" "$out"

out="$(run 'SELLEROPS_INQUIRY_GOAL_ENABLED=true' 'SELLEROPS_INQUIRY_GOAL_API_KEY=sk-not-a-real-key' "SELLEROPS_INQUIRY_GOAL_ORG_IDS=$ORG" \
           'SELLEROPS_INQUIRY_DECISION_ENABLED=true' 'SELLEROPS_INQUIRY_DECISION_API_KEY=sk-not-a-real-key' "SELLEROPS_INQUIRY_DECISION_ORG_IDS=$ORG" \
           'SELLEROPS_INQUIRY_SIGNATURE_ENABLED=true' 'SELLEROPS_INQUIRY_SIGNATURE_API_KEY=sk-not-a-real-key' "SELLEROPS_INQUIRY_SIGNATURE_ORG_IDS=$ORG")"
passes "all three inquiry capabilities on, keyed and named → passes" "$out"

# ── 4. Review AI triage: nothing else would have noticed ─────────────────────────────────────────
out="$(run 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true' "SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS=$ORG" 'SELLEROPS_AI_TRIAGE_API_KEY=')"
refuses "AI triage on with no key → refused" "$out"

out="$(run 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true' 'SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS=' 'SELLEROPS_AI_TRIAGE_API_KEY=sk-not-a-real-key')"
refuses "AI triage on with no organisation → refused" "$out"

out="$(run 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true' 'SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS=*' 'SELLEROPS_AI_TRIAGE_API_KEY=sk-not-a-real-key')"
refuses "AI triage org list of * → refused" "$out"

out="$(run 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true' "SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS=$ORG" 'SELLEROPS_AI_TRIAGE_API_KEY=sk-not-a-real-key')"
passes "AI triage on, keyed and named → passes"                       "$out"
says   "…and says the list will not mark itself without the AUTO half" "$out" "SELLEROPS_SELF_PILOT_TRIAGE_AUTO_ENABLED is not true"

out="$(run 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true' "SELLEROPS_AI_TRIAGE_PILOT_ORG_IDS=$ORG" 'SELLEROPS_AI_TRIAGE_API_KEY=sk-not-a-real-key' 'SELLEROPS_SELF_PILOT_TRIAGE_AUTO_ENABLED=true')"
silent "…and stays quiet once both halves are on" "$out" "SELLEROPS_SELF_PILOT_TRIAGE_AUTO_ENABLED is not true"

# ── 5. AI triage off is off — no key, no organisation, nothing to say ────────────────────────────
out="$(run 'SELLEROPS_AI_TRIAGE_PILOT_ENABLED=false')"
passes "AI triage off → passes with nothing required" "$out"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
