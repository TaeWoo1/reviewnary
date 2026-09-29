#!/usr/bin/env bash
# <b>Two endpoints that are not under /api, and an edge that answered them with the app itself.</b>
#   deploy/pilot/oauth-edge-route-guard.test.sh
#
# Social sign-in is Spring Security's OAuth2 client. Its two endpoints are
#   /oauth2/authorization/{provider}   — where the button navigates
#   /login/oauth2/code/{provider}      — where the provider sends the browser back
# and neither is under /api/*, which was the only backend prefix the edge routed. Both therefore fell
# into Caddy's catch-all and were served by the SPA, which answers 200 text/html to any path it does
# not know. 200 + HTML reads as healthy in a log and in a browser; it is the defect.
#
# It hid because the two faults were stacked: with no client id/secret the backend reports no
# providers, the frontend draws no buttons, and nobody walks the path that is broken. Fill the
# credentials in and the button appears and reloads the app.
#
# Section A reads the Caddyfile — route present, pointed at the backend, and ABOVE the catch-all,
# because handle blocks match in written order and a catch-all placed first would swallow them.
# Section B runs the real smoke.sh against a stub curl and checks it scores the postures correctly.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }
CF="$HERE/Caddyfile"

printf 'A. the edge routes both OAuth endpoints to the backend\n'

# Line number of a `handle <prefix> {` block, and of the catch-all `handle {`.
lineof() { grep -nE "^[[:space:]]*handle $1 \{" "$CF" | head -1 | cut -d: -f1; }
# The proxy target on the line after a handle block opens.
targetof() { awk -v s="$1" 'NR>s && NR<=s+3 && /reverse_proxy/{print $2; exit}' "$CF"; }

catchall="$(grep -nE '^[[:space:]]*handle \{' "$CF" | head -1 | cut -d: -f1)"
[[ -n "$catchall" ]] \
  && ok "the SPA catch-all is still present (line $catchall)" \
  || no "the SPA catch-all is still present" "handle { } is gone — the app would not be served at all"
[[ "$(targetof "$catchall")" == "frontend:80" ]] \
  && ok "the catch-all still serves the SPA (frontend:80)" \
  || no "the catch-all still serves the SPA" "got $(targetof "$catchall")"

for p in '/oauth2/\*' '/login/oauth2/\*'; do
  pretty="${p//\\/}"
  ln="$(lineof "$p")"
  if [[ -z "$ln" ]]; then
    no "Caddyfile routes $pretty" "no handle block — it would fall through to the SPA"
    continue
  fi
  ok "Caddyfile routes $pretty (line $ln)"
  t="$(targetof "$ln")"
  [[ "$t" == "backend:8080" ]] \
    && ok "$pretty goes to backend:8080" \
    || no "$pretty goes to backend:8080" "got '${t:-<none>}'"
  [[ -n "$catchall" && "$ln" -lt "$catchall" ]] \
    && ok "$pretty is matched before the catch-all" \
    || no "$pretty is matched before the catch-all" "route=$ln catch-all=$catchall — handle matches in written order"
done

printf 'B. smoke refuses an OAuth endpoint answered by the SPA\n'

mkdir -p "$WORK/bin"
# Stub curl: map lines are "<url> <code> <content-type>". FAIL reproduces a transport failure
# (the -w template is written, then a non-zero exit).
cat > "$WORK/bin/curl" <<'CURL'
#!/usr/bin/env bash
url=""; want=""
prev=""
for a in "$@"; do
  [[ "$prev" == "-w" ]] && want="$a"
  [[ "$a" == http://* || "$a" == https://* ]] && url="$a"
  prev="$a"
done
row="$(awk -v u="$url" '$1==u{print; exit}' "${CURL_MAP:-/dev/null}")"
code="$(printf '%s' "$row" | awk '{print $2}')"; ct="$(printf '%s' "$row" | awk '{print $3}')"
[[ -n "$code" ]] || { code=200; ct=application/json; }
if [[ "$code" == FAIL ]]; then
  [[ "$want" == '%{http_code}' ]] && printf '000'
  exit 7
fi
loc="$(printf '%s' "$row" | awk '{print $4}')"
case "$want" in
  '%{http_code}')    printf '%s' "$code" ;;
  '%{content_type}') printf '%s' "${ct:-application/json}" ;;
  '%{redirect_url}') printf '%s' "${loc:-}" ;;
  *)                 printf '%s' "${CURL_BODY:-}" ;;
esac
exit 0
CURL
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/docker"
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/ss"
chmod +x "$WORK/bin"/*
export PATH="$WORK/bin:$PATH"

AUTHZ=https://pilot.example.com/oauth2/authorization/google
CBK=https://pilot.example.com/login/oauth2/code/google

# $1 = google client id ("" = not configured), $2..$3 = code/ctype for BOTH google paths
run() {
  cat > "$WORK/pilot.env" <<ENV
PILOT_PUBLIC_HOST=pilot.example.com
PILOT_ACME_EMAIL=ops@example.com
POSTGRES_PASSWORD=not-a-real-password
SELLEROPS_JWT_SECRET=0123456789abcdef0123456789abcdef0123
SELLEROPS_OAUTH_GOOGLE_CLIENT_ID=$1
ENV
  chmod 600 "$WORK/pilot.env"
  { printf '%s %s %s\n' "$AUTHZ" "$2" "$3"; printf '%s %s %s\n' "$CBK" "$2" "$3"; } > "$WORK/map"
  CURL_MAP="$WORK/map" PILOT_PUBLIC_HOST=pilot.example.com PILOT_ENV_FILE="$WORK/pilot.env" \
    bash "$HERE/smoke.sh" 2>&1 | grep -E 'oauth2/authorization/google|login/oauth2/code/google' | head -2
}

# The defect itself: the SPA answering an OAuth endpoint.
out="$(run '' 200 text/html)"
[[ "$out" == *"served the SPA"* && "$out" != *"  ok"* ]] \
  && ok "SPA answer (200 text/html) FAILS, provider unconfigured" \
  || no "SPA answer FAILS, provider unconfigured" "got: $out"
out="$(run 'a-client-id' 200 text/html)"
[[ "$out" == *"served the SPA"* && "$out" != *"  ok"* ]] \
  && ok "SPA answer (200 text/html) FAILS, provider configured" \
  || no "SPA answer FAILS, provider configured" "got: $out"

# Correctly routed, provider on: Spring redirects the browser to the provider.
out="$(run 'a-client-id' 302 '')"
[[ "$out" == *"  ok"* && "$out" != *"served the SPA"* ]] \
  && ok "302 to the provider is ok when configured" \
  || no "302 is ok when configured" "got: $out"

# Correctly routed, provider off: Spring has no registration, so it 404s — that is the backend, not
# the SPA, and it is the correct answer for a deployment that offers no social login.
out="$(run '' 404 '')"
[[ "$out" == *"  ok"* ]] \
  && ok "404 from the backend is ok when unconfigured" \
  || no "404 is ok when unconfigured" "got: $out"

# A dead edge is never a pass.
out="$(run 'a-client-id' FAIL '')"
[[ "$out" == *"did not answer"* && "$out" != *"  ok"* ]] \
  && ok "a transport failure FAILS" \
  || no "a transport failure FAILS" "got: $out"

# Both endpoints are scored, not just the first.
out="$(run '' 404 '')"
[[ "$(printf '%s\n' "$out" | grep -c '  ok')" -eq 2 ]] \
  && ok "both the authorize and the callback path are scored" \
  || no "both paths are scored" "got: $out"

printf 'C. the redirect_uri handed to the provider is the registered HTTPS callback\n'

# The application must read X-Forwarded-*. Caddy sends them (X-Forwarded-Proto: https,
# X-Forwarded-Host), but Spring Boot's default for this property is `none`, which discards them —
# and then Spring Security builds redirect_uri from the CONNECTION scheme, which behind a
# TLS-terminating edge is always http. Both providers answer redirect_uri_mismatch.
YML="$(cd "$HERE/../.." && pwd)/backend/src/main/resources/application.yml"
fhs="$(grep -E '^[[:space:]]+forward-headers-strategy:' "$YML" | head -1 | sed -E 's/^[[:space:]]*forward-headers-strategy:[[:space:]]*//')"
[[ -n "$fhs" ]] \
  && ok "application.yml sets server.forward-headers-strategy" \
  || no "application.yml sets server.forward-headers-strategy" "unset means Spring Boot's default 'none' — X-Forwarded-* is discarded"
case "$fhs" in
  *native*|*framework*) ok "the strategy is one that reads the headers ($fhs)" ;;
  *none*)               no "the strategy reads the headers" "it is 'none' — the headers are discarded" ;;
  "")                   ;;
  *)                    no "the strategy reads the headers" "unrecognised value: $fhs" ;;
esac
# It must sit under server:, not somewhere Spring will never read.
awk '/^server:/{f=1;next} /^[a-z]/{f=0} f&&/forward-headers-strategy:/{found=1} END{exit !found}' "$YML" \
  && ok "it is under the server: block" \
  || no "it is under the server: block" "Spring only reads server.forward-headers-strategy"

# And smoke must actually catch a wrong scheme. Drive it with a stubbed authorize Location.
AUTHZ_G=https://pilot.example.com/oauth2/authorization/google
runloc() { # $1 = the redirect_uri the provider would be handed
  cat > "$WORK/pilot.env" <<ENV
PILOT_PUBLIC_HOST=pilot.example.com
PILOT_ACME_EMAIL=ops@example.com
POSTGRES_PASSWORD=not-a-real-password
SELLEROPS_JWT_SECRET=0123456789abcdef0123456789abcdef0123
SELLEROPS_OAUTH_GOOGLE_CLIENT_ID=a-client-id
ENV
  chmod 600 "$WORK/pilot.env"
  enc="$(printf '%s' "$1" | sed -e 's|:|%3A|g' -e 's|/|%2F|g')"
  printf '%s 302 - %s\n' "$AUTHZ_G" "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&redirect_uri=$enc&scope=openid" > "$WORK/map"
  printf '%s 302 -\n' "https://pilot.example.com/login/oauth2/code/google" >> "$WORK/map"
  CURL_MAP="$WORK/map" PILOT_PUBLIC_HOST=pilot.example.com PILOT_ENV_FILE="$WORK/pilot.env" \
    bash "$HERE/smoke.sh" 2>&1 | grep -i 'redirect_uri' | head -1
}
out="$(runloc 'https://pilot.example.com/login/oauth2/code/google')"
[[ "$out" == *"  ok"* ]] \
  && ok "smoke accepts the https redirect_uri" \
  || no "smoke accepts the https redirect_uri" "got: ${out:-<no line>}"
out="$(runloc 'http://pilot.example.com/login/oauth2/code/google')"
[[ "$out" == *"  FAIL"* && "$out" == *"X-Forwarded-Proto"* ]] \
  && ok "smoke REFUSES the http redirect_uri and names the cause" \
  || no "smoke refuses the http redirect_uri" "got: ${out:-<no line>}"
out="$(runloc 'https://someone-elses-host/login/oauth2/code/google')"
[[ "$out" == *"  FAIL"* ]] \
  && ok "smoke refuses a redirect_uri pointing at another host" \
  || no "smoke refuses a foreign host" "got: ${out:-<no line>}"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ $fail -eq 0 ]]
