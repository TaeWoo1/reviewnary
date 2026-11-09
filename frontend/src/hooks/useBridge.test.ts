// @vitest-environment jsdom
//
// On the seller-PC install the helper serves the API-issuance guidance only: no other surface creates a bridge
// client, so nothing looks for a helper the install does not use (2026-10-22 external review).
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

const made = vi.fn();

vi.mock("../lib/bridge/bridgeClient", () => ({
  makeBridgeClient: () => {
    made();
    return {
      subscribe: () => () => undefined,
      refresh: () => Promise.resolve(),
      getState: () => ({ phase: "connecting", maybeNeedsLocalNetworkAccess: false }),
      pollPairingOnce: () => Promise.resolve(),
      stop: () => undefined,
    };
  },
}));

import { useBridge } from "./useBridge";
import { resetServiceModeForTests, type ServiceMode } from "../lib/serviceMode";

vi.mock("../lib/apiClient", () => ({ api: { getVendorLink: () => new Promise(() => undefined) } }));

describe("useBridge", () => {
  beforeEach(() => resetServiceModeForTests("install"));
  afterEach(() => {
    vi.unstubAllEnvs();
    made.mockClear();
  });

  it("on the seller-PC install opens only for the issuance guidance", () => {
    vi.stubEnv("VITE_LOCAL_SELLER", "true");
    const status = renderHook(() => useBridge(true));
    expect(made).not.toHaveBeenCalled();
    expect(status.result.current.state.phase).toBe("disconnected");

    renderHook(() => useBridge(false, { purpose: "issuance" }));
    expect(made).not.toHaveBeenCalled();
    renderHook(() => useBridge(true, { purpose: "issuance" }));
    expect(made).toHaveBeenCalledTimes(1);
  });

  it("elsewhere opens whenever enabled, as before", () => {
    vi.stubEnv("VITE_LOCAL_SELLER", "");
    renderHook(() => useBridge(true));
    expect(made).toHaveBeenCalledTimes(1);
  });

  // The web service never reaches for a helper on this machine — nor does a page that has not yet heard which kind
  // of service it is, or could not (external review 2026-10-31).
  it.each<ServiceMode>(["web", "unknown", "error"])("does not open when the service is %s", (mode) => {
    resetServiceModeForTests(mode);
    const status = renderHook(() => useBridge(true));
    renderHook(() => useBridge(true, { purpose: "issuance" }));
    expect(made).not.toHaveBeenCalled();
    expect(status.result.current.state.phase).toBe("disconnected");
  });
});
