#!/usr/bin/env bash
# Pilot smoke — Pilot Host Provisioning v1 §13. Read-only, no marketplace credential, no WRITE.
# Usage: PILOT_PUBLIC_HOST=host.example deploy/pilot/smoke.sh
set -uo pipefail
H="${PILOT_PUBLIC_HOST:?}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${PILOT_ENV_FILE:-/etc/sellerops/pilot.env}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$REPO/docker-compose.yml" -f "$REPO/deploy/pilot/docker-compose.pilot.yml")
# The env file drives several checks below (which connector is on, which lane was built). Sourced
# once, here, so every check reads the same file the deploy validated.
set -a; [[ -f "$ENV_FILE" ]] && . "$ENV_FILE"; set +a
pass=0; failn=0
ok()   { printf '  ok    %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '  FAIL  %s\n' "$*"; failn=$((failn+1)); }
# curl writes the -w template even when the transfer itself fails, and on a transport failure that
# template IS "000" — so the old `|| echo 000` appended a SECOND one and the function returned
# "000000". Nothing equals that, so every check written as `!= "000"` scored a dead connection as a
# pass: an unreachable edge read as "route reachable (HTTP 000000)". Normalise to exactly three
# digits, and let 000 mean precisely one thing — curl never completed a transfer.
code() {
  local out
  out="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$@" 2>/dev/null)"
  if [[ "$out" =~ ^[0-9]{3}$ ]]; then printf '%s' "$out"; else printf '000'; fi
}
# Content type of the same request. The SPA answers 200 text/html for ANY unrouted path, so on an
# endpoint that must be served by the backend this is what tells "routed" from "fell through".
ctype() { curl -sS -o /dev/null -w '%{content_type}' --max-time 15 "$@" 2>/dev/null; }

echo "smoke: https://$H"
[[ "$(code "https://$H/")" == "200" ]]                       && ok "HTTPS frontend 200"            || bad "HTTPS frontend"
[[ "$(code "http://$H/")" =~ ^30[18]$ ]]                     && ok "HTTP → HTTPS redirect"         || bad "HTTP redirect"
[[ "$(curl -sS --max-time 15 "https://$H/health")" == *'"UP"'* ]] && ok "backend /health UP via edge" || bad "backend health"
[[ "$(code "https://$H/agent-runtime/health")" == "200" ]]   && ok "agent-runtime /health via edge" || bad "agent-runtime health"
[[ "$(code "https://$H/agent-runtime/ready")" == "200" ]]    && ok "agent-runtime /ready (backend reachable)" || bad "agent-runtime ready"
[[ "$(curl -sS --max-time 15 "https://$H/api/auth/demo/config")" == *'"enabled":false'* ]] && ok "demo entry OFF" || bad "demo entry must be OFF"
# The callback is a CONDITIONAL bean: Cafe24ConnectController is
# @ConditionalOnProperty(sellerops.connector.cafe24.enabled, havingValue="true"). With the connector
# OFF the route genuinely does not exist and 404 is the correct answer — demanding it be mapped
# regardless contradicted the "Cafe24 connector OFF" check further down, in the same run.
# What must hold in BOTH postures is that the edge answered at all: 000 (no transport) and 502
# (edge up, nothing behind it) are failures either way. A 000 is what used to pass here as "000000".
c="$(code "https://$H/api/connect/cafe24/callback")"
if [[ "$c" == "000" ]]; then
  bad "Cafe24 callback route: the edge did not answer (transport failed)"
elif [[ "$c" == "502" ]]; then
  bad "Cafe24 callback route: 502 — the edge is up but the backend is not answering it"
elif [[ "${SELLEROPS_CONNECTOR_CAFE24_ENABLED:-false}" == "true" ]]; then
  [[ "$c" != "404" ]] \
    && ok "Cafe24 ON: callback route is mapped (HTTP $c)" \
    || bad "Cafe24 ON but the callback route 404s — the connector's controller did not register"
else
  [[ "$c" == "404" ]] \
    && ok "Cafe24 OFF: callback route correctly absent (404)" \
    || bad "Cafe24 OFF but the callback route answered $c — the controller registered despite the flag"
fi
# Social sign-in endpoints must reach the BACKEND. They are Spring Security's, and neither lives under
# /api — the button navigates to /oauth2/authorization/{provider} and the provider redirects back to
# /login/oauth2/code/{provider}. Caddy's catch-all serves the SPA for anything it does not route, and
# the SPA answers 200 text/html to both, which LOOKS fine and is the bug: the button would reload the
# app instead of leaving for the provider. A 200 text/html here is therefore always wrong, in every
# posture. What a correctly routed backend answers depends on whether the provider is configured, so
# the check only insists on the part that never varies.
for prov in google naver; do
  case "$prov" in
    google) conf="${SELLEROPS_OAUTH_GOOGLE_CLIENT_ID:-}" ;;
    naver)  conf="${SELLEROPS_OAUTH_NAVER_CLIENT_ID:-}" ;;
  esac
  for path in "/oauth2/authorization/$prov" "/login/oauth2/code/$prov"; do
    sc="$(code "https://$H$path")"
    if [[ "$sc" == "000" ]]; then
      bad "$path: the edge did not answer (transport failed)"
    elif [[ "$sc" == "200" && "$(ctype "https://$H$path")" == text/html* ]]; then
      bad "$path: served the SPA (200 text/html) — Caddy is not routing it to the backend"
    elif [[ -n "$conf" ]]; then
      ok "$prov configured: $path reaches the backend (HTTP $sc)"
    else
      ok "$prov not configured: $path reaches the backend, no button offered (HTTP $sc)"
    fi
  done
done
# Auth-gated API refuses anonymous reads (org isolation floor, not a login test).
[[ "$(code "https://$H/api/inquiries")" =~ ^40[13]$ ]]       && ok "API refuses anonymous"          || bad "API anonymous access"
# Raw ports are not public: from the host they must be closed on the public interface.
for p in 5432 8080 8787 5173; do
  if command -v ss >/dev/null && ss -ltn "( sport = :$p )" | grep -q ":$p"; then bad "port $p is listening on the host"; else ok "port $p not published"; fi
done
# Persistence: the data volume exists and is attached.
docker volume inspect "$(docker volume ls -q | grep -m1 sellerops_pgdata)" >/dev/null 2>&1 && ok "postgres named volume present" || bad "postgres volume"
# Restart policy.
for s in postgres backend agent-runtime frontend edge; do
  rp="$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$("${COMPOSE[@]}" ps -q "$s" 2>/dev/null)" 2>/dev/null)"
  [[ "$rp" == "unless-stopped" ]] && ok "$s restart=unless-stopped" || bad "$s restart policy ($rp)"
done
# Startup validator: a refused boot never reaches healthy, so healthy == validator green.
[[ "$("${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' | awk '$1=="backend"{print $2}')" == "healthy" ]] && ok "backend validator green (healthy)" || bad "backend not healthy"
# The guided lanes are a BUILD fact, not a runtime one: the bundle's CSP either names the seller's
# helper origin or the browser refuses it. This is the one check that catches a pilot.env that says
# guided-on against an image that was built before it did.
csp="$(curl -sS --max-time 15 "https://$H/" | tr -d '\n' | grep -o "Content-Security-Policy[^>]*" || true)"
if [[ "${PILOT_GUIDED_HELPER_ENABLED:-false}" == "true" ]]; then
  [[ "$csp" == *"${PILOT_HELPER_BRIDGE_URL:-http://127.0.0.1:47615}"* ]] \
    && ok "guided helper: CSP names the helper origin" \
    || bad "guided helper ON but the served bundle's CSP does not name it — rebuild the frontend image"
else
  [[ "$csp" != *"47615"* ]] && ok "guided helper OFF and the CSP does not name it" || bad "guided helper OFF but the bundle names the helper origin"
fi
# Runtime topology, read off the served page. The agent-runtime URL is BAKED INTO THE BUNDLE, so an
# image built without the pilot overlay carries the code default http://127.0.0.1:8787 — which on a
# remote host is the SELLER'S OWN MACHINE, and the /agent lane fails for everybody with no clue why
# (Pilot Runtime Foundation v1 §11). The CSP is where that mistake is visible from outside: it names
# every origin the page may talk to. The only loopback allowed here is the seller's own 도우미.
[[ "$csp" != *"127.0.0.1:8787"* && "$csp" != *"localhost:8787"* ]] \
  && ok "no localhost agent-runtime in the served CSP" \
  || bad "the served bundle points the browser at a local agent-runtime — VITE_AGENT_RUNTIME_URL was not set at build time"
[[ "$csp" == *"https://$H"* ]] && ok "CSP names this site's own origin for the runtime" || bad "CSP does not name https://$H"

# ── Cafe24-only pilot posture (2026-09-13 decision) ──────────────────────────────────────────────
# The redirect URI is the one value whose mistakes are invisible until a seller is standing in front
# of a Cafe24 consent screen: authorize, registration and token exchange must all be BYTE-identical,
# and the token exchange reads this very property. Checking that it is the URI THIS host serves turns
# "the callback 404s for the first seller" into a line in a deploy log.
if [[ "${SELLEROPS_CONNECTOR_CAFE24_ENABLED:-false}" == "true" ]]; then
  want="https://$H/api/connect/cafe24/callback"
  got="$("${COMPOSE[@]}" exec -T backend printenv SELLEROPS_CONNECTOR_CAFE24_REDIRECT_URI 2>/dev/null | tr -d '\r\n')"
  [[ "$got" == "$want" ]] && ok "Cafe24 redirect URI is this host's callback" \
    || bad "Cafe24 redirect URI is not https://$H/api/connect/cafe24/callback (register the SAME string in the Cafe24 app)"
  [[ -n "$(printf '%s' "${SELLEROPS_CONNECTOR_CAFE24_CLIENT_ID:-}")" ]] && ok "Cafe24 app credentials present" || bad "Cafe24 on but CLIENT_ID blank"
else
  ok "Cafe24 connector OFF (nothing to onboard yet)"
fi
# A Cafe24-only pilot needs no fixed outbound IPv4 — that is NAVER's requirement. Saying so here
# keeps an operator from treating an Elastic IP as a thing that blocks the start.
if [[ "${SELLEROPS_CONNECTOR_NAVER_ENABLED:-false}" == "true" ]]; then
  [[ -n "${SELLEROPS_CONNECTOR_NAVER_ADVERTISED_EGRESS_IPS:-}" ]] && ok "NAVER on and an advertised call IP is set" || bad "NAVER on but no advertised call IP"
else
  ok "NAVER OFF — no fixed outbound IPv4 is required for this pilot"
fi

# ── Schema state, after the deploy that just migrated it ─────────────────────────────────────────
# Every migration in this checkout reached this database, none failed, and none was BASELINED — a
# baseline row would mean the migrations before it were never applied here and nothing said so.
files="$(ls "$REPO"/backend/src/main/resources/db/migration/V*.sql 2>/dev/null | wc -l | tr -d ' ')"
row="$("${COMPOSE[@]}" exec -T postgres psql -U "${POSTGRES_USER:-sellerops}" -d "${POSTGRES_DB:-sellerops}" -tAc \
  "select count(*)||' '||count(*) filter (where not success)||' '||count(*) filter (where type='BASELINE') from flyway_schema_history" 2>/dev/null | tr -d '\r')"
read -r applied failed_m baselined <<<"${row:-0 0 0}"
[[ "$applied" == "$files" ]] && ok "migrations applied: $applied of $files in this checkout" || bad "migrations applied $applied but this checkout has $files"
[[ "${failed_m:-1}" == "0" ]] && ok "no failed migration" || bad "$failed_m failed migration(s)"
[[ "${baselined:-1}" == "0" ]] && ok "no baseline row (the schema was built, not assumed)" || bad "schema was BASELINED — earlier migrations never ran here"

# ── Pilot measurement ────────────────────────────────────────────────────────────────────────────
# The return-visit signal exists and is behind auth. It is the one endpoint on this host whose whole
# purpose is to be measured, so an anonymous POST reaching it would be an anonymous row.
[[ "$(code -X POST "https://$H/api/usage/home-opened")" =~ ^40[13]$ ]] \
  && ok "usage signal refuses anonymous" || bad "usage signal is not auth-gated"

# Outbound IP == advertised (only meaningful once NAVER is configured).
"$REPO/deploy/pilot/egress-check.sh" >/dev/null 2>&1 && ok "egress-check: host and container outbound IP agree with ADVERTISED (or NAVER not configured)" || bad "egress-check"
echo "smoke: $pass ok, $failn failed"
echo "browser steps (manual, no credential): signup → disconnected home shows 「판매 채널을 연결하면 시작할 수 있습니다」 → home command 「미답변 문의 보여줘」 returns an object → panel free-text run completes READ-only."
[[ $failn -eq 0 ]]
