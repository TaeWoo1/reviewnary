import { describe, it, expect } from "vitest";
import { lastRead, sharedAcrossLines, wasRead } from "./orderRecords";
import type { OrderLine, OrderReadState } from "./types";

const read = (over: Partial<OrderReadState>): OrderReadState => ({
  channelCode: "NAVER",
  accountId: "a",
  state: "OBSERVED_FRESH",
  orderLineCount: 1,
  lastSeenAt: "2026-09-05T11:30:00Z",
  ...over,
});

const line = (over: Partial<OrderLine>): OrderLine => ({
  externalOrderId: "L-1",
  paymentAmount: 1000,
  rawStatusCode: "PAYED",
  confirmedStatusLabelKo: "결제 완료",
  paidAt: "2026-09-03T07:24:00Z",
  ...over,
});

describe("읽은 것과 읽지 못한 것", () => {
  it("읽지 못한 상태의 시각은 「마지막으로 읽은 때」가 아니다", () => {
    const at = lastRead(
      [
        read({ state: "NOT_CONNECTED", lastSeenAt: "2026-10-05T00:00:00Z" }),
        read({ accountId: "b", state: "OBSERVED_FRESHNESS_UNPROVEN", lastSeenAt: "2026-09-05T11:30:00Z" }),
      ],
      new Date("2026-10-06T03:00:00Z"),
    );
    expect(at?.at).toBe("2026-09-05T11:30:00Z");
    expect(at?.daysAgo).toBe(30);
  });

  it("읽은 연결이 하나도 없으면 말할 시각이 없다", () => {
    expect(lastRead([read({ state: "NOT_CONNECTED", lastSeenAt: null })])).toBeNull();
  });

  it("ZERO는 읽은 것이고, 나머지 셋은 읽지 못한 것이다", () => {
    expect(wasRead("ZERO")).toBe(true);
    expect(wasRead("OBSERVED_FRESH")).toBe(true);
    expect(["NOT_CONNECTED", "BLOCKED", "NOT_SUPPORTED"].some(wasRead)).toBe(false);
  });
});

describe("줄마다 같은 값은 줄의 속성이 아니다", () => {
  it("모두 같으면 머리로 올라간다", () => {
    const shared = sharedAcrossLines([line({}), line({ externalOrderId: "L-2", paymentAmount: 2000 })]);
    expect(shared.rawStatusCode).toBe("PAYED");
    expect(shared.confirmedStatusLabelKo).toBe("결제 완료");
    expect(shared.paidAt).toBe("2026-09-03T07:24:00Z");
  });

  it("하나라도 다르면 올라가지 않는다 — 한 줄의 값이 전체의 값이 되지 않도록", () => {
    const shared = sharedAcrossLines([
      line({}),
      line({ externalOrderId: "L-2", rawStatusCode: "CANCELED", confirmedStatusLabelKo: null }),
    ]);
    expect(shared.rawStatusCode).toBeNull();
    expect(shared.confirmedStatusLabelKo).toBeNull();
    expect(shared.paidAt).toBe("2026-09-03T07:24:00Z");
  });
});
