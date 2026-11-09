// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { RecentArrivals, mergeArrivals } from "./RecentArrivals";
import type { InquiryRowItem, ReviewRecordRow } from "../../lib/types";

const api = vi.hoisted(() => ({
  getReviewRecordStrict: vi.fn(),
  getInquiryRowsStrict: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api }));

const NOW = new Date("2026-10-28T12:00:00+09:00");

function review(id: string, writtenOn: string, state: "ANSWERED" | "PENDING" | null = null): ReviewRecordRow {
  return {
    channelCode: "NAVER",
    channelNameKo: "네이버 스마트스토어",
    review: { id, writtenOn, productName: "전선몰딩", preview: "잘 붙어요", channelReplyState: state } as never,
  };
}

function inquiry(id: string, receivedAt: string, status = "ANSWERED"): InquiryRowItem {
  return {
    inquiryId: id, workItemId: null, sellerAccountId: null, channelId: null, channelCode: "NAVER",
    channelNameKo: "네이버 스마트스토어", productId: null, productName: "실크벽지", phase: null, status,
    title: "배송 언제 되나요", snippet: null, receivedAt, answeredAt: null, sourceSubtype: null, executableIdentity: null,
  };
}

describe("mergeArrivals — newest first, across both reads", () => {
  it("interleaves reviews and inquiries by when they came in and keeps five", () => {
    const rows = mergeArrivals(
      [review("r-1", "2026-10-20", "ANSWERED"), review("r-2", "2026-10-13")],
      [inquiry("i-1", "2026-10-23T01:00:00Z"), inquiry("i-2", "2026-10-13T01:00:00Z", "UNANSWERED"),
        inquiry("i-3", "2026-10-01T01:00:00Z"), inquiry("i-4", "2026-09-01T01:00:00Z")],
    );
    expect(rows.map((r) => r.key)).toEqual(["inquiry:i-1", "review:r-1", "review:r-2", "inquiry:i-2", "inquiry:i-3"]);
    expect(rows[0]).toMatchObject({ kind: "문의", state: "답변함", to: "/inquiries/i-1", day: "2026-10-23" });
    expect(rows[1]).toMatchObject({ kind: "리뷰", state: "답글 있음", to: "/reviews/reply/r-1" });
    expect(rows[2]!.state).toBeNull();
    expect(rows[3]!.state).toBe("답변 필요");
  });
});

describe("RecentArrivals — what came in, said as what it is", () => {
  beforeEach(() => vi.resetAllMocks());

  it("lists the newest arrivals under a heading that says it is not work", async () => {
    api.getReviewRecordStrict.mockResolvedValue({ items: [review("r-1", "2026-10-20", "ANSWERED")] });
    api.getInquiryRowsStrict.mockResolvedValue({ items: [inquiry("i-1", "2026-10-23T01:00:00Z")] });
    render(<MemoryRouter><RecentArrivals now={NOW} /></MemoryRouter>);

    const section = await screen.findByRole("region", { name: "최근 들어온 리뷰·문의" });
    expect(within(section).getByText("할 일은 아니에요. 무엇이 들어왔는지만 보여 드려요.")).toBeInTheDocument();
    const links = within(section).getAllByRole("link").filter((a) => a.getAttribute("href") !== "/reviews");
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/inquiries/i-1", "/reviews/reply/r-1"]);
    expect(api.getReviewRecordStrict).toHaveBeenCalledWith({ sort: "newest", size: 5 });
    expect(api.getInquiryRowsStrict).toHaveBeenCalledWith({ order: "NEWEST", limit: 5 });
  });

  it("one read failing leaves the other's rows; both failing draws nothing", async () => {
    api.getReviewRecordStrict.mockRejectedValue(new Error("down"));
    api.getInquiryRowsStrict.mockResolvedValue({ items: [inquiry("i-1", "2026-10-23T01:00:00Z")] });
    const { unmount } = render(<MemoryRouter><RecentArrivals now={NOW} /></MemoryRouter>);
    expect(await screen.findByRole("region", { name: "최근 들어온 리뷰·문의" })).toBeInTheDocument();
    unmount();

    api.getInquiryRowsStrict.mockRejectedValue(new Error("down"));
    render(<MemoryRouter><RecentArrivals now={NOW} /></MemoryRouter>);
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole("region", { name: "최근 들어온 리뷰·문의" })).toBeNull();
  });
});
