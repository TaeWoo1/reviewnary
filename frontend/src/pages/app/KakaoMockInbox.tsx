import { PageHead } from "../../components/ui/PageHead";
import { BtnLink } from "../../components/ui/Btn";
import { useApiData } from "../../lib/useApiData";
import { api } from "../../lib/apiClient";
import { statusLabel, type IntakeAlertDelivery } from "../../lib/intakeAlerts";

const TONE: Record<string, string> = {
  ok: "text-good",
  warn: "text-warn",
  bad: "text-bad",
  muted: "text-muted",
};

export function kstTime(iso: string | null): string {
  if (!iso) return "";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(iso));
}

/** One message as the phone would show it — labelled an example, never shown as something that reached KakaoTalk. */
export function MessagePreview({ d }: { d: IntakeAlertDelivery }) {
  const status = statusLabel(d.status, d.failureCode);
  const example = d.mode !== "LIVE";
  return (
    <article aria-label={`알림 ${kstTime(d.createdAt)}`} className="rounded-2xl border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="rounded-full bg-ink/5 px-2.5 py-1 text-sm text-ink">
          {example ? "카톡 발송 예시 · 모의 알림" : "카카오톡 알림톡"}
        </span>
        <time dateTime={d.createdAt} className="text-sm text-muted">
          {kstTime(d.createdAt)}
        </time>
      </div>
      <div className="mt-3 max-w-sm rounded-2xl bg-well px-4 py-3">
        <p className="whitespace-pre-line break-keep text-[15px] leading-relaxed text-ink">{d.messageText}</p>
        <BtnLink to={d.linkPath} variant="outline" className="mt-3 !min-h-11 w-full justify-center">
          확인하기
        </BtnLink>
      </div>
      <p className={`mt-3 break-keep text-sm ${TONE[status.tone]}`}>{status.text}</p>
    </article>
  );
}

/**
 * <b>모의 알림함</b> — every message the server made for this user, newest first, exactly as the template would read.
 * In mock mode nothing was sent: each card says so.
 */
export function KakaoMockInbox() {
  const { data, loading, error } = useApiData(() => api.listIntakeAlertDeliveries(30), []);
  return (
    <>
      <PageHead
        title="모의 알림함"
        description="카카오톡으로 갈 알림을 여기에 먼저 남겨요. 실제 발송이 꺼져 있는 동안에는 카톡으로 가지 않아요."
      />
      <div className="mb-4">
        <BtnLink to="/settings/notifications" variant="ghost" className="!min-h-11">
          알림 설정으로
        </BtnLink>
      </div>
      {loading && !data ? <p className="text-sm text-muted">불러오는 중…</p> : null}
      {error ? <p className="text-sm text-bad">알림함을 불러오지 못했어요.</p> : null}
      {data && data.length === 0 ? (
        <p className="break-keep text-sm text-muted">
          아직 알림이 없어요. 알림을 켠 뒤 새 리뷰나 문의가 들어오면 여기에 한 건씩 생겨요.
        </p>
      ) : null}
      <div className="grid gap-4 md:grid-cols-2">
        {(data ?? []).map((d) => (
          <MessagePreview key={d.id} d={d} />
        ))}
      </div>
    </>
  );
}
