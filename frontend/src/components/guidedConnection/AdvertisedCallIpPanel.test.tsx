// @vitest-environment jsdom
//
// The 'API 호출 IP' panel must keep TWO root causes visibly distinct and never block a seller who already
// registered: an empty advertised list is OUR-side "not configured yet" (no fabricated value), a non-empty
// list is the seller's remaining action, and the acknowledgment is a local reassurance that stores no IP.
import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// The seller-PC branch asks the backend for this PC's link and address; an install with the link off answers
// with the manual guidance, which is what the local-build cases below assert. The link flow itself is
// ThisPcCallIp.test.tsx.
vi.mock("../../lib/apiClient", () => ({
  api: {
    getVendorLink: vi.fn(async () => ({ state: "OFF", offReason: "NOT_CONFIGURED", userCode: null,
      codeExpiresAt: null, pollIntervalSeconds: null, approvalUrl: null, deviceName: null, linkedAt: null })),
    getVendorPublicIp: vi.fn(async () => ({ ipv4: null, reason: "NOT_LINKED" })),
  },
}));

import { AdvertisedCallIpPanel } from "./AdvertisedCallIpPanel";
import { CALL_IP_COPY } from "../../lib/guidedConnection";

describe("AdvertisedCallIpPanel — advertised-unset vs registration-pending", () => {
  it("empty ips → our-side 'not configured yet' copy, no fabricated IP", () => {
    render(<AdvertisedCallIpPanel ips={[]} />);
    expect(screen.getByText(CALL_IP_COPY.advertisedUnsetTitle)).toBeInTheDocument();
    // Never the register-this-value phrasing when we have nothing to show.
    expect(screen.queryByText(CALL_IP_COPY.registerTitle)).toBeNull();
  });

  it("non-empty ips → register-this-value copy + each IP shown with a copy control", () => {
    render(<AdvertisedCallIpPanel ips={["203.0.113.10", "198.51.100.7"]} />);
    expect(screen.getByText(CALL_IP_COPY.registerTitle)).toBeInTheDocument();
    expect(screen.getByText("203.0.113.10")).toBeInTheDocument();
    expect(screen.getByText("198.51.100.7")).toBeInTheDocument();
  });

  it("without showRegisteredAck there is NO acknowledgment button (advisory contexts)", () => {
    render(<AdvertisedCallIpPanel ips={[]} />);
    expect(screen.queryByTestId("call-ip-already-registered")).toBeNull();
  });
});

describe("AdvertisedCallIpPanel — already-registered acknowledgment (recovery contexts)", () => {
  it("empty ips + showRegisteredAck: acknowledging replaces the nag with the local-confirmation note", () => {
    render(<AdvertisedCallIpPanel ips={[]} showRegisteredAck />);
    const ack = screen.getByTestId("call-ip-already-registered");
    expect(ack).toHaveTextContent(CALL_IP_COPY.alreadyRegisteredCta);
    fireEvent.click(ack);
    expect(screen.getByText(CALL_IP_COPY.acknowledgedNote)).toBeInTheDocument();
    // The unset nag and the ack button are both gone — a seller who registered out of band is not blocked.
    expect(screen.queryByText(CALL_IP_COPY.advertisedUnsetTitle)).toBeNull();
    expect(screen.queryByTestId("call-ip-already-registered")).toBeNull();
  });

  it("non-empty ips + showRegisteredAck also offers the acknowledgment", () => {
    render(<AdvertisedCallIpPanel ips={["203.0.113.10"]} showRegisteredAck />);
    expect(screen.getByTestId("call-ip-already-registered")).toBeInTheDocument();
  });
});

describe("AdvertisedCallIpPanel — seller-PC install (VITE_LOCAL_SELLER=true)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("asks for THIS PC's public IP and never shows a reviewnary address, even one that is configured", () => {
    vi.stubEnv("VITE_LOCAL_SELLER", "true");
    render(<AdvertisedCallIpPanel ips={["203.0.113.10"]} />);
    expect(screen.getByText(CALL_IP_COPY.thisPcTitle)).toBeInTheDocument();
    expect(screen.getByText(CALL_IP_COPY.thisPcBody)).toBeInTheDocument();
    expect(screen.queryByText("203.0.113.10")).toBeNull();
    expect(screen.queryByText(CALL_IP_COPY.registerTitle)).toBeNull();
    expect(screen.queryByText(CALL_IP_COPY.advertisedUnsetTitle)).toBeNull();
  });

  it("keeps the already-registered acknowledgment in recovery contexts", () => {
    vi.stubEnv("VITE_LOCAL_SELLER", "true");
    render(<AdvertisedCallIpPanel ips={[]} showRegisteredAck />);
    fireEvent.click(screen.getByTestId("call-ip-already-registered"));
    expect(screen.getByText(CALL_IP_COPY.acknowledgedNote)).toBeInTheDocument();
  });

  it("an ordinary build is unchanged: the advertised address is still shown", () => {
    vi.stubEnv("VITE_LOCAL_SELLER", "");
    render(<AdvertisedCallIpPanel ips={["203.0.113.10"]} />);
    expect(screen.getByText("203.0.113.10")).toBeInTheDocument();
    expect(screen.queryByText(CALL_IP_COPY.thisPcTitle)).toBeNull();
  });
});
