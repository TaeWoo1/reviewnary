"""Environment for the cloud NAVER review export agent.

Fail-closed: a missing name raises, and the message carries the NAME ONLY. No value from here is
logged, printed or written to an artifact.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROFILE = ROOT / ".profile"       # the seller centre session, on this VM's disk only
ARTIFACTS = ROOT / ".artifacts"   # official XLSX, briefly — deleted after the server ACKs
RUNS = ROOT / ".runs"             # sanitized run records

# The only hosts the agent may touch. nid.naver.com is reachable ONLY so an auth screen can be
# recognised and reported; nothing here types a credential.
ALLOWED_DOMAINS = [
    "*.smartstore.naver.com",
    "sell.smartstore.naver.com",
    "*.commerce.naver.com",
    "nid.naver.com",
]

# Removed from the agent's catalogue. Each exclusion closes something this lane must not be able to do.
EXCLUDED_ACTIONS = [
    "evaluate",      # arbitrary page JS — the endpoint reverse-engineering surface
    "search",        # a web search would take the agent off the seller centre
    "read_file",     # local filesystem, reachable by the model — also how the XLSX could reach the vendor
    "write_file",
    "replace_file",
    "upload_file",   # pushing a file INTO a marketplace page is not a read
    "save_as_pdf",   # keeps the run's artifact count at exactly one
    "screenshot",    # no vision, no screenshots. use_vision=False alone does not close this.
]


def _require(name: str) -> str:
    v = os.environ.get(name, "").strip()
    if not v:
        raise SystemExit(f"[config] {name} is not set. This agent does not guess it. See README.md.")
    return v


def _require_vision_off() -> bool:
    """Text / accessibility serialization only. The name must still be stated — a silent default is how a
    payload floor stops being a decision — but `true` is refused."""
    v = _require("AGENT_USE_VISION").lower()
    if v == "true":
        raise SystemExit("[config] AGENT_USE_VISION=true is refused: screenshots/vision are not permitted.")
    if v != "false":
        raise SystemExit("[config] AGENT_USE_VISION must be exactly 'false'.")
    return False


@dataclass(frozen=True)
class Config:
    base_url: str
    device_token: str
    llm_provider: str
    llm_api_key: str
    llm_model: str
    use_vision: bool
    max_steps: int
    headless: bool
    interval_minutes: int
    digest_salt: str

    @staticmethod
    def load() -> "Config":
        provider = _require("AGENT_LLM_PROVIDER").lower()
        if provider not in ("openai", "anthropic"):
            raise SystemExit("[config] AGENT_LLM_PROVIDER must be 'openai' or 'anthropic'.")
        base = _require("REVIEWNARY_BASE_URL").rstrip("/")
        if not base.startswith("https://") and "127.0.0.1" not in base and "localhost" not in base:
            raise SystemExit("[config] REVIEWNARY_BASE_URL must be https:// (or a loopback dev address).")
        token = _require("REVIEWNARY_DEVICE_TOKEN")
        if not token.startswith("rvh_"):
            raise SystemExit("[config] REVIEWNARY_DEVICE_TOKEN is not a helper device token (rvh_…).")
        return Config(
            base_url=base,
            device_token=token,
            llm_provider=provider,
            llm_api_key=_require("AGENT_LLM_API_KEY"),
            llm_model=_require("AGENT_LLM_MODEL"),
            use_vision=_require_vision_off(),
            max_steps=int(os.environ.get("AGENT_MAX_STEPS", "40")),
            headless=os.environ.get("AGENT_HEADLESS", "false").strip().lower() == "true",
            interval_minutes=int(os.environ.get("AGENT_INTERVAL_MINUTES", "120")),
            digest_salt=_require("AGENT_DIGEST_SALT"),
        )


def load_dotenv_if_present() -> None:
    f = ROOT / ".env"
    if not f.exists():
        return
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip())


def harden_third_party_telemetry() -> None:
    """browser-use ships anonymized PostHog telemetry ON by default. A run that drives a seller's own
    store does not report itself to a third party. Set before importing the agent."""
    os.environ["ANONYMIZED_TELEMETRY"] = "false"
    os.environ["BROWSER_USE_CLOUD_SYNC"] = "false"
