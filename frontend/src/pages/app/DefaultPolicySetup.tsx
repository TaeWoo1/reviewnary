import { useEffect, useState } from "react";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { PageHead } from "../../components/ui/PageHead";
import { Status } from "../../components/ui/Status";
import { backendMessage } from "../../components/connect/channelShared";
import { api } from "../../lib/apiClient";
import {
  DEFAULT_STATUS_WORD,
  inUse,
  type DefaultPolicyAnswer,
  type DefaultPolicyItem,
  type DefaultPolicyOverview,
} from "../../lib/defaultPolicy";

const INPUT =
  "min-h-11 rounded-md border border-line bg-surface px-3 py-2 text-base text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700";

/**
 * 기본 설정 — for a seller who has no manual (owner decision 2026-11-17).
 *
 * <b>Only what the seller confirms answers customers.</b> Each question is typed, then confirmed on its own; a
 * typed-only answer is a draft and nothing reads it. A question never confirmed is absent, so an inquiry that needs
 * it goes to a person. The 법정 기준 line beside a question is a hint about the law, never the shop's answer.
 *
 * <b>출고 마감 and 휴무일 are not typed here.</b> They are the TalkTalk shipping guidance — one place, one value — and
 * this screen shows them and links there.
 */
export function DefaultPolicySetup() {
  const [view, setView] = useState<DefaultPolicyOverview | null>(null);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    let active = true;
    void api
      .getDefaultPolicy()
      .then((v) => active && setView(v))
      .catch(() => active && setLoadError(true));
    return () => {
      active = false;
    };
  }, []);

  const groups: { key: string; label: string; items: DefaultPolicyItem[] }[] = [];
  for (const item of view?.items ?? []) {
    let g = groups.find((x) => x.key === item.group);
    if (!g) {
      g = { key: item.group, label: item.groupLabel, items: [] };
      groups.push(g);
    }
    g.items.push(item);
  }

  return (
    <>
      <PageHead
        title="기본 설정"
        description="매뉴얼이 없어도 괜찮아요. 고객이 자주 묻는 것에 답하고 하나씩 확정하면, 확정한 답만 답변에 써요."
      />
      {loadError ? (
        <p className="text-warn" role="alert">기본 설정을 불러오지 못했어요.</p>
      ) : view === null ? (
        <p className="text-muted">불러오는 중…</p>
      ) : (
        <div className="max-w-[760px] space-y-6">
          <p className="break-keep text-sm text-muted" data-testid="dp-progress">
            {view.total}개 중 <b className="font-semibold text-ink">{view.confirmed}개</b>를 확정했어요. 확정하지 않은 질문이
            들어오면 담당자에게 넘겨요.
          </p>
          {groups.map((g) => (
            <section key={g.key} aria-labelledby={`dp-${g.key}`} className="space-y-2">
              <h2 id={`dp-${g.key}`} className="text-section font-bold text-ink">
                {g.label}
              </h2>
              <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
                {g.items.map((item) => (
                  <li key={item.key} className="px-4 py-4">
                    {item.shared ? <SharedRow item={item} /> : <AnswerRow item={item} onChanged={setView} />}
                  </li>
                ))}
              </ul>
            </section>
          ))}
          {view.documents.length > 0 ? (
            <section aria-labelledby="dp-docs" className="space-y-2">
              <h2 id="dp-docs" className="text-section font-bold text-ink">
                답변에 쓰이는 문서
              </h2>
              <ul className="space-y-1 text-sm" data-testid="dp-documents">
                {view.documents.map((d) => (
                  <li key={d.sourceId} className="flex flex-wrap items-center gap-2">
                    <span className="text-ink">{d.title}</span>
                    <span className="tabular-nums text-muted">{d.version}판</span>
                    <Status tone={d.active ? "good" : "neutral"}>{d.active ? "사용 중" : "꺼짐 (확정한 답 없음)"}</Status>
                  </li>
                ))}
              </ul>
              <p className="break-keep text-sm text-muted">
                올려 둔 매뉴얼과 값이 다르면 자료 화면의 「자료끼리 다른 값」에 나와요.
              </p>
            </section>
          ) : null}
        </div>
      )}
    </>
  );
}

function StatusChip({ item }: { item: DefaultPolicyItem }) {
  const tone = item.status === "CONFIRMED" ? "good" : item.status === "CHANGED" ? "warn" : "neutral";
  return <Status tone={tone}>{DEFAULT_STATUS_WORD[item.status]}</Status>;
}

function Head({ item }: { item: DefaultPolicyItem }) {
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <h3 className="font-semibold text-ink">{item.label}</h3>
        <StatusChip item={item} />
      </div>
      <p className="break-keep text-sm text-muted">{item.question}</p>
    </div>
  );
}

/** 출고 마감 · 휴무일: the TalkTalk guidance's value, changed there. */
function SharedRow({ item }: { item: DefaultPolicyItem }) {
  return (
    <div className="space-y-2" data-testid={`dp-row-${item.key}`}>
      <Head item={item} />
      {item.sentence ? (
        <p className="break-keep text-ink">{item.sentence}</p>
      ) : (
        <p className="break-keep text-sm text-muted">아직 정하지 않았어요.</p>
      )}
      <p className="break-keep text-sm text-muted">출고 마감과 휴무일은 톡톡 설정의 배송 기준과 같은 값이에요. 한 곳에서만 바꿔요.</p>
      <BtnLink to="/settings/talktalk" variant="outline">
        배송 기준 바꾸기
      </BtnLink>
    </div>
  );
}

function AnswerRow({ item, onChanged }: { item: DefaultPolicyItem; onChanged: (v: DefaultPolicyOverview) => void }) {
  const numeric = item.kind === "DAYS" || item.kind === "WON";
  const [num, setNum] = useState(item.draftNumber == null ? "" : String(item.draftNumber));
  const [text, setText] = useState(item.draftText ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const answer = (): DefaultPolicyAnswer | null => {
    const t = text.trim() || null;
    if (!numeric) return { number: null, text: t };
    if (num.trim() === "") return { number: null, text: t };
    const n = Number(num.replace(/,/g, ""));
    return Number.isInteger(n) ? { number: n, text: t } : null;
  };

  const run = async (work: () => Promise<DefaultPolicyOverview>, done: string) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      onChanged(await work());
      setNotice(done);
    } catch (e) {
      setError(backendMessage(e) ?? "저장하지 못했어요. 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  };

  const save = () => {
    const a = answer();
    if (!a) {
      setError("숫자만 적어 주세요.");
      return;
    }
    void run(() => api.saveDefaultPolicyDraft(item.key, a), "저장했어요. 확정하기 전에는 답변에 쓰지 않아요.");
  };

  const confirm = () => {
    const a = answer();
    if (!a) {
      setError("숫자만 적어 주세요.");
      return;
    }
    void run(async () => {
      const saved = await api.saveDefaultPolicyDraft(item.key, a);
      const mine = saved.items.find((i) => i.key === item.key);
      return api.confirmDefaultPolicy(item.key, { number: mine?.draftNumber ?? null, text: mine?.draftText ?? null });
    }, "확정했어요. 이제 이 답을 고객 답변에 써요.");
  };

  const unit = item.kind === "DAYS" ? "일" : item.kind === "WON" ? "원" : null;
  const empty = numeric ? num.trim() === "" : text.trim() === "";

  return (
    <div className="space-y-3" data-testid={`dp-row-${item.key}`}>
      <Head item={item} />
      {item.legal ? (
        <p className="break-keep rounded-md bg-canvas px-3 py-2 text-sm text-muted" data-testid={`dp-legal-${item.key}`}>
          <b className="font-semibold text-ink">법정 기준</b> {item.legal} 이 안내는 가게 답으로 쓰지 않아요.
        </p>
      ) : null}
      <div className="flex flex-wrap items-end gap-3">
        {numeric ? (
          <label className="space-y-1">
            <span className="block text-sm text-muted">{item.kind === "DAYS" ? "날 수" : "금액"}</span>
            <span className="flex items-center gap-2">
              <input
                className={`${INPUT} w-28 tabular-nums`}
                inputMode="numeric"
                value={num}
                placeholder={item.placeholder ?? undefined}
                onChange={(e) => setNum(e.target.value)}
                aria-label={item.label}
              />
              <span className="text-ink">{unit}</span>
            </span>
          </label>
        ) : null}
        <label className="min-w-0 flex-1 space-y-1">
          <span className="block text-sm text-muted">{numeric ? "덧붙일 말 (선택)" : "답"}</span>
          <input
            className={`${INPUT} w-full`}
            value={text}
            maxLength={300}
            placeholder={numeric ? undefined : item.placeholder ?? undefined}
            onChange={(e) => setText(e.target.value)}
            aria-label={numeric ? `${item.label} 덧붙일 말` : item.label}
          />
        </label>
      </div>
      {item.warning ? <p className="text-sm text-warn">{item.warning}</p> : null}
      {inUse(item) && item.sentence ? (
        <p className="break-keep text-sm text-muted">
          지금 답변에 쓰는 문장: <span className="text-ink">{item.sentence}</span>
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Btn variant="outline" disabled={busy} onClick={save}>
          저장만 하기
        </Btn>
        <Btn disabled={busy || empty} onClick={confirm} data-testid={`dp-confirm-${item.key}`}>
          이대로 확정
        </Btn>
        {inUse(item) ? (
          <Btn
            variant="ghost"
           
            disabled={busy}
            onClick={() => void run(() => api.unconfirmDefaultPolicy(item.key), "확정을 내렸어요. 이 답은 이제 답변에 쓰지 않아요.")}
          >
            확정 내리기
          </Btn>
        ) : null}
      </div>
      {error ? <p className="break-keep text-sm text-warn" role="alert">{error}</p> : null}
      {notice ? <p className="break-keep text-sm text-good" role="status">{notice}</p> : null}
    </div>
  );
}
