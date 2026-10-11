"""Steel session lifecycle for the POC.

Two properties this module exists to hold:

1. **The seller's NAVER session never lands on this machine.** Persistence is done with
   Steel's own profile (`persist_profile=True` + `profile_id`), NOT by fetching
   `sessions.context(id)` — that call returns cookies/localStorage, i.e. the session
   itself. We never call it. This is what §4 TARGET means by "Aside가 소유. Reviewnary·
   Runner는 cookie·비밀번호·세션 토큰을 받지도 저장하지도 로깅하지도 않는다".

2. **No bot-detection evasion, no CAPTCHA solving.** Steel offers both. CLAUDE.md
   forbids both, for any provider. `create()` passes solve_captcha=False and never
   passes stealth_config, and `assert_session_fences()` re-reads the SERVER's answer.
"""

from __future__ import annotations

from dataclasses import dataclass

from steel import Steel
from steel.types import Session


@dataclass(frozen=True)
class LiveSession:
    id: str
    cdp_url: str
    viewer_url: str
    profile_id: str | None
    region: str | None


class SteelRunner:
    def __init__(self, api_key: str, region: str) -> None:
        self._c = Steel(steel_api_key=api_key)
        self._region = region

    # -- lifecycle ---------------------------------------------------------------
    def create(self, *, profile_id: str | None, persist_profile: bool) -> LiveSession:
        kwargs: dict = {
            "region": self._region,
            "persist_profile": persist_profile,
            # Explicit, not defaulted: the two things we refuse to do.
            "solve_captcha": False,
            # stealth_config is deliberately ABSENT.
            "headless": False,   # the human logs in through the session viewer
            "block_ads": True,
            "timeout": 900_000,  # 15 min ceiling per session
        }
        if profile_id:
            kwargs["profile_id"] = profile_id
        s: Session = self._c.sessions.create(**kwargs)
        self.assert_session_fences(s)
        return LiveSession(
            id=s.id,
            cdp_url=s.websocket_url,
            viewer_url=s.session_viewer_url,
            profile_id=s.profile_id,
            region=str(s.region) if s.region else None,
        )

    def release(self, session_id: str) -> str:
        r = self._c.sessions.release(session_id)
        return getattr(r, "status", "released") or "released"

    def retrieve(self, session_id: str) -> Session:
        return self._c.sessions.retrieve(session_id)

    # -- fences ------------------------------------------------------------------
    @staticmethod
    def assert_session_fences(s: Session) -> None:
        """Read back what the SERVER says it created. A session that came up with CAPTCHA
        solving or stealth on is not a session this POC is allowed to drive."""
        if s.solve_captcha:
            raise SystemExit("[steel] refusing session: solve_captcha is ON (CLAUDE.md forbids CAPTCHA bypass).")
        sc = s.stealth_config
        if sc is not None:
            active = {k: v for k, v in (sc.model_dump(exclude_none=True) or {}).items() if v}
            if active:
                raise SystemExit(
                    f"[steel] refusing session: stealth_config is active ({sorted(active)}). "
                    f"Bot-detection evasion is out of scope (§12)."
                )

    # -- files -------------------------------------------------------------------
    def files(self, session_id: str) -> list[dict]:
        listed = self._c.sessions.files.list(session_id)
        out = []
        for f in (listed.data or []):
            out.append({"path": f.path, "size": int(f.size), "lastModified": f.last_modified.isoformat()})
        return out

    def download(self, session_id: str, path: str) -> bytes:
        r = self._c.sessions.files.download(path, session_id=session_id)
        return r.read()
