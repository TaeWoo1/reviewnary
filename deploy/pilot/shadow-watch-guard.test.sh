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
out="$(SHADOW_WATCH_DRY_RUN=1 PILOT_ENV_FILE=/dev/null "$WATCH" 2>&1)"
grep -qE 'ShadowWatchHeartbeat +1' <<<"$out" && ok "dead environment still emits the heartbeat" \
  || no "dead environment still emits the heartbeat" "$out"
grep -qE 'BackendHealthy +0' <<<"$out" && ok "unreachable backend reports 0, not absent" \
  || no "unreachable backend reports 0, not absent" "$out"
grep -qE 'DatabaseReachable +0' <<<"$out" && ok "unreachable database reports 0, not absent" \
  || no "unreachable database reports 0, not absent" "$out"
grep -q 'not sent' <<<"$out" && ok "dry run emits nothing to AWS" || no "dry run emits nothing to AWS" "$out"

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
