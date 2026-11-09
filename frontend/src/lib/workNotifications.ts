import type { HomeAttentionReview, InquiryQueueItem, ReplyHandoffView } from "./types";
import { HANDOFF_WORD } from "../components/customerOperations/AgentHandoffs";

/**
 * <b>PC 알림 — what to tell the seller's computer, and when</b> (2026-10-28: a PC notification is the default, not mail).
 *
 * <p>Pure: given what is open now and what was already told, it returns the notifications to show and, for each, the
 * work it covers. One piece of work is one notification however many lists carry it — a review both handed over and in
 * 확인할 일 is told once, by its most important reason (third outside review, 2026-10-28). A harm or a dispute is told on
 * the next check, one by one; everything else new waits and is told together, at most once a batch. What was told is
 * recorded by the caller only for the notifications that were actually shown ({@link markTold}), so one that failed is
 * tried again. A preview says the product, the matter and what the seller has to do — or only that there is work, when
 * the seller hid previews. Nothing here can be told about work the PC never fetched.
 */
export interface WorkNotice {
  readonly key: string;
  readonly title: string;
  readonly body: string;
  readonly href: string;
  readonly urgent: boolean;
  /** The told-keys this notice covers — recorded only once it was shown. */
  readonly covers: readonly string[];
}

export interface NoticeState {
  /** Work already told on this device, with the level it was told at (`…:U` urgent, `…:O` ordinary). */
  readonly told: readonly string[];
  /** When the last batch was told (ms), or null. */
  readonly lastBatchAt: number | null;
}

/** Ordinary work is told together, no more often than this. */
export const BATCH_MS = 30 * 60 * 1000;

const URGENT_REASONS = new Set(["CATEGORY_S", "CATEGORY_L", "SAFETY_SIGNAL"]);

function previewOf(product: string | null | undefined, matter: string, action: string, hidden: boolean): string {
  if (hidden) return "확인할 일이 있어요. 눌러서 열어 주세요.";
  return [product ? `「${product}」` : null, matter, action].filter(Boolean).join(" · ");
}

/** One piece of work, merged across the lists that carry it. */
interface Work {
  readonly id: string;
  urgent: boolean;
  title: string;
  body: string;
  href: string;
}

export function planNotices(input: {
  handoffs: readonly ReplyHandoffView[];
  attention: readonly HomeAttentionReview[];
  inquiries?: readonly InquiryQueueItem[];
  state: NoticeState;
  now: number;
  hidePreview: boolean;
}): { notices: WorkNotice[] } {
  const works = new Map<string, Work>();
  // Most important first, so a later list cannot overwrite a stronger reason: an urgent handoff, a safety review in
  // 확인할 일, then the rest.
  const offer = (work: Work) => {
    const held = works.get(work.id);
    if (!held || (work.urgent && !held.urgent)) works.set(work.id, work);
  };
  for (const h of input.handoffs) {
    const word = HANDOFF_WORD[h.reason ?? ""];
    const urgent = URGENT_REASONS.has(h.reason ?? "");
    const matter = h.points && h.points.length > 0 ? h.points.join(", ") : (word?.label ?? "직접 확인이 필요한 리뷰");
    offer({
      id: `review:${h.reviewId}`,
      urgent,
      title: `리뷰 · ${word?.label ?? "직접 확인"}`,
      body: previewOf(null, matter, urgent ? "답글 전에 직접 확인해 주세요" : (word?.note ?? "확인해 주세요"), input.hidePreview),
      href: `/reviews/reply/${h.reviewId}`,
    });
  }
  for (const a of input.attention) {
    offer({
      id: `review:${a.reviewId}`,
      urgent: a.safety === true,
      title: a.safety ? "리뷰 · 안전 관련" : "리뷰 · 확인할 일",
      body: previewOf(a.productName, a.safety ? "안전 관련 내용이 있는 리뷰" : "답글이나 판단이 필요한 리뷰",
        "직접 확인해 주세요", input.hidePreview),
      href: `/reviews/reply/${a.reviewId}`,
    });
  }
  for (const q of input.inquiries ?? []) {
    if (q.status === "ANSWERED") continue;
    const ready = q.phase === "PROPOSED";
    offer({
      id: `inquiry:${q.workItemId}`,
      urgent: false,
      title: ready ? "문의 · 답변 승인 대기" : "문의 · 새 문의",
      body: previewOf(q.productName, ready ? "준비된 답변이 있어요" : "답하지 않은 문의", ready ? "확인하고 승인해 주세요" : "답해 주세요",
        input.hidePreview),
      href: `/inquiries/${q.workItemId}`,
    });
  }

  const told = new Set(input.state.told);
  const notices: WorkNotice[] = [];
  const ordinary: string[] = [];
  for (const work of works.values()) {
    if (work.urgent) {
      const key = `${work.id}:U`;
      if (told.has(key)) continue;
      notices.push({ key, title: work.title, body: work.body, href: work.href, urgent: true, covers: [key, `${work.id}:O`] });
    } else {
      const key = `${work.id}:O`;
      if (!told.has(key) && !told.has(`${work.id}:U`)) ordinary.push(key);
    }
  }
  const batchDue = input.state.lastBatchAt === null || input.now - input.state.lastBatchAt >= BATCH_MS;
  if (ordinary.length > 0 && batchDue) {
    notices.push({
      key: `batch:${input.now}`,
      title: `확인할 일 ${ordinary.length.toLocaleString("ko-KR")}건`,
      body: input.hidePreview ? "확인할 일이 있어요. 눌러서 열어 주세요." : "답글·답변 승인이나 직접 확인이 필요한 일이 새로 생겼어요.",
      href: "/",
      urgent: false,
      covers: ordinary,
    });
  }
  return { notices };
}

/** Record what was actually shown. A batch that was shown sets the batch clock; a notice that failed records nothing. */
export function markTold(state: NoticeState, shown: readonly WorkNotice[], now: number): NoticeState {
  const told = new Set(state.told);
  shown.forEach((n) => n.covers.forEach((k) => told.add(k)));
  const batch = shown.some((n) => n.key.startsWith("batch:"));
  // Bounded: the oldest are forgotten first, and a forgotten item that is still open is told again — repeated, not lost.
  return { told: [...told].slice(-500), lastBatchAt: batch ? now : state.lastBatchAt };
}

const SETTINGS_KEY = "sellerops.notifications.v1";
const STATE_KEY = "sellerops.notifications.state.v1";

export interface NoticeSettings {
  readonly enabled: boolean;
  readonly hidePreview: boolean;
}

export function readNoticeSettings(): NoticeSettings {
  try {
    const raw = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? "{}") as Partial<NoticeSettings>;
    return { enabled: raw.enabled === true, hidePreview: raw.hidePreview === true };
  } catch {
    return { enabled: false, hidePreview: false };
  }
}

export function writeNoticeSettings(settings: NoticeSettings): void {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  } catch {
    // A browser that refuses storage keeps the defaults; nothing else depends on it.
  }
}

export function readNoticeState(): NoticeState {
  try {
    const raw = JSON.parse(localStorage.getItem(STATE_KEY) ?? "{}") as Partial<NoticeState>;
    return { told: Array.isArray(raw.told) ? raw.told.filter((t) => typeof t === "string") : [],
      lastBatchAt: typeof raw.lastBatchAt === "number" ? raw.lastBatchAt : null };
  } catch {
    return { told: [], lastBatchAt: null };
  }
}

export function writeNoticeState(state: NoticeState): void {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(state));
  } catch {
    // Without storage an item may be told again after a reload — repeated, never lost.
  }
}
