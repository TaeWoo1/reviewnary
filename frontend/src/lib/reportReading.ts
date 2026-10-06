import type { ReportCounter, ReportRead, ReportReadChannel } from "./types";

/**
 * <b>리포트의 숫자가 무엇을 증명하는가</b> (리포트 redesign, 2026-10-06 — product-owner decision).
 *
 * <p>관측에서 나왔다. 데모 org의 주간 리포트(2026-09-28 ~ 10-04)는 「받은 리뷰 0건」, 「받은 문의 0건 ·
 * 이전 기간보다 4건 줄음」을 그렸다. 같은 시각 coverage는 세 채널 아홉 줄 전부가 미증명이었고, 마지막으로
 * 성공한 수집은 문의·리뷰 9월 26일, 주문 9월 5일이었다. <b>그 기간은 읽힌 적이 없다.</b>
 *
 * <p><b>그 판정은 이제 서버의 것이다.</b> {@code ReportFactsBuilder}가 Overview의 {@code counted()}를
 * 그대로 써서 기간을 세고, 자격을 갖춘 채널이 하나도 없으면 숫자 대신 {@code null}을 저장한다. 그래서 이
 * 파일에는 <b>freshness 규칙이 없다</b> — 있으면 그것이 두 번째 규칙이 되고, 두 규칙은 결국 서로 다른 말을
 * 한다. 여기 남은 것은 두 가지뿐이다:
 *
 * <ul>
 *   <li>저장된 사실을 화면이 그릴 수 있는 모양으로 옮기는 일({@link figureOf} · {@link changeOf})</li>
 *   <li>그 판이 얼려 둔 「읽은 범위」를 읽는 일({@link readVerdict} · {@link hasReadRange})</li>
 *   <li>그 관문 <b>이전에</b> 만들어진 판을 알아보는 일({@link ungated})</li>
 * </ul>
 *
 * <p>마지막 항목은 측정에서 나왔다. 데모 org의 주간 판(2026-10-06 11:25 생성)은 관문이 생기기 전의 행이고,
 * {@code current: 0} · {@code previous: 4} · {@code delta: -4}를 그대로 들고 있다 — 지우지 않는다(있는 것을
 * 지우는 것도 거짓이다). 다만 그 숫자들이 어디까지 읽은 것인지 <b>그 판은 기록하지 않았다</b>. 새 field가
 * 통째로 비어 있다는 사실이 그 자체로 증거이므로, 측정값인 척하지 않고 그렇게 적는다.
 */

export type ReportDataType = "REVIEW" | "INQUIRY" | "ORDER_SUMMARY";

export const TYPE_LABEL: Record<ReportDataType, string> = {
  REVIEW: "리뷰",
  INQUIRY: "문의",
  ORDER_SUMMARY: "주문",
};

export const DATA_TYPES: ReportDataType[] = ["REVIEW", "INQUIRY", "ORDER_SUMMARY"];

/* ─────────────── 저장된 사실 → 화면의 값 ─────────────── */

/**
 * 화면에 설 수 있는 값 하나.
 *
 * <ul>
 *   <li>{@code MEASURED} — 모든 채널이 이 기간을 말했다. 0도 사실이다.</li>
 *   <li>{@code PARTIAL} — 센 채널은 있지만 전부는 아니거나, 그 수집이 최신임이 증명되지 않았다.</li>
 *   <li>{@code UNREAD} — 자격을 갖춘 채널이 없었다. <b>숫자를 적을 자리가 아니다.</b></li>
 * </ul>
 */
export type Figure =
  | { kind: "MEASURED"; value: number }
  | { kind: "PARTIAL"; value: number; excluded: number }
  /** 관문이 생기기 전에 저장된 숫자 — 남기되, 측정됐다고는 말하지 않는다. */
  | { kind: "UNGATED"; value: number }
  | { kind: "UNREAD" };

/**
 * 이 수치가 수집 범위 관문을 통과하기 전에 저장됐는가.
 *
 * <p>{@code dataType}은 관문과 함께 들어온 field다. 기간 수치인데 그것이 비어 있다는 것은, 이 숫자가
 * 어느 수집에 기대고 있었는지조차 기록되지 않았다는 뜻이다.
 */
export function ungated(counter: ReportCounter | null | undefined): boolean {
  return Boolean(counter?.periodic) && !counter?.dataType;
}

/** 이 판 전체가 관문 이전의 것인가 — 기간 수치 가운데 하나라도 기록돼 있으면 아니다. */
export function ungatedEdition(counters: ReportCounter[]): boolean {
  const periodic = counters.filter((c) => c.periodic);
  return periodic.length > 0 && periodic.every(ungated);
}

export function figureOf(counter: ReportCounter | null | undefined): Figure {
  if (!counter || counter.current === null || counter.current === undefined) return { kind: "UNREAD" };
  if (ungated(counter)) return { kind: "UNGATED", value: counter.current };
  const excluded = counter.excludedChannels ?? 0;
  if (excluded > 0 || counter.unproven) return { kind: "PARTIAL", value: counter.current, excluded };
  return { kind: "MEASURED", value: counter.current };
}

/** 이전 기간과의 차이 — 서버가 두 창을 다 측정했을 때만 저장되어 있다. */
export function changeOf(
  counter: ReportCounter | null | undefined,
): { current: number; previous: number; delta: number } | null {
  if (!counter || counter.delta === null || counter.delta === undefined) return null;
  // 관문 이전의 delta는 읽지 않은 창과의 비교일 수 있다 — 실제로 「0건, 4건 줄음」이 그렇게 저장돼 있다.
  if (ungated(counter)) return null;
  if (counter.current === null || counter.current === undefined || counter.previous === null) return null;
  return { current: counter.current, previous: counter.previous, delta: counter.delta };
}

/** 비교가 서지 못한 이유 — 「없다」고만 적으면 읽는 사람이 제품을 의심한다. */
export function noChangeReason(counter: ReportCounter | null | undefined): string {
  if (ungated(counter)) return "이 판은 수집 범위를 확인하기 전에 만들어졌습니다";
  if (!counter || counter.current === null || counter.current === undefined) {
    return "이 기간의 수집 결과를 확인하지 못했습니다";
  }
  if (counter.previous === null) return "이전 기간의 수집 결과를 확인하지 못했습니다";
  return "이전 기간과 같습니다";
}

export function counterById(counters: ReportCounter[], id: string): ReportCounter | null {
  return counters.find((c) => c.id === id) ?? null;
}

/* ─────────────── 얼린 읽은 범위 ─────────────── */

/**
 * 이 판이 읽은 범위를 기록했는가.
 *
 * <p>옛 판에는 {@code reads}가 통째로 없다. 그때의 수집 상태는 어디에도 남아 있지 않으므로 <b>오늘의
 * 수집 상태로 보완하지 않는다</b> — 그렇게 하면 9월 27일까지 읽고 만든 판이 오늘까지 읽은 것처럼 보인다.
 */
export function hasReadRange(reads: ReportRead[] | null | undefined): boolean {
  return Boolean(reads && reads.length > 0);
}

/** 이 판이 이 종류를 어디까지 셌는가 — 얼린 읽기가 말한다. */
export function readVerdict(read: ReportRead | null | undefined): { text: string; warn: boolean } {
  if (!read) return { text: "기록 없음", warn: true };
  if (!read.measured) return { text: "확인 못 함", warn: true };
  if (read.excluded.length > 0) return { text: `채널 ${read.excluded.length}곳 빠짐`, warn: true };
  return { text: "전부 포함", warn: false };
}

/** 한 줄로 읽는 채널 상태 — 센 채널과 빠진 채널을 한 번에, 빠진 쪽은 이유와 함께. */
export function readChannels(read: ReportRead | null | undefined): ReportReadChannel[] {
  return read ? [...read.included, ...read.excluded] : [];
}
