#!/usr/bin/env bash
# <b>The edge answered exactly one name, and a hostname migration has no moment where that is true.</b>
#   deploy/pilot/edge-host-guard.test.sh
#
# Moving a pilot from one public name to another is not a swap, it is an OVERLAP. The new name must
# already serve HTTPS before anything is pointed at it — a redirect URI registered at Google, NAVER or
# Cafe24 is worthless until the name behind it answers — and the old name must keep serving after the
# switch, because those same consoles still carry callbacks for it and retiring them is not on our
# schedule. A site block with a single address cannot express either half.
#
# PILOT_EXTRA_HOSTS is that second half, and it is substituted TEXTUALLY into Caddy's address list,
# which is what makes it worth a guard: a wrong shape is not a warning in a log, it is a Caddyfile
# that does not parse, an edge that will not start, and a site that is down INCLUDING the name that
# worked a minute ago. The leading comma lives in the value for exactly one reason — an empty value
# must leave a single bare address, and `{$A}, {$B}` with B empty leaves a dangling comma.
#
# A. the wiring is what the Caddyfile and the compose overlay say it is
# B. Caddy itself parses the rendered file — empty and set — which is the claim that actually matters
# C. preflight rejects every wrong shape BEFORE the edge is recreated
# D. smoke verifies each extra name over real TLS, and survives the variable being unset
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0; skip=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }
sk(){ printf '  SKIP %s\n     %s\n' "$1" "${2:-}"; skip=$((skip+1)); }
CF="$HERE/Caddyfile"
OV="$HERE/docker-compose.pilot.yml"

printf 'A. the site address carries the canonical name and the extra names\n'

addr="$(grep -nE '^\{\$PILOT_PUBLIC_HOST\}' "$CF" | head -1)"
if [[ -z "$addr" ]]; then
  no "the site block opens on PILOT_PUBLIC_HOST" "no line starts with {\$PILOT_PUBLIC_HOST}"
else
  ok "the site block opens on PILOT_PUBLIC_HOST (line ${addr%%:*})"
  line="${addr#*:}"
  [[ "$line" == '{$PILOT_PUBLIC_HOST}{$PILOT_EXTRA_HOSTS} {' ]] \
    && ok "the address list is {\$PILOT_PUBLIC_HOST}{\$PILOT_EXTRA_HOSTS}" \
    || no "the address list is {\$PILOT_PUBLIC_HOST}{\$PILOT_EXTRA_HOSTS}" "got: $line"
  # The absence of a separator between the two placeholders IS the contract. A space or a comma here
  # turns the empty case into a parse error, which is the one failure mode this design exists to avoid.
  [[ "$line" != *'} '*'{$PILOT_EXTRA_HOSTS}'* && "$line" != *'},'*'{$PILOT_EXTRA_HOSTS}'* ]] \
    && ok "nothing separates the two placeholders (the comma lives in the value)" \
    || no "nothing separates the two placeholders" "a separator here breaks the empty case: $line"
fi

grep -qE '^[[:space:]]*PILOT_EXTRA_HOSTS: \$\{PILOT_EXTRA_HOSTS:-\}[[:space:]]*$' "$OV" \
  && ok "the compose overlay passes PILOT_EXTRA_HOSTS to the edge, defaulting to empty" \
  || no "the compose overlay passes PILOT_EXTRA_HOSTS to the edge" "the Caddyfile would expand it to empty on every deploy, silently"

grep -q 'PILOT_EXTRA_HOSTS' "$HERE/pilot.env.example" \
  && ok "pilot.env.example documents PILOT_EXTRA_HOSTS" \
  || no "pilot.env.example documents PILOT_EXTRA_HOSTS" "an operator would have to read the Caddyfile to learn the shape"

printf 'B. Caddy parses the rendered file in both states\n'

if ! command -v docker >/dev/null 2>&1; then
  sk "caddy validate accepts the empty and the set case" "docker is not available here — run this guard on the pilot host"
  sk "caddy validate REFUSES a dangling comma" "docker is not available here"
else
  validate() { # <extra-hosts-value> -> prints caddy's verdict, returns its exit code
    docker run --rm -i \
      -e PILOT_PUBLIC_HOST=primary.example.com \
      -e PILOT_EXTRA_HOSTS="$1" \
      -e PILOT_ACME_EMAIL=ops@example.com \
      -v "$CF:/etc/caddy/Caddyfile:ro" \
      caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1
  }
  if ! docker image inspect caddy:2-alpine >/dev/null 2>&1 && ! docker pull -q caddy:2-alpine >/dev/null 2>&1; then
    sk "caddy validate accepts the empty and the set case" "caddy:2-alpine is not available and could not be pulled"
    sk "caddy validate REFUSES a dangling comma" "caddy:2-alpine is not available"
  else
    out="$(validate "")"; rc=$?
    [[ $rc -eq 0 ]] && ok "caddy validate: empty PILOT_EXTRA_HOSTS parses (one name)" \
                    || no "caddy validate: empty PILOT_EXTRA_HOSTS parses" "$(printf '%s' "$out" | tail -3)"
    out="$(validate ",second.example.com,third.example.com")"; rc=$?
    [[ $rc -eq 0 ]] && ok "caddy validate: two extra names parse" \
                    || no "caddy validate: two extra names parse" "$(printf '%s' "$out" | tail -3)"
    # Falsification: the shape preflight refuses must ALSO be the shape Caddy refuses. If this ever
    # passes, the validation above is guarding against nothing.
    out="$(validate "second.example.com")"; rc=$?
    [[ $rc -ne 0 ]] && ok "caddy validate: a value without its leading comma is REFUSED" \
                    || no "caddy validate: a value without its leading comma is REFUSED" "caddy accepted it — the contract is not what this guard assumes"
  fi
fi

printf 'C. preflight refuses a wrong shape before anything is recreated\n'

mkenv() { # <extra value> -> path to a minimal env file
  local f="$WORK/pilot.env"
  cat > "$f" <<ENV
PILOT_PUBLIC_HOST=primary.example.invalid
PILOT_ACME_EMAIL=ops@example.com
POSTGRES_PASSWORD=x
SELLEROPS_JWT_SECRET=0123456789012345678901234567890123456789
PILOT_EXTRA_HOSTS=$1
ENV
  chmod 600 "$f"; printf '%s' "$f"
}
pf() { PILOT_ENV_FILE="$(mkenv "$1")" bash "$HERE/preflight.sh" 2>/dev/null | grep -i 'PILOT_EXTRA_HOSTS'; }

o="$(pf "")"
[[ "$o" == *"ok "*"empty"* ]] \
  && ok "preflight: empty is the steady state and passes" \
  || no "preflight: empty is the steady state and passes" "got: ${o:-<nothing>}"

o="$(pf ",second.example.invalid")"
[[ "$o" == *"ok "*"comma-led list"* ]] \
  && ok "preflight: a comma-led bare name is accepted" \
  || no "preflight: a comma-led bare name is accepted" "got: ${o:-<nothing>}"

for bad_value in "second.example.invalid" ", second.example.invalid" ",second.example.invalid," ",https://second.example.invalid" ",second.example.invalid/path"; do
  o="$(pf "$bad_value")"
  [[ "$o" == *"FAIL"* ]] \
    && ok "preflight: refuses '$bad_value'" \
    || no "preflight: refuses '$bad_value'" "got: ${o:-<nothing>} — this value would reach Caddy's address list verbatim"
done

o="$(pf ",primary.example.invalid")"
[[ "$o" == *"FAIL"* ]] \
  && ok "preflight: refuses a duplicate of PILOT_PUBLIC_HOST" \
  || no "preflight: refuses a duplicate of PILOT_PUBLIC_HOST" "Caddy refuses a repeated site address; got: ${o:-<nothing>}"

# deploy.sh is the gate that actually runs on the way to recreating the edge; preflight is advisory
# and an operator can skip it. Both must refuse, or the failure mode this guard exists for is still
# reachable by the shortest path anyone takes.
grep -q 'PILOT_EXTRA_HOSTS' "$HERE/deploy.sh" \
  && ok "deploy.sh validates PILOT_EXTRA_HOSTS too (preflight is skippable)" \
  || no "deploy.sh validates PILOT_EXTRA_HOSTS too" "a bad value would reach Caddy through a deploy that never ran preflight"
grep -q "sed -n 's/\^PILOT_EXTRA_HOSTS=//p' \"\$ENV_FILE\"" "$HERE/deploy.sh" \
  && ok "deploy.sh reads the raw line, not the sourced value" \
  || no "deploy.sh reads the raw line, not the sourced value" "a value with a space reads empty after sourcing but still reaches compose"

printf 'D. smoke checks each extra name over real TLS\n'

grep -q '_extra_hosts="${PILOT_EXTRA_HOSTS:-}"' "$HERE/smoke.sh" \
  && ok "smoke binds PILOT_EXTRA_HOSTS before expanding it" \
  || no "smoke binds PILOT_EXTRA_HOSTS before expanding it" "\${PILOT_EXTRA_HOSTS//,/ } on an unset name is fatal under set -u on bash 5"

# The same two lines smoke runs, executed here, for the case that killed shadow-watch at rc9.
if env -u PILOT_EXTRA_HOSTS bash -uc '_e="${PILOT_EXTRA_HOSTS:-}"; for x in ${_e//,/ }; do echo "$x"; done' >/dev/null 2>&1; then
  ok "smoke's loop survives PILOT_EXTRA_HOSTS being unset entirely"
else
  no "smoke's loop survives PILOT_EXTRA_HOSTS being unset entirely" "set -u killed it"
fi

grep -q 'curl' "$HERE/smoke.sh" && ! grep -qE 'code "https://\$x/".*-k|curl.*-k.*https://\$x' "$HERE/smoke.sh" \
  && ok "smoke does not pass -k for an extra name (no certificate must fail, not pass)" \
  || no "smoke does not pass -k for an extra name" "-k would score a name with no certificate as ready"

# Falsification of the scoring itself: drive the real smoke loop with a stub curl.
stub="$WORK/bin"; mkdir -p "$stub"
cat > "$stub/curl" <<'STUB'
#!/usr/bin/env bash
url=""; for a in "$@"; do case "$a" in https://*|http://*) url="$a";; esac; done
case "$url" in
  https://good.example.invalid/) printf '200';;
  https://nocert.example.invalid/) printf '000'; exit 7;;
  *) printf '404';;
esac
STUB
chmod +x "$stub/curl"
score() {
  PATH="$stub:$PATH" bash -uc '
    code() { local out; out="$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 "$@" 2>/dev/null)"
             if [[ "$out" =~ ^[0-9]{3}$ ]]; then printf "%s" "$out"; else printf "000"; fi; }
    _e="'"$1"'"
    for x in ${_e//,/ }; do
      c="$(code "https://$x/")"
      case "$c" in 200) echo "ok $x";; 000) echo "FAIL $x transport";; *) echo "FAIL $x $c";; esac
    done'
}
[[ "$(score ",good.example.invalid")" == "ok good.example.invalid" ]] \
  && ok "smoke scores a name that serves 200 as ready" \
  || no "smoke scores a name that serves 200 as ready" "got: $(score ',good.example.invalid')"
[[ "$(score ",nocert.example.invalid")" == FAIL* ]] \
  && ok "smoke scores a name with no TLS transport as NOT ready" \
  || no "smoke scores a name with no TLS transport as NOT ready" "got: $(score ',nocert.example.invalid')"

printf '\n%d passed, %d failed' "$pass" "$fail"
[[ $skip -gt 0 ]] && printf ', %d skipped' "$skip"
printf '\n'
[[ $fail -eq 0 ]]
