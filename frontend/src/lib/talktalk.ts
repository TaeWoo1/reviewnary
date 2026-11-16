// NAVER TalkTalk chatbot-API lane (docs/inquiry/talktalk_chat_lane_v1.md). Types mirror backend TalkTalkViews; the
// server never sends a key, a webhook token (except once, at connect), a TalkTalk user value or a full partner id.

export interface Reason {
  code: string;
  label: string;
}

export interface ReadinessItem {
  code: string;
  met: boolean;
  label: string;
}

export interface TalkTalkConnectionView {
  connected: boolean;
  status: "NONE" | "CONNECTED" | "DISCONNECTED";
  mode: "FAKE" | "LIVE" | null;
  fake: boolean;
  modeLabel: string;
  partner: string;
  lastEventAt: string | null;
  connectedAt: string | null;
}

export interface PreviewSample {
  label: string;
  text: string | null;
  refusal: Reason | null;
}

export interface ClosedDate {
  date: string;
  note: string;
}

export interface GuidanceView {
  id: string;
  version: number;
  status: "DRAFT" | "CONFIRMED" | "RETIRED";
  cutoffHour: number | null;
  cutoffMinute: number;
  dispatchDays: string[];
  closedDates: ClosedDate[];
  confirmedThrough: string | null;
  fingerprint: string;
  previewFingerprint: string;
  previews: PreviewSample[];
  confirmedAt: string | null;
}

export interface DelegationView {
  active: boolean;
  reason: Reason | null;
  guidanceVersion: number | null;
  changedAt: string | null;
  answeredAutomatically: string[];
  handedToPerson: string[];
}

export interface TalkTalkStatus {
  connection: TalkTalkConnectionView;
  readiness: { ready: boolean; items: ReadinessItem[]; residualRisk: string };
  currentGuidance: GuidanceView | null;
  draftGuidance: GuidanceView | null;
  delegation: DelegationView;
  laneNotice: string;
}

export interface ConnectResponse {
  status: TalkTalkStatus;
  webhookUrl: string;
  webhookNotice: string;
}

export interface TalkTalkSummary {
  connected: boolean;
  fake: boolean;
  needsHumanConversations: number;
  unknownSends: number;
  queuedSends: number;
  notice: string;
}

export interface ConversationRow {
  id: string;
  alias: string;
  owner: "BOT" | "PARTNER";
  ownerLabel: string;
  needsHuman: boolean;
  humanReason: Reason | null;
  automation: Reason | null;
  lastInboundAt: string | null;
  lastPreview: string | null;
  lastOutboundStatus: OutboundStatus | null;
}

export type OutboundStatus =
  | "QUEUED"
  | "SENDING"
  | "FAKE_RECORDED"
  | "API_ACCEPTED"
  | "FAILED"
  | "UNKNOWN"
  | "CANCELLED"
  | "BLOCKED";

export interface MessageView {
  id: string;
  author: "CUSTOMER" | "SERVICE" | "PARTNER_CENTER" | "BOT_ECHO";
  authorLabel: string;
  text: string | null;
  at: string;
  duplicateSuspect: boolean;
  standby: boolean;
  purged: boolean;
  outboundStatus: OutboundStatus | null;
  outboundStatusLabel: string | null;
  /** A photo or card message: what it was and where to see it. The content itself is not stored here. */
  nonTextNote: string | null;
}

export interface DraftView {
  version: number;
  authorKind: "GUIDANCE" | "MODEL" | "SELLER_EDIT";
  text: string | null;
  fingerprint: string;
  basis: "GROUNDED" | "NEEDS_HUMAN" | "SELLER_WRITTEN";
  holds: Reason[];
  evidence: { kind: string; title: string }[];
  createdAt: string;
  forLatest: boolean;
}

export interface OutboundView {
  id: string;
  kind: "MANUAL_REPLY" | "AUTO_GUIDANCE" | "HANDOVER_PASS" | "HANDOVER_TAKE";
  kindLabel: string;
  status: OutboundStatus;
  statusLabel: string;
  failure: Reason | null;
  resultCode: string | null;
  mode: "FAKE" | "LIVE";
  echoSeen: boolean;
  createdAt: string;
  finishedAt: string | null;
  byService: boolean;
}

export interface ConversationDetail {
  conversation: ConversationRow;
  productName: string | null;
  leftAt: string | null;
  mode: "FAKE" | "LIVE";
  fake: boolean;
  messages: MessageView[];
  draft: DraftView | null;
  outbound: OutboundView[];
}

export interface GuidanceRequest {
  cutoffHour: number | null;
  cutoffMinute: number;
  dispatchDays: string[];
  closedDates: ClosedDate[];
  confirmedThrough: string | null;
}

/** Holds no confirmation goes past — mirrors backend TalkTalkDraftService.FIX_FIRST. */
export const FIX_FIRST = new Set([
  "POLICY_CONFLICT",
  "COST_CONFLICT",
  "UNSETTLED_VALUE",
  "KNOWLEDGE_CONFLICT",
  "FACT_CHECK_UNAVAILABLE",
  "EVIDENCE_UNREADABLE",
  "FORBIDDEN_PHRASE",
  "STYLE_CHECK_UNAVAILABLE",
]);

export type SendAbility =
  | { kind: "send" }
  | { kind: "confirm-exception"; holds: Reason[] }
  | { kind: "rewrite"; holds: Reason[] }
  | { kind: "none"; why: string };

/** What the seller can do with the head draft — the same tiers the server enforces (it checks again). */
export function sendAbility(detail: ConversationDetail): SendAbility {
  const d = detail.draft;
  if (!d || !d.text) return { kind: "none", why: "보낼 답변이 아직 없어요." };
  if (!d.forLatest) return { kind: "none", why: "답변을 준비한 뒤 고객이 새 메시지를 보냈어요. 새로 준비해 주세요." };
  if (detail.outbound.some((o) => o.kind === "MANUAL_REPLY" && (o.status === "QUEUED" || o.status === "SENDING"))) {
    return { kind: "none", why: "이 대화에 보내는 중인 답변이 있어요." };
  }
  if (d.holds.length === 0) return { kind: "send" };
  if (d.holds.some((h) => FIX_FIRST.has(h.code)) || d.authorKind !== "SELLER_EDIT") {
    return { kind: "rewrite", holds: d.holds };
  }
  return { kind: "confirm-exception", holds: d.holds };
}

/** The newest manual reply whose result is unknown and not yet acknowledged by a later send. */
export function unresolvedUnknown(detail: ConversationDetail): OutboundView | null {
  return detail.outbound.find((o) => o.kind === "MANUAL_REPLY" && o.status === "UNKNOWN") ?? null;
}

export function statusTone(s: OutboundStatus | null): "good" | "warn" | "bad" | "info" | "neutral" {
  switch (s) {
    case "API_ACCEPTED":
      return "good";
    case "UNKNOWN":
    case "QUEUED":
    case "SENDING":
      return "warn";
    case "FAILED":
    case "BLOCKED":
      return "bad";
    default:
      return "neutral";
  }
}

export const WEEKDAYS: { code: string; label: string }[] = [
  { code: "MON", label: "월" },
  { code: "TUE", label: "화" },
  { code: "WED", label: "수" },
  { code: "THU", label: "목" },
  { code: "FRI", label: "금" },
  { code: "SAT", label: "토" },
  { code: "SUN", label: "일" },
];

export function apiCode(e: unknown): string | null {
  return (e as { response?: { data?: { code?: string } } })?.response?.data?.code ?? null;
}

/** Codes after which the screen must re-read before the seller decides again. */
export const RELOAD_CODES = new Set(["NEWER_MESSAGE", "DRAFT_CHANGED", "COMMAND_REUSED", "IN_FLIGHT", "UNKNOWN_PENDING"]);

/** Poll interval for an open conversation: fast while a send is in flight, otherwise slow. */
export function pollDelay(detail: ConversationDetail | null): number {
  if (detail && detail.outbound.some((o) => o.status === "QUEUED" || o.status === "SENDING")) return 2000;
  return 6000;
}

/**
 * What a re-read changed that a pending confirmation was given against: the head draft, the unresolved unknown send,
 * the latest line. Any change clears the confirmations (two-step send, acknowledgement, exception).
 */
export function decisionKey(detail: ConversationDetail | null): string {
  if (!detail) return "";
  const last = detail.messages.length ? detail.messages[detail.messages.length - 1].id : "";
  const unknown = unresolvedUnknown(detail)?.id ?? "";
  return `${detail.draft?.version ?? 0}:${detail.draft?.fingerprint ?? ""}:${unknown}:${last}`;
}

/**
 * What the conversation list shows for one conversation: who holds it, whether it waits for a person, and each send's
 * state. When a re-read of the open conversation moves it, the list is re-read too — so the row beside a send that
 * just got its result does not keep saying 「사람 확인 필요」 until the list's own slower poll.
 */
export function listKey(detail: ConversationDetail | null): string {
  if (!detail) return "";
  const c = detail.conversation;
  return `${c.owner}:${c.needsHuman}:${detail.outbound.map((o) => `${o.id}=${o.status}`).join(",")}`;
}

export function apiMessage(e: unknown, fallback = "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요."): string {
  const data = (e as { response?: { data?: { message?: string; detail?: string } } })?.response?.data;
  return data?.detail ?? data?.message ?? fallback;
}

export function kstTime(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(d);
}
