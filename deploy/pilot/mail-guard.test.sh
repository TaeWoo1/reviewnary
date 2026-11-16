#!/usr/bin/env bash
# <b>Password-reset mail through deploy.sh's step 2 — smtp must name a server and a sender.</b>
#   deploy/pilot/mail-guard.test.sh
#
# docker-compose.yml hands SPRING_MAIL_* to the backend only when the env file sets them (null-valued
# entries). A set-but-empty SPRING_MAIL_HOST still creates Spring's mail sender, so mode=smtp would boot and
# fail on every reset mail; deploy.sh refuses that before anything is built. `docker` is a stub that refuses,
# --no-pull and --no-backup keep git and the database out, and no mail is sent.
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

echo "Password-reset mail — what deploy.sh accepts and refuses"
out="$(run)"; passes "mail mode unset (off) → passes" "$out"
out="$(run 'SELLEROPS_MAIL_MODE=off' 'SPRING_MAIL_HOST=')"; passes "off + a blank host line → passes (nothing is sent)" "$out"
out="$(run 'SELLEROPS_MAIL_MODE=smtp' 'SPRING_MAIL_HOST=smtp.example.com' 'SELLEROPS_MAIL_FROM=no-reply@pilot.example.com')"
passes "smtp + host + sender → passes" "$out"
out="$(run 'SELLEROPS_MAIL_MODE=smtp' 'SELLEROPS_MAIL_FROM=no-reply@pilot.example.com')"
refuses "smtp without SPRING_MAIL_HOST → refused" "$out"; says "…and names the host" "$out" "SPRING_MAIL_HOST"
out="$(run 'SELLEROPS_MAIL_MODE=smtp' 'SPRING_MAIL_HOST=' 'SELLEROPS_MAIL_FROM=no-reply@pilot.example.com')"
refuses "smtp with a blank SPRING_MAIL_HOST → refused" "$out"
out="$(run 'SELLEROPS_MAIL_MODE=SMTP ' 'SPRING_MAIL_HOST=' 'SELLEROPS_MAIL_FROM=no-reply@pilot.example.com')"
refuses "…upper case / trailing space is still smtp → refused" "$out"
out="$(run 'SELLEROPS_MAIL_MODE=smtp' 'SPRING_MAIL_HOST=smtp.example.com')"
refuses "smtp without SELLEROPS_MAIL_FROM → refused" "$out"; says "…and names the sender" "$out" "SELLEROPS_MAIL_FROM"
out="$(run 'SELLEROPS_MAIL_MODE=smtp' 'SPRING_MAIL_HOST=smtp.example.com' 'SELLEROPS_MAIL_FROM=no-reply@localhost')"
refuses "smtp with the localhost default sender → refused" "$out"
out="$(run 'SELLEROPS_MAIL_MODE=dev-outbox')"; refuses "dev-outbox → still refused" "$out"

# preflight.sh reads the same rule (it reports every line instead of stopping at the first).
pre() { local env_file="$WORK/pre.env"; { base; printf '%s\n' "$@"; } > "$env_file"; chmod 600 "$env_file"
        PILOT_ENV_FILE="$env_file" bash "$HERE/preflight.sh" 2>&1; }
out="$(pre)"; says "preflight: off → ok, mail not sent" "$out" "ok    mail mode off: password reset mail is not sent"
out="$(pre 'SELLEROPS_MAIL_MODE=smtp' 'SPRING_MAIL_HOST=' 'SELLEROPS_MAIL_FROM=no-reply@pilot.example.com')"
says "preflight: smtp + blank host → FAIL naming it" "$out" "FAIL  SELLEROPS_MAIL_MODE=smtp needs SPRING_MAIL_HOST"
out="$(pre 'SELLEROPS_MAIL_MODE=smtp' 'SPRING_MAIL_HOST=smtp.example.com' 'SELLEROPS_MAIL_FROM=no-reply@pilot.example.com')"
says "preflight: smtp + host + sender → ok" "$out" "ok    smtp: SPRING_MAIL_HOST set"; says "…and the sender ok" "$out" "ok    smtp: sender address set"

echo; echo "mail guard: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
