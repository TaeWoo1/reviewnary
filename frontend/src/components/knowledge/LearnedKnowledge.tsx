import { useCallback, useEffect, useState } from "react";
import type {
  KnowledgeBootstrapReport,
  LearnedKnowledgeChannelLine,
  LearnedKnowledgeSource,
  LearnedKnowledgeView,
} from "../../lib/types";
import { api } from "../../lib/apiClient";
import { Btn } from "../ui/Btn";

/**
 * <b>Reviewnary가 배운 것</b> — what the company's operating history has already taught, where each piece came from,
 * and, per connected channel, which history can and cannot be learned.
 *
 * <p>Counts per source, from the same tables a case retrieves — so a number here is something a case can actually
 * use. A source a channel does not provide is named with its reason rather than shown as 0, because 0 reads as
 * «you have none» and the truth is «this channel does not give it to us».
 *
 * <p><b>예시 문장은 더 이상 싣지 않는다</b> (지식 canonical, 2026-10-05). 출처마다 고객의 글 두 토막을
 * 펼치면 이 구역 하나가 1,000px을 넘었고, 그 길이가 말해 주는 것은 「무엇을 얼마나 읽어 왔는가」 하나뿐이다.
 * 그 하나는 수와 날짜가 이미 말하고, 문장 자체는 그것을 인용한 답변 옆에서 읽는 것이 맞다.
 *
 * <p>The one control reads the seller's own channel history (READ only) and learns from it. It never writes to a
 * channel and never changes an answer; a case that could use the new knowledge picks it up on its next preparation.
 */
export function LearnedKnowledge() {
  const [learned, setLearned] = useState<LearnedKnowledgeView | null | undefined>(undefined);
  const [lastRun, setLastRun] = useState<KnowledgeBootstrapReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    try {
      setLearned((await api.getLearnedKnowledge()).learned);
    } catch {
      setLearned(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const learn = async () => {
    setBusy(true);
    setFailed(false);
    try {
      const response = await api.learnFromHistory();
      setLearned(response.learned);
      setLastRun(response.lastRun);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  if (learned === undefined) {
    return <p className="px-1 py-2 text-sm text-muted">확인하는 중…</p>;
  }
  if (learned === null) {
    return <p className="px-1 py-2 text-sm text-muted">배운 내용을 불러오지 못했습니다.</p>;
  }

  return (
    <div data-testid="learned-knowledge">
      {/* 위의 표들과 같은 열 자리, 다른 무게 — 상자도 바탕색도 없이 가는 선 하나로만 나뉜다. 여기 서는
          것은 보유한 지식이 아니라 읽어 온 것이므로 「답변 근거」 열도 없다.
          aria-hidden: 각 줄이 제 이름을 달고 있다. */}
      <div
        aria-hidden="true"
        className="flex items-center gap-4 border-b border-line px-1 py-1.5 text-xs text-muted"
      >
        <span className="min-w-0 flex-1">읽어 온 것</span>
        <span className="w-[96px] shrink-0 text-right">건수</span>
        <span className="w-[96px] shrink-0 text-right">최근</span>
      </div>
      <ul className="divide-y divide-line/60">
        {learned.sources.map((source) => (
          <SourceRow key={source.key} source={source} lines={learned.channels.filter((l) => l.source === source.key)} />
        ))}
      </ul>

      <div className="space-y-2 border-t border-line px-1 pt-3">
        {learned.historyReads.length > 0 ? (
          <ul className="space-y-0.5 text-xs text-muted">
            {learned.historyReads.map((read) => (
              <li key={`${read.channelNameKo}-${read.readOn}`} className="break-keep">
                {read.channelNameKo}의 과거 문의를 {read.readOn ?? "이전에"} 읽었습니다 · {read.rowsRead}건
              </li>
            ))}
          </ul>
        ) : null}
        {learned.canLearnHistory ? (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <Btn size="sm" variant="outline" onClick={learn} disabled={busy}>
                {busy ? "과거 기록을 읽는 중…" : "과거 운영 기록에서 배우기"}
              </Btn>
              <p className="min-w-0 flex-1 break-keep text-xs text-muted">
                연결된 채널의 지난 문의와 답변, 고객이 이야기한 상품의 상세 정보를 읽습니다. 채널에 아무것도 쓰거나
                보내지 않습니다.
              </p>
            </div>
            {failed ? <p className="text-sm text-bad">과거 기록을 읽지 못했습니다. 잠시 뒤 다시 시도해 주세요.</p> : null}
            {lastRun ? <RunSummary run={lastRun} /> : null}
          </>
        ) : null}
      </div>
    </div>
  );
}

/**
 * 한 출처와, 채널마다 그것을 가져올 수 있는지.
 *
 * 채널 줄은 이 줄 아래에 붙는다 — 전에는 채널별로 다시 묶여 화면 아래쪽에 따로 서 있었고, 「과거 리뷰
 * 답글 2건」과 「쿠팡은 판매자 리뷰 답글 기능이 없습니다」가 같은 것에 대한 두 문장이라는 사실을
 * 읽으려면 두 구역을 오가야 했다. 가져오지 못하는 쪽의 사유는 서버가 쓴 문장 그대로 선다 — 두 채널의
 * 서로 다른 이유를 한 구절로 합치면 그것은 아무도 쓰지 않은 주장이 된다.
 */
function SourceRow({ source, lines }: { source: LearnedKnowledgeSource; lines: LearnedKnowledgeChannelLine[] }) {
  const missing = lines.filter((l) => l.availability !== "LEARNED" && l.availability !== "SCREEN_READ");
  return (
    <li className="px-1 py-2">
      <div className="flex items-center gap-4">
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">{source.labelKo}</span>
        <span className="w-[96px] shrink-0 text-right text-sm tabular-nums text-ink">
          {source.count > 0 ? (source.key === "PRODUCT_DETAIL" ? `상품 ${source.count}` : source.count) : ""}
        </span>
        <span className="w-[96px] shrink-0 text-right text-xs tabular-nums text-muted">{source.latestOn ?? ""}</span>
      </div>
      {lines.length > 0 ? (
        <div className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted">
          {lines.map((line) => (
            <span key={line.channelNameKo}>
              {line.channelNameKo}{" "}
              {/* 공식 경로(LEARNED)와 판매자센터 화면을 범위를 정해 읽는 것(SCREEN_READ)은 둘 다 가져온
                  것이다. 두 번째를 「못 가져옴」으로 그리면 바로 옆 제 문장과 모순된다. */}
              <b className={line.availability === "LEARNED" || line.availability === "SCREEN_READ" ? "font-semibold text-ink" : "font-normal"}>
                {line.availability === "LEARNED" || line.availability === "SCREEN_READ" ? "가져옴" : "못 가져옴"}
              </b>
            </span>
          ))}
        </div>
      ) : null}
      {missing.map((line) => (
        <p key={`${line.channelNameKo}-why`} className="break-keep text-xs leading-relaxed text-muted">
          {line.sentenceKo}
        </p>
      ))}
    </li>
  );
}

/** Why the product-detail pass stopped early — the channel refused this connection, not any one product. */
const STOPPED_BY: Record<string, string> = {
  ENVIRONMENT_NOT_ALLOWED:
    "채널이 지금 연결 환경을 허용하지 않아 상품 상세를 읽지 못했습니다. 판매자센터 애플리케이션의 'API 호출 IP'를 확인해 주세요.",
  PERMISSION: "채널 애플리케이션에 상품 API 권한이 없어 상품 상세를 읽지 못했습니다.",
  CREDENTIAL: "채널 연결 정보가 더 이상 유효하지 않아 상품 상세를 읽지 못했습니다. 채널을 다시 연결해 주세요.",
  CHANNEL_REFUSED: "채널이 요청을 거절해 상품 상세를 읽지 못했습니다.",
};

function RunSummary({ run }: { run: KnowledgeBootstrapReport }) {
  const lines: string[] = [];
  for (const history of run.inquiryHistory) {
    const channel = history.channelNameKo ?? "채널";
    if (history.status === "READ") {
      lines.push(`${channel}: ${history.from}부터 문의 ${history.rowsRead}건을 읽었습니다.`);
    } else if (history.status === "ALREADY_READ") {
      lines.push(`${channel}: 과거 문의는 이미 읽었습니다.`);
    } else if (history.status === "IN_PROGRESS") {
      lines.push(`${channel}: 다른 수집이 진행 중이라 끝난 뒤 다시 시도해 주세요.`);
    } else {
      lines.push(`${channel}: 과거 문의를 읽지 못했습니다.`);
    }
  }
  lines.push(`기억하고 있는 지난 문의 답변은 ${run.answersRemembered}건입니다.`);
  for (const read of run.catalogue ?? []) {
    const channel = read.channelNameKo ?? "채널";
    if (read.status === "READ") {
      lines.push(`${channel}: 판매 상품 목록을 새로 읽었습니다.`);
    } else if (read.status === "FAILED") {
      lines.push(`${channel}: 판매 상품 목록을 읽지 못해 저장된 목록으로 진행했습니다.`);
    }
  }
  if (!run.productDetail.enabled) {
    lines.push("상품 상세 읽기는 지금 꺼져 있습니다.");
  } else if (run.productDetail.considered > 0) {
    const parts = [`상품 ${run.productDetail.considered}개를 확인해 상세 글 ${run.productDetail.indexed}개를 읽었습니다`];
    if (run.productDetail.imageOnly > 0) {
      parts.push(`이미지로만 된 상세페이지 ${run.productDetail.imageOnly}개는 읽지 못했습니다`);
    }
    lines.push(`${parts.join(" · ")}.`);
  }
  const stopped = run.productDetail.stoppedBy ? STOPPED_BY[run.productDetail.stoppedBy] : null;
  if (stopped) {
    lines.push(stopped);
  }
  if (run.productDetail.enabled && (run.productDetail.onSaleCatalogue ?? 0) > 0) {
    let line = `판매 중인 상품 ${run.productDetail.onSaleCatalogue}개 중 ${run.productDetail.covered}개의 상세·옵션·속성을 알고 있습니다.`;
    if ((run.productDetail.remaining ?? 0) > 0) {
      line += ` 나머지 ${run.productDetail.remaining}개는 다음 번에 읽습니다.`;
    }
    lines.push(line);
  }
  return (
    <ul className="space-y-1 rounded-xl border border-line bg-surface p-3 text-xs text-ink" aria-live="polite">
      {lines.map((line) => (
        <li key={line} className="break-keep">
          {line}
        </li>
      ))}
    </ul>
  );
}
