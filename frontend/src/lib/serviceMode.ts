import { useCallback, useEffect, useState } from "react";
import { api } from "./apiClient";
import { isLocalSellerBuild } from "./security/localSellerMode";

/**
 * **Where this screen is served from, and so whether a local helper may be looked for at all** (boundary §5,
 * owner decision 2026-10-31; external review the same day).
 *
 * - `install` — confirmed an install that may have a PC helper: a seller-PC build, or a backend whose vendor link is
 *   not the web service's (`INSTALL`, linked or not). Only here does anything reach for the helper on this machine.
 * - `web` — the Reviewnary web service (vendor link `WORKSPACE`). The seller is asked to install, pair or approve
 *   nothing on a PC, and nothing here looks for one.
 * - `unknown` — not answered yet; `error` — could not be read. Neither is taken for `install`: a slow or failed
 *   answer must not bring back an install guide or a probe of this machine. Screens say they are waiting, or offer
 *   to ask again.
 *
 * One answer per page load, shared by every reader; a failed read is not kept, so the next reader asks again.
 */
export type ServiceMode = "unknown" | "install" | "web" | "error";

let current: ServiceMode = isLocalSellerBuild() ? "install" : "unknown";
let inflight: Promise<ServiceMode> | null = null;
const listeners = new Set<(mode: ServiceMode) => void>();

function set(mode: ServiceMode) {
  current = mode;
  listeners.forEach((l) => l(mode));
}

/** The mode as known now — `unknown` until a reader asked and the answer came. */
export function serviceMode(): ServiceMode {
  return current;
}

/** Whether something on this page may reach for a helper on this machine: only on a confirmed install. */
export function localHelperAllowed(): boolean {
  return current === "install";
}

/** Asks once (or again after a failure) and tells every reader. */
export function resolveServiceMode(): Promise<ServiceMode> {
  if (current === "install" || current === "web") return Promise.resolve(current);
  if (inflight) return inflight;
  if (current === "error") set("unknown");
  inflight = Promise.resolve()
    .then(() => api.getVendorLink())
    .then(
      (link) => (link?.mode === "WORKSPACE" ? "web" : link?.mode === "INSTALL" ? "install" : "error") as ServiceMode,
      () => "error" as ServiceMode,
    )
    .then((mode) => {
      inflight = null;
      set(mode);
      return mode;
    });
  return inflight;
}

/** The mode for a component, asked for on mount; `retry` asks again after `error`. */
export function useServiceMode(): { mode: ServiceMode; retry: () => void } {
  const [mode, setMode] = useState<ServiceMode>(current);
  useEffect(() => {
    listeners.add(setMode);
    setMode(current);
    if (current === "unknown" || current === "error") void resolveServiceMode();
    return () => {
      listeners.delete(setMode);
    };
  }, []);
  const retry = useCallback(() => void resolveServiceMode(), []);
  return { mode, retry };
}

/** Test seam: forget the answer (or set one). */
export function resetServiceModeForTests(mode: ServiceMode = "unknown"): void {
  inflight = null;
  current = mode;
}
