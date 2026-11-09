import { useState } from "react";
import { PageHead } from "../../components/ui/PageHead";
import { Btn } from "../../components/ui/Btn";
import { readNoticeSettings, writeNoticeSettings, type NoticeSettings } from "../../lib/workNotifications";

type Permission = NotificationPermission | "unsupported";

function permissionNow(): Permission {
  return typeof window !== "undefined" && "Notification" in window ? Notification.permission : "unsupported";
}

/**
 * <b>PC 알림 설정</b> (2026-10-28). On this computer only — the choice lives in this browser. It says plainly what the
 * notifications can and cannot do: they come while SellerOps is open, and a PC that is off neither fetches new reviews
 * nor tells anyone about them.
 */
export function NotificationSettings() {
  const [settings, setSettings] = useState<NoticeSettings>(readNoticeSettings);
  const [permission, setPermission] = useState<Permission>(permissionNow);
  const on = settings.enabled && permission === "granted";

  function save(next: NoticeSettings) {
    setSettings(next);
    writeNoticeSettings(next);
  }

  async function turnOn() {
    if (permission === "unsupported") return;
    const granted = permission === "granted" ? "granted" : await Notification.requestPermission();
    setPermission(granted);
    save({ ...settings, enabled: granted === "granted" });
  }

  return (
    <>
      <PageHead
        title="PC 알림"
        description="확인할 일이 생기면 이 컴퓨터에 알려요. 안전 관련은 하나씩, 나머지는 묶어서 알려요."
      />
      <div className="space-y-4">
        <ul aria-label="PC 알림" className="divide-y divide-line/70 overflow-hidden rounded-2xl border border-line bg-surface">
          <li className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4">
            <div className="min-w-[14rem] flex-1">
              <p className="font-semibold text-ink">알림</p>
              <p className="mt-0.5 break-keep text-sm text-muted">
                {permission === "unsupported"
                  ? "이 브라우저는 시스템 알림을 지원하지 않아요."
                  : permission === "denied"
                    ? "브라우저에서 알림이 막혀 있어요. 주소창 옆 사이트 설정에서 알림을 허용한 뒤 다시 켜 주세요."
                    : on
                      ? "켜져 있어요."
                      : "꺼져 있어요. 켜면 브라우저가 알림 허용을 물어요."}
              </p>
            </div>
            {on ? (
              <Btn onClick={() => save({ ...settings, enabled: false })}>알림 끄기</Btn>
            ) : (
              <Btn onClick={() => void turnOn()} disabled={permission === "unsupported" || permission === "denied"}>
                알림 켜기
              </Btn>
            )}
          </li>
          <li className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4">
            <label className="flex min-w-[14rem] flex-1 items-center gap-3">
              <input
                type="checkbox"
                checked={settings.hidePreview}
                onChange={(e) => save({ ...settings, hidePreview: e.target.checked })}
              />
              <span>
                <span className="block font-semibold text-ink">미리보기 숨기기</span>
                <span className="mt-0.5 block break-keep text-sm text-muted">
                  알림에 상품과 리뷰 내용을 보이지 않고 「확인할 일이 있어요」만 보여요.
                </span>
              </span>
            </label>
          </li>
        </ul>
        <ul className="space-y-1.5 break-keep text-sm text-muted">
          <li>2분마다 새 일을 확인해요. 안전 관련 리뷰는 확인될 때마다 하나씩, 나머지 리뷰·문의는 30분에 한 번 묶어서 알려요. 같은 일은 여러 목록에 있어도 한 번만 알려요.</li>
          <li>알림을 누르면 그 일로 바로 가요.</li>
          <li>
            reviewnary가 이 컴퓨터에서 열려 있을 때만 알려요. 창을 닫았거나 PC가 꺼져 있으면 새 리뷰를 가져오지도 못해서, 그동안 생긴
            일은 다시 열었을 때 홈에서 마지막 확인 시각과 함께 보여요.
          </li>
          <li>이 설정은 이 컴퓨터의 이 브라우저에만 저장돼요.</li>
        </ul>
      </div>
    </>
  );
}
