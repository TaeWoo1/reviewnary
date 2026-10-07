import { describe, expect, it } from "vitest";
import { apiCardOf, reviewCardOf, reviewCollectionPath, reviewRecoveryLabel } from "./coupangCapabilities";
import type { ChannelCapabilityOverview, ConnectionInfoView, ConnectionStatusView } from "../types";

const overview = (autoCollectSupported: boolean): ChannelCapabilityOverview => ({
  channelCode: "COUPANG",
  channelNameKo: "쿠팡",
  connectorClass: "API",
  autoCollectSupported,
  dataTypes: [],
  unsupportedScopes: [],
});

const info = { authType: "API_KEY" } as unknown as ConnectionInfoView;
const status = (state: string) => ({ state, consecutiveFailures: 0 }) as unknown as ConnectionStatusView;

describe("리뷰 수집 카드", () => {
  it("읽기 전에는 상태를 주장하지 않는다", () => {
    const card = reviewCardOf(null);
    expect(card?.status.label).toBe("확인 중");
    expect(card?.kind).toBe("CHECKING");
  });

  it("이 방식이 없는 계정에는 카드가 없다", () => {
    expect(reviewCardOf({ state: "CHANNEL_NOT_SUPPORTED", channelCode: "NAVER" })).toBeNull();
    expect(reviewCardOf({ state: "FILE_UPLOAD_ACCOUNT", channelCode: "COUPANG" })).toBeNull();
  });

  it("카드는 상태만 말한다 — 수집 동작은 더 이상 여기서 나오지 않는다", () => {
    // 2026-10-07 라이브: 이 카드가 「리뷰 수집 연결하기」를 가장 강한 컨트롤로 들고 있었고, 리뷰를
    // 가져오려던 판매자가 그것을 눌러 canonical 경로가 아닌 안내 carrier가 돌았다. 같은 자료에 수집처럼
    // 보이는 입구가 둘 있으면 안 된다.
    for (const state of ["READY", "HELPER_NOT_LINKED", "STORE_IDENTITY_UNKNOWN"] as const) {
      const card = reviewCardOf({ state, channelCode: "COUPANG" });
      expect(card).not.toBeNull();
      expect(Object.keys(card!).sort()).toEqual(["kind", "status"]);
    }
    expect(reviewCardOf({ state: "READY", channelCode: "COUPANG" }))
      .toEqual({ status: { tone: "good", label: "연결됨" }, kind: "READY" });
    expect(reviewCardOf({ state: "HELPER_NOT_LINKED", channelCode: "COUPANG" }))
      .toEqual({ status: { tone: "warn", label: "연결 필요" }, kind: "SETUP" });
  });
});

describe("복구 동작의 이름 — 책상 상태가 정한다", () => {
  it("막혀 있을 때만 이름이 있고, 그 이름에 「수집」이 들어가지 않는다", () => {
    expect(reviewRecoveryLabel("UNPAIRED")).toBe("이 Mac 연결하기");
    expect(reviewRecoveryLabel("AUTH_REQUIRED")).toBe("판매자 센터 로그인");
    for (const label of [reviewRecoveryLabel("UNPAIRED"), reviewRecoveryLabel("AUTH_REQUIRED")]) {
      expect(label).not.toContain("수집");
    }
  });

  it("누를 수 있는 상태에서는 다른 입구가 없다", () => {
    expect(reviewRecoveryLabel("READY")).toBeNull();
    expect(reviewRecoveryLabel("RUNNING")).toBeNull();
    expect(reviewRecoveryLabel("BUSY")).toBeNull();
    expect(reviewRecoveryLabel(null)).toBeNull();
  });
});

describe("문의·주문 자동 수집 카드 — 돌지 않는 것은 설정하게 하지 않는다", () => {
  it("커넥터가 해석되지 않는 배포에는 카드도 자격 폼도 없다", () => {
    expect(apiCardOf(overview(false), null, null)).toBeNull();
  });

  it("확인하지 못했으면 권하지 않는다", () => {
    expect(apiCardOf(null, info, status("CONNECTED"))).toBeNull();
  });

  it("자격이 없으면 연결이 다음 걸음이다", () => {
    expect(apiCardOf(overview(true), null, null)).toMatchObject({ kind: "SETUP", primaryLabel: "API 연결하기" });
  });

  it("만료·끊김은 다시 연결이고, 그것은 처음 연결과 다른 문장이다", () => {
    for (const state of ["EXPIRED", "NEEDS_REAUTH", "DISCONNECTED"]) {
      expect(apiCardOf(overview(true), info, status(state))).toMatchObject({
        kind: "REAUTH",
        primaryLabel: "연결 정보 갱신",
      });
    }
  });

  it("살아 있는 연결의 다음 걸음은 수집이다", () => {
    expect(apiCardOf(overview(true), info, status("CONNECTED"))).toMatchObject({
      kind: "READY",
      primaryLabel: "지금 가져오기",
    });
  });
});

describe("경로", () => {
  it("카드와 라우트가 같은 한 정의를 쓴다", () => {
    expect(reviewCollectionPath("acc-1")).toBe("/connect/channels/acc-1/review-collection");
  });
});
