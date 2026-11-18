import { describe, expect, it } from "vitest";
import { nextActionFor, staleConnectedDays } from "./channelShared";
import type { ConnectionStatusView } from "../../lib/types";

const NOW = Date.parse("2026-11-18T03:00:00Z");
const status = (state: string, lastSuccessAt: string | null) =>
  ({ sellerAccountId: "a", state, lastSuccessAt, consecutiveFailures: 0, lastError: null,
     lastSyncedAt: lastSuccessAt, nextScheduledAt: null }) as unknown as ConnectionStatusView;

// B212 (2026-11-18): a Cafe24 card read 「정상 수집 중이에요」 twenty days after its last read, with the consent
// already lapsed. The seller had nothing on screen telling them to reconnect.
describe("a connected channel nobody has collected from", () => {
  it("twenty days idle is not called normal, and the next step is a collection", () => {
    const action = nextActionFor(status("CONNECTED", "2026-10-28T17:13:34Z"), NOW);
    expect(action.title).toBe("한동안 수집하지 않았어요");
    expect(action.title).not.toContain("정상");
    expect(action.guidance).toContain("20일 전");
    expect(action.cta?.target).toBe("collect");
  });

  it("a recent collection still reads as normal", () => {
    expect(nextActionFor(status("CONNECTED", "2026-11-17T16:15:00Z"), NOW).title).toBe("정상 수집 중이에요");
    expect(staleConnectedDays(status("CONNECTED", "2026-11-15T03:00:01Z"), NOW)).toBeNull();
    expect(staleConnectedDays(status("CONNECTED", "2026-11-15T03:00:00Z"), NOW)).toBe(3);
  });

  it("only CONNECTED with a known time can be stale — other states keep their own words", () => {
    expect(staleConnectedDays(status("CONNECTED", null), NOW)).toBeNull();
    expect(staleConnectedDays(status("NEEDS_REAUTH", "2026-10-28T17:13:34Z"), NOW)).toBeNull();
    expect(nextActionFor(status("NEEDS_REAUTH", "2026-10-28T17:13:34Z"), NOW).title).toBe("재연결이 필요해요");
  });
});
