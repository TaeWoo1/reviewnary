import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../lib/apiClient";
import type { ReplyHandoffView } from "../../lib/types";

/**
 * Why the agent stopped, in the seller's words — and, for a harm, the duty a seller may have beyond the reply (research
 * 2026-10-28: product-safety reports may be due within a day for some goods; whether a review triggers one needs advice).
 */
export const HANDOFF_WORD: Record<string, { label: string; note: string; urgent: boolean }> = {
  CATEGORY_S: {
    label: "안전 관련",
    note: "직접 확인해 주세요. 상품에 따라 법정 보고 의무가 있을 수 있어 검토가 필요해요.",
    urgent: true,
  },
  CATEGORY_L: { label: "분쟁 신호", note: "답글을 달기 전에 직접 확인해 주세요.", urgent: true },
  CATEGORY_X: { label: "신고 검토", note: "답글보다 플랫폼 신고를 먼저 검토해 주세요.", urgent: false },
  STILL_FAILS: { label: "직접 작성 필요", note: "AI가 기준에 맞는 답글을 쓰지 못했어요.", urgent: false },
  SAFETY_SIGNAL: {
    label: "안전 단어 확인",
    note: "리뷰에 다침·이물·신고 같은 말이 있어요. AI가 읽은 내용과 다를 수 있어 직접 확인해 주세요.",
    urgent: true,
  },
  PERSON_REVIEW: { label: "직접 검토", note: "AI가 답글보다 판매자가 먼저 볼 리뷰로 판단했어요.", urgent: false },
  UNSURE_AMBIGUOUS: {
    label: "표현이 모호함",
    note: "고객이 무엇을 말하는지 AI가 정하지 못했어요. 문제없다는 뜻이 아니니 읽고 판단해 주세요.",
    urgent: false,
  },
  UNSURE_PRODUCT: {
    label: "상품 확인 필요",
    note: "어떤 상품·옵션 이야기인지 연결되지 않았어요. 상품을 확인하면 답할 수 있어요.",
    urgent: false,
  },
  TOO_MANY_POINTS: {
    label: "다룰 점이 많음",
    note: "리뷰가 말한 문제·질문이 많아 답글 하나로 빠짐없이 다루기 어려워요. 직접 정리해 주세요.",
    urgent: false,
  },
  QUESTION_NO_FACTS: {
    label: "답할 사실 없음",
    note: "리뷰에 질문이 있는데 등록된 상품 정보·정책이 없어요. 답을 정해 주세요.",
    urgent: false,
  },
  KNOWLEDGE_MISSING: {
    label: "근거 부족",
    note: "등록된 정보가 이 질문·문제에 답하지 못해요. 위 「AI가 읽은 점」 중 답을 정해 주세요.",
    urgent: false,
  },
  KNOWLEDGE_NOT_SHOWN: {
    label: "자료 일부만 봄",
    note: "등록된 자료가 많아 AI가 일부만 보고 답을 찾지 못했어요. 자료가 없다는 뜻은 아니니 상품·정책을 확인해 주세요.",
    urgent: false,
  },
  REVIEW_PENDING: {
    label: "검토 대기",
    note: "오늘 AI 사용량 상한에 닿아 초안을 아직 검토하지 못했어요. 한도가 풀리면 새로 쓰지 않고 검토부터 이어서 해요.",
    urgent: false,
  },
  POLICY_CONFLICT: {
    label: "근거 충돌",
    note: "등록된 정보끼리 서로 다르게 적혀 있어요. 어느 쪽이 맞는지 정리해 주세요.",
    urgent: false,
  },
};

const FALLBACK = { label: "직접 확인 필요", note: "AI가 이 리뷰를 사람에게 넘겼어요.", urgent: false };

/**
 * <b>「AI가 사람에게 넘긴 리뷰」</b> (review 2026-10-28: 「넘긴 리뷰가 화면에 안 보인다」). What the reply agent would not
 * draft — a harm, a legal signal, a review to report, or a draft that still failed the standard — stays here until the
 * seller answers it. Drawn only when there is something; a failed read draws nothing.
 */
export function AgentHandoffs() {
  const [rows, setRows] = useState<ReplyHandoffView[] | null>(null);

  useEffect(() => {
    let live = true;
    // Inside the promise, so a read that throws before it starts is the same quiet nothing as one that fails.
    void Promise.resolve()
      .then(() => api.getReplyHandoffsStrict())
      .then((list) => live && setRows(Array.isArray(list) ? list : []))
      .catch(() => live && setRows([]));
    return () => {
      live = false;
    };
  }, []);

  if (!rows || rows.length === 0) return null;
  const ordered = [...rows].sort(
    (a, b) => Number((HANDOFF_WORD[b.reason ?? ""] ?? FALLBACK).urgent) - Number((HANDOFF_WORD[a.reason ?? ""] ?? FALLBACK).urgent),
  );
  return (
    <section aria-label="AI가 사람에게 넘긴 리뷰" className="rounded-2xl border border-line bg-surface p-3" data-testid="agent-handoffs">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-section font-bold tracking-tight text-ink">AI가 사람에게 넘긴 리뷰</h2>
        <span className="text-sm tabular-nums text-muted">{rows.length.toLocaleString("ko-KR")}건</span>
      </div>
      <ul className="mt-2 divide-y divide-line">
        {ordered.map((row) => {
          const word = HANDOFF_WORD[row.reason ?? ""] ?? FALLBACK;
          return (
            <li key={row.reviewId}>
              <Link
                to={`/reviews/reply/${row.reviewId}`}
                className="block break-keep py-2 text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
              >
                <span className="flex items-baseline gap-2">
                  <span className={`shrink-0 text-sm font-semibold ${word.urgent ? "text-bad" : "text-brand-700"}`}>{word.label}</span>
                  <span className="min-w-0 flex-1 truncate text-sm text-muted">{row.preview ?? ""}</span>
                  <span className="shrink-0 text-muted" aria-hidden="true">›</span>
                </span>
                {row.points && row.points.length > 0 ? (
                  <span className="mt-0.5 block text-sm text-ink">AI가 읽은 점: {row.points.join(" · ")}</span>
                ) : null}
                <span className="mt-0.5 block text-sm text-muted">
                  {row.unmet && row.unmet.length > 0 ? row.unmet.join(" ") : word.note}
                </span>
                {row.earlierDraft ? (
                  <span className="mt-0.5 block text-sm text-muted">넘기기 전에 만든 초안이 있어요. 그대로 쓰기 전에 다시 확인해 주세요.</span>
                ) : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
