#!/usr/bin/env bash
# <b>The unit installs; the browser is missing; the failure arrives hours later.</b>
#   deploy/pilot/review-export-agent-guard.test.sh
#
# browser-use resolves a SYSTEM browser by name and downloads nothing. Before this guard,
# install-review-export-agent.sh checked Xvfb and not the browser, so a host without one accepted the
# unit, `systemctl start` reported success, and the first cycle failed inside the 72h window with a
# verdict that reads like the agent rather than the host.
#
# Scored on two things per case: what the script printed, and whether the UNIT FILE was written. The
# second is the point — a guard that prints a complaint after installing the unit has not fail-fasted.
#
# Hermetic PATH: only the stubs and the handful of real binaries the script needs, so a developer
# machine that happens to have Chromium installed cannot make the no-browser case pass.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_DIR="$(cd "$HERE/../.." && pwd)/tools/naver-review-cloud-agent"
WORK="$(mktemp -d)"
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

# The agent's .env is gitignored and absent in a fresh checkout; the script requires it at 0600. Create
# it only if it is not already there, and put it back exactly as found.
BORROWED_ENV=0
cleanup() {
  rm -rf "$WORK"
  [[ "$BORROWED_ENV" == "1" ]] && rm -f "$AGENT_DIR/.env"
  return 0
}
trap cleanup EXIT
if [[ ! -f "$AGENT_DIR/.env" ]]; then
  : > "$AGENT_DIR/.env"; chmod 600 "$AGENT_DIR/.env"; BORROWED_ENV=1
fi

mkdir -p "$WORK/stub" "$WORK/pure" "$WORK/units"
# the real binaries the script calls, and nothing else
# bash too: the stubs carry `#!/usr/bin/env bash`, and env searches the PATH we hand it.
for b in dirname stat cat bash; do ln -sf "$(command -v $b)" "$WORK/pure/$b"; done
# stubs: enough to be observed, never enough to change this machine
printf '#!/usr/bin/env bash\nexit 0\n'            > "$WORK/stub/Xvfb"
printf '#!/usr/bin/env bash\nexit 0\n'            > "$WORK/stub/id"
printf '#!/usr/bin/env bash\necho "useradd $*" >> "$SYSLOG"\n' > "$WORK/stub/useradd"
printf '#!/usr/bin/env bash\necho "chown $*" >> "$SYSLOG"\n'   > "$WORK/stub/chown"
printf '#!/usr/bin/env bash\necho "systemctl $*" >> "$SYSLOG"\n' > "$WORK/stub/systemctl"
chmod +x "$WORK"/stub/*
export SYSLOG="$WORK/syslog"

run() {  # run [browser-binary-name ...]   -> stdout+stderr; unit lands in $WORK/units
  rm -rf "$WORK/browsers" "$WORK/units"; mkdir -p "$WORK/browsers" "$WORK/units"; : > "$SYSLOG"
  for b in "$@"; do printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/browsers/$b"; chmod +x "$WORK/browsers/$b"; done
  PATH="$WORK/stub:$WORK/browsers:$WORK/pure" PILOT_SYSTEMD_DIR="$WORK/units" \
    "$HERE/install-review-export-agent.sh" 2>&1
}
unit_written() { [[ -f "$WORK/units/reviewnary-review-export.service" ]]; }

echo "review export agent installer — the browser guard"

# 1. The gap itself.
out="$(run)"
case "$out" in
  *"no browser on PATH"*) ok "no browser → refused" ;;
  *) no "no browser → refused" "guard message absent: $(printf '%s' "$out" | tail -2 | tr '\n' ' ')" ;;
esac
unit_written && no "no browser → unit NOT installed" "the unit was written anyway" \
             || ok "no browser → unit NOT installed"
case "$out" in
  *"google-chrome-stable"*) ok "no browser → names an installable binary" ;;
  *) no "no browser → names an installable binary" "no install hint" ;;
esac

# 2. The supported names. browser-use looks for exactly these four; the guard must admit each alone.
for b in google-chrome-stable google-chrome chromium chromium-browser; do
  out="$(run "$b")"
  case "$out" in
    *"browser: $b"*) ok "$b alone → admitted" ;;
    *) no "$b alone → admitted" "$(printf '%s' "$out" | tail -2 | tr '\n' ' ')" ;;
  esac
  unit_written && ok "$b alone → unit installed" || no "$b alone → unit installed" "no unit file"
done

# 3. Ordering is unchanged: Xvfb is still the first of the two, and its message is still its own.
rm -f "$WORK/stub/Xvfb"
out="$(run google-chrome-stable)"
case "$out" in
  *"install Xvfb first"*) ok "no Xvfb → still the Xvfb message (ordering unchanged)" ;;
  *) no "no Xvfb → still the Xvfb message" "$(printf '%s' "$out" | tail -2 | tr '\n' ' ')" ;;
esac
unit_written && no "no Xvfb → unit NOT installed" "the unit was written anyway" \
             || ok "no Xvfb → unit NOT installed"
printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/stub/Xvfb"; chmod +x "$WORK/stub/Xvfb"

# 4. Nothing on THIS machine was touched: no real user, no real systemd unit.
[[ ! -f /etc/systemd/system/reviewnary-review-export.service ]] \
  && ok "no unit was written to /etc/systemd/system" \
  || no "no unit was written to /etc/systemd/system" "a real unit file exists"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
