import { useState } from "react";
import { Btn } from "../ui/Btn";
import type {
  InquiryKindName,
  InquiryKindView,
  SellerCheckAnswered,
  SellerCheckAsk,
  SellerCheckOutcome,
  SellerCheckView,
} from "../../lib/types";

/**
 * <b>판매자센터에서 확인한 결과</b> (2026-11-02 판매자 확인 리뷰 · 넷째 리뷰).
 *
 * <p>서비스가 실행할 수도 읽을 수도 없는 주문 일 — 환불·취소 같은 조치, 이 주문의 상태 — 은 판매자가 판매자센터에서 확인하고
 * 한 줄로 적는다. 그 기록은 <b>이 문의에만</b> 쓰이는 근거이고, 가게 지식이나 주문 연결이 되지 않는다.
 *
 * <ul>
 *   <li>결과는 미리 고르지 않는다. 결과를 안 고르거나 한 줄이 비면 답변을 만들지 않는다(서버도 같은 이유로 거절한다).</li>
 *   <li>기록 저장과 답변 준비는 따로 말한다: 저장됐지만 초안이 없으면 「확인한 내용은 저장했어요. 답변은 아직 준비하지
 *       못했어요.」와 「이 기록으로 답변 다시 만들기」. 다시 만들기는 기록을 다시 저장하지 않는다.</li>
 *   <li>새로고침해도 그 길은 남는다(2026-11-03 다섯째 리뷰): 서버가 「가장 새 답변이 지금 기록으로 쓰였는가」를 알려 주고,
 *       아니면 「이 기록으로 만든 답변이 아직 없어요」와 「이 기록으로 답변 만들기」. 기록을 읽지 못했으면 「없음」으로
 *       그리지 않고 「불러오지 못했어요 · 다시 불러오기」.</li>
 *   <li>보내는 요청에는 화면이 보던 기록을 함께 싣는다 — 다른 기기에서 먼저 고쳤으면 서버가 409로 거절하고, 새 기록을 보여 준다.</li>
 *   <li>승인 뒤에는 결과마다 다른 한 줄. 등록이 확인되기 전에는 「등록 중」, 확인된 뒤에만 「안내했어요」라고 쓴다.</li>
 *   <li>그 한 줄은 <b>고객에게 보낸 답변이 쓰인 기록</b>으로 쓴다(2026-11-05 일곱째 리뷰). 「진행 중」으로 답한 뒤 기록을
 *       「처리 완료」로 고쳤으면 「처리 완료를 안내했어요」가 아니라, 지금 처리 결과와 고객에게 안내한 내용을 따로 보여 준다.</li>
 *   <li>등록 전에 기록이 바뀌어 등록이 막힌 답변은 되돌릴 길이 있다: 채널에 등록된 흔적이 없고 판매자가 손으로 붙여 넣지
 *       않았다고 확인하면 승인을 거두고(거둔 승인은 서버에 남는다) 지금 기록으로 새 답변을 만든다. 새 답변은 다시 승인해야
 *       등록된다.</li>
 * </ul>
 *
 * 이전 기록으로 만든 초안의 보류는 이 패널이 아니라 위의 보류 안내({@code ReplyHoldNotice})가 그린다 — 이유는 서버가 쓴다.
 */
export const OUTCOMES: Record<SellerCheckOutcome, { label: string; help: string; field: string; placeholder: string }> = {
  DONE: { label: "처리 완료", help: "실제로 끝낸 조치", field: "무엇을 했나요?", placeholder: "예: 카드 결제 취소로 환불 처리" },
  IN_PROGRESS: {
    label: "접수·진행 중",
    help: "지금까지 확인한 상태",
    field: "지금 어디까지 됐나요?",
    placeholder: "예: 반품 상품 도착 확인, 환불 대기",
  },
  CANNOT: {
    label: "처리 불가",
    help: "처리할 수 없는 이유",
    field: "이유를 적어 주세요",
    placeholder: "예: 사용한 흔적이 있어 환불 대상이 아님",
  },
  NEEDS_CUSTOMER_INFO: {
    label: "고객 정보가 더 필요함",
    help: "고객에게 물어볼 것",
    field: "고객에게 무엇을 받아야 하나요?",
    placeholder: "예: 주문자 성함과 주문일",
  },
};

const ORDER: SellerCheckOutcome[] = ["DONE", "IN_PROGRESS", "CANNOT", "NEEDS_CUSTOMER_INFO"];

const ASK: Record<SellerCheckAsk, { title: string; body: string; judged: string }> = {
  ORDER_ACTION: {
    title: "이 주문에 요청된 조치를 확인해 주세요.",
    body: "판매자센터에서 처리 가능 여부와 현재 결과를 확인하면 답변을 준비할게요.",
    judged: "조치 요청 — 이 주문에 무언가를 해 달라는 요청",
  },
  ORDER_STATE: {
    title: "이 주문이 지금 어떤 상태인지 확인해 주세요.",
    body: "서비스가 이 주문을 읽을 수 없어요. 판매자센터에서 본 상태를 알려주시면 답변을 준비할게요.",
    judged: "상태 확인 — 이 주문이 어떻게 됐는지 묻는 문의",
  },
};

/**
 * 판매자 확인이 이 답변 영역의 할 일을 이끄는가 — 기록을 묻는 중이거나, 기록은 있는데 그 기록으로 쓴 답변이 없을 때. 그때
 * 「AI 답변 초안 만들기」와 「답변 기준 추가」는 같은 일을 두 번 시키거나 따라 해도 끝나지 않는 일을 시키므로 그리지 않는다.
 */
export function sellerCheckLeads(s: {
  ask: SellerCheckAsk | null;
  check: SellerCheckView | null;
  approved: boolean;
  draftMatches: boolean | null;
  draftState: "READY" | "NOT_WRITTEN" | "FAILED" | "KEPT" | null;
}): boolean {
  if (s.approved) return false;
  if (!s.check) return Boolean(s.ask);
  const pressFailed = s.draftState === "FAILED" || s.draftState === "NOT_WRITTEN";
  return pressFailed || (s.draftMatches === false && s.draftState !== "READY");
}

/** The seller's day (`YYYY-MM-DD`) — next-check days are counted from it, as the server counts them. */
export function sellerToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" })
    .format(now);
}

function plusDays(day: string, n: number): string {
  const [y, m, d] = day.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return t.toISOString().slice(0, 10);
}

/** 「11월 5일 (수)」 — a calendar day, never a time. */
export function dayLabel(day: string): string {
  const [y, m, d] = day.split("-").map(Number);
  const w = "일월화수목금토"[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return `${m}월 ${d}일 (${w})`;
}

/** The days offered for the next look: tomorrow, two, three days and a week on — plus the one already set. */
export function nextCheckChoices(today: string, current: string | null): { day: string; label: string }[] {
  const days = [1, 2, 3, 7].map((n) => ({ day: plusDays(today, n), label: `${dayLabel(plusDays(today, n))} · ${n === 1 ? "내일" : n === 7 ? "일주일 뒤" : `${n}일 뒤`}` }));
  if (current && !days.some((c) => c.day === current)) {
    days.push({ day: current, label: `${dayLabel(current)}${current < today ? " · 지난 날" : current === today ? " · 오늘" : ""}` });
    days.sort((a, b) => a.day.localeCompare(b.day));
  }
  return days;
}

/**
 * 승인 뒤의 한 줄 — 등록이 확인되기 전과 후가 다르다. 「…안내했어요」는 등록된 글이 우리 초안이라는 것을 알 때만 쓴다
 * (2026-11-06 sixteenth review): 판매자센터에서 직접 쓴 답변이면 무엇을 안내했는지 서비스는 단정할 수 없다 — 그때는
 * {@link registeredElsewhereLine}.
 */
export function afterApprovalLine(outcome: SellerCheckOutcome, registered: boolean): string {
  switch (outcome) {
    case "DONE":
      return registered ? "처리 완료를 안내했어요." : "처리 완료를 안내하는 답변을 등록 중이에요.";
    case "IN_PROGRESS":
      return registered
        ? "진행 상황을 안내했어요 · 처리는 아직 진행 중이에요."
        : "진행 상황을 안내하는 답변을 등록 중이에요 · 처리는 아직 진행 중이에요.";
    case "CANNOT":
      return registered ? "처리할 수 없는 이유를 안내했어요." : "처리할 수 없는 이유를 안내하는 답변을 등록 중이에요.";
    case "NEEDS_CUSTOMER_INFO":
      return registered
        ? "고객에게 필요한 정보를 물었어요 · 고객의 회신을 기다리고 있어요."
        : "고객에게 필요한 정보를 묻는 답변을 등록 중이에요.";
  }
}

/** 등록은 확인했지만 그 글이 우리 초안인지 모를 때 — 확인한 것과 판매자의 기록만 말한다. */
export function registeredElsewhereLine(outcome: SellerCheckOutcome): { line: string; record: string } {
  return { line: "채널에 답변이 등록된 것을 확인했어요.", record: `판매자 확인 기록: ${OUTCOMES[outcome].label}` };
}

/** 방법 안내 · 상태 확인 · 조치 요청 — the three readings the seller can pick between (2026-11-05 seventh review). */
export const KINDS: Record<InquiryKindName, { label: string; help: string }> = {
  HOW_TO: { label: "방법 안내", help: "정책이나 상품 정보로 답하는 문의" },
  ORDER_STATE: { label: "상태 확인", help: "이 주문이 어떻게 됐는지 묻는 문의" },
  ORDER_ACTION: { label: "조치 요청", help: "이 주문에 무언가를 해 달라는 요청" },
};
const KIND_ORDER: InquiryKindName[] = ["HOW_TO", "ORDER_STATE", "ORDER_ACTION"];

/**
 * 「서비스 판단」 and 「판단 고치기」 (2026-11-05 seventh review). The service's reading stays on screen beside the seller's
 * correction; a correction drafts the reply again under it. Nothing is preselected but the reading in force, and the
 * press is refused until another one is picked.
 */
/** 「…이었어요」 or 「…였어요」 — by whether the label ends in a final consonant. */
export function wasKo(label: string): string {
  const c = label.charCodeAt(label.length - 1);
  const batchim = c >= 0xac00 && c <= 0xd7a3 && (c - 0xac00) % 28 !== 0;
  return batchim ? `${label}이었어요` : `${label}였어요`;
}

export function KindLine({
  kind,
  busy,
  onCorrect,
  error = null,
  hasDraft = false,
  asked = false,
  onRedraft,
  onOpenChange,
}: {
  kind: InquiryKindView;
  busy: boolean;
  onCorrect?: (kind: InquiryKindName) => Promise<boolean>;
  /** Why the last correction was refused — shown while the form is open. */
  error?: string | null;
  /** An answer version is on screen. */
  hasDraft?: boolean;
  /** The seller centre is asked for this inquiry — then the record, not a plain redraft, writes the new answer. */
  asked?: boolean;
  /** 「이 판단으로 답변 만들기」 — a plain redraft under the correction. */
  onRedraft?: () => void;
  onOpenChange?: (open: boolean) => void;
}) {
  const [open, setOpenState] = useState(false);
  const setOpen = (v: boolean) => {
    setOpenState(v);
    onOpenChange?.(v);
  };
  const stale = kind.draftMatches === false && hasDraft && Boolean(onCorrect);
  const effective = kind.corrected ?? kind.judged;
  const [pick, setPick] = useState<InquiryKindName | null>(null);
  const chosen = pick ?? effective;
  return (
    <div className="mt-1" data-testid="kind-line">
      <p className="break-keep text-sm text-muted">
        {kind.corrected ? (
          <>
            판매자 판단: <b className="text-ink">{KINDS[kind.corrected].label}</b> — {KINDS[kind.corrected].help}
            {kind.judged && kind.judged !== kind.corrected ? ` · 서비스 판단은 ${wasKo(KINDS[kind.judged].label)}` : ""}
          </>
        ) : kind.judged ? (
          <>
            서비스 판단: {KINDS[kind.judged].label} — {KINDS[kind.judged].help}
          </>
        ) : null}
        {onCorrect && !open ? (
          <button
            type="button"
            className="ml-2 text-sm font-medium text-ink underline underline-offset-4"
            onClick={() => setOpen(true)}
            disabled={busy}
          >
            판단 고치기
          </button>
        ) : null}
      </p>
      {stale && !open ? (
        <div className="mt-2" role="status" data-testid="kind-stale">
          <p className="break-keep text-sm text-ink">
            이 판단으로 만든 답변이 아직 없어요. 지금 보이는 답변은 판단을 고치기 전에 만든 것이라 그대로 복사하거나 보내지 않아요.
          </p>
          {asked ? (
            <p className="mt-1 break-keep text-sm text-muted">판매자센터에서 확인한 결과를 적으면 이 판단으로 답변을 만들어요.</p>
          ) : onRedraft ? (
            <div className="mt-2">
              <Btn size="sm" onClick={onRedraft} disabled={busy}>
                {busy ? "답변을 만드는 중…" : "이 판단으로 답변 만들기"}
              </Btn>
            </div>
          ) : null}
        </div>
      ) : null}
      {open && onCorrect ? (
        <div className="mt-2 flex flex-col gap-2" data-testid="kind-form">
          <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="이 문의는">
            {KIND_ORDER.map((k) => (
              <Btn
                key={k}
                size="sm"
                variant={chosen === k ? "selected" : "outline"}
                role="radio"
                aria-checked={chosen === k}
                title={KINDS[k].help}
                onClick={() => setPick(k)}
              >
                {KINDS[k].label}
              </Btn>
            ))}
          </div>
          <p className="break-keep text-sm text-muted">
            {chosen ? KINDS[chosen].help : ""}
            {chosen === "HOW_TO"
              ? " · 이 주문을 확인하지 않고 등록된 정책·상품 정보로만 답해요."
              : chosen
                ? " · 판매자센터에서 확인한 결과를 적으면 그 기록으로 답해요."
                : ""}
          </p>
          <p className="break-keep text-sm text-muted">고친 판단은 이 문의에만 쓰여요. 답변을 다시 만들고 다시 검사해요.</p>
          {error ? (
            <p className="break-keep text-sm text-bad" role="alert" data-testid="kind-error">
              {error}
            </p>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <Btn
              disabled={busy || !chosen || chosen === effective}
              onClick={() => {
                if (!chosen) return;
                void onCorrect(chosen).then((ok) => {
                  if (ok) {
                    setOpen(false);
                    setPick(null);
                  }
                });
              }}
            >
              {busy ? "답변을 만드는 중…" : "이 판단으로 답변 다시 만들기"}
            </Btn>
            <Btn
              variant="outline"
              disabled={busy}
              onClick={() => {
                setOpen(false);
                setPick(null);
              }}
            >
              취소
            </Btn>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** What an answer written from a record of this outcome told the customer — a noun phrase for 「…을 안내했어요」 lines. */
const TOLD: Record<SellerCheckOutcome, string> = {
  DONE: "처리 완료",
  IN_PROGRESS: "진행 상황",
  CANNOT: "처리할 수 없는 이유",
  NEEDS_CUSTOMER_INFO: "필요한 정보 요청",
};

/** The same, as the object of 「…안내하지 않았어요」 — with its particle. */
const TOLD_OBJECT: Record<SellerCheckOutcome, string> = {
  DONE: "처리 완료를",
  IN_PROGRESS: "진행 상황을",
  CANNOT: "처리할 수 없는 이유를",
  NEEDS_CUSTOMER_INFO: "필요한 정보 요청을",
};

/** 「11월 4일 10:00」 — when the service last read the inquiry, or a record was kept. */
function atLabel(iso: string | null): string {
  return iso ? formatAt(iso) : "";
}

/**
 * The approved answer and the record now are two facts (2026-11-05 seventh review): which record the customer's answer
 * was written from, what the action is now, and — when the answer is not on the channel and nothing was sent — the way
 * back to a new answer.
 */
function AnsweredApart({
  answered,
  check,
  registered,
  ours,
  busy,
  onReopen,
  onCloseToldBehind,
}: {
  answered: SellerCheckAnswered;
  check: SellerCheckView;
  registered: boolean;
  /** The registered text is known to be our approved answer. */
  ours: boolean;
  busy: boolean;
  onReopen?: () => Promise<boolean>;
  onCloseToldBehind?: (closure: "TOLD_SEPARATELY" | "NOT_NEEDED") => Promise<boolean>;
}) {
  const [sure, setSure] = useState(false);
  const [closing, setClosing] = useState(false);
  const told = answered.outcome ? TOLD[answered.outcome] : null;
  const sentLabel = registered && ours ? "고객에게 보낸 답변" : "승인한 답변";
  const onChannel = registered || answered.reopen === "REGISTERED";
  return (
    <div className="mt-3 border-l-2 border-warn pl-3" data-testid="seller-check-apart">
      <p className="break-keep text-base font-semibold leading-relaxed text-ink">
        지금 처리 결과와 {sentLabel}이 달라요.
      </p>
      <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted">지금 처리 결과</dt>
        <dd className="break-keep text-ink" data-testid="seller-check-apart-now">
          {OUTCOMES[check.outcome].label}
        </dd>
        <dt className="text-muted">{sentLabel}</dt>
        <dd className="break-keep text-ink" data-testid="seller-check-apart-sent">
          {told
            ? `${told} 안내 · ${atLabel(answered.recordedAt)} 기록으로 작성`
            : "판매자 확인 기록 없이 쓴 답변"}
        </dd>
      </dl>
      {onChannel ? (
        <p className="mt-2 break-keep text-sm text-muted" data-testid="seller-check-apart-registered">
          {registered && !ours
            ? `채널에 등록된 답변이 ${TOLD_OBJECT[check.outcome]} 안내했는지는 서비스가 알 수 없어요. 등록된 답변은 그대로 두고, 같은 문의에 두 번째 답변은 보내지 않아요. 따로 알려야 하면 판매자센터에서 직접 알려 주세요.`
            : `고객에게는 아직 ${TOLD_OBJECT[check.outcome]} 안내하지 않았어요. 이미 보낸 답변은 그대로 두고, 같은 문의에 두 번째 답변은 보내지 않아요. 따로 알려야 하면 판매자센터에서 직접 알려 주세요.`}
        </p>
      ) : answered.reopen === "OFFERED" && answered.because !== "HOLD" && onReopen ? (
        <div className="mt-3" data-testid="seller-check-reopen">
          <p className="break-keep text-base leading-relaxed text-ink">
            이 답변은 지금 기록과 맞지 않아 등록하지 않았어요. 승인을 거두고 지금 기록으로 새 답변을 만들 수 있어요.
          </p>
          <p className="mt-1 break-keep text-sm text-muted">
            서비스는 이 답변을 등록하지 않았어요 · 승인한 뒤 {atLabel(answered.channelReadAt)}에 채널에서 확인했을 때 등록된 답변이
            없었어요. 그 뒤에 직접 붙여 넣은 답변은 서비스가 알 수 없어요.
          </p>
          <label className="mt-2 flex items-start gap-2 text-sm text-ink">
            <input
              type="checkbox"
              className="mt-1"
              checked={sure}
              disabled={busy}
              onChange={(e) => setSure(e.target.checked)}
              data-testid="seller-check-reopen-sure"
            />
            <span className="break-keep">판매자센터에 이 답변을 직접 붙여 넣어 등록하지 않았어요.</span>
          </label>
          <div className="mt-2">
            <Btn
              disabled={busy || !sure}
              onClick={() => {
                void onReopen().then((ok) => {
                  if (ok) setSure(false);
                });
              }}
            >
              {busy ? "새 답변을 만드는 중…" : "승인 거두고 이 기록으로 새 답변 만들기"}
            </Btn>
          </div>
          <p className="mt-2 break-keep text-sm text-muted">
            거둔 승인은 기록으로 남아요. 새 답변은 다시 확인하고 승인해야 등록돼요.
          </p>
        </div>
      ) : answered.reopen === "UNCONFIRMED" ? (
        <p className="mt-2 break-keep text-sm text-muted" data-testid="seller-check-apart-unconfirmed">
          이 답변이 채널에 등록됐는지 아직 몰라요. 승인한 뒤로 채널에서 이 문의를 읽지 못했어요
          {answered.channelReadAt ? ` (마지막 확인: ${atLabel(answered.channelReadAt)}, 승인 전)` : " (확인 기록 없음)"}. 다음
          수집에서 등록된 답변이 없는 것이 확인되면 승인을 거두고 새 답변을 만들 수 있어요.
        </p>
      ) : answered.reopen === "UNSETTLED" ? (
        <p className="mt-2 break-keep text-sm text-muted" data-testid="seller-check-apart-unsettled">
          이 답변이 등록됐는지 아직 확인하지 못했어요. 등록 상태를 먼저 확인해 주세요. 등록되지 않은 것이 확인되기 전에는 승인을
          거둘 수 없어요.
        </p>
      ) : null}
      {onChannel && answered.toldBehind ? (
        answered.closure ? (
          <p className="mt-2 break-keep text-sm text-muted" data-testid="told-behind-closed">
            {answered.closure === "TOLD_SEPARATELY" ? "따로 안내했다고 표시했어요" : "안내할 필요 없다고 마무리했어요"}
            {answered.closedAt ? ` · ${atLabel(answered.closedAt)}` : ""}. 처리 결과를 다시 고치면 다시 확인할 일로 올라와요.
          </p>
        ) : onCloseToldBehind ? (
          <div className="mt-3" data-testid="told-behind-close">
            <p className="break-keep text-sm text-ink">고객에게 알렸거나 알릴 필요가 없으면 마무리해 주세요. 아무것도 보내지 않아요.</p>
            <div className="mt-2 flex flex-wrap gap-2">
              {(["TOLD_SEPARATELY", "NOT_NEEDED"] as const).map((closure) => (
                <Btn
                  key={closure}
                  variant="outline"
                  disabled={busy || closing}
                  onClick={() => {
                    setClosing(true);
                    void onCloseToldBehind(closure).finally(() => setClosing(false));
                  }}
                >
                  {closure === "TOLD_SEPARATELY" ? "따로 안내함" : "안내 필요 없음"}
                </Btn>
              ))}
            </div>
          </div>
        ) : null
      ) : null}
    </div>
  );
}

export function SellerCheckPanel({
  ask,
  check,
  loadFailed,
  onReload,
  draftMatches,
  hasDraft,
  draftState,
  draftReason,
  approved,
  registered,
  registeredTextIsOurs = false,
  busy,
  error,
  onRecord,
  onRedraft,
  onSetNextCheck,
  answered = null,
  onReopen,
  onCloseToldBehind,
  reopened = false,
  kind = null,
  onCorrectKind,
  onRedraftKind,
  today = sellerToday(),
}: {
  /** What the seller is asked to check — the system's reading. Null with no record: the panel draws nothing. */
  ask: SellerCheckAsk | null;
  check: SellerCheckView | null;
  /** The record could not be read. */
  loadFailed: boolean;
  onReload: () => void;
  /** Whether the newest answer was written from `check`; null with no record or before the server said. */
  draftMatches: boolean | null;
  /** Whether an answer version is on screen at all. */
  hasDraft: boolean;
  /** How the last record or redraft press went; null before any press in this session. `KEPT`: approved already. */
  draftState: "READY" | "NOT_WRITTEN" | "FAILED" | "KEPT" | null;
  /** Why no draft was written, when the server said (NOT_WRITTEN). */
  draftReason: string | null;
  /** The answer was approved — the record can no longer change what is sent from here. */
  approved: boolean;
  /** The channel was read back and the answer is there. */
  registered: boolean;
  /**
   * The registered text is known to be our approved answer — SellerOps sent it, or a collected text matches it. Without
   * it the panel says only that an answer is registered, never what the customer was told (2026-11-06 sixteenth review).
   */
  registeredTextIsOurs?: boolean;
  busy: boolean;
  error: string | null;
  onRecord: (outcome: SellerCheckOutcome, note: string) => Promise<boolean>;
  onRedraft: () => void;
  /** 「이 날로 정하기」 — moves the next-check day of the record on screen; absent where the screen cannot. */
  onSetNextCheck?: (day: string) => Promise<boolean>;
  /** The approved answer against the record, from the server; null before approval or before it said. */
  answered?: SellerCheckAnswered | null;
  /** 「승인 거두고 이 기록으로 새 답변 만들기」 — absent where the screen cannot. */
  onReopen?: () => Promise<boolean>;
  /** 「따로 안내함」 · 「안내 필요 없음」 for a registered answer behind the record — absent where the screen cannot. */
  onCloseToldBehind?: (closure: "TOLD_SEPARATELY" | "NOT_NEEDED") => Promise<boolean>;
  /** An approval was taken back in this session: the new answer waits for the seller's own approval. */
  reopened?: boolean;
  /** What kind of inquiry this is: the service's reading and the seller's correction. */
  kind?: InquiryKindView | null;
  /** 「이 판단으로 답변 다시 만들기」 — absent where the screen cannot; never offered on an approved answer. */
  onCorrectKind?: (kind: InquiryKindName) => Promise<boolean>;
  /** A plain redraft under the standing correction, when the answer on screen was written before it. */
  onRedraftKind?: () => void;
  /** The seller's day; tests fix it. */
  today?: string;
}) {
  const [writing, setWriting] = useState(false);
  const [pick, setPick] = useState<SellerCheckOutcome | null>(null);
  const [note, setNote] = useState("");
  const [local, setLocal] = useState<string | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const [kindOpen, setKindOpen] = useState(false);

  if (loadFailed) {
    return (
      <div className="mb-4 border-l-2 border-warn pl-3" role="status" data-testid="seller-check-load-failed">
        <p className="break-keep text-base leading-relaxed text-ink">판매자센터에서 확인한 결과를 불러오지 못했어요.</p>
        <div className="mt-2">
          <Btn size="sm" variant="outline" onClick={onReload} disabled={busy}>
            다시 불러오기
          </Btn>
        </div>
      </div>
    );
  }
  const kindLine =
    kind && (kind.judged || kind.corrected) ? (
      <KindLine
        kind={kind}
        busy={busy}
        onCorrect={approved ? undefined : onCorrectKind}
        error={error}
        hasDraft={hasDraft}
        asked={Boolean(ask) && !check}
        onRedraft={onRedraftKind}
        onOpenChange={setKindOpen}
      />
    ) : null;
  if (!ask && !check) {
    // Nothing to check on the seller centre: the reading still shows, so 「방법 안내」 can be corrected the other way.
    return kindLine && !approved ? (
      <div className="mb-4" data-testid="seller-check-kind-only">
        {kindLine}
      </div>
    ) : null;
  }

  async function submit() {
    if (!pick) {
      setLocal("확인한 결과를 하나 골라 주세요.");
      return;
    }
    if (!note.trim()) {
      setLocal(`「${OUTCOMES[pick].field}」에 한 줄을 적어 주세요. 비워 두면 답변을 만들지 않아요.`);
      return;
    }
    setLocal(null);
    if (await onRecord(pick, note.trim())) {
      setWriting(false);
      setPick(null);
      setNote("");
    }
  }

  const form = (
    <div className="mt-3 flex flex-col gap-3" data-testid="seller-check-form">
      <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="확인한 결과">
        {ORDER.map((o) => (
          <Btn
            key={o}
            variant={pick === o ? "selected" : "outline"}
            size="sm"
            role="radio"
            aria-checked={pick === o}
            title={OUTCOMES[o].help}
            onClick={() => {
              setPick(o);
              setLocal(null);
            }}
          >
            {OUTCOMES[o].label}
          </Btn>
        ))}
      </div>
      {pick ? (
        <div>
          <label className="block text-sm font-medium text-ink" htmlFor="seller-check-note">
            {OUTCOMES[pick].field}
          </label>
          <input
            id="seller-check-note"
            type="text"
            value={note}
            maxLength={300}
            disabled={busy}
            onChange={(e) => setNote(e.target.value)}
            placeholder={OUTCOMES[pick].placeholder}
            className="mt-1 min-h-[40px] w-full rounded-lg border border-line bg-surface px-3 text-ink"
          />
          <p className="mt-1 break-keep text-sm text-muted">
            {pick === "IN_PROGRESS"
              ? "끝나지 않은 일은 「완료」로 쓰지 않고, 정해지지 않은 날짜도 쓰지 않아요."
              : "적어 주신 내용만 답변에 써요. 예시 문장은 쓰지 않아요."}
          </p>
        </div>
      ) : local || error ? null : (
        <p className="break-keep text-sm text-muted">확인한 결과를 하나 골라 주세요.</p>
      )}
      <p className="break-keep text-sm text-muted">
        이 기록은 <b className="text-ink">이 문의에만</b> 쓰여요. 가게 전체 기준으로 저장되지 않고, 채널이 확인한 사실과 따로
        표시해요.
      </p>
      {local || error ? (
        <p className="break-keep text-sm text-bad" role="alert" data-testid="seller-check-error">
          {local ?? error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Btn onClick={() => void submit()} disabled={busy}>
          {approved ? (busy ? "기록하는 중…" : "기록 고치기") : busy ? "답변을 만드는 중…" : "기록하고 답변 만들기"}
        </Btn>
        <Btn
          variant="outline"
          disabled={busy}
          onClick={() => {
            setWriting(false);
            setLocal(null);
          }}
        >
          취소
        </Btn>
      </div>
    </div>
  );

  if (!check) {
    const a = ASK[ask as SellerCheckAsk];
    return (
      <div className="mb-4 border-l-2 border-warn pl-3" data-testid="seller-check-ask">
        <p className="break-keep text-lg font-semibold leading-relaxed text-ink">{a.title}</p>
        <p className="mt-1 break-keep text-base leading-relaxed text-ink">{a.body}</p>
        {kindLine ?? <p className="mt-1 break-keep text-sm text-muted">서비스 판단: {a.judged}</p>}
        {writing ? (
          form
        ) : kindOpen ? null : (
          <div className="mt-3">
            <Btn onClick={() => setWriting(true)}>확인한 결과 적기</Btn>
            <p className="mt-2 break-keep text-sm text-muted">
              주문은 판매자센터에서 직접 찾아 주세요. 이 화면은 주문을 고르거나 환불을 실행하지 않아요.
            </p>
          </div>
        )}
      </div>
    );
  }

  /**
   * 「다음 확인」 — the seller's own next look at an action still in progress (approved mockup, round 4). The day is a task
   * for the seller, never a promise to the customer; on that day the inquiry comes back to the Home as a check, and nothing
   * is sent because a day passed.
   */
  function nextCheckBox() {
    const current = check?.nextCheckOn ?? null;
    const chosen = day ?? current ?? plusDays(today, 2);
    const choices = nextCheckChoices(today, current);
    return (
      <div className="mt-3 border-l-2 border-warn pl-3" data-testid="seller-check-next">
        <p className="text-sm font-semibold text-ink">다음 확인</p>
        <p className="mt-1 break-keep text-base leading-relaxed text-ink">
          처리가 끝났는지 서비스는 알 수 없어요.{" "}
          {current ? (
            <>
              <b>{dayLabel(current)}</b>에 이 문의가 다시 할 일에 올라와요.
            </>
          ) : (
            "확인할 날을 정해 두면 그날 이 문의가 다시 할 일에 올라와요."
          )}
        </p>
        {onSetNextCheck ? (
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <label className="sr-only" htmlFor="seller-check-next-day">
              다시 확인할 날
            </label>
            <select
              id="seller-check-next-day"
              value={chosen}
              disabled={busy}
              onChange={(e) => setDay(e.target.value)}
              className="min-h-[40px] rounded-lg border border-line bg-surface px-3 text-ink"
            >
              {choices.map((c) => (
                <option key={c.day} value={c.day}>
                  {c.label}
                </option>
              ))}
            </select>
            <Btn
              size="sm"
              variant="outline"
              disabled={busy || chosen === current}
              onClick={() => {
                void onSetNextCheck(chosen).then((ok) => {
                  if (ok) setDay(null);
                });
              }}
            >
              이 날로 정하기
            </Btn>
          </div>
        ) : null}
        <p className="mt-1 break-keep text-sm text-muted">
          사장님이 다시 확인할 날이에요. 고객에게는 날짜를 약속하지 않아요. 끝났으면 「결과 고치기」에서 「처리 완료」로 바꿔
          주세요.
        </p>
      </div>
    );
  }

  const o = OUTCOMES[check.outcome];
  const pressFailed = draftState === "FAILED" || draftState === "NOT_WRITTEN";
  const notDrafted = !approved && (pressFailed || (draftMatches === false && draftState !== "READY"));
  return (
    <div className="mb-4" data-testid="seller-check-record">
      {notDrafted ? (
        <div className="mb-3 border-l-2 border-warn pl-3" role="status" data-testid="seller-check-not-drafted">
          <p className="break-keep text-lg font-semibold leading-relaxed text-ink">
            {pressFailed ? "확인한 내용은 저장했어요. 답변은 아직 준비하지 못했어요." : "이 기록으로 만든 답변이 아직 없어요."}
          </p>
          <p className="mt-1 break-keep text-sm text-muted">
            {pressFailed
              ? draftState === "NOT_WRITTEN"
                ? (draftReason ?? "이 기록만으로는 아직 답변을 만들지 못했어요. 적어 주신 기록은 그대로 남아 있어요.")
                : "답변을 만드는 중에 문제가 생겼어요. 적어 주신 기록은 그대로 남아 있어요."
              : hasDraft
                ? "기록은 저장되어 있어요. 지금 보이는 답변은 이 기록으로 만든 것이 아니라 그대로 보내지 않아요."
                : "기록은 저장되어 있어요."}
          </p>
          <div className="mt-3">
            <Btn onClick={onRedraft} disabled={busy}>
              {busy ? "답변을 만드는 중…" : pressFailed ? "이 기록으로 답변 다시 만들기" : "이 기록으로 답변 만들기"}
            </Btn>
          </div>
          <p className="mt-2 break-keep text-sm text-muted">다시 만들어도 기록은 새로 저장하지 않아요.</p>
        </div>
      ) : null}
      <p className="text-sm font-semibold text-muted">판매자센터에서 확인한 결과</p>
      <p className="mt-1 break-keep text-base leading-relaxed text-ink">
        <span className="mr-2 font-semibold">{o.label}</span>
        {check.note}
      </p>
      <p className="mt-0.5 break-keep text-sm text-muted">
        판매자 확인 · {formatAt(check.recordedAt)} · 이 문의에만 사용
      </p>
      {approved ? null : kindLine}
      {approved ? (
        <>
          {answered && !answered.current ? (
            <AnsweredApart
              answered={answered}
              check={check}
              registered={registered}
              ours={registeredTextIsOurs}
              busy={busy}
              onReopen={onReopen}
              onCloseToldBehind={onCloseToldBehind}
            />
          ) : (
            registered && !registeredTextIsOurs ? (
              <div className="mt-2" data-testid="seller-check-after">
                <p className="break-keep text-base font-medium text-ink">
                  {registeredElsewhereLine(check.outcome).line}
                </p>
                <p className="mt-0.5 break-keep text-sm text-muted" data-testid="seller-check-after-record">
                  {registeredElsewhereLine(check.outcome).record}
                </p>
              </div>
            ) : (
              <p className="mt-2 break-keep text-base font-medium text-ink" data-testid="seller-check-after">
                {afterApprovalLine(answered?.outcome ?? check.outcome, registered)}
              </p>
            )
          )}
          {check.outcome === "IN_PROGRESS" ? nextCheckBox() : null}
          {check.outcome === "NEEDS_CUSTOMER_INFO" && registered ? (
            <p className="mt-1 break-keep text-sm text-muted">
              고객이 회신하면 판매자센터에서 확인한 뒤 결과를 고쳐 주세요. 서비스는 고객의 회신을 알 수 없어요.
            </p>
          ) : null}
          {draftState === "KEPT" ? (
            <p className="mt-2 break-keep text-sm text-ink" role="status" data-testid="seller-check-kept">
              기록을 고쳤어요. 이미 승인한 답변은 그대로 두었어요.
            </p>
          ) : null}
          {writing ? (
            form
          ) : (
            <div className="mt-2">
              <button
                type="button"
                className="text-sm font-medium text-ink underline underline-offset-4"
                onClick={() => setWriting(true)}
                disabled={busy}
              >
                결과 고치기
              </button>
              <span className="ml-2 text-sm text-muted">
                {registered
                  ? registeredTextIsOurs
                    ? "이미 보낸 답변은 그대로 둬요"
                    : "등록된 답변은 그대로 둬요"
                  : "승인한 답변은 그대로 두고, 결과가 바뀌면 그 답변은 등록하지 않아요"}
              </span>
              {error ? (
                <p className="mt-2 break-keep text-sm text-bad" role="alert" data-testid="seller-check-error">
                  {error}
                </p>
              ) : null}
            </div>
          )}
        </>
      ) : writing ? (
        form
      ) : (
        <div className="mt-2">
          {reopened ? (
            <p className="mb-2 break-keep text-sm text-ink" role="status" data-testid="seller-check-reopened">
              승인을 거두었어요. 이 기록으로 만든 새 답변을 확인하고 다시 승인해 주세요. 거둔 승인은 기록으로 남아요.
            </p>
          ) : null}
          <button
            type="button"
            className="text-sm font-medium text-ink underline underline-offset-4"
            onClick={() => setWriting(true)}
            disabled={busy}
          >
            결과 고치기
          </button>
          <span className="ml-2 text-sm text-muted">고치면 답변을 다시 만들고 다시 검사해요</span>
          {error ? (
            <p className="mt-2 break-keep text-sm text-bad" role="alert" data-testid="seller-check-error">
              {error}
            </p>
          ) : null}
        </div>
      )}
    </div>
  );
}

export function formatAt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
