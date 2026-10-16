import { describe, expect, it } from "vitest";
import { localSellerBuildProblems } from "./localSellerBuild";

const OFF_PC = {
  VITE_SENTRY_DSN: "https://key@sentry.example.com/1",
  VITE_GTM_ID: "GTM-XXXX",
  VITE_POSTHOG_KEY: "phc_x",
  VITE_API_BASE_URL: "https://api.example.com",
  VITE_AGENT_RUNTIME_URL: "https://agent.example.com",
  VITE_BRIDGE_URL: "http://192.168.0.10:47615",
};

describe("localSellerBuildProblems", () => {
  it("checks nothing for an ordinary build, even with every off-PC destination set", () => {
    expect(localSellerBuildProblems(OFF_PC)).toEqual([]);
    expect(localSellerBuildProblems({ ...OFF_PC, VITE_LOCAL_SELLER: "false" })).toEqual([]);
  });

  it("passes a clean seller-PC build: no telemetry, addresses empty or loopback", () => {
    expect(localSellerBuildProblems({ VITE_LOCAL_SELLER: "true" })).toEqual([]);
    expect(
      localSellerBuildProblems({
        VITE_LOCAL_SELLER: "true",
        VITE_API_BASE_URL: "http://127.0.0.1:8080",
        VITE_AGENT_RUNTIME_URL: "http://localhost:8787",
        VITE_BRIDGE_URL: "http://[::1]:47615",
        VITE_SENTRY_DSN: "  ",
      }),
    ).toEqual([]);
  });

  it("names every off-PC destination in a seller-PC build", () => {
    expect(localSellerBuildProblems({ ...OFF_PC, VITE_LOCAL_SELLER: "true" })).toEqual([
      "VITE_SENTRY_DSN",
      "VITE_GTM_ID",
      "VITE_POSTHOG_KEY",
      "VITE_API_BASE_URL",
      "VITE_AGENT_RUNTIME_URL",
      "VITE_BRIDGE_URL",
    ]);
  });

  it("refuses a look-alike or unparseable address", () => {
    for (const bad of ["http://localhost.example.com", "http://127.0.0.1.example.com", "not a url", "file:///x"]) {
      expect(localSellerBuildProblems({ VITE_LOCAL_SELLER: "true", VITE_API_BASE_URL: bad })).toEqual([
        "VITE_API_BASE_URL",
      ]);
    }
  });
});
