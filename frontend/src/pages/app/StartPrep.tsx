import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { BtnLink } from "../../components/ui/Btn";
import { KnowledgeInbox } from "../../components/knowledge/KnowledgeInbox";
import { KnowledgeDocumentAdd } from "../../components/knowledge/KnowledgeDocuments";
import { api } from "../../lib/apiClient";
import type {
  KnowledgeCandidateView,
  LearnedKnowledgeView,
  SellerAccountResponse,
} from "../../lib/types";

/**
 * <b>답변 준비 — 처음 한 번</b> (2026-10-27, the first of the three screens the owner approved: 첫 준비 → 홈 → 상품·정책).
 *
 * <p>What the store already has is gathered, not asked for: the page says what was read from each source and, where
 * nothing could be, the channel's own reason. The seller's part is only what a reply needs and nothing holds — the
 * open items, most reviews affected first (§12-4). Nobody else asks only for the missing facts before the first
 * reply; that is this product's difference and its least proven part, so the page invents nothing: no source that
 * is not wired (a watched folder is not), no count the server did not give.
 *
 * <p>Not in the menu. Reached from 홈 and 상품·정책, and it never blocks the work: 「나중에 하기」 is always there.
 */
const SHOWN_ITEMS = 5;

/** The sources a reply can stand on, in the order the seller thinks of them, with the screen's own words. */
const SOURCES: { key: string; label: string; use: string }[] = [
  { key: "PRODUCT_DETAIL", label: "상품 상세", use: "상품마다 무엇을 어떻게 쓰는지" },
  { key: "PAST_REVIEW_REPLY", label: "지난 리뷰 답글", use: "말투 참고, 정책 근거로는 쓰지 않아요" },
  { key: "PAST_INQUIRY_ANSWER", label: "지난 문의 답변", use: "자주 한 답" },
  { key: "SELLER_MATERIAL", label: "올린 자료 파일", use: "정책, 안내서, 자주 묻는 질문" },
];

const WAY_FIRST = ["SCREEN_READ", "NOT_WIRED", "SCREEN_UNPROVEN", "NOT_PROMOTED", "NOT_AVAILABLE"];

export function StartPrep() {
  const [accounts, setAccounts] = useState<SellerAccountResponse[] | null>(null);
  const [candidates, setCandidates] = useState<KnowledgeCandidateView[] | null>(null);
  const [learned, setLearned] = useState<LearnedKnowledgeView | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setFailed(false);
    const [acc, cands, learn] = await Promise.all([
      api.getSellerAccountsStrict().catch(() => null),
      api.getKnowledgeCandidates().catch(() => null),
      api.getLearnedKnowledge().catch(() => null),
    ]);
    if (acc) setAccounts(acc);
    if (cands) setCandidates(cands);
    if (learn) setLearned(learn.learned);
    if (!acc || !cands || !learn) setFailed(true);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const open = candidates ?? [];
  const top = [...open].sort((a, b) => b.evidenceCount - a.evidenceCount).slice(0, SHOWN_ITEMS);
  const connected = accounts === null ? null : accounts.length;

  return (
    <div className="max-w-content space-y-6">
      <PageHead title="답변 준비" />
      {failed ? (
        <p className="break-keep text-sm text-bad" role="alert">
          일부를 불러오지 못했어요.{" "}
          <button type="button" onClick={() => void load()} className="font-semibold underline underline-offset-2">
            다시 불러오기
          </button>
        </p>
      ) : null}

      <ol aria-label="준비 단계" className="grid gap-px overflow-hidden rounded-xl border border-line bg-line md:grid-cols-3">
        <Step
          n={1}
          title="채널 연동"
          state={connected === null ? "wait" : connected > 0 ? "done" : "now"}
          note={connected === null ? "확인하는 중…" : connected > 0 ? `${connected}곳 연동됨` : "연동한 채널이 없어요"}
        />
        <Step
          n={2}
          title="가게 자료 준비"
          state={connected === 0 ? "later" : candidates === null ? "wait" : open.length > 0 ? "now" : "done"}
          note={candidates === null ? "확인하는 중…" : open.length > 0 ? `채울 내용 ${open.length}개` : "채울 내용이 없어요"}
        />
        <Step n={3} title="준비된 답변 확인" state={open.length === 0 && candidates !== null ? "now" : "later"} note="리뷰·문의에서 확인해요" />
      </ol>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_400px]">
        <section aria-label="답에 필요한데 아직 없는 것" className="space-y-2">
          <SectionHeader
            title="답에 필요한데 아직 없는 것"
            hint="리뷰가 많이 묻는 것부터예요"
            action={
              open.length > SHOWN_ITEMS ? (
                <Link to="/knowledge" className="text-sm font-semibold text-ink underline-offset-4 hover:underline">
                  {open.length}개 모두 보기
                </Link>
              ) : undefined
            }
          />
          {connected === 0 ? (
            <p className="break-keep py-2 text-sm text-muted">채널을 연동하면 리뷰를 읽고 여기에 물어볼 것을 모아요.</p>
          ) : candidates === null ? (
            <p className="py-2 text-sm text-muted">불러오는 중…</p>
          ) : (
            <KnowledgeInbox candidates={top} documents={[]} onChanged={load} />
          )}
        </section>

        <section aria-label="가져온 자료" className="space-y-2">
          <SectionHeader title="가져온 자료" hint="자동으로 가져와요" />
          {learned === null ? (
            <p className="py-2 text-sm text-muted">불러오는 중…</p>
          ) : (
            <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
              {SOURCES.map((source) => (
                <SourceRow key={source.key} source={source} learned={learned} onImported={load} />
              ))}
            </ul>
          )}
        </section>
      </div>

      <div className="flex flex-wrap items-center justify-end gap-2">
        <BtnLink to="/" variant="ghost">
          나중에 하기
        </BtnLink>
        <BtnLink to="/reviews">준비된 답변 보기</BtnLink>
      </div>
    </div>
  );
}

function Step({
  n,
  title,
  note,
  state,
}: {
  n: number;
  title: string;
  note: string;
  state: "done" | "now" | "later" | "wait";
}) {
  const mark =
    state === "done" ? "bg-good/10 text-good" : state === "now" ? "bg-brand-700 text-white" : "bg-canvas text-muted";
  return (
    <li
      aria-current={state === "now" ? "step" : undefined}
      className={`flex items-center gap-3 px-4 py-3 ${state === "now" ? "bg-well" : "bg-surface"}`}
    >
      <span aria-hidden="true" className={`grid h-7 w-7 shrink-0 place-items-center rounded-full text-sm font-bold ${mark}`}>
        {state === "done" ? "✓" : n}
      </span>
      <span className="min-w-0">
        <span className="block font-semibold text-ink">{title}</span>
        <span className="block break-keep text-sm text-muted">
          {state === "done" ? <span className="sr-only">끝남 · </span> : null}
          {note}
        </span>
      </span>
    </li>
  );
}

/**
 * One source: what was read, or the channel's own reason it could not be. A count is the server's; when there is
 * none, the first channel line that says why stands in its place, and silence is 「아직 없어요」, never a zero.
 */
function SourceRow({
  source,
  learned,
  onImported,
}: {
  source: { key: string; label: string; use: string };
  learned: LearnedKnowledgeView;
  onImported: () => void | Promise<void>;
}) {
  const read = learned.sources.find((s) => s.key === source.key);
  const count = read?.count ?? 0;
  // A channel that HAS a way (a bounded screen read through the helper, a path not wired yet) says more to the
  // seller than one that has none, so it speaks first.
  const lines = learned.channels.filter((line) => line.source === source.key && line.availability !== "LEARNED");
  const why = [...lines].sort((a, b) => WAY_FIRST.indexOf(a.availability) - WAY_FIRST.indexOf(b.availability))[0];
  return (
    <li className="flex items-start gap-3 px-4 py-3" data-testid={`start-source-${source.key}`}>
      <span className="min-w-0 flex-1">
        <span className="block font-semibold text-ink">
          {source.label}
          {count > 0 ? <span className="ml-2 tabular-nums">{count.toLocaleString("ko-KR")}건</span> : null}
        </span>
        <span className="block break-keep text-sm text-muted">
          {count > 0 ? source.use : why ? `${why.channelNameKo}: ${why.sentenceKo}` : "아직 없어요"}
        </span>
      </span>
      {source.key === "SELLER_MATERIAL" ? (
        <KnowledgeDocumentAdd scope="ORG" onImported={onImported} label="파일 올리기" />
      ) : (
        <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${count > 0 ? "bg-good/10 text-good" : "bg-canvas text-muted"}`}>
          {count > 0 ? "가져옴" : "없음"}
        </span>
      )}
    </li>
  );
}
