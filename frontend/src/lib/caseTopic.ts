/**
 * <b>기준의 이름으로 내보낼 수 있는 말인가</b> (UI audit, 2026-10-06 — product-owner decision).
 *
 * <p>확인할 일의 한 건이 「「드립니다」에 대해 고객에게 안내할 기준이 없습니다.」를, 그 위의 칩이
 * 「드립니다 기준 없음」을 그리고 있었다. 문의 제목이 「문의 드립니다」였고, 서버의 주제 추출
 * ({@code CaseKnowledgeService}의 {@code missingSubject})이 그 마지막 토막을 주제로 집었다.
 * 「드립니다」는 회사가 기준을 적어 둘 수 있는 주제가 아니라 인사말의 서술어다 — 화면은 그것을 따옴표에
 * 넣어 회사의 기준 이름인 것처럼 내보냈다.
 *
 * <p><b>여기서 고치는 것은 표시뿐이다.</b> 추출을 고치는 것은 서버의 일이고(보고서에 올린다), 화면이
 * 할 수 있는 정직한 일은 <b>믿을 수 없는 이름을 지어내지도 인용하지도 않는 것</b>이다. 주제가 서술어로
 * 끝나거나 문장부호로 끝나면 주제를 빼고 말한다 — 없는 사실을 만들지 않고, 있는 사실(기준이 없다)은
 * 그대로 남는다.
 */

/** 서술어로 끝나는 말 — 주제가 아니라 문장의 끝이다. */
const PREDICATE_ENDING = /(니다|해요|세요|어요|아요|네요|나요|가요|까요|죠|군요|거든요)$/;

/**
 * 주제로 쓸 수 있으면 그 말을, 아니면 {@code null}.
 *
 * <p>공백이 있는 주제는 통과한다 — 「엘보 구간에 쓸 사이즈」는 실제로 회사가 기준을 적는 단위다.
 * 거르는 것은 <b>서술어로 끝나는 말</b>과 <b>문장부호로 끝나는 말</b>, 즉 주제가 아니라 문장인 것뿐이다.
 */
export function usableTopic(subject: string | null | undefined): string | null {
  const text = subject?.trim();
  if (!text) return null;
  if (/[.!?…。！？]$/.test(text)) return null;
  if (PREDICATE_ENDING.test(text)) return null;
  return text;
}

/** 주제를 못 믿을 때 쓰는, 주제 없는 같은 사실. */
export const TOPICLESS_GAP_SENTENCE = "이 질문에 대해 고객에게 안내할 기준이 없습니다.";

/**
 * 「필요한 정보」가 세우는 한 문장. 주제를 믿을 수 있을 때만 서버가 쓴 문장을 그대로 쓴다.
 *
 * <p>서버 문장을 다시 조립하지 않는 이유: 그 문장의 주인은 서버이고, 화면이 같은 뜻을 두 번 쓰면
 * 두 문장이 서로 다른 말을 하기 시작한다. 쓸 수 있으면 그대로, 못 쓰면 주제 없는 문장 하나.
 */
export function gapSentence(gap: { missingSubject?: string | null; sentence?: string | null } | null | undefined): string | null {
  if (!gap) return null;
  const sentence = gap.sentence?.trim();
  if (!sentence) return null;
  /*
    주제가 <b>없는</b> 것과 주제를 <b>못 믿는</b> 것은 다르다. 없으면 서버 문장은 이미 주제 없이 쓰인
    것이므로 그대로 쓴다 — 같은 뜻의 문장을 화면이 한 벌 더 쓰면 그 문장의 주인이 둘이 된다. 가로채는
    것은 주제가 적혀 있는데 그것을 인용할 수 없을 때뿐이다.
  */
  const subject = gap.missingSubject?.trim();
  if (!subject) return sentence;
  return usableTopic(subject) ? sentence : TOPICLESS_GAP_SENTENCE;
}
