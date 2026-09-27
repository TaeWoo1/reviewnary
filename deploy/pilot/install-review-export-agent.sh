#!/usr/bin/env bash
# Install the cloud NAVER review export agent as a systemd unit on the pilot host.
#   sudo deploy/pilot/install-review-export-agent.sh
#
# Minimal wiring on purpose — the agent is NOT a compose service:
#   * it needs a browser with a PERSISTENT PROFILE that survives redeploys, and a compose rebuild is
#     exactly the event that would lose it;
#   * it needs a ONE-TIME human sign-in on a visible screen, which a container started by `up -d` has
#     nowhere to show;
#   * and `restart: unless-stopped` is the wrong posture for it: a cycle that ends in AUTH_REQUIRED must
#     STOP and wait for a person, not be restarted into the same wall. Restart=no says that.
#
# It talks to the product over HTTPS like any other paired helper, so it needs no compose network and no
# port. Nothing here touches docker-compose.yml.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AGENT_DIR="$REPO/tools/naver-review-cloud-agent"
UNIT_DIR="${PILOT_SYSTEMD_DIR:-/etc/systemd/system}"
UNIT="$UNIT_DIR/reviewnary-review-export.service"
RUN_USER="${AGENT_RUN_USER:-reviewnary-agent}"

[[ -d "$AGENT_DIR" ]] || { echo "agent directory not found: $AGENT_DIR" >&2; exit 1; }
[[ -f "$AGENT_DIR/.env" ]] || { echo "create $AGENT_DIR/.env first (see .env.example)" >&2; exit 1; }
perm="$(stat -c '%a' "$AGENT_DIR/.env" 2>/dev/null || stat -f '%Lp' "$AGENT_DIR/.env")"
[[ "$perm" == "600" || "$perm" == "400" ]] || { echo ".env must be mode 0600 (is $perm)" >&2; exit 1; }

# Xvfb: the browser runs with a real window (one consistent fingerprint across the sign-in leg and every
# later cycle), so a headless host needs a virtual display. Chromium's own deps come with the package.
command -v Xvfb >/dev/null || { echo "install Xvfb first: apt-get install -y xvfb" >&2; exit 1; }

# A browser. browser-use resolves a SYSTEM binary by name and downloads nothing, so a host without one
# installs this unit happily and fails at the first cycle — hours later, inside the 72h window, with a
# verdict that looks like the agent rather than the host. These four names are the ones the library
# actually looks for (browser_use.browser.chrome), so this guard admits exactly what will work.
#
# `google-chrome-stable` is the one to install on Ubuntu 24.04: the `chromium` apt package there is a
# snap stub, and snap confinement plus this unit's ProtectSystem=strict is a second problem to have.
BROWSER=""
for b in google-chrome-stable google-chrome chromium chromium-browser; do
  if command -v "$b" >/dev/null 2>&1; then BROWSER="$b"; break; fi
done
[[ -n "$BROWSER" ]] || {
  cat >&2 <<'NOBROWSER'
no browser on PATH — the agent needs one and does not download it.
  install it first (Ubuntu 24.04, a real .deb rather than the snap stub):
    curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
      | gpg --dearmor -o /etc/apt/keyrings/google-chrome.gpg
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" \
      > /etc/apt/sources.list.d/google-chrome.list
    apt-get update -y && apt-get install -y google-chrome-stable
  accepted names: google-chrome-stable | google-chrome | chromium | chromium-browser
NOBROWSER
  exit 1
}
echo "browser: $BROWSER ($(command -v "$BROWSER"))"

id -u "$RUN_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$RUN_USER"
chown -R "$RUN_USER":"$RUN_USER" "$AGENT_DIR"

cat > "$UNIT" <<UNIT_EOF
[Unit]
Description=reviewnary NAVER review export agent (shadow run)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$AGENT_DIR
# 72h window, one cycle per AGENT_INTERVAL_MINUTES. The agent stops itself on the first non-ingest
# verdict; Restart=no is what lets that stop mean something.
ExecStart=/usr/bin/xvfb-run -a $AGENT_DIR/.venv/bin/python -m agent.run shadow --hours 72
Restart=no
# The profile, the artifacts and the run logs live under the agent directory and nowhere else.
ReadWritePaths=$AGENT_DIR
PrivateTmp=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes

[Install]
WantedBy=multi-user.target
UNIT_EOF

systemctl daemon-reload
echo "installed $UNIT (not started)."
echo
echo "next, as $RUN_USER, ONE TIME, on a screen you can see:"
echo "  sudo -u $RUN_USER xvfb-run -a $AGENT_DIR/.venv/bin/python -m agent.run login"
echo "  (or forward X / attach a VNC to that Xvfb display — you sign in yourself)"
echo "then:"
echo "  sudo -u $RUN_USER $AGENT_DIR/.venv/bin/python -m agent.run once   # prove one cycle"
echo "  systemctl start reviewnary-review-export                          # begin the 72h window"
