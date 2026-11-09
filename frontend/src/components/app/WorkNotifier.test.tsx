// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { WorkNotifier } from "./WorkNotifier";
import { readNoticeState, writeNoticeSettings } from "../../lib/workNotifications";

const api = vi.hoisted(() => ({
  getReplyHandoffsStrict: vi.fn(),
  getReviewWorkStrict: vi.fn(),
  getInquiryQueueStrict: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api }));

describe("PC 알림 — the notifier", () => {
  beforeEach(() => {
    localStorage.clear();
    writeNoticeSettings({ enabled: true, hidePreview: false });
    api.getReplyHandoffsStrict.mockResolvedValue([
      { reviewId: "r1", category: "S", reason: "CATEGORY_S", at: null, rating: 1, preview: null, points: [] },
    ]);
    api.getReviewWorkStrict.mockResolvedValue({ attentionTotal: 0, attention: [], committed: [] });
    api.getInquiryQueueStrict.mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
  });

  it("a notification the browser refused to show is not recorded as told", async () => {
    const shown = vi.fn(() => {
      throw new Error("blocked");
    });
    vi.stubGlobal("Notification", Object.assign(shown, { permission: "granted" }));
    render(<MemoryRouter><WorkNotifier /></MemoryRouter>);
    await waitFor(() => expect(shown).toHaveBeenCalled());
    await waitFor(() => expect(readNoticeState().told).toEqual([]));
    vi.unstubAllGlobals();
  });

  it("a notification that was shown is recorded, once", async () => {
    const shown = vi.fn(function (this: { onclick: unknown; close: () => void }) {
      this.close = () => undefined;
    });
    vi.stubGlobal("Notification", Object.assign(shown, { permission: "granted" }));
    render(<MemoryRouter><WorkNotifier /></MemoryRouter>);
    await waitFor(() => expect(readNoticeState().told).toContain("review:r1:U"));
    expect(shown).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });
});
