/** What one product row says beside its name. `knowledge` is null when that read failed. */
export interface ProductRowFacts {
  channels: string[];
  inquiries: number;
  unanswered: number;
  reviews: number;
  issueEvidence: number;
  knowledge: number | null;
}

const CHANNEL_KO: Record<string, string> = { NAVER: "네이버", COUPANG: "쿠팡", CAFE24: "카페24" };

export function productChannelLabel(code: string): string {
  return CHANNEL_KO[code] ?? code;
}
