// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { IssueOutcomes } from "./IssueOutcomes";
import type { OpportunityOutcomeView } from "../../../lib/types";

const getOpportunityOutcomes = vi.fn();

vi.mock("../../../lib/apiClient", () => ({
  api: { getOpportunityOutcomes: (id: string) => getOpportunityOutcomes(id) },
  getToken: () => "token",
}));

function outcome(over: Partial<OpportunityOutcomeView> = {}): OpportunityOutcomeView {
  return {
    kind: "OPERATING_POLICY_SUPPLEMENT", kindLabelKo: "운영 기준 보완", scope: "ORG",
    appliedOn: "2026-08-10", observedThrough: "2026-09-06",
    evidenceBefore: 11, reviewsBefore: 90, evidenceAfter: 0, reviewsAfter: 0,
    verdict: "INCONCLUSIVE", verdictLabelKo: "판단 보류",
    reasonLabelKo: "그 기간에 들어온 리뷰가 없어 비교할 수 없습니다", settled: true,
    ...over,
  };
}

afterEach(() => vi.clearAllMocks());

describe("한 일과 그 결과 — read by the problem, not by the suggestion", () => {
  it("a withheld verdict carries both counts AND why it was withheld", async () => {
    getOpportunityOutcomes.mockResolvedValue([outcome()]);
    render(<IssueOutcomes issueId="issue-1" />);

    expect(await screen.findByText("판단 보류")).toBeTruthy();
    // The refusal is the valuable half: 판단 보류 beside nothing reads as a lost measurement.
    expect(screen.getByText(/그 기간에 들어온 리뷰가 없어 비교할 수 없습니다/)).toBeTruthy();
    expect(screen.getByText(/적용 전 4주 11건 → 뒤 4주 0건/)).toBeTruthy();
    expect(screen.getByText("운영 기준 보완")).toBeTruthy();
    expect(screen.getByText(/2026-08-10 적용/)).toBeTruthy();
  });

  it("a problem with nothing applied draws no block at all — a named empty block would be a claim", async () => {
    getOpportunityOutcomes.mockResolvedValue([]);
    const { container } = render(<IssueOutcomes issueId="issue-1" />);
    await waitFor(() => expect(getOpportunityOutcomes).toHaveBeenCalledWith("issue-1"));
    expect(container.textContent).toBe("");
  });

  it("a read that fails says so, and the rest of the page is untouched", async () => {
    getOpportunityOutcomes.mockRejectedValue(new Error("boom"));
    render(<IssueOutcomes issueId="issue-1" />);
    expect(await screen.findByText(/적용 기록을 불러오지 못했습니다/)).toBeTruthy();
  });

  it("every verdict is about the evidence — no label claims the act worked", async () => {
    getOpportunityOutcomes.mockResolvedValue([
      outcome({ verdict: "IMPROVED", verdictLabelKo: "근거 줄었습니다", evidenceAfter: 2, reviewsAfter: 80,
        reasonLabelKo: "같은 문제가 적게 들어왔습니다" }),
      outcome({ kind: "FAQ_SUPPLEMENT", kindLabelKo: "FAQ 보완", appliedOn: "2026-07-01",
        verdict: "WORSENED", verdictLabelKo: "근거 늘었습니다", evidenceAfter: 30, reviewsAfter: 85,
        reasonLabelKo: "같은 문제가 더 들어왔습니다" }),
    ]);
    render(<IssueOutcomes issueId="issue-1" />);
    expect(await screen.findByText("근거 줄었습니다")).toBeTruthy();
    expect(screen.getByText("근거 늘었습니다")).toBeTruthy();
    expect(screen.queryByText(/해결|효과|덕분/)).toBeNull();
  });
});
