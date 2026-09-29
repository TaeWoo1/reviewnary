#!/usr/bin/env bash
# <b>The source builds and the image does not — a contract that lives one directory too high.</b>
#   deploy/pilot/frontend-build-context-guard.test.sh
#
# The frontend consumes the normative Action Window contract from its SOURCE and never keeps a local
# copy (frontend/src/lib/actionWindow/contract.ts). Those imports climb OUT of frontend/ into the
# repo-root `contracts/` sibling. `npm run build` on a developer's checkout therefore passes, while
# `docker compose build` — whose context is ./frontend — cannot see contracts/ at all and dies in
# `tsc --noEmit` on every action-window module. That gap is invisible to every other guard here:
# it is not a running stack, not an env value, and not a line of product code. It shipped through
# rc1..rc4 and only surfaced as a Cloud deploy failure.
#
# Scored on three things that must agree, plus the arithmetic that ties them together:
#   1. every FE import that escapes frontend/ goes to contracts/ and nowhere else,
#   2. docker-compose.yml hands the frontend build that tree as a named additional context,
#   3. the Dockerfile lands it at the exact absolute path those imports resolve to,
#   4. and each import's target file actually exists there.
#
# No daemon, no network, no build: this reads the three files and does the path maths itself.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
pass=0; fail=0
ok(){ printf '  ok   %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

COMPOSE="$REPO/docker-compose.yml"
DOCKERFILE="$REPO/frontend/Dockerfile"

printf 'A. the frontend image is given the contract tree\n'

# The build context is still ./frontend — if that ever widens to the repo root this guard's whole
# premise changes, and so does the .dockerignore in effect (the root one does NOT exclude
# node_modules). Fail loudly rather than pass by accident.
ctx="$(awk '/^  frontend:/{f=1} f&&/^      context:/{print $2; exit}' "$COMPOSE")"
[[ "$ctx" == "./frontend" ]] \
  && ok "compose: frontend build context is ./frontend" \
  || no "compose: frontend build context is ./frontend" "got '${ctx:-<none>}' — re-check .dockerignore coverage and this guard"

awk '/^  frontend:/{f=1} f&&/^      additional_contexts:/{g=1} g&&/^        contracts: \.\/contracts$/{print "y"; exit}' "$COMPOSE" | grep -q y \
  && ok "compose: frontend build names 'contracts: ./contracts' as an additional context" \
  || no "compose: frontend build names 'contracts: ./contracts' as an additional context" \
        "without it the image has no contracts/ and tsc fails on action-window"

# WORKDIR /app + `COPY . .` puts frontend/ at /app, so an import climbing out of frontend/ lands at
# /contracts. The COPY below is the only thing that puts anything there.
grep -qE '^WORKDIR /app$' "$DOCKERFILE" \
  && ok "Dockerfile: WORKDIR is /app (the path arithmetic below assumes it)" \
  || no "Dockerfile: WORKDIR is /app" "the escaping imports resolve relative to WORKDIR"

grep -qE '^COPY --from=contracts \. /contracts$' "$DOCKERFILE" \
  && ok "Dockerfile: COPY --from=contracts . /contracts" \
  || no "Dockerfile: COPY --from=contracts . /contracts" "the named context must be landed at /contracts"

# Ordering matters: the contract has to be on disk BEFORE tsc runs, or the COPY is decoration.
cline="$(grep -nE '^COPY --from=contracts ' "$DOCKERFILE" | head -1 | cut -d: -f1)"
bline="$(grep -nE '^RUN npm run build$' "$DOCKERFILE" | head -1 | cut -d: -f1)"
if [[ -n "$cline" && -n "$bline" && "$cline" -lt "$bline" ]]; then
  ok "Dockerfile: the contract is copied before 'RUN npm run build'"
else
  no "Dockerfile: the contract is copied before 'RUN npm run build'" "copy=${cline:-none} build=${bline:-none}"
fi


printf 'B. every import that leaves frontend/ is one the image can satisfy\n'

# Only real module specifiers count: `from "..."`, `import("...")`, `require("...")`. A plain string
# that merely NAMES a sibling file is not an import — frontend/src has two, both in contract tests
# that readFileSync a collector/ and a backend/ source. vitest never runs in the image and tsc never
# follows a string, so scoring those would fail the guard on something the image does not need.
SCAN="$(mktemp)"; trap 'rm -f "$SCAN"' EXIT
cat > "$SCAN" <<'PYEOF'
import os, re, sys
ROOT = os.getcwd()
# \x27 is an apostrophe: spelling it this way keeps the quoting readable in both languages.
SPEC = re.compile(r'(?:\bfrom|\bimport|\brequire)\s*\(?\s*(["\x27])((?:\.\./)+[^"\x27]+)\1')
esc = bad_dir = missing = 0
notes = []
for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, "frontend", "src")):
    dirnames[:] = [d for d in dirnames if d != "node_modules"]
    for fn in sorted(filenames):
        if not fn.endswith((".ts", ".tsx")):
            continue
        rel = os.path.relpath(os.path.join(dirpath, fn), ROOT)
        try:
            text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        except OSError:
            continue
        for m in SPEC.finditer(text):
            resolved = os.path.normpath(os.path.join(os.path.dirname(rel), m.group(2)))
            if resolved.startswith("frontend" + os.sep):
                continue                       # still inside the build context
            esc += 1
            if not resolved.startswith("contracts" + os.sep):
                bad_dir += 1
                notes.append("       %s -> %s (outside contracts/)" % (rel, resolved))
                continue
            # In the image frontend/ IS /app, so frontend/../contracts/X lands at /contracts/X.
            if not any(os.path.isfile(os.path.join(ROOT, resolved + e))
                       for e in (".ts", ".tsx", ".d.ts", "/index.ts", "/index.tsx", "")):
                missing += 1
                notes.append("       %s -> %s (no such contract file)" % (rel, resolved))
if notes:
    sys.stderr.write("\n".join(notes) + "\n")
print(esc, bad_dir, missing)
PYEOF
counts="$(cd "$REPO" && python3 "$SCAN")"
esc="$(printf '%s' "$counts" | awk '{print $1}')"
bad_dir="$(printf '%s' "$counts" | awk '{print $2}')"
missing="$(printf '%s' "$counts" | awk '{print $3}')"

[[ "${esc:-0}" -gt 0 ]] \
  && ok "found $esc module import(s) in frontend/src that climb out of frontend/" \
  || no "found module imports that climb out of frontend/" "expected the action-window contract imports; found none — has the bridge moved?"

[[ "${bad_dir:-1}" -eq 0 ]] \
  && ok "every escaping import points into contracts/ — the only tree the image is given" \
  || no "every escaping import points into contracts/" "${bad_dir} import(s) escape to a directory the image never receives"

[[ "${missing:-1}" -eq 0 ]] \
  && ok "every escaping import resolves to a file that exists in contracts/" \
  || no "every escaping import resolves to a file that exists" "${missing} unresolved"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ $fail -eq 0 ]]
