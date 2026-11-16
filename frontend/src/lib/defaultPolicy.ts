/**
 * 기본 설정 질문지 — the wire shape of /api/knowledge/defaults (backend `DefaultPolicyViews`).
 *
 * A typed answer is a draft and answers nothing; only a confirmed one becomes the shop's word. 출고 마감 and 휴무일 are
 * `shared`: they are the TalkTalk shipping guidance, read here and changed there.
 */
export type DefaultPolicyStatus = "EMPTY" | "DRAFT" | "CONFIRMED" | "CHANGED";
export type DefaultPolicyKind = "TEXT" | "DAYS" | "WON" | "SHARED";

export interface DefaultPolicyItem {
  key: string;
  group: string;
  groupLabel: string;
  label: string;
  question: string;
  kind: DefaultPolicyKind;
  placeholder: string | null;
  legal: string | null;
  shared: boolean;
  status: DefaultPolicyStatus;
  draftNumber: number | null;
  draftText: string | null;
  confirmedNumber: number | null;
  confirmedText: string | null;
  sentence: string | null;
  draftSentence: string | null;
  warning: string | null;
  confirmedAt: string | null;
}

export interface DefaultPolicyDocument {
  group: string;
  groupLabel: string;
  sourceId: string;
  title: string;
  active: boolean;
  version: number;
}

export interface DefaultPolicyOverview {
  items: DefaultPolicyItem[];
  confirmed: number;
  total: number;
  documents: DefaultPolicyDocument[];
}

export interface DefaultPolicyAnswer {
  number: number | null;
  text: string | null;
}

/** The status as the seller reads it. */
export const DEFAULT_STATUS_WORD: Record<DefaultPolicyStatus, string> = {
  EMPTY: "아직 안 적음",
  DRAFT: "확정 전",
  CONFIRMED: "확정",
  CHANGED: "고친 내용 확정 전",
};

/** Whether the answer is in use for customers' answers right now. */
export function inUse(item: DefaultPolicyItem): boolean {
  return item.status === "CONFIRMED" || item.status === "CHANGED";
}
