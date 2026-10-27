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

type Outcome = { kind: "done" } | { kind: "failed"; sentence: string };

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
    () => (items ?? []).filter((item) => item.prep && readyToConfirm(item.prep) && !outcomes.has(item.row.key)),
    [items, outcomes],
  );
  const chosen = ready.filter((item) => !unticked.has(item.row.key));
  const done = [...outcomes.values()].filter((o) => o.kind === "done").length;

  async function confirmChosen() {
    if (running) return;
    setRunning(true);
    for (const item of chosen) {
      const prep = item.prep!;
      try {
        await api.decideReviewReplyApproval(item.row.reply!.accountId, item.row.reply!.actionRef, {
          commandId: newCommandId(),
          state: "APPROVED",
          baseVersion: prep.draft!.version,
        });
        setOutcomes((m) => new Map(m).set(item.row.key, { kind: "done" }));
      } catch {
        setOutcomes((m) => new Map(m).set(item.row.key, { kind: "failed", sentence: "확정하지 못했어요. 이 리뷰를 열어 확인해 주세요." }));
      }
    }
    setRunning(false);
  }

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
        ★4–5 리뷰에 써 둔 답글이에요. 읽어 보고, 따로 볼 것은 체크를 풀어 주세요. 확정해도 마켓에 바로 올라가지 않아요.
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
          {done > 0 ? `${done.toLocaleString("ko-KR")}건 확정했어요. ` : ""}
          {chosen.length > 0 ? `${chosen.length.toLocaleString("ko-KR")}건 골랐어요` : "고른 답글이 없어요"}
        </span>
        <Btn className="ml-auto" disabled={running || chosen.length === 0} onClick={() => void confirmChosen()}>
          {running ? "확정하는 중…" : `고른 ${chosen.length.toLocaleString("ko-KR")}건 확정`}
        </Btn>
      </div>
    </div>
  );
}

/** A reply that can be confirmed as shown: a saved draft, open to approval, nothing banned in it. */
function readyToConfirm(prep: ReviewReplyPrep): boolean {
  const banned = (prep.phraseFindings ?? []).some((f) => f.kind === "FORBIDDEN");
  return prep.capabilities.canApprove && prep.draft != null && !banned;
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
  const ready = prep != null && readyToConfirm(prep);
  const why =
    prep === undefined
      ? "답글을 읽는 중이에요."
      : prep === null
        ? "답글을 읽지 못했어요."
        : ready
          ? null
          : (prep.phraseFindings ?? []).some((f) => f.kind === "FORBIDDEN")
            ? "쓰지 않기로 한 표현이 있어요. 열어서 고쳐 주세요."
            : approveBlockedSentence({
                dirty: false,
                phraseBlock: null,
                responseNeeded: prep.triageDisposition === "RESPONSE_NEEDED",
                reason: prep.approveUnavailableReason,
              });
  const checkId = `praise-${row.subjectId}`;
  return (
    <li className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 py-4 md:grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)] md:gap-x-6" data-testid="praise-row">
      <div className="pt-0.5">
        {outcome?.kind === "done" ? (
          <span className="text-sm font-semibold text-good">확정함</span>
        ) : ready ? (
          <input
            id={checkId}
            type="checkbox"
            checked={ticked}
            disabled={disabled}
            onChange={onToggle}
            className="h-5 w-5 accent-[oklch(var(--lch-board))]"
            aria-label={`확정할 답글로 고르기: ${row.title}`}
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
        {why ? <p className="break-keep text-sm text-muted">{why}</p> : null}
        {outcome?.kind === "failed" ? <p className="break-keep text-sm text-bad">{outcome.sentence}</p> : null}
      </div>
    </li>
  );
}
