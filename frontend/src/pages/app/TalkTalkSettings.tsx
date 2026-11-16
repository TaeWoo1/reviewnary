import { useEffect, useState } from "react";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { PageHead } from "../../components/ui/PageHead";
import { Status } from "../../components/ui/Status";
import { api } from "../../lib/apiClient";
import {
  WEEKDAYS,
  apiMessage,
  kstTime,
  type ClosedDate,
  type GuidanceView,
  type TalkTalkStatus,
} from "../../lib/talktalk";

const INPUT =
  "min-h-11 rounded-md border border-line bg-surface px-3 py-2 text-base text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700";

/**
 * <b>톡톡 연동 · 배송 기준 · 자동 안내</b> (docs/inquiry/talktalk_chat_lane_v1.md §3, §6). Connecting, confirming a
 * guidance version and delegating are the owner's (the server checks). Default: not connected, fake transport,
 * automatic guidance off. Live readiness is a checklist the server computes; nothing here can switch it on.
 */
export function TalkTalkSettings() {
  const [s, setS] = useState<TalkTalkStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [webhook, setWebhook] = useState<{ url: string; notice: string } | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .getTalkTalkStatus()
      .then((v) => alive && setS(v))
      .catch(() => alive && setError("톡톡 연동 상태를 불러오지 못했어요."));
    return () => {
      alive = false;
    };
  }, []);

  async function run(fn: () => Promise<TalkTalkStatus>, message: string) {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      setS(await fn());
      setDone(message);
    } catch (e) {
      setError(apiMessage(e));
    } finally {
      setBusy(false);
    }
  }

  if (!s) {
    return (
      <div className="space-y-6">
        <PageHead title="톡톡 연동" />
        <p className="text-sm text-muted">{error ?? "불러오는 중…"}</p>
      </div>
    );
  }

  return (
    <div className="space-y-6" data-testid="talktalk-settings">
      <PageHead
        title="톡톡 연동"
        description="네이버 톡톡 챗봇 API로 상담을 받고 답해요. 커머스 API의 문의와는 다른 연동이에요."
        action={
          <BtnLink to="/talktalk" variant="outline" size="sm">
            톡톡 상담 열기
          </BtnLink>
        }
      />
      <p className="break-keep text-sm text-muted">{s.laneNotice}</p>
      {error ? (
        <p role="alert" className="break-keep text-sm font-semibold text-bad">
          {error}
        </p>
      ) : null}
      {done ? (
        <p role="status" className="break-keep text-sm text-good">
          {done}
        </p>
      ) : null}

      <ConnectionSection
        s={s}
        busy={busy}
        webhook={webhook}
        onConnect={async (partner, key) => {
          setBusy(true);
          setError(null);
          setDone(null);
          try {
            const r = await api.connectTalkTalk(partner, key);
            setS(r.status);
            setWebhook({ url: r.webhookUrl, notice: r.webhookNotice });
            setDone("연동했어요.");
            return true;
          } catch (e) {
            setError(apiMessage(e));
            return false;
          } finally {
            setBusy(false);
          }
        }}
        onRotate={async () => {
          setBusy(true);
          setError(null);
          try {
            const r = await api.rotateTalkTalkWebhook();
            setS(r.status);
            setWebhook({ url: r.webhookUrl, notice: r.webhookNotice });
          } catch (e) {
            setError(apiMessage(e));
          } finally {
            setBusy(false);
          }
        }}
        onDisconnect={() => run(() => api.disconnectTalkTalk(), "연동을 해제했어요. 대기 중이던 발신은 모두 취소했어요.")}
      />

      <GuidanceSection s={s} busy={busy} run={run} setError={setError} />

      <DelegationSection s={s} busy={busy} run={run} />
    </div>
  );
}

function ConnectionSection(props: {
  s: TalkTalkStatus;
  busy: boolean;
  webhook: { url: string; notice: string } | null;
  onConnect: (partner: string, key: string) => Promise<boolean>;
  onRotate: () => void;
  onDisconnect: () => void;
}) {
  const { s } = props;
  const [partner, setPartner] = useState("");
  const [key, setKey] = useState("");
  return (
    <section aria-labelledby="tt-conn" className="space-y-3 rounded-xl border border-line bg-surface px-4 py-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 id="tt-conn" className="text-section font-bold text-ink">
          연동
        </h2>
        <Status tone={s.connection.connected ? "good" : "neutral"}>{s.connection.connected ? "연동됨" : "연동 안 됨"}</Status>
        {s.connection.fake ? (
          <Status tone="warn" variant="chip">
            모의 전송 — 실제 톡톡으로 가지 않아요
          </Status>
        ) : null}
      </div>
      {s.connection.connected ? (
        <p className="break-keep text-sm text-muted">
          파트너 {s.connection.partner} · 마지막 이벤트 {kstTime(s.connection.lastEventAt)} · 보내기 API 인증 키 등록됨
        </p>
      ) : null}
      {props.webhook ? (
        <div className="space-y-1 rounded-lg border border-line bg-canvas px-3 py-3" data-testid="tt-webhook-once">
          <p className="break-keep text-sm font-semibold text-ink">{props.webhook.notice}</p>
          <p className="select-all break-all text-sm text-ink">{props.webhook.url}</p>
        </div>
      ) : null}
      <form
        className="grid gap-3 md:grid-cols-2"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await props.onConnect(partner, key)) {
            setKey("");
          }
        }}
      >
        <label className="space-y-1">
          <span className="block text-sm text-muted">파트너 아이디 (예: wc1234)</span>
          <input className={`${INPUT} w-full`} value={partner} onChange={(e) => setPartner(e.target.value)} autoComplete="off" />
        </label>
        <label className="space-y-1">
          <span className="block text-sm text-muted">보내기 API 인증 키 (저장 후 다시 보이지 않아요)</span>
          <input
            className={`${INPUT} w-full`}
            type="password"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            autoComplete="off"
            data-testid="tt-key"
          />
        </label>
        <div className="flex flex-wrap gap-2 md:col-span-2">
          <Btn type="submit" disabled={props.busy}>
            {s.connection.connected ? "다시 연동하기" : "연동하기"}
          </Btn>
          {s.connection.connected ? (
            <>
              <Btn variant="outline" disabled={props.busy} onClick={props.onRotate}>
                받는 주소 새로 만들기
              </Btn>
              <Btn variant="ghost" disabled={props.busy} onClick={props.onDisconnect}>
                연동 해제
              </Btn>
            </>
          ) : null}
        </div>
        {s.connection.connected ? (
          <p className="break-keep text-sm text-muted md:col-span-2">
            다시 연동하거나 주소를 새로 만들면 대기 중이던 발신은 모두 취소되고, 실제 전송 준비의 첫 이벤트 확인도 처음부터 다시 해요.
          </p>
        ) : null}
      </form>
      <div className="space-y-1">
        <h3 className="text-lg font-semibold text-ink">실제 전송 준비</h3>
        <ul className="space-y-1 text-sm" data-testid="tt-readiness">
          {s.readiness.items.map((i) => (
            <li key={i.code} className="flex items-start gap-2 break-keep">
              <Status tone={i.met ? "good" : "warn"} variant="word">
                {i.met ? "됨" : "아직"}
              </Status>
              <span className="text-ink">{i.label}</span>
            </li>
          ))}
        </ul>
        <p className="break-keep text-sm text-muted">
          {s.readiness.ready ? "준비가 끝났어요." : "모두 갖추기 전에는 실제 톡톡으로 보내지 않아요."} {s.readiness.residualRisk}
        </p>
      </div>
    </section>
  );
}

function GuidanceSection(props: {
  s: TalkTalkStatus;
  busy: boolean;
  run: (fn: () => Promise<TalkTalkStatus>, message: string) => Promise<void>;
  setError: (v: string | null) => void;
}) {
  const base = props.s.draftGuidance ?? props.s.currentGuidance;
  const [hour, setHour] = useState<string>(base?.cutoffHour != null ? String(base.cutoffHour) : "12");
  const [minute, setMinute] = useState<string>(base ? String(base.cutoffMinute) : "0");
  const [days, setDays] = useState<string[]>(base?.dispatchDays.length ? base.dispatchDays : ["MON", "TUE", "WED", "THU", "FRI"]);
  const [closed, setClosed] = useState<ClosedDate[]>(base?.closedDates ?? []);
  const [through, setThrough] = useState<string>(base?.confirmedThrough ?? "");
  const [newDate, setNewDate] = useState("");
  const [newNote, setNewNote] = useState("");
  const [draft, setDraft] = useState<GuidanceView | null>(props.s.draftGuidance);
  const current = props.s.currentGuidance;

  async function save() {
    props.setError(null);
    try {
      const g = await api.saveTalkTalkGuidance({
        cutoffHour: hour === "" ? null : Number(hour),
        cutoffMinute: minute === "" ? 0 : Number(minute),
        dispatchDays: days,
        closedDates: closed,
        confirmedThrough: through || null,
      });
      setDraft(g);
    } catch (e) {
      props.setError(apiMessage(e));
    }
  }

  return (
    <section aria-labelledby="tt-guidance" className="space-y-3 rounded-xl border border-line bg-surface px-4 py-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 id="tt-guidance" className="text-section font-bold text-ink">
          배송 기준 (휴무·출고 마감)
        </h2>
        {current ? (
          <Status tone="good">확정 v{current.version}</Status>
        ) : (
          <Status tone="neutral">확정된 기준 없음</Status>
        )}
      </div>
      <p className="break-keep text-sm text-muted">
        재고가 있는 일반 상품의 기준이에요. 주문 제작·예약·재고 확인 상품이나 기준이 다른 상품은 따로 확인해요. 공휴일은 직접 휴무일에 넣어 주세요.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="space-y-1">
          <span className="block text-sm text-muted">출고 마감 (시)</span>
          <input className={`${INPUT} w-24`} inputMode="numeric" value={hour} onChange={(e) => setHour(e.target.value)} />
        </label>
        <label className="space-y-1">
          <span className="block text-sm text-muted">분</span>
          <input className={`${INPUT} w-24`} inputMode="numeric" value={minute} onChange={(e) => setMinute(e.target.value)} />
        </label>
        <fieldset className="space-y-1">
          <legend className="text-sm text-muted">출고 요일</legend>
          <div className="flex flex-wrap gap-2">
            {WEEKDAYS.map((w) => (
              <label key={w.code} className="flex min-h-11 items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  checked={days.includes(w.code)}
                  onChange={(e) => setDays(e.target.checked ? [...days, w.code] : days.filter((x) => x !== w.code))}
                />
                {w.label}
              </label>
            ))}
          </div>
        </fieldset>
      </div>
      <div className="space-y-2">
        <p className="text-sm font-semibold text-ink">휴무일</p>
        <ul className="space-y-1 text-sm" data-testid="tt-closed">
          {closed.map((c) => (
            <li key={c.date} className="flex flex-wrap items-center gap-2">
              <span className="tabular-nums text-ink">{c.date}</span>
              <span className="text-muted">{c.note}</span>
              <Btn variant="ghost" size="sm" onClick={() => setClosed(closed.filter((x) => x.date !== c.date))}>
                빼기
              </Btn>
            </li>
          ))}
        </ul>
        <div className="flex flex-wrap items-end gap-2">
          <input className={INPUT} type="date" value={newDate} onChange={(e) => setNewDate(e.target.value)} aria-label="휴무일 날짜" />
          <input className={INPUT} value={newNote} maxLength={40} onChange={(e) => setNewNote(e.target.value)} placeholder="메모 (예: 금요일 휴무)" aria-label="휴무일 메모" />
          <Btn
            variant="outline"
            size="sm"
            onClick={() => {
              if (newDate && !closed.some((c) => c.date === newDate)) {
                setClosed([...closed, { date: newDate, note: newNote }].sort((a, b) => a.date.localeCompare(b.date)));
                setNewDate("");
                setNewNote("");
              }
            }}
          >
            휴무일 추가
          </Btn>
        </div>
        <label className="block space-y-1">
          <span className="block text-sm text-muted">이 날까지 휴무일을 빠짐없이 적었어요 (이 날 뒤의 출고일은 자동으로 답하지 않아요)</span>
          <input className={INPUT} type="date" value={through} onChange={(e) => setThrough(e.target.value)} />
        </label>
      </div>
      <div className="flex flex-wrap gap-2">
        <Btn variant="outline" disabled={props.busy} onClick={() => void save()}>
          작성 중인 기준 저장
        </Btn>
        {draft ? (
          <Btn
            disabled={props.busy}
            onClick={() => void props.run(() => api.confirmTalkTalkGuidance(draft.id, draft.fingerprint), `배송 기준 v${draft.version}을 확정했어요. 자동 안내는 다시 켜야 해요.`)}
            data-testid="tt-confirm-guidance"
          >
            v{draft.version} 확정하기
          </Btn>
        ) : null}
        {current ? (
          <Btn variant="ghost" disabled={props.busy} onClick={() => void props.run(() => api.retireTalkTalkGuidance(current.id), "확정 기준을 내렸어요. 자동 안내도 멈췄어요.")}>
            확정 기준 내리기
          </Btn>
        ) : null}
      </div>
      {draft ? <Previews title={`v${draft.version} (작성 중) 미리보기`} g={draft} /> : null}
    </section>
  );
}

function Previews({ title, g }: { title: string; g: GuidanceView }) {
  return (
    <div className="space-y-2" data-testid="tt-previews">
      <p className="text-sm font-semibold text-ink">{title}</p>
      {g.previews.map((p) => (
        <div key={p.label} className="rounded-lg border border-line bg-canvas px-3 py-2">
          <p className="text-sm text-muted">{p.label}</p>
          {p.text ? (
            <p className="whitespace-pre-wrap break-keep text-base text-ink">{p.text}</p>
          ) : (
            <p className="break-keep text-sm font-semibold text-warn">자동으로 답하지 않아요 · {p.refusal?.label}</p>
          )}
        </div>
      ))}
    </div>
  );
}

function DelegationSection(props: {
  s: TalkTalkStatus;
  busy: boolean;
  run: (fn: () => Promise<TalkTalkStatus>, message: string) => Promise<void>;
}) {
  const { s } = props;
  const current = s.currentGuidance;
  const d = s.delegation;
  const [seen, setSeen] = useState(false);
  const [ask, setAsk] = useState(false);
  return (
    <section aria-labelledby="tt-auto" className="space-y-3 rounded-xl border border-line bg-surface px-4 py-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 id="tt-auto" className="text-section font-bold text-ink">
          배송 기준 자동 안내
        </h2>
        <span data-testid="tt-delegation">
          <Status tone={d.active ? "good" : "neutral"}>{d.active ? `켜짐 · v${d.guidanceVersion}` : "꺼짐"}</Status>
        </span>
      </div>
      {!d.active && d.reason ? <p className="break-keep text-sm text-muted">{d.reason.label}</p> : null}
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <p className="text-sm font-semibold text-ink">자동으로 답하는 것</p>
          <ul className="list-disc space-y-1 pl-6 text-sm text-ink">
            {d.answeredAutomatically.map((t) => (
              <li key={t} className="break-keep">
                {t}
              </li>
            ))}
          </ul>
        </div>
        <div>
          <p className="text-sm font-semibold text-ink">사람에게 넘기는 것</p>
          <ul className="list-disc space-y-1 pl-6 text-sm text-ink">
            {d.handedToPerson.map((t) => (
              <li key={t} className="break-keep">
                {t}
              </li>
            ))}
          </ul>
        </div>
      </div>
      {current ? <Previews title={`확정 v${current.version}로 보낼 문장`} g={current} /> : (
        <p className="break-keep text-sm text-muted">확정된 배송 기준이 있어야 켤 수 있어요.</p>
      )}
      {current && !d.active ? (
        <>
          <label className="flex min-h-11 items-start gap-2 text-sm">
            <input type="checkbox" checked={seen} onChange={(e) => setSeen(e.target.checked)} />
            <span className="break-keep">위 문장을 확인했고, 이 기준으로 위 범위의 질문에만 개별 승인 없이 보내는 데 동의해요.</span>
          </label>
          <Btn
            disabled={props.busy}
            onClick={() =>
              !seen ? setAsk(true) : void props.run(
                () =>
                  api.setTalkTalkDelegation({
                    enabled: true,
                    guidanceId: current.id,
                    guidanceFingerprint: current.fingerprint,
                    previewFingerprint: current.previewFingerprint,
                  }),
                "자동 안내를 켰어요.",
              )
            }
            data-testid="tt-delegate-on"
          >
            자동 안내 켜기
          </Btn>
          {ask && !seen ? <p className="break-keep text-sm text-warn">위 확인란을 체크해야 켤 수 있어요.</p> : null}
        </>
      ) : null}
      {d.active ? (
        <Btn
          variant="outline"
          disabled={props.busy}
          onClick={() =>
            void props.run(
              () => api.setTalkTalkDelegation({ enabled: false, guidanceId: null, guidanceFingerprint: null, previewFingerprint: null }),
              "자동 안내를 껐어요. 대기 중이던 안내는 보내지 않아요.",
            )
          }
          data-testid="tt-delegate-off"
        >
          자동 안내 끄기
        </Btn>
      ) : null}
    </section>
  );
}
