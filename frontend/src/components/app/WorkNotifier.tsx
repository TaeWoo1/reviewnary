import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../lib/apiClient";
import { markTold, planNotices, readNoticeSettings, readNoticeState, writeNoticeState, type WorkNotice } from "../../lib/workNotifications";

/** How often the open app looks for new work to tell the PC about. */
export const NOTIFY_POLL_MS = 2 * 60 * 1000;

/**
 * <b>PC 알림 — the leaf that tells the computer</b> (2026-10-28). Draws nothing. While SellerOps is open and the seller
 * turned notifications on and the browser allowed them, it reads what Home reads — what the AI handed over, the reviews
 * that need the seller, and the inquiries still unanswered — every {@link NOTIFY_POLL_MS}, and shows what
 * {@link planNotices} says. A click opens that work. A failed read tells nothing
 * and tries again next time; it never invents work.
 */
export function WorkNotifier() {
  const navigate = useNavigate();
  useEffect(() => {
    if (typeof window === "undefined" || !("Notification" in window)) return undefined;
    let stopped = false;
    async function tick() {
      const settings = readNoticeSettings();
      if (stopped || !settings.enabled || Notification.permission !== "granted") return;
      try {
        const [handoffs, work, inquiries] = await Promise.all([
          api.getReplyHandoffsStrict(),
          api.getReviewWorkStrict(),
          api.getInquiryQueueStrict({ size: 50 }).catch(() => null),
        ]);
        const now = Date.now();
        const planned = planNotices({
          handoffs: handoffs ?? [],
          attention: work?.attention ?? [],
          inquiries: inquiries?.content ?? [],
          state: readNoticeState(),
          now,
          hidePreview: settings.hidePreview,
        });
        const shown: WorkNotice[] = [];
        for (const notice of planned.notices) {
          try {
            const n = new Notification(notice.title, { body: notice.body, tag: notice.key });
            n.onclick = () => {
              window.focus();
              navigate(notice.href);
              n.close();
            };
            shown.push(notice);
          } catch {
            // Not shown — not recorded, so the next check tries it again.
          }
        }
        // Only what was shown is told (third outside review, 2026-10-28).
        writeNoticeState(markTold(readNoticeState(), shown, now));
      } catch {
        // Nothing read, nothing told — the next tick tries again.
      }
    }
    void tick();
    const timer = window.setInterval(() => void tick(), NOTIFY_POLL_MS);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [navigate]);
  return null;
}
