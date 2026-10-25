// What a safety mark says (mockup ②, confirmed 2026-10-25). The mark reports what the REVIEW says — never that
// reviewnary established an accident — so every label is 「~ 언급」, and a worry reads as a worry whatever it is
// about. These replace 「안전 확인 · 발생 / 우려」 everywhere a review's mark is drawn.

export type SafetyKind = "INJURY" | "BODY_REACTION" | "PRODUCT_HAZARD" | "LEGAL";
export type SafetyReadingName = "OCCURRENCE" | "WORRY";

const KIND_WORD: Record<SafetyKind, string> = {
  INJURY: "부상 언급",
  BODY_REACTION: "몸 반응 언급",
  PRODUCT_HAZARD: "제품 위험 언급",
  LEGAL: "신고·소송 언급",
};

/** The one label a marked review wears. An unknown kind (an older server) still says it is a mention. */
export function safetyLabel(kind: SafetyKind | null | undefined, reading: SafetyReadingName | null | undefined): string {
  if (reading === "WORRY") return "걱정 언급";
  return (kind && KIND_WORD[kind]) || "안전 관련 언급";
}

/** A worry is the softer of the two: it is drawn in the attention tone, an occurrence in the stronger one. */
export function safetyTone(reading: SafetyReadingName | null | undefined): "warn" | "bad" {
  return reading === "WORRY" ? "warn" : "bad";
}
