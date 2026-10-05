import { useEffect, useState } from "react";
import { api } from "../../lib/apiClient";
import { count } from "../../lib/format";
import type { OpportunityView } from "../../lib/types";
import { Link } from "react-router-dom";
import { SectionHeader } from "../ui/SectionHeader";
import { actionable } from "./opportunityWords";

/**
 * A product's improvement opportunities — the door from 「반복되는 문제 15건」 to what can be done about them.
 *
 * <b>Rows, not cards.</b> The place to decide and to prepare a draft is the issue's own surface
 * (고객운영 메모리), where the evidence is; this section says what is suggested and takes the seller there.
 * It reads the same product-scoped issue list the signal card above it is built from, so it can never
 * name an issue that card does not.
 *
 * <b>Fail-soft, and zero is silence.</b> A failed read renders nothing rather than a warning — an
 * empty list and an unread list cannot be told apart here, and neither is a fact about the product.
 */
export function ProductOpportunities({ productId }: { productId: string }) {
  const [items, setItems] = useState<OpportunityView[] | null>(null);

  useEffect(() => {
    let active = true;
    void api
      .getOpportunitiesStrict({ productId })
      .then((list) => active && setItems(actionable(list)))
      .catch(() => active && setItems(null));
    return () => {
      active = false;
    };
  }, [productId]);

  if (!items || items.length === 0) return null;

  return (
    <section aria-label="개선 기회">
      <SectionHeader
        title={
          <>
            개선 기회{" "}
            <span className="font-semibold tabular-nums text-muted">{count(items.length)}</span>
          </>
        }
        action={
          <span className="text-xs text-muted">반복되는 문제에서 판매자님이 손볼 수 있는 곳</span>
        }
      />
      {/*
        상자가 아니라 줄이다 (상품 상세 canonical, 2026-10-05). 결정하고 초안을 준비하는 자리는 문제의
        화면이고, 여기는 무엇이 제안됐는지 말하고 그리로 데려가는 줄이다. 테두리를 두르면 이 화면에서
        가장 조용해야 할 제안이 가장 큰 물건이 된다.
      */}
      <ul className="divide-y divide-line/70">
        {items.map((o) => (
          <li key={`${o.issueId}:${o.kind}`}>
            <Link
              to={`/memory/${o.issueId}`}
              className="block rounded-lg py-3 transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              <p className="flex flex-wrap items-baseline gap-x-2.5">
                <span className="font-semibold text-ink">{o.kindLabelKo}</span>
                <span className="text-sm text-muted">
                  {[o.issueTitle, `근거 ${count(o.evidenceCount)}건`, o.statusLabelKo]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </p>
              <p className="mt-0.5 break-keep text-sm text-muted">{o.recommendationKo}</p>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
