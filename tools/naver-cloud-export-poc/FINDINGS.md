# Provider capability survey — SDK level (read-only, no network, no NAVER contact)

`docs/review_acquisition_aside_v2.md` §16 step 2 asks for a read-only capability survey before
any PoC. This is the part that can be answered without a Steel account, by introspecting the
installed SDKs. It answers three of the doc's open hypotheses; it does not close them, because
the live half is unmeasured.

## What Steel actually offers (steel-sdk 0.19.0)

- `sessions.create(...)` real parameters relevant here: `profile_id`, `persist_profile`,
  `session_context`, `region`, `solve_captcha`, `stealth_config`, `credentials`, `headless`,
  `proxy_url`, `block_ads`, `timeout`, `inactivity_timeout`.
- `Session` response carries: `websocket_url` (CDP), `session_viewer_url`, `profile_id`,
  `persist_profile`, `region`, `status`, `release_reason`, `credits_used`, `solve_captcha`,
  `stealth_config`. So the **server's** answer about the two forbidden features is readable, which
  is why `assert_session_fences` checks the response and not just our request.
- `region` enum includes `nrt` and `ap-northeast`; **there is no Korean region.**
- `sessions.files.list(session_id)` → `path`, `size`, `last_modified`;
  `sessions.files.download(path, session_id=…)` → bytes. This is the export artifact path.
- `sessions.live_details(id)` → viewer URLs + `ws_url` + per-page list. This is how a human logs in.
- `sessions.context(id)` → `cookies`, `local_storage`, `session_storage`, `indexed_db`.
  **This is the seller's session itself. The POC never calls it** — persistence uses the
  profile instead, so the cookies stay on Steel's side.
- `profiles.create(user_data_dir=…)` requires *uploading* a Chrome user-data dir, so it is the
  bring-your-own-profile path. The POC does not use it; `persist_profile=True` lets Steel mint the
  profile and return its id.

### H-2 (how does the provider expose "authentication is needed"?) — **still open**
Steel exposes session lifecycle (`status`, `release_reason`) but **no authentication-state signal**.
So `AUTH_REQUIRED` vs `UNSUPPORTED_STATE` cannot be distinguished from the provider's API; in this
POC the distinction comes from the **agent's own observation** of the screen, reported as a typed
verdict. That is weaker than a provider signal and is exactly the residual §4 predicted: both
outcomes stop, so safety holds, but the seller-facing sentence is less certain.

## What browser-use actually offers (browser-use 0.13.10)

- `Browser(cdp_url=…, is_local=False, allowed_domains=[…])` connects to a remote CDP target; no
  Playwright is involved (the package uses `cdp-use`). Confirmed constructed offline.
- `BrowserProfile` carries real fences: `allowed_domains`, `prohibited_domains`,
  `cookie_whitelist_domains`, `accept_downloads`, `permissions`.
- `Tools(exclude_actions=[…])` removes actions from the registry. Default catalogue is 24 actions;
  with this POC's 7 exclusions, 17 remain (verified by counting the registry, not by reading docs).
  The default set includes `evaluate` (arbitrary page JS), `search` (web search), and
  `read_file`/`write_file`/`replace_file` (local filesystem) — all of which a review-export agent
  has no business holding.
- `Agent(output_model_schema=…)` yields a typed result on `history.structured_output`, so the run
  ends in a verdict rather than prose we would have to re-parse. This matters for the same reason
  §1 principle 5 matters: an ambiguous outcome must be a stop, and prose is ambiguous.
- **Anonymized PostHog telemetry is ON by default** (`ANONYMIZED_TELEMETRY`), plus a cloud-sync
  flag. Both are turned off before the agent module loads.

### H-3 (what machine-verifiable store identity can we actually observe?) — **unmeasured**
The harness asks for it and records it as a salted digest, but whether the NAVER review-management
screen exposes a stable store/channel number to an agent that reads the rendered page is the first
thing the live run will tell us. Until then `STORE_UNRESOLVED` is a real possible outcome, and
PD-4 says that outcome is a stop.

### H-4 (does the `ExecutionProvider` contract shape fit?) — **partly answered**
Steel + browser-use is **synchronous** from the caller's side: create session → drive → read files →
release. That fits §14's `execute(run) -> {ok, artifact, observed}` shape without callbacks or
polling. The one mismatch is that the artifact arrives from the **provider's** Files API rather than
a local download, so `artifact.bytes` comes from an authenticated fetch, not a filesystem watch.

## Unmeasured, and only a live run can measure it

1. Whether a NAVER seller-center login survives in a Steel profile across sessions **at all**.
2. Whether logging in from a Tokyo egress IP triggers additional verification that a Korean-egress
   login would not (the local helper path has always been the seller's own machine and IP).
3. Whether the export control is reachable by an agent that uses no selectors.
4. Whether the download lands in the Files API at all, and as one file or several.
5. The cost: Steel session credits + LLM steps per export. Nothing here estimates it.
