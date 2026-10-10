# NAVER Review Cloud Export — POC (isolated experiment)

**Status: harness complete and offline-verified. No NAVER contact has happened. No live run has happened.**

This directory answers one question and nothing else:

> Can an LLM-driven agent on a cloud browser keep a NAVER seller-center login across
> sessions and perform the **official** review Excel export, with no selector-based scraper?

It is a candidate for the `ExecutionProvider` axis that `docs/review_acquisition_aside_v2.md`
§14 already defines (`LOCAL_HELPER | ASIDE | future provider`). It is **not wired to anything.**

---

## Why this directory

`tools/` is outside every build and test graph in this repo — there is no root `package.json`,
no `tsconfig` includes it, and the four CI workflows are backend / frontend / collector /
agent-runtime only. The POC is Python with its own `.venv`, so it shares **zero** dependency
graph with `collector/` (TypeScript). Nothing here imports repo code, and no repo code can
import this.

**Untouched, by construction:** `collector/` (Aside/local acquisition, `ImportSegmentEngine`,
`NaverLiveImportDriver`, profiles, bridge), `backend/` (`FileParser`, `ReviewRowMapper`,
`IngestionService`, `review_import_*`), `frontend/`, `agent-runtime/`, `contracts/`.

## What it deliberately does NOT do

- **No ingest.** No Reviewnary endpoint is called. No `reviews` row, no `sync_jobs` row, no
  `review_import_segment_attempt` row. The artifact stops in `.artifacts/`.
- **No parser.** Per PD-8 the canonical parser is the backend's; a second copy is exactly what
  that decision forbids. This POC measures the bytes (sha256 / size / name category / sniff)
  and stops.
- **No credential typing.** The human logs in through Steel's session viewer. This tool never
  types an id, a password or a verification code.
- **No CAPTCHA / 2FA / bot-detection bypass.** Steel offers `solve_captcha` and `stealth_config`;
  the POC passes `solve_captcha=False`, never passes `stealth_config`, and re-reads the
  **server's** answer before driving the session (`SteelRunner.assert_session_fences`).
  An auth screen is a **stop** (`AUTH_REQUIRED`), never a puzzle to get around.
- **No selector, no page JS.** `poc/` contains no CSS/XPath and the agent's `evaluate` action
  is removed, along with `search`, `read_file`, `write_file`, `replace_file`, `upload_file`,
  `save_as_pdf`. `run.py selfcheck` asserts all of it against this package's own source.
- **No marketplace WRITE.** No reply, edit, delete or report. The task text forbids it and the
  excluded actions remove the means.
- **No scheduling.** One explicit run per approval (PD-7). Q-1 stays open.

## Product decisions — 2026-09-27 (product owner)

**PC-1 — PD-1 exception, this POC only.** Steel may hold the browser profile / session.
Reviewnary and the backend do **not** read, copy or store the raw cookie/session: the POC never
calls `sessions.context()` (the one call that returns them) and `selfcheck` asserts that absence.
The **production adoption decision is deferred until after the POC.** PD-1 itself is unchanged for
production; this is a scoped exception, not an amendment.

**PC-2 — no vision, no screenshots.** Text / accessibility serialization only.
- `POC_USE_VISION=true` is **refused at config load**, not merely defaulted off. The name must still
  be stated, because a silent default is how a payload floor stops being a decision.
- The `screenshot` **action is removed from the agent's catalogue**. `use_vision=false` alone did not
  close this — the action would still have captured a page image and sent it to the vendor. Found
  while applying this decision; the decision is now enforced by removing the means.
- The XLSX **never reaches the LLM.** It is downloaded by the browser into Steel's session files and
  fetched by our own process; the agent's `read_file` action is gone, `display_files_in_done_text` is
  off, and `selfcheck` asserts that the file which builds the LLM cannot even name a byte-fetch call.
  The agent reports `download_observed` — a boolean — and nothing else about the file.
- CAPTCHA / security verification is **never auto-solved**: the run stops with `AUTH_REQUIRED`.

**Action catalogue: 24 default → 16.** Removed: `evaluate`, `search`, `read_file`, `write_file`,
`replace_file`, `upload_file`, `save_as_pdf`, `screenshot`.

**On any step failure: stop.** No automatic retry-by-relaxation — no stealth, no CAPTCHA solving, no
widening of `allowed_domains`, no loosening of the fences to get a green run.

## Two things that were decisions, not defaults — now decided above

1. **Where the seller's NAVER session lives.** PD-1 decided the Runner is the **Seller PC** and
   that credential/session are **not** moved to the cloud. This POC puts the session in
   **Steel's cloud** (`persist_profile` + `profile_id`). That is the premise of the words
   "Cloud Export", and it is a **divergence from PD-1's placement** that only the product owner
   can accept. It does satisfy §4's TARGET row — the session is held by the execution provider
   and *this machine never receives the cookies*: the POC never calls `sessions.context()`,
   which is the call that would return them.
2. **What the LLM vendor sees.** browser-use sends the seller center's page content to the LLM
   on every step, and with `POC_USE_VISION=true` it sends **screenshots** — customer names and
   review text included. Every other LLM capability in this repo has a declared payload floor;
   this one's floor is "the page". `POC_USE_VISION` has **no default** so that the choice is made
   on purpose. (Also: browser-use ships anonymized PostHog telemetry **on** by default —
   `config.harden_third_party_telemetry()` turns it and cloud sync off before the agent loads.)

Missing prerequisites: **`STEEL_API_KEY`** and **`POC_LLM_API_KEY`**. Neither exists in this
environment, and neither is guessed.

## Run order

```bash
./setup.sh                                  # isolated venv + selfcheck
cp .env.example .env                        # fill in; .env is gitignored
.venv/bin/python -m poc.run selfcheck       # offline. fences assert against our own source
.venv/bin/python -m poc.run manifest        # offline. prints the Approval Manifest
export POC_LIVE_APPROVAL=<approval id>      # only after the operator approves it in-turn

.venv/bin/python -m poc.run probe           # STEEL only — NAVER contact 0 (§16 step 2 survey)
.venv/bin/python -m poc.run login           # STEP 1+2: you sign in via the viewer URL
#   save the printed profile id into .env as POC_PROFILE_ID
.venv/bin/python -m poc.run restore         # STEP 3: new session, same profile — did the login survive?
.venv/bin/python -m poc.run export  --start 2026-09-20 --end 2026-09-27   # STEP 4+5
.venv/bin/python -m poc.run repeat  --start 2026-09-20 --end 2026-09-27   # STEP 6
```

`probe` is the only non-offline command that needs no approval: it creates a Steel session,
reads what the session says about itself, lists the (empty) Files API and releases it, without
navigating anywhere.

## Success criteria → where each is measured

| criterion | measured by |
|---|---|
| second session keeps the login | `restore` verdict `SESSION_RESTORED`, `sessionIsNew: true`, `profileReused: true` |
| no selector-based scraper | `selfcheck` source scan over `poc/` (proven to bite: injecting a violation turns it red) |
| real XLSX artifact | `export` → `artifact.sniff == OOXML`, with sha256 / size / name category |
| repeat run succeeds | `repeat` = fresh session on the same profile, then export again |

Two extra measurements the harness takes because §6 / H-3 asks for them: the **observed store
identifier** (recorded only as a salted digest, so a run log can say "same store as last time"
without saying which store) and the **period read-back** from the export form, so the run reports
what the form actually showed rather than what it was asked for.

## Output

- `.runs/*.json` — sanitized run records. Every write passes `sanitize.assert_clean`, which
  **refuses** to write if a URL, cookie name, selector, path, email or phone number is present.
- `.artifacts/*.bin` — the official export. This holds **real customer review text**. Gitignored,
  never copied out of this directory. Per PD-6 there is no seller-facing raw copy; delete it when
  the measurement is done.

Everything in this directory is untracked and stays that way until the product owner decides
this provider is worth a contract.
