import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { api } from "../../lib/apiClient";
import { CALL_IP_COPY } from "../../lib/guidedConnection";
import type { VendorLinkStatus, VendorPublicIpView } from "../../lib/types";
import { CopyableIp } from "./CopyableIp";

/**
 * **The 'API 호출 IP' step on a seller's PC** — the address to register is this PC's own public IPv4, read from the
 * reviewnary server once this PC is linked to it (docs/vendor_server_contract_v1.md §2 · §7).
 *
 * <ul>
 *   <li>Linked and answered: the address with a copy control. Nothing else is claimed — whether NAVER accepts it is
 *       still decided by the connection test.</li>
 *   <li>Not linked: 「이 PC 연결」 opens the server's approval page in this same browser with the code filled in
 *       (owner decision 2026-10-17: no phone, no copying a code), then polls until the seller allows it.</li>
 *   <li>Anything else (link off, server unreachable, IPv6 only, revoked): the manual guidance, which always stays
 *       available — the seller is never blocked on the server.</li>
 * </ul>
 */
export function ThisPcCallIp({ ackSlot }: { ackSlot?: ReactNode }) {
  const [ip, setIp] = useState<VendorPublicIpView | null>(null);
  const [link, setLink] = useState<VendorLinkStatus | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);

  const load = useCallback(async () => {
    try {
      const [nextIp, nextLink] = await Promise.all([api.getVendorPublicIp(), api.getVendorLink()]);
      if (!mounted.current) return;
      setIp(nextIp);
      setLink(nextLink);
    } catch {
      if (mounted.current) setFailed(true);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
    };
  }, [load]);

  // While a code waits for approval, ask the backend once per interval whether it was allowed.
  useEffect(() => {
    if (link?.state !== "PENDING") return undefined;
    const delay = Math.max(1, link.pollIntervalSeconds ?? 3) * 1000;
    const timer = window.setTimeout(async () => {
      try {
        const next = await api.pollVendorLink();
        if (!mounted.current) return;
        setLink(next);
        if (next.state === "LINKED") setIp(await api.getVendorPublicIp());
      } catch {
        if (mounted.current) setLink({ ...link });
      }
    }, delay);
    return () => window.clearTimeout(timer);
  }, [link]);

  const openApproval = (status: VendorLinkStatus) => {
    if (status.approvalUrl) window.open(status.approvalUrl, "_blank", "noopener,noreferrer");
  };

  const start = async (relink: boolean) => {
    setBusy(true);
    setFailed(false);
    try {
      if (relink) await api.unlinkVendor();
      const next = await api.startVendorLink();
      if (!mounted.current) return;
      setLink(next);
      setIp(null);
      openApproval(next);
    } catch {
      if (mounted.current) setFailed(true);
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  if (ip?.ipv4) {
    return (
      <div className="space-y-1 rounded-lg bg-canvas px-3 py-2" aria-label="이 PC의 호출 IP">
        <p className="text-xs text-muted">{CALL_IP_COPY.thisPcDetectedTitle}</p>
        <ul className="space-y-1">
          <CopyableIp ip={ip.ipv4} />
        </ul>
        <p className="text-xs text-muted">{CALL_IP_COPY.thisPcChangeNote}</p>
        {ackSlot}
      </div>
    );
  }

  const canLink = link !== null && link.state !== "OFF";
  const pending = link?.state === "PENDING" ? link : null;
  const reasonLine =
    ip?.reason === "UNREACHABLE"
      ? CALL_IP_COPY.ipUnreachable
      : ip?.reason === "IPV6_ONLY"
        ? CALL_IP_COPY.ipIpv6Only
        : ip?.reason === "NOT_AUTHORIZED"
          ? CALL_IP_COPY.ipRevoked
          : null;

  return (
    <div className="space-y-2 rounded-lg bg-canvas px-3 py-2" role="note" aria-label="이 PC의 호출 IP 등록 안내">
      <p className="text-xs font-medium text-ink">{CALL_IP_COPY.thisPcTitle}</p>
      {reasonLine && <p className="text-xs text-muted">{reasonLine}</p>}
      {failed && <p className="text-xs text-muted">{CALL_IP_COPY.linkFailed}</p>}
      {pending ? (
        <div className="space-y-1" data-testid="vendor-link-pending">
          <p className="text-xs text-ink">{CALL_IP_COPY.linkPending}</p>
          {pending.userCode && (
            <p className="text-xs text-muted">
              {CALL_IP_COPY.linkCodeLabel}{" "}
              <span className="font-medium tabular-nums text-ink">
                {pending.userCode.slice(0, 4)}-{pending.userCode.slice(4)}
              </span>
            </p>
          )}
          <button type="button" className="btn-ghost text-xs" onClick={() => openApproval(pending)}>
            {CALL_IP_COPY.linkReopen}
          </button>
        </div>
      ) : (
        canLink &&
        (link?.state === "UNLINKED" || link?.state === "CODE_EXPIRED" || ip?.reason === "NOT_AUTHORIZED") && (
          <div className="space-y-1">
            <p className="text-xs text-muted">
              {link?.state === "CODE_EXPIRED" ? CALL_IP_COPY.linkExpired : CALL_IP_COPY.linkOffer}
            </p>
            <button
              type="button"
              className="btn-primary text-xs"
              disabled={busy}
              onClick={() => void start(ip?.reason === "NOT_AUTHORIZED")}
              data-testid="vendor-link-start"
            >
              {ip?.reason === "NOT_AUTHORIZED" ? CALL_IP_COPY.relinkCta : CALL_IP_COPY.linkCta}
            </button>
          </div>
        )
      )}
      <p className="text-xs text-muted">{CALL_IP_COPY.thisPcBody}</p>
      {ackSlot}
    </div>
  );
}
