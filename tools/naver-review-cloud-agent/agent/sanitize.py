"""The no-leak gate and artifact metadata.

docs/review_acquisition_aside_v2.md §5: "실행 로그(Aside가 무엇을 했는가)는 run에 sanitized
enum/count로만 붙인다. baseline의 wire 금지 필드(selector·URL·path·credential·page content)는
provider 응답에도 그대로 적용."

This module is this agent's copy of that rule. Anything written to .runs/ passes through
`scrub()` first, and `assert_clean()` refuses to write when a forbidden shape is present.
"""

from __future__ import annotations

import hashlib
import os
import re

# A run log may name enums, counts and digests. It may not carry these.
FORBIDDEN = [
    (re.compile(r"(?i)\bcookie\b"), "cookie"),
    (re.compile(r"(?i)set-cookie"), "set-cookie"),
    (re.compile(r"(?i)\b(password|passwd|pwd)\b"), "password"),
    (re.compile(r"(?i)\b(bearer|authorization)\b"), "auth-header"),
    (re.compile(r"(?i)NID_AUT|NID_SES|NID_JKL"), "naver-session-cookie"),
    (re.compile(r"https?://"), "url"),
    (re.compile(r"(?i)querySelector|xpath|css=|\bcss_selector\b"), "selector"),
    (re.compile(r"/Users/|/home/|[A-Za-z]:\\\\"), "filesystem-path"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), "email"),
    (re.compile(r"01[016789][-\s]?\d{3,4}[-\s]?\d{4}"), "phone-kr"),
]

# The one place a salt lives. Absent salt is not a silent fallback to unsalted:
# an unsalted hash of a store id is a lookup table away from the store id.
_SALT_ENV = "AGENT_DIGEST_SALT"


def findings(text: str) -> list[str]:
    return sorted({label for pat, label in FORBIDDEN if pat.search(text)})


def assert_clean(text: str, *, what: str) -> None:
    bad = findings(text)
    if bad:
        raise SystemExit(f"[sanitize] refusing to record {what}: forbidden field(s) present: {', '.join(bad)}")


def scrub(text: str) -> str:
    """Best-effort redaction for text we still want to keep the SHAPE of."""
    out = text
    out = re.sub(r"https?://[^\s\"'<>)]+", "[url]", out)
    out = re.sub(r"[\w.+-]+@[\w-]+\.[\w.]+", "[email]", out)
    out = re.sub(r"01[016789][-\s]?\d{3,4}[-\s]?\d{4}", "[phone]", out)
    out = re.sub(r"(/Users/|/home/)[^\s\"']+", "[path]", out)
    return out


def digest(raw: str, *, prefix: int = 12) -> str:
    """Salted one-way digest, same shape as the baseline's account-fingerprint contract.
    Used for the observed store identity (PD-4 / H-3): we record THAT we observed a
    stable identifier and whether it is the same one across runs — never its value."""
    salt = os.environ.get(_SALT_ENV, "").strip()
    if not salt:
        raise SystemExit(
            f"[sanitize] {_SALT_ENV} is not set. A store-identity digest without a salt is\n"
            f"           reversible by lookup. Set any long random local string in .env."
        )
    h = hashlib.sha256((salt + "\x1f" + raw.strip()).encode("utf-8")).hexdigest()
    return f"sha256:{h[:prefix]}"


# --- artifact metadata -------------------------------------------------------------

_OOXML = b"PK\x03\x04"


def name_category(path: str) -> str:
    """NAVER's review export lands as an extensionless UUID name (baseline §2.4), so the
    POC records the CATEGORY of the name, never the name."""
    base = path.rsplit("/", 1)[-1]
    if "." not in base:
        return "EXTENSIONLESS"
    ext = base.rsplit(".", 1)[-1].lower()
    return {"xlsx": "XLSX", "xls": "XLS", "csv": "CSV"}.get(ext, "OTHER")


def sniff(data: bytes) -> str:
    """Byte sniff, the same question the Runner asks before upload (§7):
    UNKNOWN => ARTIFACT_INVALID, upload 0."""
    if data.startswith(_OOXML):
        return "OOXML"
    if data[:512].count(b"\x00") == 0 and (b"," in data[:512] or b"\t" in data[:512]):
        return "TEXT_DELIMITED"
    return "UNKNOWN"


def artifact_meta(data: bytes, path: str) -> dict:
    return {
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
        "nameCategory": name_category(path),
        "sniff": sniff(data),
    }
