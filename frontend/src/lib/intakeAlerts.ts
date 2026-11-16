/** Mirrors com.sellerops.intakealert.IntakeAlertViews — the KakaoTalk intake-alert settings, mock inbox and notices. */

export type SenderMode = "MOCK" | "LIVE" | "BLOCKED";
export type DeliveryStatus =
  | "PREPARED"
  | "SENDING"
  | "MOCK_RECORDED"
  | "ACCEPTED"
  | "FAILED"
  | "UNKNOWN"
  | "BLOCKED"
  | "CANCELLED";

export interface IntakeAlertSettings {
  enabled: boolean;
  notifyReviews: boolean;
  notifyInquiries: boolean;
  deliveryMode: "EACH" | "DIGEST";
  digestMinutes: number;
  quietHoursEnabled: boolean;
  daysMask: number;
  startMinute: number;
  endMinute: number;
  phoneMasked: string | null;
  phoneConsented: boolean;
  numberStorageAvailable: boolean;
  watchFrom: string | null;
  sender: { mode: SenderMode; reason: string | null };
  limits: { recoveryHours: number; oldAfterHours: number; perPassCap: number; passSeconds: number };
  counts: { pending: number; told: number; handledBeforeTold: number; oldNotTold: number; cancelled: number };
}

export interface IntakeAlertSettingsRequest {
  enabled?: boolean;
  notifyReviews?: boolean;
  notifyInquiries?: boolean;
  deliveryMode?: "EACH" | "DIGEST";
  digestMinutes?: number;
  quietHoursEnabled?: boolean;
  daysMask?: number;
  startMinute?: number;
  endMinute?: number;
  phone?: string;
  removePhone?: boolean;
  phoneConsent?: boolean;
}

export interface IntakeAlertDelivery {
  id: string;
  kind: "SINGLE" | "DIGEST";
  mode: SenderMode;
  status: DeliveryStatus;
  reviewCount: number;
  inquiryCount: number;
  urgentCount: number;
  messageText: string;
  linkPath: string;
  failureCode: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface IntakeAlertDeliveryItem {
  subjectKind: "REVIEW" | "INQUIRY";
  subjectId: string;
  channelCode: string | null;
  reason: "SAFETY" | "LOW_RATING" | "NEW_REVIEW" | "NEW_INQUIRY";
  issueLabel: string | null;
  urgent: boolean;
  status: string;
  cancelReason: string | null;
  stillOpen: boolean;
  targetPath: string | null;
  arrivedAt: string;
  writtenAt: string | null;
}

export interface IntakeAlertDeliveryDetail {
  delivery: IntakeAlertDelivery;
  items: IntakeAlertDeliveryItem[];
}

/** What a delivery's state means, without overstating it. Accepted is not delivered; mock is not sent. */
export function statusLabel(status: DeliveryStatus, failureCode: string | null): { text: string; tone: "ok" | "warn" | "bad" | "muted" } {
  switch (status) {
    case "MOCK_RECORDED":
      return { text: "모의 알림으로 기록 · 카톡으로 보내지 않았어요", tone: "muted" };
    case "ACCEPTED":
      return { text: "발송 공급자가 접수했어요 · 휴대폰 도착은 확인하지 않았어요", tone: "ok" };
    case "FAILED":
      return { text: `보내지 못했어요${failureCode ? ` (${failureCode})` : ""} · 자동으로 다시 보내지 않아요`, tone: "bad" };
    case "UNKNOWN":
      return { text: "결과를 알 수 없어요 · 두 번 가지 않도록 자동으로 다시 보내지 않아요", tone: "warn" };
    case "BLOCKED":
      return { text: `보내지 않았어요 · ${blockedWhy(failureCode)}`, tone: "warn" };
    case "CANCELLED":
      return { text: `설정이 바뀌어 보내지 않고 닫았어요 · ${cancelWhy(failureCode)}`, tone: "muted" };
    case "SENDING":
      return { text: "보내는 중", tone: "muted" };
    default:
      return { text: "보낼 준비 중", tone: "muted" };
  }
}

export function blockedWhy(code: string | null): string {
  if (!code) return "실발송 설정이 끝나지 않았어요";
  if (code === "LIVE_OFF") return "실제 카톡 발송이 꺼져 있어요";
  if (code === "NO_RECIPIENT_NUMBER") return "받을 번호나 동의가 없어요";
  if (code === "MODE_CHANGED") return "모의로 준비된 알림이라 실발송으로 바꿔 보내지 않아요";
  if (code.startsWith("MISSING:")) return "실발송에 필요한 설정이 빠져 있어요";
  return "실발송 설정을 확인해 주세요";
}

export function cancelWhy(code: string | null): string {
  if (!code) return "설정 변경";
  if (code.includes("TURNED_OFF") || code === "SETTING_OFF") return "알림을 껐어요";
  if (code.includes("NUMBER_CHANGED")) return "번호가 바뀌었어요";
  if (code.includes("CONSENT_WITHDRAWN")) return "동의를 철회했어요";
  if (code.includes("KIND_OFF")) return "이 종류의 알림을 껐어요";
  if (code === "BEFORE_WATCH_FROM") return "알림을 켜기 전에 들어온 자료예요";
  return "설정이 바뀌었어요";
}

export const CHANNEL_KO: Record<string, string> = { NAVER: "네이버", COUPANG: "쿠팡", CAFE24: "카페24" };

export function reasonKo(reason: IntakeAlertDeliveryItem["reason"]): string {
  return { SAFETY: "안전 관련 제보", LOW_RATING: "별점 낮은 리뷰", NEW_REVIEW: "새 리뷰", NEW_INQUIRY: "새 문의" }[reason];
}

export const DAY_NAMES = ["월", "화", "수", "목", "금", "토", "일"] as const;

export function minuteToTime(m: number): string {
  const h = Math.floor(m / 60);
  const mm = m % 60;
  return `${String(h).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
}

export function timeToMinute(t: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(t);
  if (!m) return null;
  const v = Number(m[1]) * 60 + Number(m[2]);
  return v >= 0 && v <= 1440 ? v : null;
}
