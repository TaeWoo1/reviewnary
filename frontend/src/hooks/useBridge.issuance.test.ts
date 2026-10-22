// The seller-PC install keeps the one helper use it ships: the API-issuance guidance. Each guided walkthrough
// asks for the bridge with `purpose: "issuance"`; a call without it would leave that guidance inert on the
// install (useBridge opens nothing else there). Read from the sources: every useBridge call in these files.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const WALKTHROUGHS = [
  "src/components/guidedConnection/NaverIssuanceGuidedWalkthrough.tsx",
  "src/components/coupang/CoupangIssuanceGuidedWalkthrough.tsx",
  "src/components/coupang/CoupangRenewalGuidedWalkthrough.tsx",
];

describe("issuance guidance on the seller-PC install", () => {
  it.each(WALKTHROUGHS)("%s opens the bridge for issuance", (file) => {
    const source = readFileSync(resolve(__dirname, "../..", file), "utf8");
    const calls = source.match(/useBridge\([^)]*\)/g) ?? [];
    expect(calls.length).toBeGreaterThan(0);
    for (const call of calls) expect(call).toContain('purpose: "issuance"');
  });
});
