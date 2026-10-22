// @vitest-environment jsdom
//
// On the seller-PC install the helper serves the API-issuance guidance only: no other surface creates a bridge
// client, so nothing looks for a helper the install does not use (2026-10-22 external review).
import { afterEach, describe, expect, it, vi } from "vitest";
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

describe("useBridge", () => {
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
});
