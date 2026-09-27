#!/usr/bin/env bash
# Install the 72h shadow observer as a systemd oneshot + 5-minute timer — Pilot Shadow Monitoring v1.
#   sudo SHADOW_WATCH_T0='2026-10-01T09:00:00+09:00' deploy/pilot/install-shadow-watch.sh
#
# oneshot + timer rather than a long-lived loop, and the reason is the thing being monitored: a resident
# process that dies stops emitting, and «stopped emitting» is exactly the state the alarms must catch. A
# timer that fails to start a oneshot ALSO stops emitting, so both failure modes land on the same signal
# (missing data) and the alarms treat missing data as breaching. A loop would additionally need its own
# liveness story; the timer already has systemd's.
#
# Nothing here recovers anything: `Restart=no`, no `OnFailure=`, no restart of the product. The observer
# reports and exits. What to do about a red metric is a person's decision.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UNIT_DIR="${PILOT_SYSTEMD_DIR:-/etc/systemd/system}"
SVC="$UNIT_DIR/reviewnary-shadow-watch.service"
TMR="$UNIT_DIR/reviewnary-shadow-watch.timer"
ENV_FILE="${PILOT_ENV_FILE:-/etc/sellerops/pilot.env}"
NAMESPACE="${SHADOW_WATCH_NAMESPACE:-Reviewnary/Shadow}"
REGION="${SHADOW_WATCH_REGION:-ap-northeast-2}"
INTERVAL="${SHADOW_WATCH_INTERVAL:-5min}"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[[ -x "$REPO/deploy/pilot/shadow-watch.sh" ]] || fail "deploy/pilot/shadow-watch.sh is missing or not executable"
command -v systemctl >/dev/null 2>&1 || fail "systemctl not found — the pilot host contract is Ubuntu 24.04 with systemd"
# The emitter. Installing a watcher that cannot emit produces exactly the silence the alarms read as a
# failure, which is safe but indistinguishable from a real outage — so refuse now instead.
command -v aws >/dev/null 2>&1 || fail "aws cli not found — the observer emits with put-metric-data (host-bootstrap.sh installs it)"

# T0 is what makes the WRITE delta a delta. Without it shadow-watch.sh emits -1 rather than guessing zero,
# and -1 breaches — correct, but it means the run has no usable write signal, so require it here.
[[ -n "${SHADOW_WATCH_T0:-}" ]] || fail "SHADOW_WATCH_T0 is required (the snapshot instant the 72h window is measured from, e.g. 2026-10-01T09:00:00+09:00)"

cat > "$SVC" <<UNIT
[Unit]
Description=reviewnary 72h shadow observer (read-only; emits CloudWatch custom metrics)
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$REPO
Environment=PILOT_ENV_FILE=$ENV_FILE
Environment=SHADOW_WATCH_NAMESPACE=$NAMESPACE
Environment=SHADOW_WATCH_REGION=$REGION
Environment=SHADOW_WATCH_T0=$SHADOW_WATCH_T0
ExecStart=$REPO/deploy/pilot/shadow-watch.sh
# No recovery, by design. A red metric is a person's decision, not a restart.
Restart=no
NoNewPrivileges=yes
PrivateTmp=yes
UNIT

cat > "$TMR" <<UNIT
[Unit]
Description=run the reviewnary shadow observer every $INTERVAL

[Timer]
OnBootSec=2min
OnUnitActiveSec=$INTERVAL
AccuracySec=30s
Unit=reviewnary-shadow-watch.service

[Install]
WantedBy=timers.target
UNIT

grep -qF "OnUnitActiveSec=$INTERVAL" "$TMR" || fail "$TMR lost its interval"
grep -qF "Type=oneshot" "$SVC" || fail "$SVC is not a oneshot"
grep -qF "Restart=no" "$SVC" || fail "$SVC must not restart anything"

systemctl daemon-reload
systemctl enable --now reviewnary-shadow-watch.timer
printf 'installed %s and %s; next firing:\n' "$SVC" "$TMR"
systemctl list-timers reviewnary-shadow-watch.timer --no-pager 2>/dev/null || true
printf '\nalarms are a SEPARATE step (they are AWS resources, not host state): docs/naver_cafe24_shadow_run_v1.md §5-A\n'
