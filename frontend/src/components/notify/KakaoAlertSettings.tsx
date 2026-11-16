import { useEffect, useState } from "react";
import { Btn, BtnLink } from "../ui/Btn";
import { Disclosure } from "../ui/Disclosure";
import { api } from "../../lib/apiClient";
import {
  DAY_NAMES,
  minuteToTime,
  timeToMinute,
  type IntakeAlertSettings,
  type IntakeAlertSettingsRequest,
} from "../../lib/intakeAlerts";

function apiMessage(e: unknown): string {
  const data = (e as { response?: { data?: { message?: string; detail?: string } } })?.response?.data;
  return data?.detail ?? data?.message ?? "저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}

export function senderLine(s: IntakeAlertSettings["sender"]): { text: string; tone: string } {
  if (s.mode === "LIVE") return { text: "실제 카카오톡 발송이 켜져 있어요", tone: "bg-good/10 text-good" };
  if (s.mode === "BLOCKED")
    return { text: "실제 발송을 요청했지만 설정이 끝나지 않아 보내지 않아요", tone: "bg-warn/10 text-warn" };
  return { text: "모의 알림 · 실제 카카오톡으로 보내지 않아요", tone: "bg-ink/5 text-muted" };
}

/**
 * <b>카카오톡 접수 알림</b> (2026-11-16, unit ③). Server-side, per signed-in user. Default: one message per new review
 * or inquiry, at once, at any hour — bundling and a receiving window are opt-in. The text never promises more than the
 * server does: what is told, what is not, and the limits of the catch-up check.
 */
export function KakaoAlertSettings() {
  const [s, setS] = useState<IntakeAlertSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [phone, setPhone] = useState("");
  const [consent, setConsent] = useState(false);
  const [start, setStart] = useState("09:00");
  const [end, setEnd] = useState("18:00");

  useEffect(() => {
    let alive = true;
    api
      .getIntakeAlertSettings()
      .then((v) => {
        if (!alive) return;
        setS(v);
        setStart(minuteToTime(v.startMinute));
        setEnd(minuteToTime(v.endMinute));
      })
      .catch(() => alive && setError("알림 설정을 불러오지 못했어요."));
    return () => {
      alive = false;
    };
  }, []);

  async function save(body: IntakeAlertSettingsRequest, done: string) {
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const v = await api.updateIntakeAlertSettings(body);
      setS(v);
      setSaved(done);
      return true;
    } catch (e) {
      setError(apiMessage(e));
      return false;
    } finally {
      setBusy(false);
    }
  }

  if (!s) {
    return (
      <section aria-labelledby="kakao-alert-title" className="space-y-3">
        <h2 id="kakao-alert-title" className="text-lg font-semibold text-ink">
          카카오톡 알림
        </h2>
        <p className="text-sm text-muted">{error ?? "불러오는 중…"}</p>
      </section>
    );
  }

  const line = senderLine(s.sender);
  const limits = s.limits;

  return (
    <section aria-labelledby="kakao-alert-title" className="space-y-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <h2 id="kakao-alert-title" className="text-lg font-semibold text-ink">
          카카오톡 알림
        </h2>
        <span className={`rounded-full px-2.5 py-1 text-sm ${line.tone}`}>{line.text}</span>
      </div>
      <p className="break-keep text-sm text-muted">
        새 리뷰나 문의가 리뷰너리에 들어올 때마다 한 건씩 알려요. 알림에는 채널·유형·주제와 할 일만 적고, 고객이 쓴 글·이름·번호·주문번호는
        넣지 않아요. 확인과 승인은 알림의 링크를 눌러 서비스 안에서 해요.
      </p>

      <ul aria-label="카카오톡 알림 설정" className="divide-y divide-line/70 overflow-hidden rounded-2xl border border-line bg-surface">
        <li className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4">
          <div className="min-w-[14rem] flex-1">
            <p className="font-semibold text-ink">알림</p>
            <p className="mt-0.5 break-keep text-sm text-muted">
              {s.enabled
                ? `켜져 있어요. ${s.watchFrom ? "켠 뒤 들어온 것만 알려요." : ""}`
                : "꺼져 있어요. 켜면 그때부터 들어오는 리뷰·문의만 알려요 — 이전 자료는 알리지 않아요."}
            </p>
          </div>
          <Btn
            className="!min-h-11"
            disabled={busy}
            onClick={() => void save({ enabled: !s.enabled }, s.enabled ? "알림을 껐어요. 기다리던 알림은 보내지 않고 닫았어요." : "알림을 켰어요.")}
          >
            {s.enabled ? "알림 끄기" : "알림 켜기"}
          </Btn>
        </li>
        <li className="flex flex-wrap gap-x-6 gap-y-3 px-5 py-4">
          <p className="w-full font-semibold text-ink sm:w-auto sm:min-w-[8rem]">알릴 것</p>
          <div className="flex flex-wrap gap-x-6 gap-y-2">
            <label className="flex min-h-11 items-center gap-2">
              <input
                type="checkbox"
                checked={s.notifyReviews}
                disabled={busy}
                onChange={(e) => void save({ notifyReviews: e.target.checked }, "저장했어요.")}
              />
              <span>새 리뷰</span>
            </label>
            <label className="flex min-h-11 items-center gap-2">
              <input
                type="checkbox"
                checked={s.notifyInquiries}
                disabled={busy}
                onChange={(e) => void save({ notifyInquiries: e.target.checked }, "저장했어요.")}
              />
              <span>새 문의</span>
            </label>
          </div>
        </li>
        <li className="space-y-3 px-5 py-4">
          <div>
            <p className="font-semibold text-ink">받을 번호</p>
            <p className="mt-0.5 break-keep text-sm text-muted">
              {s.phoneMasked
                ? `${s.phoneMasked} · 수신과 번호 저장에 동의했어요. 번호는 암호화해 저장하고 끝 네 자리만 보여요.`
                : "아직 없어요. 모의 알림은 번호 없이도 아래 알림함에서 볼 수 있어요."}
            </p>
            {!s.numberStorageAvailable ? (
              <p className="mt-1 break-keep text-sm text-warn">이 서버에는 암호화 키가 없어 번호를 저장할 수 없어요.</p>
            ) : null}
          </div>
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              void save({ phone, phoneConsent: consent }, "번호를 저장했어요. 바꾸기 전 번호로 기다리던 알림은 보내지 않아요.").then(
                (ok) => ok && setPhone(""),
              );
            }}
          >
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted">휴대폰 번호</span>
              <input
                inputMode="tel"
                autoComplete="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="010-0000-0000"
                className="min-h-11 w-52 rounded-xl border border-line bg-canvas px-3 text-ink"
              />
            </label>
            <label className="flex min-h-11 max-w-md items-center gap-2 text-sm">
              <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              <span className="break-keep">이 번호로 접수 알림을 받고, 알림 발송을 위해 번호를 저장하는 데 동의해요.</span>
            </label>
            <Btn className="!min-h-11" type="submit" disabled={busy || phone.trim() === "" || !consent || !s.numberStorageAvailable}>
              번호 저장
            </Btn>
            {s.phoneMasked ? (
              <Btn
                className="!min-h-11"
                variant="ghost"
                disabled={busy}
                onClick={() => void save({ phoneConsent: false }, "동의를 철회하고 번호를 지웠어요. 기다리던 알림은 닫았어요.")}
              >
                동의 철회·번호 지우기
              </Btn>
            ) : null}
          </form>
        </li>
      </ul>

      <Disclosure label="보내는 방식 바꾸기 (선택)" summaryClassName="min-h-11">
        <div className="space-y-4 pt-3">
          <fieldset className="space-y-2">
            <legend className="font-semibold text-ink">보내는 방식</legend>
            <label className="flex min-h-11 items-center gap-2">
              <input
                type="radio"
                name="delivery-mode"
                checked={s.deliveryMode === "EACH"}
                disabled={busy}
                onChange={() => void save({ deliveryMode: "EACH" }, "건마다 바로 알려요.")}
              />
              <span>건마다 바로 (기본)</span>
            </label>
            <label className="flex min-h-11 flex-wrap items-center gap-2">
              <input
                type="radio"
                name="delivery-mode"
                checked={s.deliveryMode === "DIGEST"}
                disabled={busy}
                onChange={() => void save({ deliveryMode: "DIGEST" }, "묶어서 알려요.")}
              />
              <span>모아서</span>
              <select
                aria-label="묶음 간격"
                value={s.digestMinutes}
                disabled={busy || s.deliveryMode !== "DIGEST"}
                onChange={(e) => void save({ digestMinutes: Number(e.target.value) }, "저장했어요.")}
                className="min-h-11 rounded-xl border border-line bg-canvas px-2"
              >
                {[30, 60, 120, 240].map((m) => (
                  <option key={m} value={m}>
                    {m < 60 ? `${m}분` : `${m / 60}시간`}마다
                  </option>
                ))}
              </select>
            </label>
          </fieldset>
          <fieldset className="space-y-2">
            <legend className="font-semibold text-ink">받을 시간</legend>
            <label className="flex min-h-11 items-center gap-2">
              <input
                type="checkbox"
                checked={s.quietHoursEnabled}
                disabled={busy}
                onChange={(e) => void save({ quietHoursEnabled: e.target.checked }, "저장했어요.")}
              />
              <span className="break-keep">정한 시간에만 받기 (끄면 시간 제한 없음 · 기본)</span>
            </label>
            {s.quietHoursEnabled ? (
              <div className="space-y-3">
                <div className="flex flex-wrap gap-2" role="group" aria-label="받을 요일">
                  {DAY_NAMES.map((d, i) => {
                    const bit = 1 << i;
                    const on = (s.daysMask & bit) !== 0;
                    return (
                      <Btn
                        key={d}
                        size="sm"
                        variant={on ? "selected" : "outline"}
                        aria-pressed={on}
                        className="!min-h-11 min-w-11"
                        disabled={busy || (on && s.daysMask === bit)}
                        onClick={() => void save({ daysMask: s.daysMask ^ bit }, "저장했어요.")}
                      >
                        {d}
                      </Btn>
                    );
                  })}
                </div>
                <form
                  className="flex flex-wrap items-end gap-3"
                  onSubmit={(e) => {
                    e.preventDefault();
                    const a = timeToMinute(start);
                    const b = timeToMinute(end);
                    if (a === null || b === null) {
                      setError("시각은 09:00처럼 적어 주세요.");
                      return;
                    }
                    void save({ startMinute: a, endMinute: b }, "저장했어요.");
                  }}
                >
                  <label className="flex flex-col gap-1 text-sm">
                    <span className="text-muted">시작</span>
                    <input type="time" value={start} onChange={(e) => setStart(e.target.value)} className="min-h-11 rounded-xl border border-line bg-canvas px-2" />
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    <span className="text-muted">끝</span>
                    <input type="time" value={end} onChange={(e) => setEnd(e.target.value)} className="min-h-11 rounded-xl border border-line bg-canvas px-2" />
                  </label>
                  <Btn className="!min-h-11" type="submit" variant="outline" disabled={busy}>
                    시간 저장
                  </Btn>
                </form>
                <p className="break-keep text-sm text-muted">
                  시간 밖에 들어온 일반 알림은 기다렸다가, 시간이 시작되면 아직 처리하지 않은 것만 한 번에 모아 알려요. 안전 관련 제보는 시간과
                  상관없이 바로 알려요.
                </p>
              </div>
            ) : null}
          </fieldset>
        </div>
      </Disclosure>

      {error ? (
        <p role="alert" className="break-keep text-sm text-bad">
          {error}
        </p>
      ) : null}
      {saved ? (
        <p role="status" className="break-keep text-sm text-good">
          {saved}
        </p>
      ) : null}

      <div className="rounded-2xl border border-line bg-well/60 px-5 py-4">
        <p className="font-semibold text-ink">지금까지</p>
        <p className="mt-1 break-keep text-sm text-muted">
          알림에 담긴 것 {s.counts.told}건 · 기다리는 것 {s.counts.pending}건 · 알리기 전에 이미 처리된 것 {s.counts.handledBeforeTold}건 · 늦게
          가져온 예전 글 {s.counts.oldNotTold}건(알리지 않음) · 설정 변경으로 닫은 것 {s.counts.cancelled}건
        </p>
        <ul className="mt-3 list-disc space-y-1.5 pl-5 break-keep text-sm text-muted">
          <li>
            알림 기준은 리뷰너리에 들어온 때예요. 들어온 시각보다 고객이 {limits.oldAfterHours}시간 넘게 먼저 쓴 글(수집을 쉬었다가 한꺼번에 가져온
            예전 글)은 새 접수로 알리지 않고 개수만 세요.
          </li>
          <li>
            알림 이벤트가 빠져도 {limits.passSeconds}초마다 원본 자료를 다시 확인해 찾아요. 다만 들어온 지 {limits.recoveryHours}시간이 지난 자료는 다시 찾지
            않아서, 서버가 그보다 오래 멈춰 있었다면 놓칠 수 있어요.
          </li>
          <li>한 번에 많이 들어오면 묶지 않고 {limits.perPassCap}건씩 나눠서 차례로 보내요.</li>
          <li>공급자가 접수해도 휴대폰 도착까지 확인한 것은 아니에요. 결과를 모르는 알림은 두 번 가지 않도록 자동으로 다시 보내지 않아요.</li>
        </ul>
        <div className="mt-3">
          <BtnLink to="/settings/notifications/kakao" variant="outline" className="!min-h-11">
            모의 알림함 보기
          </BtnLink>
        </div>
      </div>
    </section>
  );
}
