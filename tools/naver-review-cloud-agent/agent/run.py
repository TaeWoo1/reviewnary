"""Cloud NAVER review export agent — runner.

    selfcheck   offline. asserts this package's own fences. No network at all.
    login       opens the seller centre so YOU can sign in once. Types nothing.
    once        one cycle: mint a launch -> official export -> ingest -> delete the local file.
    shadow      the 72h loop: `once`, every AGENT_INTERVAL_MINUTES, until the deadline or the first stop.

Nothing here retries by relaxation. A cycle that ends in anything but a successful ingest stops the
loop and says what it observed.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from agent import config as C  # noqa: E402
from agent import sanitize as S  # noqa: E402
from agent.product import ProductError, Reviewnary  # noqa: E402

KST = timezone(timedelta(hours=9))

# A cycle ends in exactly one of these. Only the first is a success.
INGESTED = "INGESTED"
STOPS = ("AUTH_REQUIRED", "STORE_UNRESOLVED", "EXPORT_CONTROL_NOT_FOUND", "UNSUPPORTED_STATE",
         "NO_LAUNCH", "NO_FILE", "AMBIGUOUS_FILES", "ARTIFACT_INVALID", "INGEST_REFUSED")


# ---------------------------------------------------------------- run log

def _runlog(kind: str, payload: dict) -> None:
    C.RUNS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(KST).strftime("%Y%m%dT%H%M%S")
    body = json.dumps({"kind": kind, "atKst": stamp, **payload}, ensure_ascii=False, indent=2, sort_keys=True)
    S.assert_clean(body, what=f"{kind} run log")
    (C.RUNS / f"{stamp}-{kind}.json").write_text(body + "\n", encoding="utf-8")


# ---------------------------------------------------------------- selfcheck

SELECTOR_MARKERS = ["querySelector", "xpath", "XPath", "css=", "css_selector", "get_by_role", "locator("]
FORBIDDEN_IMPORTS = ["playwright", "selenium"]


def cmd_selfcheck(_args) -> int:
    src = {p.name: p.read_text(encoding="utf-8") for p in (C.ROOT / "agent").glob("*.py")}
    # run.py and sanitize.py NAME the forbidden shapes in order to forbid them; a guard that fails on its
    # own description gets deleted instead of fixed, so they are not scanned.
    scanned = {k: v for k, v in src.items() if k not in ("run.py", "sanitize.py")}
    fails: list[str] = []

    for name, text in scanned.items():
        for m in SELECTOR_MARKERS:
            if m in text:
                fails.append(f"{name}: selector marker present ({m})")
        for m in FORBIDDEN_IMPORTS:
            if m in text:
                fails.append(f"{name}: forbidden direct browser driver ({m})")

    for a in ("evaluate", "search", "read_file", "write_file", "replace_file", "upload_file",
              "save_as_pdf", "screenshot"):
        if f'"{a}"' not in src["config.py"]:
            fails.append(f"config.py: action '{a}' is not excluded")
    # Behavioural, not by name: ask the loader for vision and check that it refuses.
    saved = os.environ.get("AGENT_USE_VISION")
    try:
        os.environ["AGENT_USE_VISION"] = "true"
        try:
            C._require_vision_off()  # noqa: SLF001 - the point of the check is the private guard
            fails.append("config.py: AGENT_USE_VISION=true was ACCEPTED")
        except SystemExit:
            pass
    finally:
        if saved is None:
            os.environ.pop("AGENT_USE_VISION", None)
        else:
            os.environ["AGENT_USE_VISION"] = saved
    if "ANONYMIZED_TELEMETRY" not in src["config.py"]:
        fails.append("config.py: browser-use telemetry is not disabled")
    if "display_files_in_done_text=False" not in src["export_agent.py"]:
        fails.append("export_agent.py: Tools may echo file contents into the done text")
    for marker in ("use_vision=True", "stealth", "solve_captcha"):
        if marker in " ".join(scanned.values()).replace(" ", ""):
            fails.append(f"agent/: forbidden setting present ({marker})")
    # the file that builds the LLM must not be able to obtain the artifact's bytes
    for marker in ("read_bytes", "ARTIFACTS", "artifact_meta", "Reviewnary"):
        if marker in src["export_agent.py"]:
            fails.append(f"export_agent.py: touches the artifact/ingest path ('{marker}')")
    # acquisition only: this agent names no publish/approval/execution route
    for name, text in scanned.items():
        for forbidden in ("/publish", "/approve", "/approval", "reply-submission", "inquiry-publish"):
            if forbidden in text:
                fails.append(f"{name}: names a non-acquisition route ({forbidden})")

    print("=== selfcheck ===")
    for f in fails:
        print("  FAIL:", f)
    if not fails:
        print("  PASS: no selector, no direct driver, 8 actions excluded (incl. screenshot), vision refused,")
        print("        done-text carries no file contents, LLM file cannot fetch the artifact,")
        print("        no publish/approval route named, telemetry off.")
    return 1 if fails else 0


# ---------------------------------------------------------------- browser legs

async def _agent_leg(cfg, task: str, downloads: Path):
    from agent.export_agent import build_browser, build_llm, run_task

    browser = build_browser(profile_dir=C.PROFILE, downloads_dir=downloads, headless=cfg.headless,
                            allowed_domains=C.ALLOWED_DOMAINS)
    history = await run_task(task=task, llm=build_llm(cfg.llm_provider, cfg.llm_model, cfg.llm_api_key),
                             browser=browser, use_vision=cfg.use_vision, max_steps=cfg.max_steps,
                             excluded_actions=C.EXCLUDED_ACTIONS)
    return history


def cmd_login(_args) -> int:
    """You sign in. This agent types nothing — not an id, not a password, not a verification code."""
    cfg = C.Config.load()
    C.harden_third_party_telemetry()
    C.PROFILE.mkdir(parents=True, exist_ok=True)
    if cfg.headless:
        raise SystemExit("[login] AGENT_HEADLESS=true cannot be used for the sign-in leg — you need to see it.")
    from agent.export_agent import build_browser

    print(f"""
[login] Opening the seller centre in this VM's Chromium (profile: .profile/).
        Sign in YOURSELF, complete any 2FA yourself, then close the window.
        Nothing here bypasses a login, a CAPTCHA or a device verification.
""")
    browser = build_browser(profile_dir=C.PROFILE, downloads_dir=C.ARTIFACTS, headless=False,
                            allowed_domains=C.ALLOWED_DOMAINS)

    async def hold() -> None:
        await browser.start()
        await browser.new_page("https://sell.smartstore.naver.com/")
        print("[login] press Enter here once you are signed in > ", end="", flush=True)
        await asyncio.get_running_loop().run_in_executor(None, sys.stdin.readline)
        await browser.stop()

    asyncio.run(hold())
    _runlog("login", {"credentialsTypedByTool": 0, "profileDirCreated": True})
    print("[login] done. The session now lives in .profile/ on this VM.")
    return 0


# ---------------------------------------------------------------- one cycle

def cmd_once(args) -> int:
    cfg = C.Config.load()
    C.harden_third_party_telemetry()
    verdict, detail = _cycle(cfg)
    print(json.dumps({"verdict": verdict, **detail}, ensure_ascii=False, indent=2, sort_keys=True))
    _runlog("cycle", {"verdict": verdict, **detail})
    return 0 if verdict == INGESTED else 1


def _cycle(cfg) -> tuple[str, dict]:
    api = Reviewnary(cfg.base_url, cfg.device_token)
    detail: dict = {}

    # 1. authorize one run. The product decides org, device, channel and account.
    try:
        scope = api.next_launch()
    except ProductError as e:
        return "NO_LAUNCH", {"status": e.status, "serverSaid": S.scrub(e.message)[:160]}
    detail["requiredStart"] = scope.required_start
    detail["requiredEnd"] = scope.required_end
    detail["channelCode"] = scope.channel_code
    if scope.channel_code and scope.channel_code != "NAVER":
        return "UNSUPPORTED_STATE", {**detail, "why": "launch is not a NAVER launch"}
    if not scope.required_start or not scope.required_end:
        return "UNSUPPORTED_STATE", {**detail, "why": "the launch names no required period"}

    # 2. the official export, on this VM's Chromium.
    downloads = C.ARTIFACTS / scope.launch_ref[:8]
    downloads.mkdir(parents=True, exist_ok=True)
    before = {p.name for p in downloads.iterdir()}
    from agent.export_agent import TASK_TEMPLATE

    history = asyncio.run(_agent_leg(
        cfg, TASK_TEMPLATE.format(start=scope.required_start, end=scope.required_end), downloads))
    parsed = history.structured_output
    detail["steps"] = history.number_of_steps()
    if parsed is None:
        return "UNSUPPORTED_STATE", {**detail, "why": "the run produced no typed verdict"}
    detail["agentVerdict"] = parsed.verdict
    detail["note"] = S.scrub(parsed.note or "")[:160]
    raw_store = (parsed.observed_store_identifier or "").strip()
    detail["storeIdentifierObserved"] = bool(raw_store)
    detail["storeDigest"] = S.digest(raw_store) if raw_store else None
    readback_matched = (parsed.period_start_readback == scope.required_start
                        and parsed.period_end_readback == scope.required_end)
    detail["scopeReadbackMatched"] = readback_matched
    if parsed.verdict != "EXPORT_REQUESTED":
        return parsed.verdict if parsed.verdict in STOPS else "UNSUPPORTED_STATE", detail

    # 3. the artifact. Ambiguity fails closed — we do not pick one file and call it the export.
    new_files = sorted(p for p in downloads.iterdir() if p.name not in before and p.is_file())
    if not new_files:
        return "NO_FILE", detail
    if len(new_files) > 1:
        return "AMBIGUOUS_FILES", {**detail, "count": len(new_files)}
    artifact = new_files[0]
    data = artifact.read_bytes()
    meta = S.artifact_meta(data, artifact.name)
    detail["artifact"] = meta
    if meta["sniff"] == "UNKNOWN":
        return "ARTIFACT_INVALID", detail

    # 4. the canonical importer. `MACHINE_MATCHED` only when the form's own dates were read back.
    evidence = "MACHINE_MATCHED" if readback_matched else "OPERATOR_CONFIRMED"
    detail["scopeEvidence"] = evidence
    try:
        ack = api.ingest(scope.launch_ref, data, artifact.name, evidence)
    except ProductError as e:
        # The raw file stays for inspection (local quarantine); it is NOT deleted on a failed ingest.
        return "INGEST_REFUSED", {**detail, "status": e.status, "serverSaid": S.scrub(e.message)[:160]}
    detail["rowsNew"] = ack.get("rowsNew")
    detail["rowsDuplicate"] = ack.get("rowsDuplicate")
    detail["rowsFailed"] = ack.get("rowsFailed")
    detail["attemptStatus"] = ack.get("status")

    # 5. ACK received -> delete the local raw file (PD-5/PD-6: no seller-facing raw copy is kept).
    artifact.unlink(missing_ok=True)
    detail["localRawDeleted"] = True
    return INGESTED, detail


# ---------------------------------------------------------------- the 72h loop

def cmd_shadow(args) -> int:
    cfg = C.Config.load()
    C.harden_third_party_telemetry()
    deadline = datetime.now(timezone.utc) + timedelta(hours=args.hours)
    print(f"[shadow] until {deadline.isoformat()} — one cycle every {cfg.interval_minutes} min. "
          f"The first non-ingest verdict stops the loop.")
    cycle = 0
    while datetime.now(timezone.utc) < deadline:
        cycle += 1
        print(f"\n[shadow] cycle {cycle}")
        verdict, detail = _cycle(cfg)
        _runlog("cycle", {"cycle": cycle, "verdict": verdict, **detail})
        print(f"[shadow] cycle {cycle}: {verdict}")
        if verdict != INGESTED:
            # No relaxation, no widening, no second attempt with different settings.
            print("[shadow] stopping. Nothing is retried by loosening a fence.")
            _runlog("shadow-stop", {"cycle": cycle, "verdict": verdict})
            return 1
        remaining = (deadline - datetime.now(timezone.utc)).total_seconds()
        if remaining <= 0:
            break
        time.sleep(min(cfg.interval_minutes * 60, remaining))
    print(f"[shadow] window closed after {cycle} cycle(s), all INGESTED.")
    _runlog("shadow-complete", {"cycles": cycle})
    return 0


# ---------------------------------------------------------------- main

def main() -> int:
    C.load_dotenv_if_present()
    p = argparse.ArgumentParser(prog="agent.run", description="Cloud NAVER review export agent")
    sub = p.add_subparsers(dest="cmd", required=True)
    for name, fn in (("selfcheck", cmd_selfcheck), ("login", cmd_login),
                     ("once", cmd_once), ("shadow", cmd_shadow)):
        sp = sub.add_parser(name)
        sp.add_argument("--hours", type=float, default=72.0, help="shadow only: window length")
        sp.set_defaults(fn=fn)
    args = p.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
