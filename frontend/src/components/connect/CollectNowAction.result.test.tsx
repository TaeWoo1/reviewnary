// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { CollectNowAction } from "./CollectNowAction";

const collectNow = vi.fn();
vi.mock("../../lib/apiClient", () => ({
  api: {
    collectNow: (...a: unknown[]) => collectNow(...a),
    screenReadStatus: vi.fn(),
    collectNowReadiness: vi.fn(() => Promise.resolve({ path: "API", localAgent: null, lastSuccessAt: null })),
    collectNowResume: () => Promise.resolve(null),
  },
  getToken: () => null,
}));

const run = (status: string, successRows: number) => ({
  path: "API",
  dataType: "INQUIRY",
  run: { status, successRows, skippedRows: 0, failedRows: status === "FAILED" ? 1 : 0 },
  screenRead: null,
});

function mount(dataType: string, onReport = vi.fn()) {
  render(
    <MemoryRouter>
      <CollectNowAction
        accountId="acct-1"
        dataType={dataType}
        label={dataType === "INQUIRY" ? "문의" : "리뷰"}
        onReport={onReport}
        onChanged={vi.fn()}
      />
    </MemoryRouter>,
  );
  return onReport;
}

// B211 (2026-11-18): an API collection saved four NAVER inquiries; the result went only to the banner at the top
// of the page, the seller was down at the button, saw nothing change, and reported 「안 되는데?」.
describe("the collect result stays beside the button that was pressed", () => {
  afterEach(() => {
    cleanup();
    collectNow.mockReset();
  });

  it("an inquiry collection says what happened there, with the way to the inquiries", async () => {
    collectNow.mockResolvedValue(run("SUCCESS", 4));
    const onReport = mount("INQUIRY");
    await userEvent.click(screen.getByTestId("collect-now-INQUIRY"));

    const line = await screen.findByTestId("collect-result-INQUIRY");
    expect(line.textContent).toContain("저장 4");
    expect(screen.getByRole("link", { name: "문의 보러 가기" }).getAttribute("href")).toBe("/inquiries");
    expect(onReport).toHaveBeenCalledWith(expect.stringContaining("저장 4"), false); // the banner still hears it
  });

  it("a review collection points at the reviews", async () => {
    collectNow.mockResolvedValue(run("SUCCESS", 0));
    mount("REVIEW");
    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await screen.findByTestId("collect-result-REVIEW");
    expect(screen.getByRole("link", { name: "리뷰 보러 가기" }).getAttribute("href")).toBe("/reviews");
  });

  it("a failed collection says so beside the button and offers no way to results it did not bring", async () => {
    collectNow.mockResolvedValue(run("FAILED", 0));
    mount("INQUIRY");
    await userEvent.click(screen.getByTestId("collect-now-INQUIRY"));
    await screen.findByTestId("collect-result-INQUIRY");
    expect(screen.queryByRole("link", { name: "문의 보러 가기" })).toBeNull();
  });
});
