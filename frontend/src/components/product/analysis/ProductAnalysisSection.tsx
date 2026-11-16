import { Link, useSearchParams } from "react-router-dom";
import { SectionHeader } from "../../ui/SectionHeader";
import { api } from "../../../lib/apiClient";
import { useApiData } from "../../../lib/useApiData";
import { daysFrom, type AnalysisDays } from "../../../lib/productAnalysis";
import { AnalysisBody, PeriodSwitch } from "./AnalysisParts";
import { ComparisonChart } from "./ComparisonChart";

/**
 * 상품 상세의 「강점·약점 분석」 (night unit ②, 2026-11-16): this product's SWOT over the period in `?days=`, and
 * where it stands among the store's own products on the same axes. The period is URL state so a shared link
 * shows the same reading.
 */
export function ProductAnalysisSection({ productId }: { productId: string }) {
  const [params, setParams] = useSearchParams();
  const days = daysFrom(params.get("days"));
  const setDays = (d: AnalysisDays) => {
    const next = new URLSearchParams(params);
    next.set("days", String(d));
    setParams(next, { replace: true });
  };
  const view = useApiData(() => api.getProductAnalysis(productId, days), [productId, days]);
  const compare = useApiData(() => api.getProductComparison(days), [days]);

  return (
    <section aria-label="강점·약점 분석" className="space-y-4">
      <SectionHeader title="강점·약점 분석" action={<PeriodSwitch days={days} onChange={setDays} />} />
      {view.loading ? (
        <p className="text-sm text-muted">분석을 불러오는 중…</p>
      ) : view.error || !view.data ? (
        <p className="text-sm text-bad">분석을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.</p>
      ) : (
        <AnalysisBody view={view.data} />
      )}
      <section aria-label="가게 안 상품 비교" className="space-y-2">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <h3 className="text-base font-semibold text-ink">가게 안 상품 비교</h3>
          <Link
            to={`/products/analysis?days=${days}`}
            className="inline-flex min-h-[44px] items-center rounded text-sm text-ink underline underline-offset-2 sm:min-h-0 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            가게 전체 분석 보기
          </Link>
        </div>
        {compare.loading ? (
          <p className="text-sm text-muted">비교를 불러오는 중…</p>
        ) : compare.error || !compare.data ? (
          <p className="text-sm text-bad">비교를 불러오지 못했어요.</p>
        ) : (
          <ComparisonChart data={compare.data} currentId={productId} />
        )}
      </section>
    </section>
  );
}
