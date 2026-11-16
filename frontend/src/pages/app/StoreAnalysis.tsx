import { Link, useSearchParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { AnalysisBody, PeriodSwitch } from "../../components/product/analysis/AnalysisParts";
import { ComparisonChart } from "../../components/product/analysis/ComparisonChart";
import { api } from "../../lib/apiClient";
import { useApiData } from "../../lib/useApiData";
import { useAgentSurface } from "../../lib/agentPanel";
import { daysFrom, type AnalysisDays } from "../../lib/productAnalysis";

/**
 * 가게 전체 분석 (night unit ②, 2026-11-16) — the store (brand) as one subject, then its products side by side.
 *
 * Two reads, kept apart on screen: the store summary is ONE org-scoped reading of every review and inquiry in
 * the period, product-linked or not (it says how many were unlinked, and which channels brought nothing in); the
 * comparison puts products next to each other on one denominator. Neither draws a competitor or a market.
 */
export function StoreAnalysis() {
  useAgentSurface({ surface: "products", label: "가게 전체 분석" });
  const [params, setParams] = useSearchParams();
  const days = daysFrom(params.get("days"));
  const setDays = (d: AnalysisDays) => {
    const next = new URLSearchParams(params);
    next.set("days", String(d));
    setParams(next, { replace: true });
  };
  const store = useApiData(() => api.getStoreAnalysis(days), [days]);
  const compare = useApiData(() => api.getProductComparison(days), [days]);

  return (
    <div className="space-y-6">
      <div>
        <Link
          to="/products"
          className="rounded text-xs text-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          ← 상품
        </Link>
        <div className="mt-1.5">
          <PageHead
            title="가게 전체 분석"
            description="리뷰·문의에서 반복되는 강점과 약점을 가게 단위로 봐요."
            action={<PeriodSwitch days={days} onChange={setDays} />}
          />
        </div>
      </div>

      <section aria-label="가게 전체 강점·약점" className="space-y-4">
        <SectionHeader title="가게 전체 강점·약점" />
        {store.loading ? (
          <p className="text-sm text-muted">분석을 불러오는 중…</p>
        ) : store.error || !store.data ? (
          <p className="text-sm text-bad">분석을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.</p>
        ) : (
          <AnalysisBody view={store.data} />
        )}
      </section>

      <section aria-label="가게 안 상품 비교" className="space-y-3">
        <SectionHeader title="가게 안 상품 비교" hint="같은 기간 · 같은 규칙 · 같은 분모(그 상품 리뷰 수)" />
        {compare.loading ? (
          <p className="text-sm text-muted">비교를 불러오는 중…</p>
        ) : compare.error || !compare.data ? (
          <p className="text-sm text-bad">비교를 불러오지 못했어요.</p>
        ) : (
          <ComparisonChart data={compare.data} />
        )}
      </section>
    </div>
  );
}
