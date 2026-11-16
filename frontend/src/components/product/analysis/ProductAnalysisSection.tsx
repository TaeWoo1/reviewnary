import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Btn } from "../../ui/Btn";
import { backendMessage } from "../../connect/channelShared";
import { SectionHeader } from "../../ui/SectionHeader";
import { api } from "../../../lib/apiClient";
import { useApiData } from "../../../lib/useApiData";
import { daysFrom, type AnalysisDays, type AspectReadSummary } from "../../../lib/productAnalysis";
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
  const [refresh, setRefresh] = useState(0);
  const view = useApiData(() => api.getProductAnalysis(productId, days), [productId, days], refresh);
  const compare = useApiData(() => api.getProductComparison(days), [days], refresh);
  const ai = useApiData(() => api.getAspectReadAvailable(), []);
  const [reading, setReading] = useState(false);
  const [summary, setSummary] = useState<AspectReadSummary | null>(null);
  const [readError, setReadError] = useState<string | null>(null);

  const readWithAi = async () => {
    setReading(true);
    setReadError(null);
    try {
      setSummary(await api.readProductAnalysisWithAi(productId, days));
      setRefresh((n) => n + 1);
    } catch (e) {
      setReadError(backendMessage(e) ?? "AI로 읽지 못했어요. 잠시 뒤 다시 눌러 주세요.");
    } finally {
      setReading(false);
    }
  };

  return (
    <section aria-label="강점·약점 분석" className="space-y-4">
      <SectionHeader title="강점·약점 분석" action={<PeriodSwitch days={days} onChange={setDays} />} />
      {ai.data?.enabled ? (
        <div className="space-y-1" data-testid="analysis-ai-control">
          <div className="flex flex-wrap items-center gap-3">
            <Btn variant="outline" disabled={reading} onClick={() => void readWithAi()}>
              {reading ? "AI가 읽는 중…" : "AI로 읽기"}
            </Btn>
            <p className="break-keep text-sm text-muted">
              이 기간 리뷰를 AI가 측면·평가·근거로 읽고, 기회·위협을 그 근거로 써요. 한 번에 일부씩 읽어요.
            </p>
          </div>
          {summary ? <p className="break-keep text-sm text-ink" role="status">{summaryLine(summary)}</p> : null}
          {readError ? <p className="break-keep text-sm text-warn" role="alert">{readError}</p> : null}
        </div>
      ) : null}
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

/** One press in one sentence: what was read now, what is left, and why it stopped. */
export function summaryLine(s: AspectReadSummary): string {
  const parts = [`리뷰 글 ${s.textsRead}개를 새로 읽었어요`];
  if (s.remaining > 0) parts.push(`아직 ${s.remaining}개가 남았어요 — 한 번 더 누르면 이어서 읽어요`);
  if (s.outlookWritten) parts.push("기회·위협을 근거로 새로 썼어요");
  if (s.failedBatches > 0) parts.push(`${s.failedBatches}묶음은 읽지 못해 다음에 다시 읽어요`);
  if (s.stopped === "QUOTA") parts.push("오늘 AI 사용 한도에 닿아 멈췄어요");
  if (s.textsRead === 0 && s.remaining === 0 && !s.outlookWritten && s.stopped === null) {
    return "이미 모두 읽었어요. 새 리뷰가 들어오면 다시 눌러 주세요.";
  }
  return parts.join(". ") + ".";
}
