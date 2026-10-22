import { useEffect, useRef, useState } from "react";
import { BridgeClient, makeBridgeClient, type BridgeState } from "../lib/bridge/bridgeClient";
import { isLocalSellerBuild } from "../lib/security/localSellerMode";

/**
 * React binding for the Local Agent Bridge client. Owns the client lifecycle, drives the pairing poll while
 * a confirmation is pending, and auto-reconnects after a drop or while the agent is unreachable. Reconnect
 * after a page refresh happens naturally: a fresh client reads the stored pairing token on mount and
 * restores the snapshot (slice acceptance criterion — refresh reconnects + restores state).
 *
 * `enabled` (default true) gates the whole client lifecycle. When false, NO client is created and NO poll
 * runs, so a surface that does not use the bridge (e.g. the Local-Agent-free NAVER order connection when
 * the bridge feature flag is off) never opens a local connection or triggers a Local-Network-Access prompt.
 *
 * **On the seller-PC install** (`VITE_LOCAL_SELLER=true`) the helper serves one thing only: the API-center
 * issuance guidance the seller starts by pressing (scope lock v1.14 ⑧-4, the guidance-only exception to ⑦).
 * There the client exists only for `purpose: "issuance"`; every other surface stays inert and asks nothing —
 * a helper the install does not use must not be looked for every 1.5 seconds (2026-10-22 external review).
 */
export function useBridge(
  enabled = true,
  options: {
    /**
     * Whether an UNPAIRED agent is asked to pair automatically on first sight (default true — the product
     * intent). A surface that may mount several times on one screen (a conversation artifact) passes
     * false so it never raises more than the ONE OS dialog the seller's own press asks for.
     */
    autoPair?: boolean;
    /** What the bridge is for. On the seller-PC install only `"issuance"` opens it (see above). */
    purpose?: "issuance";
  } = {},
): {
  state: BridgeState;
  requestPairing: () => void;
  revoke: () => void;
  retry: () => void;
} {
  const clientRef = useRef<BridgeClient | null>(null);
  const [state, setState] = useState<BridgeState>({ phase: "connecting", maybeNeedsLocalNetworkAccess: false });

  const open = enabled && (!isLocalSellerBuild() || options.purpose === "issuance");

  useEffect(() => {
    if (!open) {
      // Disabled: no client, no polling. Report a stable inert state (never "paired").
      clientRef.current = null;
      setState({ phase: "disconnected", maybeNeedsLocalNetworkAccess: false });
      return;
    }
    const client = makeBridgeClient(options.autoPair === undefined ? {} : { autoPair: options.autoPair });
    clientRef.current = client;
    const unsubscribe = client.subscribe(setState);
    void client.refresh();

    const interval = setInterval(() => {
      const s = client.getState();
      if (s.phase === "pairing_pending") void client.pollPairingOnce();
      else if (s.phase === "disconnected" || s.phase === "unreachable") void client.refresh();
    }, 1500);

    return () => {
      clearInterval(interval);
      unsubscribe();
      client.stop();
      clientRef.current = null;
    };
    // `options.autoPair` is read once, at client creation, on purpose — it must not re-create the client.
  }, [open]);

  return {
    state,
    requestPairing: () => void clientRef.current?.requestPairing(),
    revoke: () => void clientRef.current?.revoke(),
    retry: () => void clientRef.current?.refresh(),
  };
}
