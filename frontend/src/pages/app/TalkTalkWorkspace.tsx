import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { Empty } from "../../components/ui/Empty";
import { PageHead } from "../../components/ui/PageHead";
import { Status } from "../../components/ui/Status";
import { ReviewInquirySwitch } from "../../components/reviews/ReviewInquirySwitch";
import { MasterDetail, selectionHref, useWideLayout } from "../../components/workspace/MasterDetail";
import { api } from "../../lib/apiClient";
import { newCommandId } from "../../lib/commandId";
import {
  RELOAD_CODES,
  apiCode,
  apiMessage,
  decisionKey,
  listKey,
  pollDelay,
  kstTime,
  sendAbility,
  statusTone,
  unresolvedUnknown,
  type ConversationDetail,
  type ConversationRow,
  type TalkTalkStatus,
} from "../../lib/talktalk";

/**
 * <b>톡톡 상담</b> (docs/inquiry/talktalk_chat_lane_v1.md §7). The conversation list and one conversation: what the
 * customer wrote, who holds the thread, why it waits for a person, the reply and what it rests on, the send and what
 * TalkTalk said back. Nothing here claims the customer received or read anything.
 */
export function TalkTalkWorkspace() {
  const wide = useWideLayout();
  const { conversationId } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const selected = conversationId ?? params.get("item");
  const [status, setStatus] = useState<TalkTalkStatus | null>(null);
  const [rows, setRows] = useState<ConversationRow[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [tick, setTick] = useState(0);

  // A new conversation or a changed state shows up without a reload: the list re-reads while the screen is visible.
  useVisiblePoll(() => setTick((t) => t + 1), 10000);

  useEffect(() => {
    let alive = true;
    Promise.all([api.getTalkTalkStatus(), api.listTalkTalkConversations()])
      .then(([s, r]) => {
        if (!alive) return;
        setStatus(s);
        setRows(r);
        setFailed(false);
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [tick]);

  const reload = () => setTick((t) => t + 1);
  const close = () => {
    if (conversationId) {
      navigate("/talktalk");
    } else {
      const next = new URLSearchParams(params);
      next.delete("item");
      setParams(next);
    }
  };

  // A phone (or a narrow window) opens one conversation as its own screen.
  if (conversationId && !wide) {
    return (
      <div className="space-y-4" data-testid="talktalk-conversation-screen">
        <Link to="/talktalk" className="text-sm font-semibold text-brand-700 hover:underline">
          ← 톡톡 대화 목록
        </Link>
        <ConversationPanel key={conversationId} id={conversationId} onChanged={reload} />
      </div>
    );
  }

  const list = (
    <div className="space-y-6" data-testid="talktalk-workspace">
      <PageHead
        title="톡톡 상담"
        description="네이버 톡톡으로 온 대화예요. 문의·리뷰 숫자와 따로 세요."
        meta={<ReviewInquirySwitch current="talktalk" />}
        action={
          <BtnLink to="/settings/talktalk" variant="outline" size="sm">
            톡톡 연동·자동 안내
          </BtnLink>
        }
      />
      {status ? <LaneBanner status={status} /> : null}
      {failed ? (
        <p role="alert" className="break-keep text-sm font-semibold text-bad">
          톡톡 대화를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </p>
      ) : null}
      {!rows && !failed ? <p className="text-sm text-muted">불러오는 중…</p> : null}
      {rows && status && !status.connection.connected && rows.length === 0 ? (
        <Empty
          title="톡톡이 아직 연동되지 않았어요"
          body="톡톡 파트너센터의 챗봇 API 설정과 이 서비스를 이어야 대화가 들어와요."
          action={<BtnLink to="/settings/talktalk">연동하러 가기</BtnLink>}
        />
      ) : null}
      {rows && rows.length === 0 && status?.connection.connected ? (
        <Empty title="아직 들어온 대화가 없어요" body="고객이 톡톡으로 말을 걸면 여기에 보여요." />
      ) : null}
      {rows && rows.length > 0 ? (
        <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface" aria-label="톡톡 대화">
          {rows.map((r) => (
            <li key={r.id}>
              <Link
                to={selectionHref(wide, r.id, `/talktalk/${r.id}`, params.toString())}
                aria-current={selected === r.id ? "true" : undefined}
                className={`block px-4 py-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
                  selected === r.id ? "bg-brand/10" : "hover:bg-canvas"
                }`}
                data-testid="talktalk-row"
              >
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="font-semibold text-ink">{r.alias}</span>
                  <Status tone={r.owner === "PARTNER" ? "info" : "neutral"} variant="quiet">
                    {r.ownerLabel}
                  </Status>
                  {r.needsHuman ? (
                    <Status tone="warn" variant="chip">
                      사람 확인 필요
                    </Status>
                  ) : null}
                  <span className="ml-auto text-sm tabular-nums text-muted">{kstTime(r.lastInboundAt)}</span>
                </div>
                <p className="mt-1 truncate break-keep text-sm text-ink">{r.lastPreview ?? "(메시지 없음)"}</p>
                {r.needsHuman && r.humanReason ? (
                  <p className="mt-1 break-keep text-sm text-muted">왜: {r.humanReason.label}</p>
                ) : null}
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );

  return (
    <MasterDetail
      list={list}
      wide={wide}
      detailLabel="톡톡 대화"
      onClose={close}
      detail={selected ? <ConversationPanel key={selected} id={selected} onChanged={reload} /> : null}
    />
  );
}

function LaneBanner({ status }: { status: TalkTalkStatus }) {
  return (
    <div className="space-y-1 rounded-xl border border-line bg-surface px-4 py-3" data-testid="talktalk-banner">
      {status.connection.fake ? (
        <p className="break-keep text-sm font-semibold text-warn" data-testid="talktalk-fake">
          모의 전송 — 실제 톡톡으로 가지 않아요. 보낸 답변은 이 서버에만 기록돼요.
        </p>
      ) : null}
      <p className="break-keep text-sm text-muted">{status.laneNotice}</p>
      <p className="break-keep text-sm text-muted">
        자동 안내: {status.delegation.active ? `켜짐 (배송 기준 v${status.delegation.guidanceVersion})` : "꺼짐"}
        {!status.delegation.active && status.delegation.reason ? ` · ${status.delegation.reason.label}` : ""}
      </p>
    </div>
  );
}

function ConversationPanel({ id, onChanged }: { id: string; onChanged: () => void }) {
  const [d, setD] = useState<ConversationDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState("");
  const [arming, setArming] = useState(false);
  const [ackUnknown, setAckUnknown] = useState(false);
  const [confirmHolds, setConfirmHolds] = useState(false);
  const [commandId, setCommandId] = useState<string>(() => newCommandId());
  const base = useRef<string>(""); // the draft text the textarea started from — what "unsaved" is measured against
  const key = useRef<string>("");
  // Every read and every action takes a number when it starts; an answer is shown only when it is newer than the last
  // one shown, and only for this conversation — a late poll never overwrites an action's result or another
  // conversation's screen.
  const seq = useRef(0);
  const shown = useRef(0);
  // The list beside this panel re-reads when what it shows for this conversation moved. A ref, so a list reload (a new
  // `onChanged`) never re-creates `read` and re-runs the first read over the seller's unsaved sentence.
  const changed = useRef(onChanged);
  changed.current = onChanged;
  const listed = useRef<string | null>(null);

  /**
   * Take a fresh read. The seller's unsaved sentence is never overwritten; if the draft under it changed, they are
   * told. Any change to what a confirmation was given against clears it.
   */
  const apply = useCallback((v: ConversationDetail, fromSeller: boolean, n: number) => {
    if (n <= shown.current || v.conversation.id !== id) {
      return;
    }
    shown.current = n;
    setD(v);
    const lk = listKey(v);
    if (listed.current !== null && lk !== listed.current) changed.current();
    listed.current = lk;
    const next = v.draft?.text ?? "";
    setText((current) => {
      const dirty = current.trim() !== base.current.trim();
      if (!dirty || fromSeller) {
        base.current = next;
        return next;
      }
      if (next !== base.current) {
        setNotice("그사이 답변이 바뀌었어요. 고치던 문장은 그대로 두었어요 — 저장하면 새 버전이 돼요.");
      }
      return current;
    });
    const k = decisionKey(v);
    if (k !== key.current) {
      key.current = k;
      setArming(false);
      setAckUnknown(false);
      setConfirmHolds(false);
    }
  }, [id]);

  const read = useCallback(
    (fromSeller = false) => {
      const n = ++seq.current;
      return api
        .getTalkTalkConversation(id)
        .then((v) => apply(v, fromSeller, n))
        .catch(() => {
          if (n > shown.current) setError("대화를 불러오지 못했어요.");
        });
    },
    [id, apply],
  );

  useEffect(() => {
    base.current = "";
    key.current = "";
    void read(true);
  }, [read]);

  // While visible: fast while a send is queued or in flight (QUEUED → a result), slow otherwise.
  useVisiblePoll(() => {
    if (!busy) void read(false);
  }, pollDelay(d));

  async function act(run: () => Promise<ConversationDetail>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    const n = ++seq.current;
    try {
      const v = await run();
      apply(v, true, n);
      setArming(false);
      setAckUnknown(false);
      setConfirmHolds(false);
      setCommandId(newCommandId());
      onChanged();
    } catch (e) {
      setError(apiMessage(e));
      setArming(false);
      if (RELOAD_CODES.has(apiCode(e) ?? "")) {
        // The decision was made against an older screen: show the latest before anything else is pressed.
        await read(false);
        setNotice("최신 내용을 다시 불러왔어요. 확인한 뒤 다시 진행해 주세요.");
      }
    } finally {
      setBusy(false);
    }
  }

  if (!d) {
    return <p className="text-sm text-muted">{error ?? "불러오는 중…"}</p>;
  }

  const ability = sendAbility(d);
  const unknown = unresolvedUnknown(d);
  const edited = d.draft ? text.trim() !== (d.draft.text ?? "").trim() : text.trim().length > 0;
  const c = d.conversation;

  return (
    <article className="space-y-6" aria-labelledby="tt-conv-title" data-testid="talktalk-detail">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <h2 id="tt-conv-title" className="text-section font-bold text-ink">
            {c.alias}
          </h2>
          <Status tone={c.owner === "PARTNER" ? "info" : "neutral"}>{c.ownerLabel}</Status>
          {d.fake ? (
            <Status tone="warn" variant="quiet">
              모의 전송
            </Status>
          ) : null}
        </div>
        {c.needsHuman && c.humanReason ? (
          <p className="break-keep text-sm font-semibold text-warn" data-testid="tt-human-reason">
            사람 확인 필요 · {c.humanReason.label}
          </p>
        ) : c.automation ? (
          <p className="break-keep text-sm text-muted">자동 안내 판단: {c.automation.label}</p>
        ) : null}
        {d.productName ? <p className="break-keep text-sm text-muted">보고 문의한 상품: {d.productName}</p> : null}
      </header>

      <section aria-labelledby="tt-thread" className="space-y-2">
        <h3 id="tt-thread" className="text-lg font-semibold text-ink">
          대화
        </h3>
        <ol className="space-y-2" data-testid="tt-thread">
          {d.messages.map((m) => (
            <li
              key={m.id}
              className={`rounded-lg border border-line px-3 py-2 ${m.author === "CUSTOMER" ? "bg-surface" : "bg-canvas"}`}
            >
              <p className="flex flex-wrap items-center gap-x-2 text-sm text-muted">
                <span className="font-semibold text-ink">{m.authorLabel}</span>
                <span className="tabular-nums">{kstTime(m.at)}</span>
                {m.duplicateSuspect ? <span className="text-warn">같은 내용을 다시 받음</span> : null}
                {m.standby ? <span>상담원 응대 중에 옴</span> : null}
                {m.outboundStatusLabel ? <span>{m.outboundStatusLabel}</span> : null}
              </p>
              <p className="mt-1 whitespace-pre-wrap break-keep text-base text-ink">
                {m.purged ? "보관 기간이 지나 내용을 지웠어요." : m.text ?? m.nonTextNote ?? "—"}
              </p>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="tt-reply" className="space-y-3">
        <h3 id="tt-reply" className="text-lg font-semibold text-ink">
          답변
        </h3>
        <div className="flex flex-wrap gap-2">
          <Btn variant="outline" size="sm" disabled={busy} onClick={() => act(() => api.prepareTalkTalkDraft(id, "GUIDANCE"))}>
            배송 기준으로 준비
          </Btn>
          <Btn variant="outline" size="sm" disabled={busy} onClick={() => act(() => api.prepareTalkTalkDraft(id, "MODEL"))}>
            참고 자료로 AI 답변 준비
          </Btn>
        </div>
        <label className="block space-y-1">
          <span className="text-sm text-muted">보낼 문장 (고치면 새 버전으로 저장돼요)</span>
          <textarea
            className="min-h-40 w-full rounded-md border border-line bg-surface px-3 py-2 text-base text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            value={text}
            maxLength={1000}
            readOnly={busy}
            aria-busy={busy}
            onChange={(e) => {
              setText(e.target.value);
              setArming(false);
            }}
            data-testid="tt-text"
          />
        </label>
        {edited ? (
          <Btn
            variant="outline"
            size="sm"
            disabled={busy || text.trim().length === 0}
            onClick={() => act(() => api.editTalkTalkDraft(id, text, d.draft?.version ?? null))}
            data-testid="tt-save-edit"
          >
            고친 문장 저장
          </Btn>
        ) : null}
        {d.draft ? (
          <div className="space-y-2 text-sm">
            <p className="text-muted">
              버전 {d.draft.version} ·{" "}
              {d.draft.authorKind === "GUIDANCE" ? "배송 기준 문장" : d.draft.authorKind === "MODEL" ? "AI 답변" : "직접 쓴 문장"}
            </p>
            {d.draft.evidence.length > 0 ? (
              <div>
                <p className="font-semibold text-ink">참고한 내용</p>
                <ul className="list-disc pl-6 text-muted">
                  {d.draft.evidence.map((e, i) => (
                    <li key={i} className="break-keep">
                      {e.title}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            {d.draft.holds.length > 0 ? (
              <div data-testid="tt-holds">
                <p className="font-semibold text-warn">확인이 필요해요</p>
                <ul className="list-disc pl-6 text-ink">
                  {d.draft.holds.map((h) => (
                    <li key={h.code} className="break-keep">
                      {h.label}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        ) : null}

        {!edited ? (
          <SendControls
            ability={ability}
            unknownLabel={unknown ? `${kstTime(unknown.createdAt)}에 보낸 답변` : null}
            ackUnknown={ackUnknown}
            setAckUnknown={setAckUnknown}
            confirmHolds={confirmHolds}
            setConfirmHolds={setConfirmHolds}
            arming={arming}
            setArming={setArming}
            busy={busy}
            partnerOwns={c.owner === "PARTNER"}
            onSend={() =>
              d.draft &&
              act(
                () =>
                  api.sendTalkTalkReply(id, {
                    commandId,
                    draftVersion: d.draft!.version,
                    expectedFingerprint: d.draft!.fingerprint,
                    acknowledgeUnknownOf: unknown && ackUnknown ? unknown.id : null,
                    confirmHolds,
                  }),
              )
            }
          />
        ) : (
          <p className="break-keep text-sm text-muted">고친 문장을 저장한 뒤 보낼 수 있어요.</p>
        )}
      </section>

      <section aria-labelledby="tt-handover" className="space-y-2">
        <h3 id="tt-handover" className="text-lg font-semibold text-ink">
          담당 넘기기
        </h3>
        <p className="break-keep text-sm text-muted">
          톡톡 파트너센터 상담원에게 넘기면 그동안 자동 안내를 하지 않아요. 상담원이 「상담 완료」를 누르면 다시 챗봇이 맡아요.
        </p>
        <div className="flex flex-wrap gap-2">
          <Btn variant="outline" size="sm" disabled={busy} onClick={() => act(() => api.handOverTalkTalk(id, newCommandId(), "PASS"))}>
            상담원에게 넘기기
          </Btn>
          <Btn variant="ghost" size="sm" disabled={busy} onClick={() => act(() => api.handOverTalkTalk(id, newCommandId(), "TAKE"))}>
            챗봇으로 가져오기
          </Btn>
        </div>
      </section>

      {error ? (
        <p role="alert" className="break-keep text-sm font-semibold text-bad" data-testid="tt-error">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="break-keep text-sm text-warn" data-testid="tt-notice">
          {notice}
        </p>
      ) : null}

      <section aria-labelledby="tt-sent" className="space-y-2">
        <h3 id="tt-sent" className="text-lg font-semibold text-ink">
          보낸 기록
        </h3>
        <p className="break-keep text-sm text-muted">「톡톡 접수」는 톡톡이 요청을 받았다는 뜻이에요. 고객이 받았거나 읽었다는 뜻은 아니에요.</p>
        {d.outbound.length === 0 ? <p className="text-sm text-muted">아직 없어요.</p> : null}
        <ul className="divide-y divide-line rounded-xl border border-line bg-surface" data-testid="tt-outbound">
          {d.outbound.map((o) => (
            <li key={o.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm">
              <span className="font-semibold text-ink">{o.kindLabel}</span>
              <Status tone={statusTone(o.status)} variant="chip">
                {o.statusLabel}
              </Status>
              {o.failure ? <span className="break-keep text-muted">{o.failure.label}</span> : null}
              {o.echoSeen ? <span className="text-muted">톡톡이 되돌려 준 메시지 확인</span> : null}
              <span className="ml-auto tabular-nums text-muted">{kstTime(o.createdAt)}</span>
            </li>
          ))}
        </ul>
      </section>
    </article>
  );
}

function SendControls(props: {
  ability: ReturnType<typeof sendAbility>;
  unknownLabel: string | null;
  ackUnknown: boolean;
  setAckUnknown: (v: boolean) => void;
  confirmHolds: boolean;
  setConfirmHolds: (v: boolean) => void;
  arming: boolean;
  setArming: (v: boolean) => void;
  busy: boolean;
  partnerOwns: boolean;
  onSend: () => void;
}) {
  const { ability } = props;
  if (ability.kind === "none") {
    return <p className="break-keep text-sm text-muted">{ability.why}</p>;
  }
  if (ability.kind === "rewrite") {
    return (
      <p className="break-keep text-sm font-semibold text-warn" data-testid="tt-rewrite">
        이 답변은 그대로 보낼 수 없어요. 표시된 내용을 고친 문장으로 저장해 주세요.
      </p>
    );
  }
  const needsAck = props.unknownLabel != null;
  const ready = (!needsAck || props.ackUnknown) && (ability.kind !== "confirm-exception" || props.confirmHolds);
  return (
    <div className="space-y-2" data-testid="tt-send">
      {props.partnerOwns ? (
        <p className="break-keep text-sm text-muted">상담원이 맡은 대화예요. 보내면 상담원 화면에도 이 답이 보여요.</p>
      ) : null}
      {needsAck ? (
        <label className="flex min-h-11 items-start gap-2 text-sm">
          <input type="checkbox" checked={props.ackUnknown} onChange={(e) => props.setAckUnknown(e.target.checked)} />
          <span className="break-keep">
            {props.unknownLabel}의 결과를 몰라요. 톡톡 파트너센터에서 실제로 가지 않은 것을 확인했어요.
          </span>
        </label>
      ) : null}
      {ability.kind === "confirm-exception" ? (
        <label className="flex min-h-11 items-start gap-2 text-sm">
          <input type="checkbox" checked={props.confirmHolds} onChange={(e) => props.setConfirmHolds(e.target.checked)} />
          <span className="break-keep">표시된 내용은 이번 건만 예외로, 제가 쓴 그대로 보낼게요.</span>
        </label>
      ) : null}
      {!props.arming ? (
        <Btn disabled={props.busy} onClick={() => (ready ? props.setArming(true) : undefined)} aria-disabled={!ready} data-testid="tt-send-first">
          톡톡으로 보내기
        </Btn>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <Btn disabled={props.busy} onClick={props.onSend} data-testid="tt-send-confirm">
            확인하고 보내기
          </Btn>
          <Btn variant="ghost" disabled={props.busy} onClick={() => props.setArming(false)}>
            취소
          </Btn>
        </div>
      )}
      {!ready ? <p className="break-keep text-sm text-muted">위 확인란을 체크해야 보낼 수 있어요.</p> : null}
    </div>
  );
}

/** Calls {@code fn} every {@code ms} while the page is visible, and once when it becomes visible again. */
function useVisiblePoll(fn: () => void, ms: number) {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    if (typeof document === "undefined") return undefined;
    const tick = () => {
      if (document.visibilityState === "visible") ref.current();
    };
    const timer = setInterval(tick, ms);
    const onVisible = () => {
      if (document.visibilityState === "visible") ref.current();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [ms]);
}
