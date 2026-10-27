import { describe, expect, it } from "vitest";
import { emptyCause } from "./emptyCause";
import { lastCollectedByChannel } from "./channelFreshness";
import type { ChannelCoverageRowView } from "./types";

const row = (over: Partial<ChannelCoverageRowView>): ChannelCoverageRowView =>
  ({ channelCode: "NAVER", channelNameKo: "네이버", dataType: "INQUIRY", connected: true, lastSuccessfulSyncAt: null, rows: 0, ...over }) as ChannelCoverageRowView;

describe("an empty list says its cause (2026-10-28)", () => {
  it("tells no channel, nothing read yet, and read-and-empty apart", () => {
    expect(emptyCause([row({ connected: false })], "INQUIRY")).toBe("NOT_CONNECTED");
    expect(emptyCause([row({ dataType: "REVIEW", lastSuccessfulSyncAt: "2026-10-25T00:00:00Z" })], "INQUIRY")).toBe("NOT_CONNECTED");
    expect(emptyCause([row({})], "INQUIRY")).toBe("NOT_FETCHED");
    expect(emptyCause([row({ lastSuccessfulSyncAt: "2026-10-25T00:00:00Z" })], "INQUIRY")).toBe("NONE");
    expect(emptyCause(null, "INQUIRY"), "an unread coverage is not a cause").toBeNull();
  });
});

describe("마지막으로 가져온 때 — one answer per channel", () => {
  it("is the newest collection that read data, and null for a channel that never did", () => {
    const at = lastCollectedByChannel([
      row({ dataType: "INQUIRY", lastSuccessfulSyncAt: "2026-10-20T00:00:00Z" }),
      row({ dataType: "REVIEW", lastSuccessfulSyncAt: "2026-10-25T00:00:00Z" }),
      row({ channelCode: "COUPANG" }),
    ]);
    expect(at.get("NAVER")).toBe(Date.parse("2026-10-25T00:00:00Z"));
    expect(at.has("COUPANG")).toBe(true);
    expect(at.get("COUPANG")).toBeNull();
  });
});
