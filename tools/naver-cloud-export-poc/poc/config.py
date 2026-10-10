"""Environment for the NAVER Review Cloud Export POC.

Fail-closed by design: a missing name raises and the message carries the NAME ONLY.
No value from here is ever logged, printed or written to an artifact.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT / ".artifacts"
RUNS = ROOT / ".runs"

# The only hosts this POC is allowed to touch. The browser agent is fenced to these
# (browser-use `allowed_domains`); anything else is out of the sanctioned scope of
# docs/review_acquisition_aside_v2.md §12 (official Seller Center UI only).
ALLOWED_DOMAINS = [
    "*.smartstore.naver.com",
    "sell.smartstore.naver.com",
    "*.commerce.naver.com",
    "nid.naver.com",  # NAVER's own login host — reachable so we can DETECT and STOP on it
]

# Actions removed from the agent's catalogue. Each exclusion has a reason.
EXCLUDED_ACTIONS = [
    "evaluate",      # arbitrary JS in the page = the network/endpoint reverse-engineering surface §12 excludes
    "search",        # web search would take the agent off the seller center
    "read_file",     # local filesystem, reachable by the model — also how the XLSX could reach the vendor
    "write_file",
    "replace_file",
    "upload_file",   # pushing a file INTO a marketplace page is not a read
    "save_as_pdf",   # keeps the run's artifact count at exactly one
    "screenshot",    # PRODUCT DECISION 2026-09-27: no screenshot, no vision. use_vision=False alone does
                     # NOT close this — the action would still capture a page image and send it to the
                     # vendor. The decision is enforced by removing the means, not by a default.
]


def _require(name: str) -> str:
    v = os.environ.get(name, "").strip()
    if not v:
        raise SystemExit(
            f"[config] {name} is not set. This POC does not guess it.\n"
            f"         Copy .env.example to .env and fill it in. See README.md."
        )
    return v


def _require_bool(name: str) -> bool:
    v = _require(name).lower()
    if v not in ("true", "false"):
        raise SystemExit(f"[config] {name} must be exactly 'true' or 'false' (got a different value).")
    return v == "true"


def _require_vision_off() -> bool:
    """PRODUCT DECISION 2026-09-27: text/accessibility-driven execution only; screenshot and
    vision are forbidden for this POC. The name must still be stated explicitly — a silent
    default is how a payload floor stops being a decision — but `true` is refused."""
    if _require_bool("POC_USE_VISION"):
        raise SystemExit(
            "[config] POC_USE_VISION=true is refused.\n"
            "         Product decision 2026-09-27: screenshot/vision are not permitted in this POC\n"
            "         (text/accessibility serialization only). Set POC_USE_VISION=false."
        )
    return False


@dataclass(frozen=True)
class Config:
    steel_api_key: str
    steel_region: str
    llm_provider: str
    llm_api_key: str
    llm_model: str
    use_vision: bool
    profile_id: str | None
    max_steps: int

    @staticmethod
    def load(*, need_llm: bool) -> "Config":
        provider = os.environ.get("POC_LLM_PROVIDER", "").strip().lower()
        if need_llm:
            provider = _require("POC_LLM_PROVIDER").lower()
            if provider not in ("openai", "anthropic"):
                raise SystemExit("[config] POC_LLM_PROVIDER must be 'openai' or 'anthropic'.")
        return Config(
            steel_api_key=_require("STEEL_API_KEY"),
            steel_region=os.environ.get("POC_STEEL_REGION", "nrt").strip() or "nrt",
            llm_provider=provider,
            llm_api_key=_require("POC_LLM_API_KEY") if need_llm else "",
            llm_model=_require("POC_LLM_MODEL") if need_llm else "",
            use_vision=_require_vision_off() if need_llm else False,
            profile_id=(os.environ.get("POC_PROFILE_ID", "").strip() or None),
            max_steps=int(os.environ.get("POC_MAX_STEPS", "40")),
        )


def load_dotenv_if_present() -> None:
    """Minimal .env reader. Deliberately not `python-dotenv` writing into os.environ
    with surprises: KEY=VALUE lines only, '#' comments, no export, no interpolation."""
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
    """browser-use ships anonymized PostHog telemetry ON by default. A run that touches a
    seller's store does not report itself to a third party. Set before importing the agent."""
    os.environ["ANONYMIZED_TELEMETRY"] = "false"
    os.environ["BROWSER_USE_CLOUD_SYNC"] = "false"
