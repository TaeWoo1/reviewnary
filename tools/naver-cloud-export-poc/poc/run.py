"""NAVER Review Cloud Export POC — runner.

    selfcheck   offline. asserts this package's own fences. No network at all.
    manifest    offline. prints the run-scoped Approval Manifest the live legs need.
    probe       Steel capability probe. Touches STEEL only — NAVER contact 0.
    login       creates a persistent-profile session and hands the human the viewer URL.
    restore     new session on the saved profile; reports whether the login survived.
    export      the agent performs the official export; artifact is fetched and measured.
    repeat      restore + export again in a FRESH session on the same profile (step 6).

Every live leg refuses to start unless POC_LIVE_APPROVAL is set to the approval id that
`manifest` printed. A plan is not an approval (CLAUDE.md).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from poc import config as C  # noqa: E402
from poc import sanitize as S  # noqa: E402

KST = timezone(timedelta(hours=9))


# ---------------------------------------------------------------- run log

def _runlog(kind: str, payload: dict) -> Path:
    C.RUNS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(KST).strftime("%Y%m%dT%H%M%S")
    body = json.dumps({"kind": kind, "atKst": stamp, **payload}, ensure_ascii=False, indent=2, sort_keys=True)
    S.assert_clean(body, what=f"{kind} run log")
    p = C.RUNS / f"{stamp}-{kind}.json"
    p.write_text(body + "\n", encoding="utf-8")
    print(f"[log] {p.relative_to(C.ROOT)}")
    return p


# Product decision 2026-09-27: one approval per leg, and an approval is not reusable outside
# its leg. `probe` needs none (NAVER contact 0).
LEG_APPROVAL_ENV = {
    "login": ("POC_APPROVAL_AUTH", "auth"),
    "restore": ("POC_APPROVAL_AUTH", "auth"),
    "export": ("POC_APPROVAL_EXPORT", "export"),
    "repeat": ("POC_APPROVAL_REPEAT", "repeat"),
}

LEDGER = "approvals.json"


def _ledger_path() -> Path:
    C.RUNS.mkdir(parents=True, exist_ok=True)
    return C.RUNS / LEDGER


def _read_ledger() -> dict:
    p = _ledger_path()
    if not p.exists():
        return {}
    return json.loads(p.read_text(encoding="utf-8"))


@dataclass
class Approval:
    """Validated but not yet spent. `commit()` is called at the moment of first marketplace
    contact, not at process start: a config error that never reached NAVER must not burn the
    operator's single-use grant."""

    approval: str
    scope: str
    leg: str
    _spent: bool = False

    def commit(self, *, reason: str) -> None:
        if self._spent:
            return
        ledger = _read_ledger()
        key = f"{self.scope}:{self.approval}"
        if key in ledger:
            raise SystemExit(f"[approval] refusing: {self.approval} was spent while this leg was starting.")
        ledger[key] = {
            "approval": self.approval,
            "scope": self.scope,
            "leg": self.leg,
            "atKst": datetime.now(KST).strftime("%Y-%m-%dT%H:%M:%S"),
            "spentAt": reason,
        }
        _ledger_path().write_text(json.dumps(ledger, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        self._spent = True
        print(f"[approval] SPENT {self.approval} on the '{self.scope}' leg at: {reason}")


def _validate_approval(leg: str) -> "Approval | None":
    """Resolve, validate and SPEND the approval for this leg. Spending is recorded, and a
    second spend is refused: that is what single-use means. A retry after a failure needs a
    freshly minted id (…-r2), not the same one again."""
    if leg not in LEG_APPROVAL_ENV:
        return None  # probe / offline commands
    env_name, scope = LEG_APPROVAL_ENV[leg]
    approval = os.environ.get(env_name, "").strip()
    if not approval:
        raise SystemExit(
            f"[approval] '{leg}' is a live leg and {env_name} is not set.\n"
            f"           Run `python -m poc.run manifest`, have the operator approve it in-turn,\n"
            f"           then set {env_name}=<the approval id for the {scope} leg>.\n"
            f"           A plan is not an approval."
        )

    ledger = _read_ledger()
    # (a) the same id must not appear under a different scope — no reuse outside its leg.
    for key, rec in ledger.items():
        if rec.get("approval") == approval and rec.get("scope") != scope:
            raise SystemExit(
                f"[approval] refusing: {approval} was already spent on the '{rec.get('scope')}' leg.\n"
                f"           An approval is not reusable outside its leg."
            )
    # (b) single use.
    key = f"{scope}:{approval}"
    if key in ledger:
        raise SystemExit(
            f"[approval] refusing: {approval} was already spent (at {ledger[key].get('atKst')}).\n"
            f"           Single use means single use. A retry needs a freshly approved id."
        )
    print(f"[approval] {approval} is valid for the '{scope}' leg and not yet spent.")
    return Approval(approval=approval, scope=scope, leg=leg)


# ---------------------------------------------------------------- selfcheck

SELECTOR_MARKERS = ["querySelector", "xpath", "XPath", "css=", "css_selector", "get_by_role", "locator("]
FORBIDDEN_IMPORTS = ["playwright", "selenium", "requests.get(", "urllib.request"]


def cmd_selfcheck(_args) -> int:
    src = {p.name: p.read_text(encoding="utf-8") for p in (C.ROOT / "poc").glob("*.py")}
    # run.py and sanitize.py NAME the forbidden shapes in order to forbid them; a guard that
    # fails on its own description gets deleted instead of fixed, so they are not scanned.
    scanned = {k: v for k, v in src.items() if k not in ("run.py", "sanitize.py")}
    fails: list[str] = []

    for name, text in scanned.items():
        for m in SELECTOR_MARKERS:
            if m in text:
                fails.append(f"{name}: selector marker present ({m})")
        for m in FORBIDDEN_IMPORTS:
            if m in text:
                fails.append(f"{name}: forbidden direct driver/import ({m})")

    # the fences must be declared, not merely intended
    if "solve_captcha\": False" not in src["steel_client.py"].replace("'", '"'):
        fails.append("steel_client.py: solve_captcha=False is not passed explicitly")
    if "stealth_config" in src["steel_client.py"] and "kwargs[\"stealth_config\"]" in src["steel_client.py"]:
        fails.append("steel_client.py: stealth_config is being passed")
    if ".sessions.context(" in " ".join(scanned.values()):
        fails.append("poc/: sessions.context() is called — that returns the seller's cookies")
    for a in ("evaluate", "search", "read_file", "write_file", "replace_file",
              "upload_file", "save_as_pdf", "screenshot"):
        if f'"{a}"' not in src["config.py"]:
            fails.append(f"config.py: action '{a}' is not excluded")

    # product decision 2026-09-27: vision/screenshot forbidden — enforced, not defaulted
    if "_require_vision_off" not in src["config.py"]:
        fails.append("config.py: POC_USE_VISION=true is not refused (vision must be impossible, not merely off)")
    for name, text in scanned.items():
        if "use_vision=True" in text.replace(" ", ""):
            fails.append(f"{name}: use_vision=True is hardcoded")
    if "display_files_in_done_text=False" not in src["export_agent.py"]:
        fails.append("export_agent.py: Tools may echo file contents into the done text")

    # product decision 2026-09-27: a rejected model id/API is a STOP, not a fallback.
    if "fallbacks" in src["export_agent.py"]:
        fails.append("export_agent.py: a model fallback is configured (a rejected model must stop the run)")
    if "except" in src["export_agent.py"]:
        fails.append("export_agent.py: an exception is swallowed where the model is built")

    # product decision 2026-09-27: the XLSX reaches us as an artifact and never reaches the vendor.
    # export_agent.py is the ONLY file that builds an LLM; it must not know how to obtain the bytes.
    # (marker list is precise on purpose: `download_observed` is a BOOLEAN observation the
    # agent is allowed to report; obtaining the bytes is what it must not be able to do.)
    for marker in ("files.download", "sessions.files", "ARTIFACTS", "artifact_meta",
                   "write_bytes", "read_bytes"):
        if marker in src["export_agent.py"]:
            fails.append(f"export_agent.py: touches the artifact path ('{marker}') in the file that builds the LLM")

    # telemetry hardening must exist
    if "ANONYMIZED_TELEMETRY" not in src["config.py"]:
        fails.append("config.py: browser-use telemetry is not disabled")

    print("=== selfcheck ===")
    for f in fails:
        print("  FAIL:", f)
    if not fails:
        print("  PASS: no selector in poc/, no direct browser driver, sessions.context() never called,")
        print("        solve_captcha=False explicit, stealth absent, 8 actions excluded (incl. screenshot),")
        print("        vision refused not defaulted, done-text carries no file contents, telemetry off.")
    return 1 if fails else 0


# ---------------------------------------------------------------- manifest

def cmd_manifest(args) -> int:
    start, end = _window(args)
    day = datetime.now(KST).strftime("%Y%m%d")
    ids = {
        "auth  (login, restore)": f"apr-nv-cloudexport-{day}-auth-r1",
        "export": f"apr-nv-cloudexport-{day}-export-r1",
        "repeat": f"apr-nv-cloudexport-{day}-repeat-r1",
    }
    approval_id = " / ".join(ids.values())
    print(
        f"""
=== Approval Manifest — NAVER Review Cloud Export POC ===

  approvals       per leg, single use, not reusable outside its leg:
                    auth   (login, restore) : {ids["auth  (login, restore)"]}   -> POC_APPROVAL_AUTH
                    export                  : {ids["export"]}   -> POC_APPROVAL_EXPORT
                    repeat                  : {ids["repeat"]}   -> POC_APPROVAL_REPEAT
                  probe needs none (NAVER contact 0)
  channel         NAVER SmartStore (seller center, official UI)
  surface         Review management → official Excel export
  operation       READ + official EXPORT download
  mode            READ            (marketplace WRITE 0 — reply/edit/delete/report excluded by tool fence)
  allowedActions  navigate · click · type (date fields) · official export control · download
  requestedPeriod {start} .. {end}
  executionHost   Steel cloud browser, region {os.environ.get('POC_STEEL_REGION', 'nrt')}
  provider        STEEL + BROWSER_USE  (candidate `ExecutionProvider`, not wired to production)
  llmVendor       {os.environ.get('POC_LLM_PROVIDER', '<unset>')}  model {os.environ.get('POC_LLM_MODEL', '<unset>')}
  vision          {os.environ.get('POC_USE_VISION', '<unset>')}   (true = page screenshots leave this machine)
  singleUse       yes — one approval, one export run
  notExtendedTo   scheduled/unattended runs · other stores · other periods · any WRITE · production ingest

  THIS POC DOES NOT INGEST. No Reviewnary endpoint is called, no `reviews` row is written,
  no `sync_jobs`/attempt row is created. The artifact stops in .artifacts/.

Each id is spent once and recorded in .runs/approvals.json. A retry after a failure needs a
freshly approved id (…-r2), not the same one again.
"""
    )
    return 0


def _window(args) -> tuple[str, str]:
    end = args.end or date.today().isoformat()
    start = args.start or (date.fromisoformat(end) - timedelta(days=7)).isoformat()
    return start, end


# ---------------------------------------------------------------- probe (STEP 0)

def cmd_probe(_args) -> int:
    """§16 step 2: read-only capability survey. NAVER contact 0 — we create a session,
    read what it says about itself, and release it without navigating anywhere."""
    cfg = C.Config.load(need_llm=False)
    from poc.steel_client import SteelRunner

    r = SteelRunner(cfg.steel_api_key, cfg.steel_region)
    print("[probe] creating a session (persist_profile=True, no navigation) ...")
    s = r.create(profile_id=cfg.profile_id, persist_profile=True)
    observed = {
        "sessionCreated": True,
        "regionRequested": cfg.steel_region,
        "regionObserved": s.region,
        "profileIdReturned": bool(s.profile_id),
        "profileIdIsNew": bool(s.profile_id) and s.profile_id != cfg.profile_id,
        "cdpUrlPresent": bool(s.cdp_url),
        "viewerUrlPresent": bool(s.viewer_url),
        "captchaSolvingAccepted": False,
        "stealthRequested": False,
    }
    files = r.files(s.id)
    observed["filesApiReachable"] = True
    observed["filesAtCreation"] = len(files)
    status = r.release(s.id)
    observed["releaseStatus"] = str(status)

    print("[probe] observed:")
    for k, v in observed.items():
        print(f"    {k}: {v}")
    print(f"\n[probe] profile id for the next step (put in .env as POC_PROFILE_ID):\n    {s.profile_id}")
    _runlog("probe", {"observed": observed, "naverContact": 0, "profileIdDigest": S.digest(s.profile_id or "none")})
    return 0


# ---------------------------------------------------------------- login (STEP 1+2)

def cmd_login(_args) -> int:
    cfg = C.Config.load(need_llm=False)
    from poc.steel_client import SteelRunner

    r = SteelRunner(cfg.steel_api_key, cfg.steel_region)
    s = r.create(profile_id=cfg.profile_id, persist_profile=True)
    _args.approval.commit(reason="handing the session viewer to the operator for NAVER sign-in")
    print(
        f"""
[login] Session is live. YOU log in — this POC never types a credential.

    Open this in your own browser and sign in to the NAVER seller center:

        {s.viewer_url}

    profile id (persisted by Steel, save it to .env as POC_PROFILE_ID):

        {s.profile_id}

    Notes:
      - The session lives in Steel's cloud, region {s.region}. Your cookies stay there;
        this machine never receives them.
      - Complete any 2FA / device registration YOURSELF. Nothing here bypasses it.
      - When you are fully signed in and can see the seller center home, press Enter here
        so the session is released and the profile is written.
"""
    )
    try:
        input("[login] press Enter once you are signed in > ")
    except (EOFError, KeyboardInterrupt):
        print("\n[login] no confirmation received — releasing without claiming a login.")
        r.release(s.id)
        return 1
    status = r.release(s.id)
    print(f"[login] released: {status}")
    _runlog(
        "login",
        {
            "profileIdDigest": S.digest(s.profile_id or "none"),
            "regionObserved": s.region,
            "humanConfirmedSignIn": True,
            "credentialsTypedByTool": 0,
        },
    )
    return 0


# ---------------------------------------------------------------- restore (STEP 3)

RESTORE_CHECK_TASK_NOTE = "navigate to the seller center home and report whether a login screen appears"


def cmd_restore(args) -> int:
    cfg = C.Config.load(need_llm=True)
    if not cfg.profile_id:
        raise SystemExit("[restore] POC_PROFILE_ID is not set. Run `login` first and save the printed profile id.")
    C.harden_third_party_telemetry()
    from poc.steel_client import SteelRunner

    r = SteelRunner(cfg.steel_api_key, cfg.steel_region)
    s = r.create(profile_id=cfg.profile_id, persist_profile=True)
    print(f"[restore] new session {s.id[:8]}… on profile {cfg.profile_id[:8]}…")
    print(f"[restore] watch it live: {s.viewer_url}")
    args.approval.commit(reason="agent is about to navigate to the NAVER seller center")
    try:
        outcome = asyncio.run(_probe_login_state(cfg, s.cdp_url))
    finally:
        r.release(s.id)
    print(f"[restore] verdict: {outcome.get('verdict')}  store={outcome.get('storeDigest')}")
    _runlog("restore", {"sessionIsNew": True, "profileReused": True, **outcome})
    return 0 if outcome.get("verdict") == "SESSION_RESTORED" else 1


async def _probe_login_state(cfg, cdp_url: str) -> dict:
    """A minimal agent turn whose only job is: is the saved login still good?"""
    from poc.export_agent import build_llm
    from browser_use import Agent, Browser
    from browser_use.tools.service import Tools
    from pydantic import BaseModel, Field

    class LoginState(BaseModel):
        verdict: str = Field(description="SESSION_RESTORED if the seller center is usable while signed in; AUTH_REQUIRED if a login or verification screen appeared; UNSUPPORTED_STATE otherwise.")
        observed_store_identifier: str = Field(default="", description="Stable store/channel/account number if visible. Not the display name.")
        note: str = Field(default="", description="One short sentence. No URLs, no customer text.")

    browser = Browser(cdp_url=cdp_url, is_local=False, allowed_domains=C.ALLOWED_DOMAINS)
    agent = Agent(
        task=(
            "네이버 스마트스토어 판매자센터 홈으로 이동해서, 지금 로그인된 상태인지만 확인하고 끝낸다.\n"
            "로그인/재인증/보안문자 화면이 보이면 AUTH_REQUIRED로 즉시 끝낸다. 아무것도 입력하지 않는다.\n"
            "로그인되어 있으면 스토어의 안정적인 식별자(스토어/채널 번호)를 읽어 보고하고 SESSION_RESTORED로 끝낸다.\n"
            "어떤 쓰기 동작도 하지 않는다. URL이나 고객 정보를 결과에 적지 않는다."
        ),
        llm=build_llm(cfg.llm_provider, cfg.llm_model, cfg.llm_api_key),
        browser=browser,
        tools=Tools(exclude_actions=C.EXCLUDED_ACTIONS, display_files_in_done_text=False),
        use_vision=cfg.use_vision,
        output_model_schema=LoginState,
    )
    history = await agent.run(max_steps=min(cfg.max_steps, 12))
    parsed = history.structured_output
    if parsed is None:
        return {"verdict": "UNSUPPORTED_STATE", "storeDigest": None, "steps": history.number_of_steps()}
    raw_id = (parsed.observed_store_identifier or "").strip()
    return {
        "verdict": parsed.verdict,
        "storeDigest": S.digest(raw_id) if raw_id else None,
        "storeIdentifierObserved": bool(raw_id),
        "steps": history.number_of_steps(),
        "note": S.scrub(parsed.note or "")[:160],
    }


# ---------------------------------------------------------------- export (STEP 4+5)

def cmd_export(args) -> int:
    cfg = C.Config.load(need_llm=True)
    if not cfg.profile_id:
        raise SystemExit("[export] POC_PROFILE_ID is not set. Run `login` first.")
    C.harden_third_party_telemetry()
    start, end = _window(args)
    from poc.steel_client import SteelRunner

    r = SteelRunner(cfg.steel_api_key, cfg.steel_region)
    s = r.create(profile_id=cfg.profile_id, persist_profile=True)
    print(f"[export] session {s.id[:8]}…  window {start}..{end}")
    print(f"[export] watch it live: {s.viewer_url}")

    result: dict = {"window": {"start": start, "end": end}, "sessionIsNew": True, "profileReused": True}
    args.approval.commit(reason="agent is about to navigate to the NAVER seller center and export")
    try:
        outcome = asyncio.run(_run_agent(cfg, s.cdp_url, start, end))
        result.update(outcome)

        if outcome.get("verdict") != "EXPORT_REQUESTED":
            print(f"[export] agent stopped with {outcome.get('verdict')} — no artifact fetch, fail closed.")
        else:
            result["artifact"] = _collect_artifact(r, s.id, run_tag=f"{start}_{end}")
    finally:
        r.release(s.id)

    _runlog("export", result)
    print("\n[export] result:")
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    ok = result.get("verdict") == "EXPORT_REQUESTED" and (result.get("artifact") or {}).get("sniff") in ("OOXML", "TEXT_DELIMITED")
    return 0 if ok else 1


async def _run_agent(cfg, cdp_url: str, start: str, end: str) -> dict:
    from poc.export_agent import build_llm, run_export

    history = await run_export(
        cdp_url=cdp_url,
        llm=build_llm(cfg.llm_provider, cfg.llm_model, cfg.llm_api_key),
        start=start,
        end=end,
        use_vision=cfg.use_vision,
        max_steps=cfg.max_steps,
        allowed_domains=C.ALLOWED_DOMAINS,
        excluded_actions=C.EXCLUDED_ACTIONS,
    )
    parsed = history.structured_output
    if parsed is None:
        return {"verdict": "UNSUPPORTED_STATE", "steps": history.number_of_steps()}
    raw_id = (parsed.observed_store_identifier or "").strip()
    return {
        "verdict": parsed.verdict,
        "steps": history.number_of_steps(),
        "storeIdentifierObserved": bool(raw_id),
        "storeDigest": S.digest(raw_id) if raw_id else None,
        "storeDisplayNameObserved": bool((parsed.observed_store_display_name or "").strip()),
        "scopeReadback": {
            "start": parsed.period_start_readback or None,
            "end": parsed.period_end_readback or None,
            "match": (parsed.period_start_readback == start and parsed.period_end_readback == end),
        },
        "downloadObserved": bool(parsed.download_observed),
        "note": S.scrub(parsed.note or "")[:160],
    }


def _collect_artifact(r, session_id: str, *, run_tag: str, settle_seconds: int = 20) -> dict:
    """STEP 5 — Steel Files API holds whatever the browser downloaded."""
    print("[export] waiting for the download to land in Steel's Files API ...")
    files: list[dict] = []
    for _ in range(settle_seconds):
        files = r.files(session_id)
        if files:
            break
        time.sleep(1)
    if not files:
        return {"present": False, "reason": "NO_FILE_IN_SESSION"}
    if len(files) > 1:
        # Ambiguity fails closed (§1 principle 5): we do not pick one and call it the export.
        return {"present": True, "count": len(files), "reason": "AMBIGUOUS_MULTIPLE_FILES",
                "sizes": sorted(f["size"] for f in files)}

    f = files[0]
    data = r.download(session_id, f["path"])
    meta = S.artifact_meta(data, f["path"])
    C.ARTIFACTS.mkdir(parents=True, exist_ok=True)
    out = C.ARTIFACTS / f"{run_tag}_{meta['sha256'][:12]}.bin"
    out.write_bytes(data)
    print(f"[export] artifact saved: .artifacts/{out.name}  ({meta['size']} bytes, {meta['sniff']})")
    return {"present": True, "count": 1, **meta, "savedAs": out.name}


# ---------------------------------------------------------------- repeat (STEP 6)

def cmd_repeat(args) -> int:
    """STEP 6 — a second export with NO human intervention, in a fresh session on the same
    profile. It runs the same two sub-legs but they are covered by the repeat approval that
    main() already spent; neither sub-leg spends the auth or export approval again."""
    print("=== STEP 6 — second run, fresh session, same profile, no human intervention ===")
    rc = cmd_restore(args)
    if rc != 0:
        print("[repeat] session did not restore; not attempting a second export.")
        return rc
    return cmd_export(args)


# ---------------------------------------------------------------- main

def main() -> int:
    C.load_dotenv_if_present()
    p = argparse.ArgumentParser(prog="poc.run", description="NAVER Review Cloud Export POC")
    sub = p.add_subparsers(dest="cmd", required=True)
    for name, fn, live in (
        ("selfcheck", cmd_selfcheck, False),
        ("manifest", cmd_manifest, False),
        ("probe", cmd_probe, False),
        ("login", cmd_login, True),
        ("restore", cmd_restore, True),
        ("export", cmd_export, True),
        ("repeat", cmd_repeat, True),
    ):
        sp = sub.add_parser(name, help=("LIVE — needs approval" if live else "offline/steel-only"))
        sp.add_argument("--start", default=None, help="window start YYYY-MM-DD")
        sp.add_argument("--end", default=None, help="window end YYYY-MM-DD")
        sp.set_defaults(fn=fn)
    args = p.parse_args()
    args.approval = _validate_approval(args.cmd)
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
