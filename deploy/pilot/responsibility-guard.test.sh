#!/usr/bin/env bash
# <b>고객 운영 관리: the job is open for a seller, but nothing runs it.</b>
#   deploy/pilot/responsibility-guard.test.sh
#
# The gap this pins is not a crash and not a missing value — every switch involved is doing exactly
# what it was set to do. RESPONSIBILITY_RUNTIME_ORG_IDS makes the job visible and startable for an
# organisation; SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED is the only thing that ever works one of
# its windows. Set the first without the second and the seller presses 「자동 확인 시작」, reads
# 「자동 확인 중」, and waits for a check that no code path will ever run.
#
# The property under test is the ENV VALIDATION STEP of deploy.sh, so every case is scored on what
# step 2 printed: `env: ok` (the last line of the step, printed before anything is built) or the
# guard's own message. Nothing is deployed, and the run is stopped on purpose at the first thing that
# would touch an image: `docker` is a stub on PATH that refuses, so step 4's `build --pull` ends the
# script under `set -e` a line after the verdict we are reading. --no-pull and --no-backup keep git
# and the database out of it too.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

# The env file must live OUTSIDE the checkout — deploy.sh refuses one inside it, and that refusal is
# itself a rule worth not tripping over here. mktemp is outside by construction.
mkdir -p "$WORK/bin"
# Exits 1, not 0: a stub that SUCCEEDS would let the script run on into the health loop, which waits
# 300 seconds for a backend that does not exist. Refusing is both faster and more honest — this test
# has no opinion about anything after step 2.
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/docker"; chmod +x "$WORK/bin/docker"
# The off-host backup uploader (blocker B5). deploy.sh refuses a pilot host that cannot perform the
# upload, so a fixture without it would stop at that guard and never reach the pair under test here.
# A stub rather than the host's own aws: whether this machine happens to have the AWS CLI installed is
# not allowed to decide what this test measures.
printf '#!/usr/bin/env bash\necho aws-cli/2.0.0\n' > "$WORK/bin/aws"; chmod +x "$WORK/bin/aws"
export PATH="$WORK/bin:$PATH"

GUARD='RESPONSIBILITY_RUNTIME_ORG_IDS names an organisation'
ORG='87f57576-7ce7-460d-b93a-579382819fc1'

# A pilot env that passes every OTHER check in step 2, so the only thing a case varies is the pair.
# The four off-host backup names are part of «every other check» since B5 became fail-closed: they are
# the shipped pilot posture, not a variation. deploy/pilot/backup-guard.test.sh owns what happens when
# they are absent; nothing about the assertions below changed.
# Factored out so the cases below can add lines to the SAME baseline. Two hand-copied baselines in
# one file is how a case ends up measuring a different deployment from the one next to it.
base_env() {
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

deploy_with() {  # extra env lines on stdin
  local env_file="$WORK/pilot.env"
  { base_env; cat; } > "$env_file"
  chmod 600 "$env_file"
  PILOT_ENV_FILE="$env_file" "$HERE/deploy.sh" --no-pull --no-backup 2>&1
}

run() {  # run <rollout-value> <scheduler-value>
  deploy_with <<ENV
RESPONSIBILITY_RUNTIME_ORG_IDS="$1"
SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=$2
ENV
}

echo "responsibility rollout × scheduler — the pair deploy.sh refuses to split"

# 1. The gap itself: a named organisation with nothing to work its windows.
out="$(run "$ORG" false)"
case "$out" in
  *"$GUARD"*) ok "rollout named + scheduler false → refused" ;;
  *) no "rollout named + scheduler false → refused" "guard message absent" ;;
esac
case "$out" in
  *"env: ok"*) no "rollout named + scheduler false → stops IN step 2" "env validation reported ok" ;;
  *) ok "rollout named + scheduler false → stops IN step 2" ;;
esac

# 2. The working pilot posture: both halves on.
out="$(run "$ORG" true)"
case "$out" in
  *"env: ok"*) ok "rollout named + scheduler true → passes" ;;
  *) no "rollout named + scheduler true → passes" "env validation did not report ok" ;;
esac
case "$out" in *"$GUARD"*) no "rollout named + scheduler true → no guard message" "guard fired anyway" ;; *) ok "rollout named + scheduler true → no guard message" ;; esac

# 3. Nobody is offered the job, so nothing is owed a scheduler. This is the SHIPPED pilot posture
#    (pilot.env.example: scheduler false, rollout blank) and it has to keep deploying.
out="$(run "" false)"
case "$out" in
  *"env: ok"*) ok "rollout blank + scheduler false → passes (the shipped posture)" ;;
  *) no "rollout blank + scheduler false → passes (the shipped posture)" "env validation did not report ok" ;;
esac
case "$out" in *"$GUARD"*) no "rollout blank + scheduler false → no guard message" "guard fired on an empty rollout" ;; *) ok "rollout blank + scheduler false → no guard message" ;; esac

# 4. Blank is blank however it is spelled. `ResponsibilityRollout.parse` trims each segment and drops
#    the empty ones, so separators and padding name nobody — and a guard that read them as «named»
#    would refuse a deployment the backend itself considers empty.
out="$(run " , " false)"
case "$out" in
  *"env: ok"*) ok "rollout of separators only → treated as blank" ;;
  *) no "rollout of separators only → treated as blank" "guard read whitespace/commas as an organisation" ;;
esac


# ── The investigator's config: reachable from the host, and the same number in both files ─────────
#
# The gap these pin is not a refusal and not a crash. `CaseInvestigationProperties` reads eight names
# under `sellerops.responsibility.investigation.*`; three of them reached the backend container and
# five did not, so a host could set MAX_PER_RUN=3, watch the deploy report success, and run 5. A
# capability whose SPEND cannot be set from the env file is a capability that gets changed in the code.
#
# Static assertions — no deploy.sh run, because the property is about what two files say.
echo
echo "investigation config — compose passthrough, and one default in two places"

COMPOSE="$HERE/../../docker-compose.yml"
APPYML="$HERE/../../backend/src/main/resources/application.yml"
TEMPLATE="$HERE/pilot.env.example"

# Every name the properties class reads. Written out rather than derived: the point is that a name
# added to the Java constructor and forgotten here should FAIL, and a list derived from the Java would
# quietly grow with it.
for n in ENABLED ORG_IDS API_KEY VENDOR MODEL MAX_OUTPUT_TOKENS REASONING_EFFORT MAX_PER_RUN; do
  name="SELLEROPS_RESPONSIBILITY_INVESTIGATION_$n"
  if grep -q "^      $name: " "$COMPOSE"; then
    ok "compose hands the backend $name"
  else
    no "compose hands the backend $name" "the container cannot see this name, so setting it on the host does nothing"
  fi
done

# The five non-blank defaults exist twice now (application.yml and the compose fallback) because
# compose cannot omit a key conditionally — a bare `${VAR:-}` would override the default with "" on
# every host that says nothing. Two copies are only safe while they are equal, and this is what makes
# them equal.
#
# Both sides are READ. Writing the expected values here would make this script a THIRD copy of the
# thing whose duplication it exists to police — and a third copy is the one that goes stale quietly,
# because nothing compares it to anything.
check_default() {  # check_default <ENV-SUFFIX> <application.yml property>
  local name="SELLEROPS_RESPONSIBILITY_INVESTIGATION_$1"
  local in_compose in_yml
  in_compose="$(sed -n "s/^      $name: \${$name:-\(.*\)}$/\1/p" "$COMPOSE")"
  in_yml="$(sed -n "s/^      $2: \${$name:\(.*\)}$/\1/p" "$APPYML")"
  if [[ -n "$in_compose" && "$in_compose" == "$in_yml" ]]; then
    ok "$1 default agrees in both files ($in_yml)"
  else
    no "$1 default agrees in both files" "compose='$in_compose' application.yml='$in_yml' — a blank fallback erases the default on every silent host; a different one runs a value nobody wrote"
  fi
}
check_default VENDOR            vendor
check_default MODEL             model
check_default MAX_OUTPUT_TOKENS max-output-tokens
check_default REASONING_EFFORT  reasoning-effort
check_default MAX_PER_RUN       max-per-run

# The pilot's own answer, which is NOT the code default. 3 is a product-owner decision for the first
# window (one organisation, proving the lane works) and it lives in the template so an operator gets
# it by copying the file rather than by remembering.
if grep -q '^SELLEROPS_RESPONSIBILITY_INVESTIGATION_MAX_PER_RUN=3$' "$TEMPLATE"; then
  ok "the shipped pilot template caps the investigator at 3 per run"
else
  no "the shipped pilot template caps the investigator at 3 per run" "pilot.env.example does not set MAX_PER_RUN=3"
fi

# ── marketplace WRITE stays OFF — the regression, not a new rule ──────────────────────────────────
#
# 「고객 운영 관리」 reads, judges and prepares. The one marketplace WRITE this product performs is the
# answer send, and it has its own flag and its own single-use approval id. These cases exist because
# the two are one deploy apart in the same file: the thing to catch is a future edit that makes
# turning the runtime on turn a send on with it.
echo
echo "marketplace WRITE — off in the shipped template, and unmoved by the runtime"

for n in SELLEROPS_INQUIRY_PUBLISH_EXECUTION_ENABLED SELLEROPS_REVIEW_PUBLISH_EXECUTION_ENABLED; do
  if grep -q "^$n=false$" "$TEMPLATE"; then
    ok "$n=false in the shipped template"
  else
    no "$n=false in the shipped template" "a pilot host copied from this file would deploy with a send lane open"
  fi
done

# An approval id is a person's decision inside one turn. A value sitting in a template is a standing
# grant, which the live-approval contract does not have a shape for.
for n in SELLEROPS_INQUIRY_PUBLISH_NAVER_LIVE_APPROVAL_ID SELLEROPS_INQUIRY_PUBLISH_CAFE24_LIVE_APPROVAL_ID \
         SELLEROPS_REVIEW_PUBLISH_CAFE24_LIVE_APPROVAL_ID; do
  if grep -q "^$n=$" "$TEMPLATE"; then
    ok "$n is blank in the shipped template"
  else
    no "$n is blank in the shipped template" "a live approval id must not ship as a value"
  fi
done

# The behavioural half: the runtime and the investigator fully ON — scheduler, rollout, key, org, cap —
# and step 2 still passes with both send flags absent. Absent, not false: the shipped application.yml
# default is false, and a posture that needed them SET to keep the write closed would be the wrong
# default.
out="$(deploy_with <<ENV
RESPONSIBILITY_RUNTIME_ORG_IDS=$ORG
SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=true
SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED=true
SELLEROPS_RESPONSIBILITY_INVESTIGATION_API_KEY=not-a-real-key
SELLEROPS_RESPONSIBILITY_INVESTIGATION_ORG_IDS=$ORG
SELLEROPS_RESPONSIBILITY_INVESTIGATION_MAX_PER_RUN=3
ENV
)"
case "$out" in
  *"env: ok"*) ok "runtime + investigator ON, no send flag named → passes" ;;
  *) no "runtime + investigator ON, no send flag named → passes" "env validation did not report ok" ;;
esac
case "$out" in
  *"PUBLISH_EXECUTION"*) no "turning the runtime on says nothing about a send lane" "a publish name appeared in the verdict" ;;
  *) ok "turning the runtime on says nothing about a send lane" ;;
esac

# ── The investigator is a vendor capability like the others, and was exempt from their rules ──────
echo
echo "investigator — keyed, named, and no wildcard"

inv() {  # inv <key> <org-ids> [extra line]
  deploy_with <<ENV
RESPONSIBILITY_RUNTIME_ORG_IDS=$ORG
SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=true
SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED=true
SELLEROPS_RESPONSIBILITY_INVESTIGATION_API_KEY=$1
SELLEROPS_RESPONSIBILITY_INVESTIGATION_ORG_IDS=$2
${3:-}
ENV
}

out="$(inv "" "$ORG")"
case "$out" in
  *"SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED=true but SELLEROPS_RESPONSIBILITY_INVESTIGATION_API_KEY is blank"*)
    ok "investigation ON with no key → refused" ;;
  *) no "investigation ON with no key → refused" "guard message absent" ;;
esac

out="$(inv not-a-real-key "")"
case "$out" in
  *"SELLEROPS_RESPONSIBILITY_INVESTIGATION_ORG_IDS is blank"*) ok "investigation ON with nobody named → refused" ;;
  *) no "investigation ON with nobody named → refused" "guard message absent" ;;
esac

# The one that matters most. `*` is a REAL wildcard in AgentOperatorProperties (`allOrgs`), the
# capability declines access-policy widening precisely because its payload is a customer's own words,
# and it runs from a background window with nobody present. Every sibling capability of this class was
# already refused `*` here; this one was not.
out="$(inv not-a-real-key '*')"
case "$out" in
  *"SELLEROPS_RESPONSIBILITY_INVESTIGATION_ORG_IDS=* would send every organisation's customer questions to the vendor"*)
    ok "investigation ORG_IDS=* → refused" ;;
  *) no "investigation ORG_IDS=* → refused" "a wildcard would send every org's customer text unattended" ;;
esac

# The cap is read as an int at boot, i.e. AFTER this deploy has replaced the running container.
out="$(inv not-a-real-key "$ORG" "SELLEROPS_RESPONSIBILITY_INVESTIGATION_MAX_PER_RUN=three")"
case "$out" in
  *"MAX_PER_RUN must be a non-negative integer"*) ok "non-numeric MAX_PER_RUN → refused before the container is replaced" ;;
  *) no "non-numeric MAX_PER_RUN → refused before the container is replaced" "guard message absent" ;;
esac

# 0 is legal and is not «off» — said, never refused.
out="$(inv not-a-real-key "$ORG" "SELLEROPS_RESPONSIBILITY_INVESTIGATION_MAX_PER_RUN=0")"
case "$out" in
  *"env: ok"*) ok "MAX_PER_RUN=0 deploys" ;;
  *) no "MAX_PER_RUN=0 deploys" "a deliberate zero is a real posture" ;;
esac
case "$out" in
  *"no case is ever investigated"*) ok "MAX_PER_RUN=0 says what it means" ;;
  *) no "MAX_PER_RUN=0 says what it means" "silence here reads as «it found nothing» rather than «it never looked»" ;;
esac

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
