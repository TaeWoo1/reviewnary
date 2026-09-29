#!/usr/bin/env bash
# <b>The runtime that was only ever a devDependency — and the image that proved it too late.</b>
#   deploy/pilot/agent-runtime-image-guard.test.sh
#
# agent-runtime is not built; it is RUN from source by tsx (`serve` = `tsx src/http/main.ts`). tsx is
# therefore a production runtime, but it lived in devDependencies. agent-runtime/Dockerfile sets
# `ENV NODE_ENV=production` before `RUN npm ci`, and npm reads NODE_ENV: `npm config get omit`
# becomes `dev`, so the one binary the CMD needs is the one binary the image does not get. The
# Dockerfile even carries a comment warning not to pass `--omit=dev` — the flag was guarded, the
# environment variable six lines above it was not.
#
# Nothing caught it because every other guard here reads scripts and env, and the image was fine
# until `build --pull` refreshed node:20-slim. Then: `sh: 1: tsx: not found`, exit 127, crash loop,
# while the deploy itself reported build and up as successful.
#
# Section A is the arithmetic — daemon-free, and on its own enough to have caught this.
# Section B BUILDS THE PRODUCTION IMAGE AND BOOTS IT. That is the only claim worth making, so when
# docker is unavailable the guard says so loudly instead of implying it checked.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
AR="$REPO/agent-runtime"
pass=0; fail=0; skip=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }
sk(){ printf '  SKIP %s\n     %s\n' "$1" "${2:-}"; skip=$((skip+1)); }

printf 'A. the CMD binary is a production dependency\n'

# The runtime binary is whatever `serve` invokes first. Derive it — do not hardcode "tsx" — so this
# still holds if the entrypoint is rewritten.
BIN="$(python3 -c "import json;print(json.load(open('$AR/package.json'))['scripts']['serve'].split()[0])" 2>/dev/null)"
[[ -n "$BIN" ]] \
  && ok "serve's runtime binary is '$BIN'" \
  || no "serve's runtime binary could be read from package.json" "scripts.serve is missing"

in_deps="$(python3 -c "import json;d=json.load(open('$AR/package.json'));print('yes' if '$BIN' in d.get('dependencies',{}) else 'no')" 2>/dev/null)"
in_dev="$(python3 -c "import json;d=json.load(open('$AR/package.json'));print('yes' if '$BIN' in d.get('devDependencies',{}) else 'no')" 2>/dev/null)"
[[ "$in_deps" == yes ]] \
  && ok "'$BIN' is in dependencies" \
  || no "'$BIN' is in dependencies" "it is the production runtime; a production install must keep it"
[[ "$in_dev" == no ]] \
  && ok "'$BIN' is NOT in devDependencies" \
  || no "'$BIN' is NOT in devDependencies" "NODE_ENV=production makes npm omit dev — the CMD would not resolve"

# The lock is what `npm ci` actually obeys. package.json alone is not the contract.
lock_dev="$(python3 -c "
import json
d=json.load(open('$AR/package-lock.json'))
e=d.get('packages',{}).get('node_modules/$BIN')
print('missing' if e is None else ('dev' if e.get('dev') else 'prod'))" 2>/dev/null)"
[[ "$lock_dev" == prod ]] \
  && ok "package-lock records '$BIN' as a production package" \
  || no "package-lock records '$BIN' as a production package" "got '$lock_dev' — run: npm install --package-lock-only"

lock_root="$(python3 -c "
import json
d=json.load(open('$AR/package-lock.json'))
print('yes' if '$BIN' in d.get('packages',{}).get('',{}).get('dependencies',{}) else 'no')" 2>/dev/null)"
[[ "$lock_root" == yes ]] \
  && ok "package-lock's root dependencies list '$BIN'" \
  || no "package-lock's root dependencies list '$BIN'" "the lock and package.json disagree"

# This is the trap itself. The ENV is legitimate — it is what makes the image a production one — so
# the guard does not forbid it; it pins the consequence that must then hold.
if grep -qE '^ENV NODE_ENV=production' "$AR/Dockerfile"; then
  ok "Dockerfile sets NODE_ENV=production (so npm omits dev — hence every check above)"
else
  sk "Dockerfile sets NODE_ENV=production" "it does not; the omit=dev trap is not active, but the checks above stay correct"
fi

printf 'B. the production image boots\n'

IMG=sellerops-agent-runtime-guard:test
CN=sellerops-ar-guard-$$
if ! docker info >/dev/null 2>&1; then
  sk "build and boot the production image" "docker is not available here — run this guard on a host with docker; section A did NOT prove the image boots"
else
  if docker build -q -t "$IMG" "$AR" >/dev/null 2>&1; then
    ok "production image builds"

    got="$(docker run --rm --entrypoint sh "$IMG" -c "node_modules/.bin/$BIN --version 2>&1 | head -1")"
    [[ -n "$got" && "$got" != *"not found"* ]] \
      && ok "'$BIN' resolves inside the production image ($got)" \
      || no "'$BIN' resolves inside the production image" "got: ${got:-<nothing>} — this is exactly the exit-127 crash loop"

    # Test tooling must NOT ride along: it proves omit=dev is still doing its job.
    if docker run --rm --entrypoint sh "$IMG" -c '[ -e node_modules/.bin/vitest ]' 2>/dev/null; then
      no "the production image carries no test runner" "vitest is present — devDependencies are being installed"
    else
      ok "the production image carries no test runner (devDependencies still omitted)"
    fi

    # Boot it for real and ask it a question. A file runstore keeps this off any database; the point
    # is that the process starts and serves, which is precisely what exit 127 prevented.
    docker rm -f "$CN" >/dev/null 2>&1
    if docker run -d --name "$CN" \
         -e APP_ENV=development -e AGENT_RUNTIME_RUNSTORE_KIND=file \
         -e AGENT_RUNTIME_RUNSTORE_DIR=/tmp/runstore -e AGENT_RUNTIME_PORT=8787 \
         "$IMG" >/dev/null 2>&1; then
      up=""
      for _ in $(seq 1 30); do
        if docker exec "$CN" node -e "fetch('http://127.0.0.1:8787/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))" >/dev/null 2>&1; then
          up=yes; break
        fi
        sleep 2
      done
      if [[ "$up" == yes ]]; then
        ok "the service boots and answers /health in the production image"
      else
        no "the service boots and answers /health in the production image" \
           "last logs: $(docker logs "$CN" 2>&1 | tail -5 | tr '\n' ' ')"
      fi
      logs="$(docker logs "$CN" 2>&1)"
      case "$logs" in
        *"not found"*) no "no 'not found' in the boot log" "$(printf '%s' "$logs" | grep -m1 'not found')" ;;
        *)             ok "no 'not found' in the boot log" ;;
      esac
      docker rm -f "$CN" >/dev/null 2>&1
    else
      no "the production image starts a container" "docker run failed"
    fi
  else
    no "production image builds" "docker build failed — run it by hand for the log"
  fi
fi

printf '\n%d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skip"
[[ $fail -eq 0 ]]
