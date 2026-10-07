import { describe, expect, it } from "vitest";
import { sourceSummaryHeadline, sourceSummaryLines, sourceSummarySubtitle } from "./firstSourceSummary";
import type { ChannelCoverageRowView, SyncRunView } from "./types";

function coverage(
  dataType: string,
  state: ChannelCoverageRowView["state"],
  rows = 0,
  channelCode = "CAFE24",
): ChannelCoverageRowView {
  return {
    channelCode,
    channelNameKo: "카페24",
    dataType,
    state,
    supported: state !== "NOT_SUPPORTED",
    verificationStatus: null,
    connected: true,
    connectionStatus: "CONNECTED",
    routineEnabled: true,
    routinePausedBy: null,
    lastSuccessfulSyncAt: null,
    latestAttemptAt: null,
    latestAttemptOutcome: null,
    rows,
    openRows: null,
    newestObservedAt: null,
  };
}

function run(dataType: string, successRows: number, status = "SUCCESS", finishedAt = "2026-08-27T01:00:00Z"): SyncRunView {
  return {
    id: `run-${dataType}-${finishedAt}`,
    sellerAccountId: "acct-1",
    channelId: "ch-1",
    dataType,
    trigger: "MANUAL",
    attempt: 1,
    rateLimited: false,
    nextRetryAt: null,
    jobType: "SYNC",
    uploadType: null,
    status,
    totalRows: successRows,
    successRows,
    skippedRows: 0,
    failedRows: 0,
    errorMessage: null,
    startedAt: "2026-08-27T00:59:00Z",
    finishedAt,
    method: "API",
    coverage: null,
  };
}

describe("sourceSummaryLines", () => {
  it("prints a number only from a finished run, never from the stored-row count", () => {
    // 4,000 stored rows and no run: the connection cannot claim it fetched them.
    const [line] = sourceSummaryLines([coverage("INQUIRY", "OBSERVED_FRESHNESS_UNPROVEN", 4000)], [], "CAFE24");
    expect(line.tone).toBe("pending");
    expect(line.count).toBeNull();
    expect(line.sentence).toBe("문의는 아직 확인하지 못했습니다.");
  });

  it("counts what the run handed over", () => {
    const [line] = sourceSummaryLines(
      [coverage("INQUIRY", "OBSERVED_FRESH", 9)],
      [run("INQUIRY", 22)],
      "CAFE24",
    );
    expect(line.tone).toBe("collected");
    expect(line.sentence).toBe("문의 22건을 가져왔습니다.");
  });

  it("says 없습니다 only for a measured ZERO", () => {
    const zero = sourceSummaryLines([coverage("REVIEW", "ZERO")], [], "CAFE24");
    expect(zero[0].sentence).toBe("확인된 리뷰가 없습니다.");
    expect(zero[0].tone).toBe("empty");

    const unproven = sourceSummaryLines([coverage("REVIEW", "OBSERVED_FRESHNESS_UNPROVEN")], [], "CAFE24");
    expect(unproven[0].sentence).not.toContain("없습니다");
  });

  it("separates a channel that does not offer the type from one that is blocked", () => {
    const unsupported = sourceSummaryLines([coverage("REVIEW", "NOT_SUPPORTED")], [], "CAFE24");
    expect(unsupported[0].tone).toBe("unsupported");
    // 「자동 수집 대상이 아니다」는 커넥터에 대한 사실이고, 「이 채널에서 제공하지 않는다」는 판매자의
    // 가게에 대한 주장이다. 이 줄이 하던 말은 뒤쪽이었고, 그래서 NAVER 리뷰 3,858건이 이미 들어와 있는
    // 채널의 연결 완료 화면이 「리뷰는 이 채널에서 제공하지 않습니다」라고 말했다.
    expect(unsupported[0].sentence).toContain("자동 수집 대상이 아닙니다");
    expect(unsupported[0].sentence).not.toContain("제공하지 않습니다");
    expect(unsupported[0].sentence).not.toContain("없습니다");

    const blocked = sourceSummaryLines([coverage("INQUIRY", "BLOCKED")], [], "CAFE24");
    expect(blocked[0].tone).toBe("blocked");
    expect(blocked[0].sentence).toContain("다시 확인");
  });

  it("names the route a seller-run type actually arrives by, and says the least when it cannot", () => {
    const row = coverage("REVIEW", "NOT_SUPPORTED");
    const withExport = sourceSummaryLines([row], [], "CAFE24",
      new Map([["REVIEW", [{ method: "EXPORT", verificationStatus: "LIVE_PROVEN" }]]]));
    expect(withExport[0].sentence).toContain("내려받은 파일");

    const withWindow = sourceSummaryLines([row], [], "CAFE24",
      new Map([["REVIEW", [{ method: "ACTION_WINDOW", verificationStatus: "LIVE_PROVEN" }]]]));
    expect(withWindow[0].sentence).toContain("직접 실행");

    // 경로를 읽지 못한 경우. 「업로드로 채울 수 있다」는 이 제품이 어느 채널에서든 지킬 수 있는 문장이고,
    // 모르는 경로를 지어내는 것보다 적게 말하는 편이 옳다.
    const unknown = sourceSummaryLines([row], [], "CAFE24");
    expect(unknown[0].sentence).toContain("자료 업로드");
  });

  it("promises automatic collection only for the types that actually arrive that way", () => {
    // 고정 문장이었을 때 이 자리는 주문·문의·리뷰 셋을 이름으로 부르며 다 알아서 해준다고 말했고,
    // 리뷰가 자동 수집 대상이 아닌 채널에서도 똑같이 말했다.
    const lines = sourceSummaryLines(
      [coverage("ORDER_SUMMARY", "OBSERVED_FRESH"), coverage("REVIEW", "NOT_SUPPORTED")],
      [],
      "CAFE24",
      new Map([["REVIEW", [{ method: "EXPORT", verificationStatus: "LIVE_PROVEN" }]]]),
    );
    const subtitle = sourceSummarySubtitle(lines);
    expect(subtitle).toContain("새 주문");
    expect(subtitle).not.toContain("새 주문·리뷰");
    expect(subtitle).toContain("리뷰는 판매자센터에서");

    // 자동으로 들어오는 것이 하나도 없으면 「알아서」라는 말을 쓰지 않는다.
    const noneAutomatic = sourceSummaryLines([coverage("REVIEW", "NOT_SUPPORTED")], [], "CAFE24");
    expect(sourceSummarySubtitle(noneAutomatic)).not.toContain("알아서");
  });

  it("reads in 주문 → 문의 → 리뷰 order and ignores other channels", () => {
    const lines = sourceSummaryLines(
      [
        coverage("REVIEW", "ZERO"),
        coverage("INQUIRY", "ZERO"),
        coverage("ORDER_SUMMARY", "ZERO"),
        coverage("INQUIRY", "OBSERVED_FRESH", 5, "NAVER"),
      ],
      [],
      "CAFE24",
    );
    expect(lines.map((l) => l.dataType)).toEqual(["ORDER_SUMMARY", "INQUIRY", "REVIEW"]);
  });

  it("uses the newest terminal run when a data type has several", () => {
    const [line] = sourceSummaryLines(
      [coverage("ORDER_SUMMARY", "OBSERVED_FRESH")],
      [run("ORDER_SUMMARY", 3, "SUCCESS", "2026-08-26T01:00:00Z"), run("ORDER_SUMMARY", 14, "PARTIAL", "2026-08-27T01:00:00Z")],
      "CAFE24",
    );
    expect(line.count).toBe(14);
  });

  it("does not describe a data type the backend did not return", () => {
    expect(sourceSummaryLines([], [], "CAFE24")).toEqual([]);
  });
});

describe("sourceSummaryHeadline", () => {
  it("announces collection when something was collected", () => {
    const lines = sourceSummaryLines([coverage("ORDER_SUMMARY", "OBSERVED_FRESH")], [run("ORDER_SUMMARY", 14)], "CAFE24");
    expect(sourceSummaryHeadline("카페24", lines)).toBe("카페24에서 다음 정보를 가져왔습니다.");
  });

  it("never adds two counts together", () => {
    const lines = sourceSummaryLines(
      [coverage("ORDER_SUMMARY", "OBSERVED_FRESH"), coverage("INQUIRY", "OBSERVED_FRESH")],
      [run("ORDER_SUMMARY", 14), run("INQUIRY", 22)],
      "CAFE24",
    );
    const headline = sourceSummaryHeadline("카페24", lines);
    expect(headline).not.toContain("36");
    expect(lines.map((l) => l.count)).toEqual([14, 22]);
  });

  it("does not call an empty first collection a failure", () => {
    const lines = sourceSummaryLines([coverage("ORDER_SUMMARY", "ZERO")], [], "CAFE24");
    expect(sourceSummaryHeadline("카페24", lines)).toContain("연결이 완료되었습니다");
  });
});

describe("a run that brought nothing back", () => {
  it("is a fact about the collection, not about the shop", () => {
    // Observed on the Demo Org, 2026-08-27: this shape printed 「문의 0건을 확인했습니다」 on a
    // completion screen for an org that holds two Coupang inquiries.
    const [line] = sourceSummaryLines(
      [coverage("INQUIRY", "OBSERVED_FRESHNESS_UNPROVEN", 2)],
      [run("INQUIRY", 0)],
      "CAFE24",
    );
    expect(line.sentence).toBe("새로 가져온 문의는 없습니다.");
    expect(line.sentence).not.toContain("문의가 없습니다");
    expect(line.tone).toBe("empty");
  });
});
