# Cloud NAVER Review Export Agent

Drives the seller's own NAVER Seller Center **official review Excel export** on a Korea-region VM, and
hands the bytes to the product's **existing** importer. It is one of the two pieces the 72h shadow run
needs; the other is the narrow server-side authority that lets it authorize its own runs
(`sellerops.review-import.unattended.*`).

## What it is not

- **Not a scraper.** No selector anywhere in `agent/`, and the agent's `evaluate` action (arbitrary page
  JS) is removed. It reads the page the way an accessibility tree is read, and presses the export the
  seller centre already offers.
- **Not a parser.** The XLSX is posted to `POST /api/imports/reviews/launches/{ref}/ingest` and parsed by
  `UploadFormat → FileParser → ReviewRowMapper → IngestionService` in backend memory. This agent never
  reads a row out of the file (PD-8: canonical parser, single source).
- **Not a second ingest path.** Three calls, all already reachable with a helper device token.
- **Not a credential typist.** You sign in once, yourself, in `run.py login`. Nothing here types an id, a
  password or a verification code, and nothing bypasses a CAPTCHA, a 2FA prompt or a device check — those
  end the cycle as `AUTH_REQUIRED`.
- **Not a marketplace writer.** No reply, edit, delete or report: the task text forbids it, the excluded
  actions remove the means, and `selfcheck` asserts this package names no publish or approval route.
- **Not Steel.** The browser is this VM's own Chromium with a persistent profile on local disk, so the
  session lives on the Korea-region fixed-IP host rather than in a third party's cloud.

## Fences, and what asserts each one

| fence | asserted by |
|---|---|
| no selector, no direct Playwright/Selenium | `run.py selfcheck` source scan over `agent/` |
| 8 actions removed (`evaluate` `search` `read_file` `write_file` `replace_file` `upload_file` `save_as_pdf` `screenshot`) | `selfcheck` reads the list out of `config.py` |
| `AGENT_USE_VISION=true` refused | `selfcheck` **calls the loader** and requires it to raise |
| the XLSX never reaches the model | `read_file` gone · `display_files_in_done_text=False` · the file that builds the LLM cannot name a byte-fetch |
| agent cannot leave the seller centre | `allowed_domains` (nid.naver.com reachable only so an auth screen can be *recognised*) |
| ambiguity stops the cycle | two new files in the download dir ⇒ `AMBIGUOUS_FILES`, nothing is ingested |
| no retry by relaxation | `shadow` stops on the first non-`INGESTED` verdict |
| browser-use telemetry off | set before the agent module loads |

Every one of these was checked by injecting a violation and watching `selfcheck` go red.

## One cycle

```
POST /api/helper-devices/review-export/next-launch   -> launch_ref + required period
  (the request names nothing: org, device, channel and account are all decided server-side)
official export in this VM's Chromium               -> one file in .artifacts/<ref>/
read back the form's own dates                      -> MACHINE_MATCHED, else OPERATOR_CONFIRMED
POST .../launches/{ref}/ingest                      -> canonical importer, in backend memory
server ACK                                          -> delete the local raw file (PD-5 / PD-6)
```

A failed ingest leaves the file in place (local quarantine) and stops the loop.

## Run

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env            # fill in; .env is gitignored, mode 0600
.venv/bin/python -m agent.run selfcheck   # offline
.venv/bin/python -m agent.run login       # YOU sign in, once
.venv/bin/python -m agent.run once        # one cycle end to end
.venv/bin/python -m agent.run shadow --hours 72
```

On a headless VM the browser still needs a display: run under `xvfb-run` (the sign-in leg refuses
`AGENT_HEADLESS=true` because you have to see the screen to sign in). See
`deploy/pilot/install-review-export-agent.sh`.

## Output

- `.runs/*.json` — sanitized cycle records. Every write passes `sanitize.assert_clean`, which **refuses**
  to write when a URL, cookie name, selector, path, email or phone number is present.
- `.profile/` — the seller centre session. Same class of secret as `collector/.profile/`.
- `.artifacts/` — an official export, briefly. Real customer review text; deleted on ACK.
