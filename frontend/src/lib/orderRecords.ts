import type { OrderLine, OrderReadState, OrderRecordDetail } from "./types";

/**
 * 주문 기록 화면이 숫자에서 문장을 얻는 자리 — 화면이 아니라 여기서, 한 번만.
 *
 * <b>왜 따로 있나.</b> 「마지막으로 읽은 뒤 31일」과 「13줄 모두 같은 값」은 화면이 즉석에서 계산하면
 * 목록과 상세가 다른 날 다른 규칙으로 갈라지는 종류의 판단이다. 순수 함수로 두면 두 화면이 같은 것을
 * 쓰고, 테스트가 규칙 자체를 잡을 수 있다.
 */

/** 서버가 「읽었다」고 말한 상태들. 나머지는 읽지 못한 것이고, 그 0은 「없다」가 아니다. */
export function wasRead(state: string): boolean {
  return state === "OBSERVED_FRESH" || state === "OBSERVED_FRESHNESS_UNPROVEN" || state === "ZERO";
}

/**
 * 마지막으로 무엇이든 읽은 시각, 그리고 그 뒤로 지난 날 수 — 읽은 연결이 하나도 없으면 null.
 *
 * <p>이 수가 목록 맨 위에 서는 이유: 「343건」은 어제까지 읽은 343건일 수도, 한 달 전까지 읽은
 * 343건일 수도 있고, 그 둘은 같은 숫자가 아니다. 숫자 위에 읽은 시점을 두지 않으면 화면은 오래된 읽기를
 * 오늘의 사실로 그린다.
 */
export function lastRead(
  reads: OrderReadState[],
  now: Date = new Date(),
): { at: string; daysAgo: number } | null {
  let newest: string | null = null;
  for (const read of reads) {
    if (!read.lastSeenAt || !wasRead(read.state)) {
      continue;
    }
    if (!newest || Date.parse(read.lastSeenAt) > Date.parse(newest)) {
      newest = read.lastSeenAt;
    }
  }
  if (!newest) {
    return null;
  }
  const days = Math.floor((now.getTime() - Date.parse(newest)) / 86_400_000);
  return { at: newest, daysAgo: Math.max(0, days) };
}

/**
 * 줄마다 같은 값은 줄의 속성이 아니라 결제 단위의 속성이다 — 그 값을 섹션 머리로 올리고 열을 지운다
 * (§4d: 줄마다 같은 값은 표에서 빼고 섹션 머리로 올린다).
 *
 * <p>13줄이 모두 같은 코드·같은 결제 시각일 때 열 둘을 13번 반복해 적으면, 줄마다 다를 수 있는 값과
 * 그렇지 않은 값이 화면에서 구별되지 않는다.
 */
export function sharedAcrossLines(lines: OrderLine[]): {
  rawStatusCode: string | null;
  confirmedStatusLabelKo: string | null;
  paidAt: string | null;
} {
  if (lines.length === 0) {
    return { rawStatusCode: null, confirmedStatusLabelKo: null, paidAt: null };
  }
  const first = lines[0];
  const sameStatus = lines.every((line) => line.rawStatusCode === first.rawStatusCode);
  const samePaidAt = lines.every((line) => line.paidAt === first.paidAt);
  return {
    rawStatusCode: sameStatus ? first.rawStatusCode : null,
    confirmedStatusLabelKo: sameStatus ? first.confirmedStatusLabelKo : null,
    paidAt: samePaidAt ? first.paidAt : null,
  };
}

/**
 * 이 주문의 어느 상품주문 줄을 문의가 지목했는가 — 채널이 적어 보낸 번호가 그 줄의 번호일 때만.
 *
 * 결제 단위 번호를 적어 보낸 문의는 어느 줄도 지목하지 않는다. 그럴 때 한 줄을 골라 표시하면, 채널이
 * 말하지 않은 것을 화면이 말하는 것이 된다.
 */
export function linesWithInquiry(detail: OrderRecordDetail): ReadonlySet<string> {
  const refs = new Set(detail.inquiries.map((inquiry) => inquiry.sourceOrderRef));
  return new Set(detail.lines.filter((line) => refs.has(line.externalOrderId)).map((l) => l.externalOrderId));
}
