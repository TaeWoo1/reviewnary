import { useEffect, useState } from "react";
import { api } from "../../../lib/apiClient";
import type {
  InquiryRowItem,
  IssueEvidenceView,
  ProductReviewItem,
  ReviewIssueView,
} from "../../../lib/types";

/** 근거 문장 한 줄 — 어느 문제의 것인지까지 들고 다닌다. */
export type ProductEvidenceQuote = IssueEvidenceView & { issueId: string; issueTitle: string };

export type ProductRecord = {
  /** 답변을 기다리는 문의 몇 줄. 읽지 못했으면 null — 빈 목록과 구별된다. */
  waiting: InquiryRowItem[] | null;
  /** 최근 리뷰 몇 줄. 같은 규칙. */
  reviews: ProductReviewItem[] | null;
  /** 상위 문제들이 이 상품에서 가진 가장 최근 문장 하나씩. */
  quotes: ProductEvidenceQuote[] | null;
};

/** 화면이 한 번에 펼치는 줄 수 — 세 줄은 목록이 아니라 표본이다. */
export const RECORD_ROWS = 3;

/**
 * 상품 레코드가 거느린 객체들 — 이미 있는 읽기로만.
 *
 * <b>새 endpoint는 없다.</b> 문의는 `/api/inquiries/rows`가 예전부터 받던 `productId` 축으로, 리뷰는
 * 상품 화면의 리뷰 숫자를 만든 바로 그 읽기(`/api/products/{id}/reviews`)로, 근거 문장은 문제 화면이
 * 쓰는 읽기(`/api/review-issues/{id}`)로 가져온다. 숫자를 새로 세거나 추론하는 곳은 한 군데도 없다.
 *
 * <b>근거 문장을 고르는 규칙은 하나다</b> — 이 상품에서 근거가 가장 많은 문제 셋, 각각의 가장 최근
 * 문장 하나. 「최근 셋」으로 뽑으면 한 문제가 세 줄을 다 가져가 다른 문제가 있다는 사실이 사라지고,
 * 임의로 고르면 다음에 열었을 때 다른 문장이 나오는 이유를 아무도 설명할 수 없다.
 *
 * <b>읽지 못한 것은 말하지 않는다.</b> 어느 읽기가 실패하면 그 섹션은 통째로 비고, 「0건」이라고
 * 적지 않는다 — 이 화면은 빈 목록과 못 읽은 목록을 구별할 수 없고, 둘 중 어느 쪽도 상품에 대한
 * 사실이 아니다.
 */
export function useProductRecord(productId: string, issues: ReviewIssueView[]): ProductRecord {
  const [waiting, setWaiting] = useState<InquiryRowItem[] | null>(null);
  const [reviews, setReviews] = useState<ProductReviewItem[] | null>(null);
  const [quotes, setQuotes] = useState<ProductEvidenceQuote[] | null>(null);

  useEffect(() => {
    let active = true;
    setWaiting(null);
    setReviews(null);
    void api
      .getInquiryRowsStrict({ productId, status: "UNANSWERED", limit: RECORD_ROWS })
      .then((page) => active && setWaiting(page.items ?? []))
      .catch(() => active && setWaiting(null));
    void api
      .getProductReviews(productId, { page: 0, size: RECORD_ROWS })
      .then((page) => active && setReviews(page.items ?? []))
      .catch(() => active && setReviews(null));
    return () => {
      active = false;
    };
  }, [productId]);

  // 문제 목록이 바뀔 때만 다시 읽는다. 배열은 렌더마다 새 객체이므로 id로 비교한다.
  const topIssues = issues.slice(0, RECORD_ROWS);
  const key = topIssues.map((issue) => issue.id).join(",");

  useEffect(() => {
    let active = true;
    setQuotes(null);
    if (topIssues.length === 0) return;
    void Promise.all(
      topIssues.map((issue) =>
        api
          .getReviewIssueDetailStrict(issue.id)
          .then((detail) => newestQuote(detail.evidence, productId, issue))
          .catch(() => null),
      ),
    ).then((rows) => {
      if (!active) return;
      const found = rows.filter((row): row is ProductEvidenceQuote => row !== null);
      setQuotes(found.length > 0 ? found : null);
    });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId, key]);

  return { waiting, reviews, quotes };
}

/** 이 상품의 근거 중 가장 최근 문장 하나. 문장이 없는 근거(별점만 남긴 리뷰)는 셈에서 빠진다. */
function newestQuote(
  evidence: IssueEvidenceView[],
  productId: string,
  issue: ReviewIssueView,
): ProductEvidenceQuote | null {
  const mine = evidence
    .filter((row) => row.productId === productId && row.quote && row.quote.trim())
    .sort((a, b) => b.occurredOn.localeCompare(a.occurredOn));
  return mine.length === 0 ? null : { ...mine[0], issueId: issue.id, issueTitle: issue.title };
}
