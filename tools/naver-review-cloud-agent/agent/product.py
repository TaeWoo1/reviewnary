"""The three calls this agent makes against Reviewnary, and no others.

All three are already reachable with a helper device token (`rvh_`):

    POST /api/helper-devices/review-export/next-launch   mint the next NAVER review launch for myself
    GET  /api/imports/reviews/launches/{ref}/scope        what period that launch asks for
    POST /api/imports/reviews/launches/{ref}/ingest       the official XLSX bytes

The third one is the canonical importer's own entry point — `UploadFormat` → `FileParser` →
`ReviewRowMapper` → `IngestionService`, in backend memory. This agent contains no parser, does not
read a single row out of the file, and has no second ingest path.

<b>It cannot reach anything else.</b> `HelperDeviceAuthFilter` refuses a device token on every route
outside its allow-list, so there is nothing to be careful about here: a mistake in this file becomes
a 401, not a wider action.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

_TIMEOUT = 60


class ProductError(RuntimeError):
    """A refusal from the product. Carries the status and the server's own sentence, nothing else."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.message = message


@dataclass(frozen=True)
class LaunchScope:
    launch_ref: str
    required_start: str | None
    required_end: str | None
    channel_code: str | None
    account_slot: str | None


class Reviewnary:
    def __init__(self, base_url: str, device_token: str) -> None:
        self._base = base_url.rstrip("/")
        self._token = device_token

    # -- wire ---------------------------------------------------------------------
    def _request(self, method: str, path: str, *, body: bytes | None = None,
                 content_type: str | None = None) -> dict:
        req = urllib.request.Request(self._base + path, data=body, method=method)
        req.add_header("Authorization", f"Bearer {self._token}")
        if content_type:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=_TIMEOUT) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                payload = json.loads(e.read() or b"{}")
                detail = str(payload.get("message") or payload.get("error") or "")
            except Exception:  # noqa: BLE001 - the server's error body is not required to be JSON
                detail = ""
            raise ProductError(e.code, detail) from None

    # -- the three calls ----------------------------------------------------------
    def next_launch(self) -> LaunchScope:
        """No arguments, by design: the organisation, the device, the channel and the account are all
        decided server-side. There is no field here through which another store could be named."""
        view = self._request("POST", "/api/helper-devices/review-export/next-launch")
        ref = view.get("launchRef") or view.get("ref")
        if not ref:
            raise ProductError(500, "launch ref missing from the mint response")
        return LaunchScope(
            launch_ref=str(ref),
            required_start=_str_or_none(view.get("requiredStart")),
            required_end=_str_or_none(view.get("requiredEnd")),
            channel_code=_str_or_none(view.get("channelCode")),
            account_slot=_str_or_none(view.get("accountSlot")),
        )

    def scope(self, launch_ref: str) -> LaunchScope:
        view = self._request("GET", f"/api/imports/reviews/launches/{launch_ref}/scope")
        return LaunchScope(
            launch_ref=launch_ref,
            required_start=_str_or_none((view.get("required") or {}).get("start") or view.get("requiredStart")),
            required_end=_str_or_none((view.get("required") or {}).get("end") or view.get("requiredEnd")),
            channel_code=_str_or_none(view.get("channelCode")),
            account_slot=_str_or_none(view.get("accountSlot")),
        )

    def ingest(self, launch_ref: str, data: bytes, filename: str, scope_evidence: str) -> dict:
        """`scopeEvidence` is the server's closed vocabulary: MACHINE_MATCHED when the export form's own
        dates were read back and matched what was asked, OPERATOR_CONFIRMED otherwise. It is never
        guessed upward — a run that could not read the form back says so."""
        if scope_evidence not in ("MACHINE_MATCHED", "OPERATOR_CONFIRMED"):
            raise ValueError("scope_evidence must be MACHINE_MATCHED or OPERATOR_CONFIRMED")
        boundary = "----reviewnary-agent-boundary"
        parts = [
            f"--{boundary}\r\n".encode(),
            b'Content-Disposition: form-data; name="scopeEvidence"\r\n\r\n',
            scope_evidence.encode(), b"\r\n",
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: application/octet-stream\r\n\r\n",
            data, b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
        return self._request("POST", f"/api/imports/reviews/launches/{launch_ref}/ingest",
                             body=b"".join(parts),
                             content_type=f"multipart/form-data; boundary={boundary}")


def _str_or_none(v) -> str | None:
    return None if v is None else str(v)
