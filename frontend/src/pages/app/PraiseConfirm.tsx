import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { Btn } from "../../components/ui/Btn";
import { Stars } from "../../components/reviews/Stars";
import { api } from "../../lib/apiClient";
import { newCommandId } from "../../lib/commandId";
import { isPraiseApproval, type HomeWorkRow } from "../../lib/homeWork";
import { loadWork } from "../../lib/workList";
import { approveBlockedSentence } from "../../components/VocItemReplyPrep";
import { COPY } from "../../lib/copy/customerOps";
import type { ReviewReplyPrep } from "../../lib/types";

/** How many replies are read at once — the reads are one per review. */
const READS_AT_ONCE = 4;

type Outcome = { kind: "done"; road: Road } | { kind: "failed"; sentence: string };

/**
 * <b>Which way this reply goes on</b> (2026-10-28, owner — found in review). The same press the review's own page
 * makes, per review:
 * <ul>
 *   <li>{@code HAND_OVER} — on a PC linked to the delegate lane the draft is already on the delegate's phone, and the
 *       page's main press is 「답글 등록 요청」: the delegate posts it. The first version of this screen approved every
 *       review on the copy road instead, and approving here takes the phone's draft back first
 *       (`yieldToApprovalHere`) — 26 thank-yous meant for the delegate would have been cancelled on their phone and
 *       left for the seller to paste by hand.</li>
 *   <li>{@code APPROVE} — no lane: 「답글 확정」, then the seller copies it to the seller center.</li>
 * </ul>
 */
type Road = "HAND_OVER" | "APPROVE";

interface Plan {
  road: Road | null;
  /** Why it cannot go on from here — open it on its own page. Null when it can. */
  why: string | null;
}

export function planFor(prep: ReviewReplyPrep): Plan {
  if (!prep.draft) return { road: null, why: "먼저 답글을 써 주세요." };
  if ((prep.phraseFindings ?? []).some((f) => f.kind === "FORBIDDEN")) {
    return { road: null, why: "쓰지 않기로 한 표현이 있어요. 열어서 고쳐 주세요." };
  }
  const lane = prep.delegation ?? null;
  if (lane) {
    if (lane.setAside) return { road: null, why: "휴대폰과 PC의 문장이 달라요. 열어서 어느 쪽을 쓸지 골라 주세요." };
    return lane.canSend
      ? { road: "HAND_OVER", why: null }
      : { road: null, why: "담당자 쪽에서 진행 중이에요. 열어서 상태를 확인해 주세요." };
  }
  if (prep.capabilities.canApprove) return { road: "APPROVE", why: null };
  return {
    road: null,
    why: approveBlockedSentence({
      dirty: false,
      phraseBlock: null,
      responseNeeded: prep.triageDisposition === "RESPONSE_NEEDED",
      reason: prep.approveUnavailableReason,
    }),
  };
}

interface Item {
  row: HomeWorkRow;
  /** undefined while reading, null when the read failed. */
  prep: ReviewReplyPrep | null | undefined;
}

/**
 * <b>칭찬 리뷰 답글, 한 번에 확인</b> (2026-10-28 — owner-approved fix from the walk-through: 49 replies waiting for 확정
 * were worked by opening each one and coming back to the list).
 *
 * <p>Each ★4–5 review that reports nothing (`isPraiseApproval`) stands beside the reply already written for it. The
 * seller reads them, unticks any they want to look at alone, and confirms the rest. <b>Each confirm is the same write
 * the review page makes</b> — one approval per review, bound to the version shown here, through every check the server
 * applies (a banned phrase, a delegate holding it, the channel already answered). Nothing is posted to a marketplace.
 */
export function PraiseConfirm() {
  const [items, setItems] = useState<Item[] | null | undefined>(undefined);
  const [unticked, setUnticked] = useState<Set<string>>(new Set());
  const [outcomes, setOutcomes] = useState<Map<string, Outcome>>(new Map());
  const [running, setRunning] = useState(false);

  useEffect(() => {
    let live = true;
    void loadWork().then(async (work) => {
      if (!live) return;
      if (!work) {
        setItems(null);
        return;
      }
      const rows = work.rows.filter((r) => isPraiseApproval(r) && r.reply);
      setItems(rows.map((row) => ({ row, prep: undefined })));
      // A few at a time, each landing as it arrives.
      let next = 0;
      const worker = async () => {
        while (live && next < rows.length) {
          const row = rows[next++]!;
          const prep = await api.getReviewReplyPrep(row.reply!.accountId, row.reply!.actionRef).catch(() => null);
          if (!live) return;
          setItems((current) => current?.map((item) => (item.row.key === row.key ? { ...item, prep } : item)) ?? current);
        }
      };
      await Promise.all(Array.from({ length: READS_AT_ONCE }, worker));
    });
    return () => {
      live = false;
    };
  }, []);

  const ready = useMemo(
    () => (items ?? []).filter((item) => item.prep && planFor(item.prep).road && !outcomes.has(item.row.key)),
    [items, outcomes],
  );
  const chosen = ready.filter((item) => !unticked.has(item.row.key));
  const handOver = chosen.filter((item) => planFor(item.prep!).road === "HAND_OVER").length;
  const approve = chosen.length - handOver;
  const doneOutcomes = [...outcomes.values()].filter((o): o is Extract<Outcome, { kind: "done" }> => o.kind === "done");
  const anyLane = (items ?? []).some((item) => item.prep?.delegation);

  async function confirmChosen() {
    if (running) return;
    setRunning(true);
    for (const item of chosen) {
      const prep = item.prep!;
      const road = planFor(prep).road!;
      try {
        if (road === "HAND_OVER") {
          // The review page's 「답글 등록 요청」, bound to the version shown here.
          await api.handOverReviewReply(item.row.subjectId, prep.draft!.version);
        } else {
          await api.decideReviewReplyApproval(item.row.reply!.accountId, item.row.reply!.actionRef, {
            commandId: newCommandId(),
            state: "APPROVED",
            baseVersion: prep.draft!.version,
          });
        }
        setOutcomes((m) => new Map(m).set(item.row.key, { kind: "done", road }));
      } catch {
        setOutcomes((m) =>
          new Map(m).set(item.row.key, {
            kind: "failed",
            sentence: road === "HAND_OVER" ? "등록을 요청하지 못했어요. 이 리뷰를 열어 확인해 주세요." : "확정하지 못했어요. 이 리뷰를 열어 확인해 주세요.",
          }),
        );
      }
    }
    setRunning(false);
  }

  const pressLabel = running
    ? "보내는 중…"
    : handOver > 0 && approve > 0
      ? `고른 ${chosen.length.toLocaleString("ko-KR")}건 처리`
      : handOver > 0
        ? `고른 ${handOver.toLocaleString("ko-KR")}건 등록 요청`
        : `고른 ${approve.toLocaleString("ko-KR")}건 확정`;
  const doneSentence = (() => {
    const asked = doneOutcomes.filter((o) => o.road === "HAND_OVER").length;
    const confirmed = doneOutcomes.length - asked;
    const parts = [
      asked > 0 ? `${asked.toLocaleString("ko-KR")}건 등록 요청했어요` : null,
      confirmed > 0 ? `${confirmed.toLocaleString("ko-KR")}건 확정했어요` : null,
    ].filter(Boolean);
    return parts.length > 0 ? `${parts.join(" · ")}. ` : "";
  })();

  const back = (
    <Link to="/customer-operations/cases" className="text-sm font-semibold text-muted hover:text-ink hover:underline">
      ← {COPY.listTitle}
    </Link>
  );

  if (items === undefined) {
    return (
      <div className="space-y-4">
        {back}
        <PageHead title="칭찬 리뷰 답글 확인" />
        <p className="text-sm text-muted">불러오는 중이에요.</p>
      </div>
    );
  }
  if (items === null || items.length === 0) {
    return (
      <div className="space-y-4">
        {back}
        <PageHead title="칭찬 리뷰 답글 확인" />
        <p className="text-sm text-muted">
          {items === null ? "할 일을 불러오지 못했어요. 잠시 후 다시 열어 주세요." : "확정을 기다리는 칭찬 리뷰 답글이 없어요."}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {back}
      <PageHead title="칭찬 리뷰 답글 확인" />
      <p className="break-keep text-sm text-muted">
        ★4–5 리뷰에 써 둔 답글이에요. 읽어 보고, 따로 볼 것은 체크를 풀어 주세요.{" "}
        {anyLane
          ? "담당자가 연결된 리뷰는 담당자에게 등록을 요청하고, 나머지는 확정만 해요 — 확정한 답글은 직접 복사해 올려요."
          : "확정해도 마켓에 바로 올라가지 않아요 — 확정한 답글은 직접 복사해 올려요."}
      </p>

      <ul aria-label="칭찬 리뷰와 답글" className="divide-y divide-line border-y border-line">
        {items.map((item) => (
          <PraiseRow
            key={item.row.key}
            item={item}
            ticked={!unticked.has(item.row.key)}
            outcome={outcomes.get(item.row.key) ?? null}
            disabled={running}
            onToggle={() =>
              setUnticked((set) => {
                const next = new Set(set);
                if (next.has(item.row.key)) next.delete(item.row.key);
                else next.add(item.row.key);
                return next;
              })
            }
          />
        ))}
      </ul>

      {/* The one press, on the floor of the screen while the list scrolls under it. */}
      <div className="sticky bottom-0 flex flex-wrap items-center gap-3 border-t border-line bg-canvas py-3 after:absolute after:inset-x-0 after:top-full after:h-8 after:bg-canvas after:content-['']">
        <span className="text-sm text-ink" role="status">
          {doneSentence}
          {chosen.length === 0
            ? "고른 답글이 없어요"
            : handOver > 0 && approve > 0
              ? `담당자 등록 요청 ${handOver.toLocaleString("ko-KR")}건 · 직접 확정 ${approve.toLocaleString("ko-KR")}건`
              : `${chosen.length.toLocaleString("ko-KR")}건 골랐어요`}
        </span>
        <Btn className="ml-auto" disabled={running || chosen.length === 0} onClick={() => void confirmChosen()}>
          {pressLabel}
        </Btn>
      </div>
    </div>
  );
}


function PraiseRow({
  item,
  ticked,
  outcome,
  disabled,
  onToggle,
}: {
  item: Item;
  ticked: boolean;
  outcome: Outcome | null;
  disabled: boolean;
  onToggle: () => void;
}) {
  const { row, prep } = item;
  const plan = prep ? planFor(prep) : null;
  const ready = plan?.road != null;
  const why = prep === undefined ? "답글을 읽는 중이에요." : prep === null ? "답글을 읽지 못했어요." : plan!.why;
  const checkId = `praise-${row.subjectId}`;
  return (
    <li className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 py-4 md:grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)] md:gap-x-6" data-testid="praise-row">
      <div className="pt-0.5">
        {outcome?.kind === "done" ? (
          <span className="text-sm font-semibold text-good">{outcome.road === "HAND_OVER" ? "요청함" : "확정함"}</span>
        ) : ready ? (
          <input
            id={checkId}
            type="checkbox"
            checked={ticked}
            disabled={disabled}
            onChange={onToggle}
            className="h-5 w-5 accent-[oklch(var(--lch-board))]"
            aria-label={`${plan?.road === "HAND_OVER" ? "등록 요청할" : "확정할"} 답글로 고르기: ${row.title}`}
          />
        ) : (
          <span aria-hidden="true" className="inline-block h-5 w-5" />
        )}
      </div>
      <div className="min-w-0 space-y-1">
        <p className="flex flex-wrap items-baseline gap-x-2 text-sm text-muted">
          {row.rating != null ? <Stars rating={row.rating} /> : null}
          <span className="truncate">{row.line}</span>
        </p>
        <label htmlFor={ready ? checkId : undefined} className="block break-keep text-base leading-relaxed text-ink">
          {row.title}
        </label>
        <Link to={`${row.to}?from=work`} className="text-sm font-semibold text-muted hover:text-ink hover:underline">
          열어서 보기
        </Link>
      </div>
      <div className="col-start-2 min-w-0 rounded-lg bg-surface px-3 py-2 ring-1 ring-line md:col-start-auto">
        {prep?.draft ? (
          <p className="whitespace-pre-wrap break-keep text-sm leading-relaxed text-ink">{prep.draft.body}</p>
        ) : null}
        {plan?.road === "HAND_OVER" && outcome == null ? (
          <p className="pt-1 text-xs text-muted">담당자가 휴대폰에서 등록해요</p>
        ) : null}
        {why ? <p className="break-keep text-sm text-muted">{why}</p> : null}
        {outcome?.kind === "failed" ? <p className="break-keep text-sm text-bad">{outcome.sentence}</p> : null}
      </div>
    </li>
  );
}
