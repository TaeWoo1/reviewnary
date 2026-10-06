import { describe, expect, it } from "vitest";
import { gapSentence, usableTopic, TOPICLESS_GAP_SENTENCE } from "./caseTopic";

/**
 * <b>기준의 이름으로 내보낼 수 있는 말인가</b> (UI audit, 2026-10-06).
 *
 * <p>관측에서 나왔다: 데모 org의 한 건이 제목 「문의 드립니다」에서 {@code missingSubject: "드립니다"}를
 * 받아, 화면이 「「드립니다」에 대해 고객에게 안내할 기준이 없습니다」와 「드립니다 기준 없음」을 그렸다.
 * 거르는 기준은 <b>주제가 아니라 문장인 것</b>이고, 공백이 들어간 진짜 주제는 통과해야 한다.
 */
describe("usableTopic — 회사가 기준을 적을 수 있는 주제인가", () => {
  it("서술어로 끝나는 말은 주제가 아니다", () => {
    for (const fragment of ["드립니다", "문의드립니다", "가능한가요", "알려주세요", "궁금해요", "맞나요"]) {
      expect(usableTopic(fragment)).toBeNull();
    }
  });

  it("문장부호로 끝나는 말도 주제가 아니다", () => {
    expect(usableTopic("교환이 되나요?")).toBeNull();
    expect(usableTopic("배송 문의.")).toBeNull();
  });

  it("공백이 있어도 명사구는 주제다 — 회사는 그 단위로 기준을 적는다", () => {
    expect(usableTopic("엘보 구간에 쓸 사이즈")).toBe("엘보 구간에 쓸 사이즈");
    expect(usableTopic("교환 가능 기간")).toBe("교환 가능 기간");
    expect(usableTopic(" 방수 ")).toBe("방수");
  });

  it("빈 값은 주제가 없는 것이다", () => {
    expect(usableTopic(null)).toBeNull();
    expect(usableTopic(undefined)).toBeNull();
    expect(usableTopic("   ")).toBeNull();
  });
});

describe("gapSentence — 못 믿을 이름은 인용하지 않는다", () => {
  it("주제를 믿을 수 있으면 서버가 쓴 문장 그대로", () => {
    const sentence = "「교환 가능 기간」에 대해 고객에게 안내할 기준이 없습니다.";
    expect(gapSentence({ missingSubject: "교환 가능 기간", sentence })).toBe(sentence);
  });

  it("주제가 문장 조각이면 주제 없는 같은 사실로", () => {
    expect(gapSentence({ missingSubject: "드립니다", sentence: "「드립니다」에 대해 고객에게 안내할 기준이 없습니다." })).toBe(
      TOPICLESS_GAP_SENTENCE,
    );
    // 사실은 지워지지 않는다 — 주제만 빠진다.
    expect(TOPICLESS_GAP_SENTENCE).toContain("기준이 없습니다");
  });

  it("주제가 없으면 서버 문장을 그대로 쓴다 — 같은 뜻의 문장을 두 벌 두지 않는다", () => {
    // 서버도 같은 울타리를 가지므로(2026-10-06), 주제 없는 gap의 문장은 이미 주제 없이 쓰여 온다.
    const own = "이 문의에 답할 판매자 안내 기준이 없습니다.";
    expect(gapSentence({ missingSubject: null, sentence: own })).toBe(own);
  });

  it("gap이 없으면 말할 문장도 없다", () => {
    expect(gapSentence(null)).toBeNull();
    expect(gapSentence({ missingSubject: "방수", sentence: "" })).toBeNull();
  });
});
