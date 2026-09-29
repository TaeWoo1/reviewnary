#!/usr/bin/env bash
# <b>The observer is read-only, recovers nothing, and its own silence is the signal.</b>
#   deploy/pilot/shadow-watch-guard.test.sh
#
# Three properties worth a test rather than a comment:
#   * the SQL is SELECT only, and the session is set read-only so a future edit cannot write;
#   * the heartbeat is emitted BEFORE anything that can fail, so a dead database still produces the
#     metric whose absence means «the observer stopped»;
#   * nothing restarts, rolls back or retries — the units say Restart=no and name no OnFailure.
# Plus the one that is easy to get wrong: with no T0 the write delta must be -1, never 0.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

WATCH="$HERE/shadow-watch.sh"
INSTALL="$HERE/install-shadow-watch.sh"
# Comments name the forbidden shapes in order to forbid them; the property is about the code.
code() { sed -e 's|#.*$||' "$1"; }

echo "shadow observer — read-only, no recovery, silence is the signal"

# ── 1. read-only SQL ────────────────────────────────────────────────────────────────────────────
c="$(code "$WATCH")"
grep -qF 'default_transaction_read_only = on' <<<"$c" \
  && ok "the psql session is set read-only" || no "the psql session is set read-only" "not set"
if grep -qiE '\b(insert|update|delete|truncate|alter|drop|create|grant)\b' <<<"$c"; then
  no "no write SQL anywhere in the script" "$(grep -oiE '\b(insert|update|delete|truncate|alter|drop|create|grant)\b' <<<"$c" | sort -u | tr '\n' ' ')"
else ok "no write SQL anywhere in the script"; fi

# ── 2. no marketplace, no model ──────────────────────────────────────────────────────────────────
if grep -qiE 'coupang|smartstore|cafe24api|naver\.com|anthropic|openai' <<<"$c"; then
  no "no marketplace or model host is named" "$(grep -oiE 'coupang|smartstore|cafe24api|naver\.com|anthropic|openai' <<<"$c" | sort -u | tr '\n' ' ')"
else ok "no marketplace or model host is named"; fi

# ── 3. no recovery ───────────────────────────────────────────────────────────────────────────────
if grep -qiE 'systemctl (restart|start)|docker compose (up|restart)|pg_restore|rollback' <<<"$c"; then
  no "the observer recovers nothing" "found a recovery action"
else ok "the observer recovers nothing"; fi
ic="$(code "$INSTALL")"
grep -qF 'Restart=no' <<<"$ic" && ok "the unit declares Restart=no" || no "the unit declares Restart=no" "absent"
grep -qE '^\s*OnFailure=' <<<"$ic" && no "the unit names no OnFailure handler" "OnFailure= present" \
                                    || ok "the unit names no OnFailure handler"
grep -qF 'Type=oneshot' <<<"$ic" && ok "oneshot, not a resident loop" || no "oneshot, not a resident loop" "absent"
grep -qF 'OnUnitActiveSec=' <<<"$ic" && ok "a timer provides the cadence" || no "a timer provides the cadence" "absent"

# ── 4. the heartbeat comes first ──────────────────────────────────────────────────────────────────
hb="$(grep -n 'emit ShadowWatchHeartbeat' "$WATCH" | head -1 | cut -d: -f1)"
db="$(grep -n 'psqlro\|curl -sS' "$WATCH" | head -1 | cut -d: -f1)"
[[ -n "$hb" && -n "$db" && "$hb" -lt "$db" ]] \
  && ok "heartbeat is emitted before anything that can fail (line $hb < $db)" \
  || no "heartbeat is emitted before anything that can fail" "heartbeat=$hb firstProbe=$db"

# ── 5. behaviour: no env at all still heartbeats, and says the rest is down rather than fine ─────
# `env -u` so "no env at all" is a fact about the run and not about whatever the caller happened to
# export. That distinction is the whole test: the script used to nest a trim inside a default —
# ${SHADOW_WATCH_ORG_ID:-${RESPONSIBILITY_RUNTIME_ORG_IDS%%,*}} — which bash evaluates whenever the
# default is taken, so an UNSET (not merely empty) name was fatal under `set -u`. bash 3.2 (macOS)
# shrugged; bash 5 (every Linux host) died at that line, BEFORE the heartbeat. The guard passed on a
# laptop and the observer was dead on the host, which is the exact failure this file exists to catch.
noenv() { env -u RESPONSIBILITY_RUNTIME_ORG_IDS -u SHADOW_WATCH_ORG_ID \
            SHADOW_WATCH_DRY_RUN=1 PILOT_ENV_FILE=/dev/null "$WATCH" 2>&1; }
out="$(noenv)"
grep -qiE 'unbound variable|parameter (null or )?not set' <<<"$out" \
  && no "no unbound-variable death with the org names unset" "$(grep -m1 -iE 'unbound variable|parameter' <<<"$out")" \
  || ok "no unbound-variable death with the org names unset"
grep -qE 'ShadowWatchHeartbeat +1' <<<"$out" && ok "dead environment still emits the heartbeat" \
  || no "dead environment still emits the heartbeat" "$out"
grep -qE 'BackendHealthy +0' <<<"$out" && ok "unreachable backend reports 0, not absent" \
  || no "unreachable backend reports 0, not absent" "$out"
grep -qE 'DatabaseReachable +0' <<<"$out" && ok "unreachable database reports 0, not absent" \
  || no "unreachable database reports 0, not absent" "$out"
grep -q 'not sent' <<<"$out" && ok "dry run emits nothing to AWS" || no "dry run emits nothing to AWS" "$out"

# ── 5b. the org is chosen the same way on every bash ─────────────────────────────────────────────
# Intent: SHADOW_WATCH_ORG_ID wins; else the FIRST id of the runtime's comma list; else empty. The
# script prints no org, so read the resolution out of the script itself rather than guessing.
orgline="$(grep -n '^ORG=' "$WATCH" | head -1)"
case "${orgline#*:}" in
  *'${SHADOW_WATCH_ORG_ID:-${RESPONSIBILITY_RUNTIME_ORG_IDS'*)
    no "the trim is not nested inside the default" "line ${orgline%%:*} nests it again — bash 5 dies here under set -u" ;;
  *'ORG="${SHADOW_WATCH_ORG_ID:-'*)
    ok "the trim is not nested inside the default" ;;
  *)
    no "ORG still prefers SHADOW_WATCH_ORG_ID" "got: ${orgline#*:}" ;;
esac
grep -qE '^_org_ids="\$\{RESPONSIBILITY_RUNTIME_ORG_IDS:-\}"' "$WATCH" \
  && ok "the runtime org list is expanded with an explicit empty default" \
  || no "the runtime org list is expanded with an explicit empty default" "an unset name is fatal under set -u on bash 5"

# Run the real resolution under THIS bash, for each of the four cases.
resolve() { # $1 = SHADOW_WATCH_ORG_ID ("-" = unset), $2 = RESPONSIBILITY_RUNTIME_ORG_IDS ("-" = unset)
  local snippet
  snippet="$(grep -A1 '^_org_ids=' "$WATCH" | head -2)"
  local pre=""
  [[ "$1" == "-" ]] || pre+="SHADOW_WATCH_ORG_ID=$1; "
  [[ "$2" == "-" ]] || pre+="RESPONSIBILITY_RUNTIME_ORG_IDS=$2; "
  env -u SHADOW_WATCH_ORG_ID -u RESPONSIBILITY_RUNTIME_ORG_IDS \
    bash -c "set -u; $pre $snippet; printf '%s' \"\$ORG\"" 2>&1
}
for spec in "-|-|" "win|a,b|win" "-|first,second|first" "-||"; do
  IFS='|' read -r a b want <<<"$spec"
  got="$(resolve "$a" "$b")"
  lbl="SHADOW_WATCH_ORG_ID=$([[ "$a" == - ]] && echo unset || echo "$a"), list=$([[ "$b" == - ]] && echo unset || echo "'$b'")"
  [[ "$got" == "$want" ]] \
    && ok "org resolution: $lbl -> '$want'" \
    || no "org resolution: $lbl -> '$want'" "got '$got'"
done

# ── 6. no T0 ⇒ the write delta is -1, never 0 ─────────────────────────────────────────────────────
grep -qF 'inq=-1; rev=-1' <<<"$(code "$WATCH")" \
  && ok "without T0 the write delta is -1 (breaching), not a guessed 0" \
  || no "without T0 the write delta is -1" "the script may be reporting 0 without a baseline"

# ── 7. the installer refuses to install a watcher with no baseline and no emitter ────────────────
for b in systemctl aws; do printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/$b"; chmod +x "$WORK/$b"; done
out="$(PATH="$WORK:$PATH" PILOT_SYSTEMD_DIR="$WORK" SHADOW_WATCH_T0="" "$INSTALL" 2>&1)"
grep -q 'SHADOW_WATCH_T0 is required' <<<"$out" && ok "installer refuses without T0" \
  || no "installer refuses without T0" "$(tail -1 <<<"$out")"
[[ ! -f "$WORK/reviewnary-shadow-watch.service" ]] && ok "no unit written when it refuses" \
  || no "no unit written when it refuses" "a unit file was created"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
