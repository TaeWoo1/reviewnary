#!/usr/bin/env bash
# <b>A dead connection that scored as a pass, and a route that was required to exist while switched off.</b>
#   deploy/pilot/smoke-harness-guard.test.sh
#
# Two defects in smoke.sh itself. Neither was in the product; both made the deploy log lie.
#
#   1. code() was `curl … -w '%{http_code}' … || echo 000`. curl writes the -w template even when
#      the transfer fails, and on a transport failure that template is already "000" — so the
#      fallback appended a second one and the function returned "000000". Every check written as
#      `!= "000"` therefore PASSED when nothing answered at all. On the rc5 host, with no
#      certificate and every HTTPS call timing out, smoke printed
#      `ok Cafe24 callback route reachable (HTTP 000000)`.
#   2. The Cafe24 callback check demanded the route be mapped unconditionally. But
#      Cafe24ConnectController is @ConditionalOnProperty(sellerops.connector.cafe24.enabled), so with
#      the connector OFF — the approved pilot posture — the bean does not exist and 404 is correct.
#      The same run printed `ok Cafe24 connector OFF` two checks later. It contradicted itself.
#
# This guard runs the REAL smoke.sh against a stub curl whose answers it dictates, and scores the
# lines smoke.sh prints. `docker` and `ss` refuse, so the container-facing checks fail — those are
# not this guard's business and are never scored. Only the two behaviours above are.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

mkdir -p "$WORK/bin"
# Stub curl. CURL_MAP lines are "<url> <code>"; the code "FAIL" reproduces a real transport failure
# exactly — curl prints the -w template (000) and THEN exits non-zero. That pairing is the whole
# reason the old code() produced "000000", so the stub has to keep it.
cat > "$WORK/bin/curl" <<'CURL'
#!/usr/bin/env bash
url=""; want_code=0
for a in "$@"; do
  [[ "$a" == "-w" ]] && want_code=1
  [[ "$a" == http://* || "$a" == https://* ]] && url="$a"
done
code="$(awk -v u="$url" '$1==u{print $2; exit}' "${CURL_MAP:-/dev/null}")"
[[ -n "$code" ]] || code=200
if [[ "$code" == FAIL ]]; then
  [[ $want_code -eq 1 ]] && printf '000'
  exit 7
fi
if [[ $want_code -eq 1 ]]; then printf '%s' "$code"; else printf '%s' "${CURL_BODY:-}"; fi
exit 0
CURL
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/docker"
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/ss"
chmod +x "$WORK/bin"/*
export PATH="$WORK/bin:$PATH"

CB=https://pilot.example.com/api/connect/cafe24/callback

# A pilot env that is valid for everything this guard does NOT vary.
mkenv() {
  cat > "$WORK/pilot.env" <<ENV
PILOT_PUBLIC_HOST=pilot.example.com
PILOT_ACME_EMAIL=ops@example.com
POSTGRES_PASSWORD=not-a-real-password
SELLEROPS_JWT_SECRET=0123456789abcdef0123456789abcdef0123
SELLEROPS_CONNECTOR_CAFE24_ENABLED=$1
ENV
  chmod 600 "$WORK/pilot.env"
}

# Run smoke.sh with the callback answering $2 and the connector $1. Returns smoke's whole output.
run() {
  mkenv "$1"
  printf '%s %s\n' "$CB" "$2" > "$WORK/map"
  CURL_MAP="$WORK/map" PILOT_PUBLIC_HOST=pilot.example.com PILOT_ENV_FILE="$WORK/pilot.env" \
    bash "$HERE/smoke.sh" 2>&1
}
# The one line this guard is about.
cbline() { printf '%s' "$1" | grep -i 'Cafe24 callback\|callback route' | head -1; }

printf 'A. a transport failure is 000, once, and never a pass\n'

out="$(run false FAIL)"
line="$(cbline "$out")"
case "$line" in
  *000000*) no "code() never returns 000000" "got: $line" ;;
  *)        ok "code() never returns 000000" ;;
esac
case "$line" in
  FAIL*|*"  FAIL"*) ok "a dead connection FAILS the callback check" ;;
  *)                no "a dead connection FAILS the callback check" "got: ${line:-<no line>}" ;;
esac
case "$line" in
  *"did not answer"*) ok "the failure names the transport, not a status code" ;;
  *)                  no "the failure names the transport" "got: $line" ;;
esac
# The whole run must not contain the old artefact anywhere.
case "$out" in
  *000000*) no "no check anywhere prints 000000" "$(printf '%s' "$out" | grep -m1 000000)" ;;
  *)        ok "no check anywhere prints 000000" ;;
esac
# ...and with the connector ON, a dead connection must still not pass.
out="$(run true FAIL)"; line="$(cbline "$out")"
case "$line" in
  *"  ok"*) no "Cafe24 ON: a dead connection still FAILS" "it passed: $line" ;;
  *)        ok "Cafe24 ON: a dead connection still FAILS" ;;
esac

printf 'B. the callback check follows the connector flag\n'

# OFF: the controller is a conditional bean, so 404 is the correct answer.
out="$(run false 404)"; line="$(cbline "$out")"
case "$line" in
  *"  ok"*) ok "Cafe24 OFF + 404 → ok (the bean is conditional; absent is correct)" ;;
  *)        no "Cafe24 OFF + 404 → ok" "got: $line" ;;
esac
# OFF but answering: the flag says off and the route exists anyway — that is a real contradiction.
out="$(run false 200)"; line="$(cbline "$out")"
case "$line" in
  *"  ok"*) no "Cafe24 OFF + 200 → FAIL" "it passed: $line" ;;
  *)        ok "Cafe24 OFF + 200 → FAIL (the controller registered despite the flag)" ;;
esac
# ON: the route must exist. A 400 from the backend (no code param) is the healthy answer.
out="$(run true 400)"; line="$(cbline "$out")"
case "$line" in
  *"  ok"*) ok "Cafe24 ON + 400 → ok (route mapped, backend refused the bare GET)" ;;
  *)        no "Cafe24 ON + 400 → ok" "got: $line" ;;
esac
# ON but 404: the connector is switched on and its controller did not register.
out="$(run true 404)"; line="$(cbline "$out")"
case "$line" in
  *"  ok"*) no "Cafe24 ON + 404 → FAIL" "it passed: $line" ;;
  *)        ok "Cafe24 ON + 404 → FAIL (connector on but the route is missing)" ;;
esac
# 502 is an edge/backend fault in either posture.
for flag in true false; do
  out="$(run $flag 502)"; line="$(cbline "$out")"
  case "$line" in
    *"  ok"*) no "Cafe24 $flag + 502 → FAIL" "it passed: $line" ;;
    *)        ok "Cafe24 $flag + 502 → FAIL (edge up, backend not answering)" ;;
  esac
done

printf 'C. smoke.sh still says the connector is off, in the same run\n'
out="$(run false 404)"
case "$out" in
  *"Cafe24 connector OFF"*) ok "the posture line and the route line agree (no self-contradiction)" ;;
  *)                        no "the posture line is still printed" "expected 'Cafe24 connector OFF'" ;;
esac

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ $fail -eq 0 ]]
