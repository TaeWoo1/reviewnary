import { describe, expect, it } from "vitest";
import {
  draftRuleNotice,
  draftUnavailableReason,
  inquiryHeadline,
  inquiryReading,
  inquiryNextAction,
  isAnswered,
} from "./inquiryNextAction";

/**
 * <b>실제 live 데이터에서 드러난 두 가지: 같은 문장이 세 번 그려지는 것과, 이유 없는 「할 수 없습니다」.</b>
 *
 * <p>여기서 재는 것은 문구가 아니라 <b>규칙</b>이다 — 어떤 문장이 나오는지가 아니라, 같은 사실을 두 번
 * 말하지 않는지와 「못 한다」에 이유가 붙는지. 문구는 바뀔 수 있고 규칙은 바뀌면 이 파일이 실패해야 한다.
 */

/** 목록과 상세가 쓰는 것과 같은 preview. 여기서는 잘라내기만 하면 되므로 최소 구현. */
const preview = (text: string | null | undefined) => (text ?? "").replace(/\s+/g, " ").trim();

describe("inquiryHeadline — 제목과 본문을 두 번 그리지 않는다", () => {
  it("제목이 있으면 제목이 제목이고 본문은 따로 남는다", () => {
    const h = inquiryHeadline({ title: "세금계산서 발행 문의", snippet: "사업자등록증 첨부했습니다" }, preview);
    expect(h.title).toBe("세금계산서 발행 문의");
    expect(h.body).toBe("사업자등록증 첨부했습니다");
  });

  it("제목이 없으면 본문의 앞부분이 제목 자리를 대신하고, 본문은 다시 그리지 않는다", () => {
    // 중복의 출처였던 경우. 예전에는 이 문장이 pane 제목 · InboxDetail 제목 · 「문의 발췌」 본문으로
    // 세 번 그려졌다.
    const h = inquiryHeadline({ title: null, snippet: "폭이 몇 mm인가요" }, preview);
    expect(h.title).toBe("폭이 몇 mm인가요");
    expect(h.body).toBeNull();
  });

  it("제목 칸과 본문 칸이 같은 문장인 채널에서도 한 번만 그린다", () => {
    // 제목 칸에 본문을 그대로 넣는 게시판이 있다. 두 칸이 채워져 있다는 것과 두 가지 사실이라는 것은
    // 다르고, 이 구분이 없으면 「채워져 있으니 둘 다 그린다」가 된다.
    const h = inquiryHeadline({ title: "폭이 몇 mm인가요", snippet: "폭이 몇 mm인가요" }, preview);
    expect(h.title).toBe("폭이 몇 mm인가요");
    expect(h.body).toBeNull();
  });

  it("둘 다 없으면 제목 자리가 비지 않는다", () => {
    expect(inquiryHeadline({ title: null, snippet: null }, preview)).toEqual({ title: "문의", body: null });
    expect(inquiryHeadline({ title: "   ", snippet: "  " }, preview)).toEqual({ title: "문의", body: null });
  });
});

describe("inquiryNextAction — 상태 다음에 바로 할 일이 온다", () => {
  it("답변된 문의에는 할 일이 없다고 말한다", () => {
    const next = inquiryNextAction({ status: "ANSWERED" }, null);
    expect(next.state.text).toBe("답변함");
    expect(next.sentence).toContain("하실 일은 없습니다");
  });

  it("답변 대기 중이면 어디서 이어가는지 말한다", () => {
    const next = inquiryNextAction({ status: "UNANSWERED" }, "w1");
    expect(next.state.text).toBe("답변 필요");
    expect(next.sentence).toContain("아래에서");
  });

  it("대기 목록에 없는 미답변 문의는 고객이 어디 서 있는지만 말한다", () => {
    const next = inquiryNextAction({ status: "UNANSWERED" }, null);
    expect(next.state.text).toBe("답변 필요");
    expect(next.sentence).toContain("답변을 받지 못했습니다");
    // 어디서 답하면 되는지는 이유 문단의 몫이다. 둘이 같이 말하면 한 화면에 같은 지시가 두 번 적힌다 —
    // 이 패키지가 없애려던 모양 그대로.
    expect(next.sentence).not.toContain("판매자센터");
  });

  it("다음 행동 줄과 이유 문단이 같은 말을 하지 않는다", () => {
    const next = inquiryNextAction({ status: "UNANSWERED" }, null);
    const reason = draftUnavailableReason({ status: "UNANSWERED" }, null, "카페24 자사몰") ?? "";
    expect(reason).toContain("판매자센터에서 직접 작성");
    expect(next.sentence).not.toContain("직접 작성");
  });

  it("「답변함」은 제품의 업무 어휘에서 오고, 「완료」라고 말하지 않는다", () => {
    // 보낸 것을 확인한 것과 고객이 만족한 것은 다른 사실이고, 이 화면이 아는 것은 앞쪽뿐이다.
    for (const status of ["ANSWERED", "UNANSWERED"]) {
      for (const workItemId of [null, "w1"]) {
        expect(inquiryNextAction({ status }, workItemId).sentence).not.toContain("완료");
      }
    }
  });
});

describe("draftUnavailableReason — 「할 수 없습니다」에 이유가 붙는다", () => {
  it("초안을 쓸 수 있으면 아무 말도 하지 않는다", () => {
    expect(draftUnavailableReason({ status: "UNANSWERED" }, "w1", "카페24 자사몰")).toBeNull();
  });

  /*
    UI audit, 2026-10-06 — 이 문장은 {@link inquiryNextAction}의 문장과 한 화면에 함께 서고, 「답변이
    등록됐다」는 그쪽이 이미 말한다. 여기 남는 사실은 하나, 어디서 읽는가이다.
  */
  it("이미 답변된 문의에는 어디서 읽는지가 이유다", () => {
    const reason = draftUnavailableReason({ status: "ANSWERED" }, null, "네이버 스마트스토어");
    expect(reason).toContain("네이버 스마트스토어 판매자센터");
    expect(reason).toContain("확인하실 수 있습니다");
    // 바로 위 문장이 말하는 사실을 다른 말로 한 번 더 말하지 않는다.
    expect(reason).not.toContain("이미 답변이 등록되어 있어");
  });

  it("그 밖에는 규칙을 말한다 — 가능성 목록이 아니라", () => {
    const reason = draftUnavailableReason({ status: "UNANSWERED" }, null, "카페24 자사몰");
    expect(reason).toContain("답변을 기다리는 문의에만 초안을 씁니다");
    expect(reason).toContain("카페24 자사몰 판매자센터");
    // 행이 담고 있지 않은 사실을 짐작해 늘어놓지 않는다. 가능성 목록은 정보가 아니라 숙제다.
    expect(reason).not.toContain("일 수 있습니다");
  });

  it("예전 문장으로 돌아가지 않는다", () => {
    for (const status of ["ANSWERED", "UNANSWERED"]) {
      expect(draftUnavailableReason({ status }, null, "카페24 자사몰")).not.toContain("제안할 수 없습니다");
    }
  });

  it("모르는 채널 이름은 지어내지 않는다", () => {
    for (const name of [null, undefined, ""]) {
      const reason = draftUnavailableReason({ status: "UNANSWERED" }, null, name);
      expect(reason).toContain("해당 채널의 판매자센터");
    }
  });

  it("상태를 모르는 화면은 규칙만 말하고, 같은 문장을 쓴다", () => {
    // 확인할 일의 pane이 그렇다 — 그 행은 status를 들고 다니지 않는다. 두 화면이 같은 규칙을 다른 말로
    // 설명하기 시작하면 판매자가 두 제품을 쓰는 셈이 된다.
    expect(draftUnavailableReason({ status: "UNANSWERED" }, null, "카페24 자사몰"))
      .toBe(draftRuleNotice("카페24 자사몰"));
  });
});

describe("isAnswered", () => {
  it("채널이 말한 상태만 읽는다", () => {
    expect(isAnswered({ status: "ANSWERED" })).toBe(true);
    expect(isAnswered({ status: "UNANSWERED" })).toBe(false);
    expect(isAnswered({ status: "OPEN" })).toBe(false);
  });
});

/**
 * <b>inquiryReading — 고객이 쓴 말이 먼저, 제목은 뭔가를 더해 줄 때만</b> (문의 canonical, 2026-10-03,
 * product-owner decision).
 *
 * <p>{@link inquiryHeadline}의 규칙(「제목이 있으면 제목이 제목이다」)이 틀렸던 것은 아니다 — 그 규칙은
 * 제목이 목록 어디에도 안 나오던 결함을 고쳤다. 바뀐 것은 어느 칸이 <b>첫 시선</b>을 갖느냐이고, 그 판단의
 * 근거는 데이터다: 데모 org 최신 11건 중 5건이 빈 제목이거나 「문의 드립니다」다.
 */
describe("inquiryReading — 제목은 더해 줄 것이 있을 때만 남는다", () => {
  it("본문이 첫 시선이고, 뜻 있는 제목은 그 옆에 남는다", () => {
    expect(inquiryReading({ title: "배송 후 분실", snippet: "배송 완료 사진을 받았지만 물건이 없었습니다" }, preview)).toEqual({
      question: "배송 완료 사진을 받았지만 물건이 없었습니다",
      titleContext: "배송 후 분실",
    });
  });

  it("게시판이 채운 제목은 그리지 않는다 — 공백과 마침표는 같은 제목이다", () => {
    for (const title of ["문의 드립니다", "문의드립니다.", "문의", "질문 있습니다", "안녕하세요"]) {
      expect(inquiryReading({ title, snippet: "교환은 언제까지 되나요" }, preview).titleContext).toBeNull();
    }
  });

  it("본문이 이미 제목으로 시작하면 제목을 다시 그리지 않는다", () => {
    expect(inquiryReading({ title: "교환 문의", snippet: "교환 문의드립니다. 언제까지 가능한가요" }, preview).titleContext).toBeNull();
  });

  it("목록에 없는 제목은 언제나 남는다 — 판매자의 자료를 숨기는 쪽으로 틀리지 않는다", () => {
    expect(inquiryReading({ title: "선바로 길이 문의", snippet: "2호 2m짜리 있나요" }, preview).titleContext).toBe("선바로 길이 문의");
  });

  it("본문이 없으면 제목이 곧 질문이고, 같은 문장을 두 번 그리지 않는다", () => {
    expect(inquiryReading({ title: "세금계산서 발행", snippet: null }, preview)).toEqual({
      question: "세금계산서 발행",
      titleContext: null,
    });
    expect(inquiryReading({ title: null, snippet: null }, preview)).toEqual({ question: "문의", titleContext: null });
  });

  it("제목이 없으면 본문뿐이다", () => {
    expect(inquiryReading({ title: "", snippet: "난연소재인가요" }, preview)).toEqual({
      question: "난연소재인가요",
      titleContext: null,
    });
  });
});
