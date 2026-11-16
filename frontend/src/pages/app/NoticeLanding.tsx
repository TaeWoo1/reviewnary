import { useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { BtnLink } from "../../components/ui/Btn";
import { useApiData } from "../../lib/useApiData";
import { api } from "../../lib/apiClient";
import { CHANNEL_KO, cancelWhy, reasonKo, statusLabel, type IntakeAlertDeliveryItem } from "../../lib/intakeAlerts";
import { kstTime } from "./KakaoMockInbox";

function itemState(i: IntakeAlertDeliveryItem): string {
  if (i.status === "CANCELLED") return `알리지 않고 닫음 · ${cancelWhy(i.cancelReason)}`;
  if (!i.targetPath) return "원본을 찾을 수 없어요";
  return i.stillOpen ? "아직 확인할 일이 남아 있어요" : "이미 처리됐어요";
}

/**
 * <b>Where an alert's 「확인하기」 lands</b> — {@code /notice/:id}, behind sign-in (a signed-out seller goes to the login
 * form and comes back here). One review or inquiry that is still open opens straight away; otherwise the alert's own
 * rows are listed with what they are now, and a way to the whole list. Opening this is not an approval.
 */
export function NoticeLanding() {
  const { deliveryId = "" } = useParams();
  const navigate = useNavigate();
  const { data, loading, error } = useApiData(() => api.getIntakeAlertDelivery(deliveryId), [deliveryId]);

  const only = data && data.items.length === 1 ? data.items[0] : null;
  useEffect(() => {
    if (only && only.stillOpen && only.targetPath && only.status !== "CANCELLED") {
      navigate(only.targetPath, { replace: true });
    }
  }, [only, navigate]);

  if (loading && !data) {
    return <p className="text-sm text-muted">알림을 여는 중…</p>;
  }
  if (error || !data) {
    return (
      <>
        <PageHead title="알림을 열 수 없어요" description="다른 계정의 알림이거나 지워진 알림이에요. 지금 로그인한 계정을 확인해 주세요." />
        <BtnLink to="/" className="!min-h-11">
          확인할 일 전체 보기
        </BtnLink>
      </>
    );
  }
  const s = statusLabel(data.delivery.status, data.delivery.failureCode);
  return (
    <>
      <PageHead
        title="알림에 담긴 일"
        description={`${kstTime(data.delivery.createdAt)} 알림 · ${s.text}`}
      />
      <ul aria-label="알림에 담긴 일" className="divide-y divide-line/70 overflow-hidden rounded-2xl border border-line bg-surface">
        {data.items.map((i) => (
          <li key={`${i.subjectKind}-${i.subjectId}`} className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4">
            <div className="min-w-[14rem] flex-1">
              <p className="font-semibold text-ink">
                {CHANNEL_KO[i.channelCode ?? ""] ?? "채널"} {reasonKo(i.reason)}
                {i.issueLabel && i.issueLabel !== "기타" ? ` · 주제 ${i.issueLabel}` : ""}
              </p>
              <p className="mt-0.5 break-keep text-sm text-muted">
                {itemState(i)} · 리뷰너리에 들어온 때 {kstTime(i.arrivedAt)}
                {i.writtenAt ? ` · 고객이 쓴 때 ${kstTime(i.writtenAt)}` : ""}
              </p>
            </div>
            {i.targetPath ? (
              <BtnLink to={i.targetPath} variant={i.stillOpen ? "solid" : "outline"} className="!min-h-11">
                {i.subjectKind === "REVIEW" ? "리뷰 열기" : "문의 열기"}
              </BtnLink>
            ) : null}
          </li>
        ))}
      </ul>
      <div className="mt-4 flex flex-wrap gap-3">
        <BtnLink to="/" variant="ghost" className="!min-h-11">
          확인할 일 전체 보기
        </BtnLink>
      </div>
      <p className="mt-3 break-keep text-sm text-muted">알림을 연 것만으로 답변이 승인되거나 등록되지 않아요.</p>
    </>
  );
}
