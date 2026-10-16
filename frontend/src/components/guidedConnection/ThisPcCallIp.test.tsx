// @vitest-environment jsdom
//
// The seller PC's 'API 호출 IP' step: the address is read from the reviewnary server once this PC is linked, the
// link is approved in this same browser (no phone, no copied code), and the manual guidance is never taken away.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { VendorLinkStatus, VendorPublicIpView } from "../../lib/types";
import { CALL_IP_COPY } from "../../lib/guidedConnection";

const getVendorLink = vi.fn<() => Promise<VendorLinkStatus>>();
const startVendorLink = vi.fn<() => Promise<VendorLinkStatus>>();
const pollVendorLink = vi.fn<() => Promise<VendorLinkStatus>>();
const unlinkVendor = vi.fn<() => Promise<VendorLinkStatus>>();
const getVendorPublicIp = vi.fn<() => Promise<VendorPublicIpView>>();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getVendorLink: () => getVendorLink(),
    startVendorLink: () => startVendorLink(),
    pollVendorLink: () => pollVendorLink(),
    unlinkVendor: () => unlinkVendor(),
    getVendorPublicIp: () => getVendorPublicIp(),
  },
}));

import { ThisPcCallIp } from "./ThisPcCallIp";

const APPROVAL = "http://127.0.0.1:8090/link?code=BCDFGHJK";

function status(state: VendorLinkStatus["state"], over: Partial<VendorLinkStatus> = {}): VendorLinkStatus {
  return {
    state, offReason: null, userCode: null, codeExpiresAt: null, pollIntervalSeconds: null, approvalUrl: null,
    deviceName: null, linkedAt: null, ...over,
  };
}

describe("ThisPcCallIp", () => {
  let open: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    open = vi.fn();
    vi.stubGlobal("open", open);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it("a linked PC shows the address the server saw, with a copy control", async () => {
    getVendorLink.mockResolvedValue(status("LINKED", { deviceName: "macOS (aarch64)" }));
    getVendorPublicIp.mockResolvedValue({ ipv4: "203.0.113.7", reason: null });
    render(<ThisPcCallIp />);
    expect(await screen.findByText("203.0.113.7")).toBeInTheDocument();
    expect(screen.getByText(CALL_IP_COPY.thisPcDetectedTitle)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "복사" })).toBeInTheDocument();
    expect(screen.queryByTestId("vendor-link-start")).toBeNull();
  });

  it("linking opens the approval page in this browser, shows the code, and continues once allowed", async () => {
    getVendorLink.mockResolvedValue(status("UNLINKED"));
    getVendorPublicIp.mockResolvedValueOnce({ ipv4: null, reason: "NOT_LINKED" });
    startVendorLink.mockResolvedValue(status("PENDING", { userCode: "BCDFGHJK", pollIntervalSeconds: 1,
      approvalUrl: APPROVAL }));
    render(<ThisPcCallIp />);
    expect(await screen.findByText(CALL_IP_COPY.thisPcBody)).toBeInTheDocument();

    vi.useFakeTimers({ shouldAdvanceTime: true });
    fireEvent.click(await screen.findByTestId("vendor-link-start"));
    await waitFor(() => expect(open).toHaveBeenCalledWith(APPROVAL, "_blank", "noopener,noreferrer"));
    expect(await screen.findByText("BCDF-GHJK")).toBeInTheDocument();
    expect(screen.queryByText(APPROVAL)).toBeNull();
    expect(screen.getByText(CALL_IP_COPY.thisPcBody)).toBeInTheDocument();

    pollVendorLink.mockResolvedValue(status("LINKED"));
    getVendorPublicIp.mockResolvedValue({ ipv4: "203.0.113.7", reason: null });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100);
    });
    expect(await screen.findByText("203.0.113.7")).toBeInTheDocument();
    expect(pollVendorLink).toHaveBeenCalled();
  });

  it("an install with the link off keeps the manual guidance and offers no link", async () => {
    getVendorLink.mockResolvedValue(status("OFF", { offReason: "NOT_CONFIGURED" }));
    getVendorPublicIp.mockResolvedValue({ ipv4: null, reason: "NOT_LINKED" });
    render(<ThisPcCallIp />);
    expect(await screen.findByText(CALL_IP_COPY.thisPcBody)).toBeInTheDocument();
    expect(screen.queryByTestId("vendor-link-start")).toBeNull();
  });

  it("an unreachable server says so and leaves the manual guidance", async () => {
    getVendorLink.mockResolvedValue(status("LINKED"));
    getVendorPublicIp.mockResolvedValue({ ipv4: null, reason: "UNREACHABLE" });
    render(<ThisPcCallIp />);
    expect(await screen.findByText(CALL_IP_COPY.ipUnreachable)).toBeInTheDocument();
    expect(screen.getByText(CALL_IP_COPY.thisPcBody)).toBeInTheDocument();
  });

  it("a PC the server no longer accepts can link again", async () => {
    getVendorLink.mockResolvedValue(status("LINKED"));
    getVendorPublicIp.mockResolvedValue({ ipv4: null, reason: "NOT_AUTHORIZED" });
    unlinkVendor.mockResolvedValue(status("UNLINKED"));
    startVendorLink.mockResolvedValue(status("PENDING", { userCode: "BCDFGHJK", approvalUrl: APPROVAL }));
    render(<ThisPcCallIp />);
    fireEvent.click(await screen.findByText(CALL_IP_COPY.relinkCta));
    await waitFor(() => expect(unlinkVendor).toHaveBeenCalled());
    await waitFor(() => expect(open).toHaveBeenCalledWith(APPROVAL, "_blank", "noopener,noreferrer"));
  });

  it("a backend that cannot be read never blocks the step", async () => {
    getVendorLink.mockRejectedValue(new Error("down"));
    getVendorPublicIp.mockRejectedValue(new Error("down"));
    render(<ThisPcCallIp />);
    expect(await screen.findByText(CALL_IP_COPY.linkFailed)).toBeInTheDocument();
    expect(screen.getByText(CALL_IP_COPY.thisPcBody)).toBeInTheDocument();
  });
});
