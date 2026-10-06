import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import type {
  KnowledgeCandidateView,
  KnowledgeDocumentView,
  KnowledgeInventoryView,
  KnowledgeSummaryView,
} from "../../lib/types";
import { api } from "../../lib/apiClient";
import { useAgentSurface } from "../../lib/agentPanel";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { Btn } from "../../components/ui/Btn";
import { Empty } from "../../components/ui/Empty";
import { count } from "../../lib/format";
import { COPY } from "../../lib/copy/customerOps";
import { KNOWLEDGE_NOUN, ORG_TOPICS } from "../../lib/knowledgeWords";
import { KnowledgeInbox } from "../../components/knowledge/KnowledgeInbox";
import { LearnedKnowledge } from "../../components/knowledge/LearnedKnowledge";
import { OperatingRuleTable, ProductFactTable, topicsOf } from "../../components/knowledge/KnowledgeInventory";
import {
  KnowledgeDocumentAdd,
  KnowledgeDocumentList,
} from "../../components/knowledge/KnowledgeDocuments";

/**
 * <b>지식</b> — 이 회사가 무엇을 알고 있고, 무엇이 비어 있고, 그것이 어디에 쓰였는지.
 *
 * <p><b>이 화면은 지식을 한 건도 보여 주지 않았다.</b> 「상품 지식 10 · 운영 기준 1 · 자료 3」이라고
 * 적어 두고, 목록으로 그린 것은 올린 파일 셋뿐이었다. 기준 두 건은 설정 안에 있었고, 상품 지식 열두
 * 건은 상품 다섯 곳에 흩어져 있었으며, 어느 화면도 다른 쪽이 있다고 말하지 않았다. 가장 큰 활자는
 * 판매자가 가르친 적 없는 수(「294개 상품 정보」)였다. 그래서 이 화면이 하는 일을 세 질문으로 다시
 * 정했고, 세 질문은 각각 표 하나를 가진다.
 *
 * <p><b>무엇을 알고 있나</b> — 운영 기준과 상품 지식이 한 줄에 한 건씩. <b>무엇이 비어 있나</b> — 적히지
 * 않은 주제가 빈 줄로 서고, 상품 몇 개에 지식이 있는지를 바닥이 말한다. <b>어디에 쓰였나</b> — 「답변
 * 근거」 열. 그 수는 저장된 근거 관계의 수이지 발송도 승인도 아니며, 그 경계는 서버 쪽
 * {@code KnowledgeInventoryContractTest}가 지킨다.
 *
 * <p><b>표가 상자 안에 들어 있지 않다</b> (2026-10-07). 네 구역이 각각 둥근 테두리 상자를 두르고 있어서
 * 한 화면에 떠 있는 물건이 넷이었고, 그 상자들은 안의 표가 이미 가진 격자를 한 번 더 그린 것이었다 —
 * 같은 줄을 두 번 긋는 데 가장 큰 잉크를 썼다. 구역의 이름과 그 아래 가는 선 하나, 그리고 열이 제
 * 자리를 지키는 행들: 주문·상품 상세·리포트가 쓰는 그 문법이고 이 화면도 같은 제품이다.
 *
 * <p><b>설명은 정보 앞에 서지 않는다.</b> 「8가지 가운데 2가지가 적혀 있습니다. 적혀 있지 않은 기준은
 * 답변에 쓰이지 않습니다」는 구역 이름 옆에서 표보다 먼저 읽히는 두 문장이었다. 앞의 수는 제목 옆
 * 한 조각으로 남고, 뒤의 문장은 표 아래로 내려간다 — 표를 읽고 나서야 쓰이는 말이기 때문이다.
 *
 * <p><b>머리의 띠에는 지식만 선다.</b> 과거 응답 27건은 이 회사가 가진 지식이 아니라 해 온 일이고,
 * 상태와 활동을 한 줄에 섞으면 둘 다 읽히지 않는다. 그것은 맨 아래 「채널에서 읽어 온 것」에 제 이름으로
 * 서 있다 — 거기에는 참고용이라고 말할 자리가 있다.
 *
 * <p><b>머리의 「+ 추가」는 없다.</b> 그 메뉴가 연 두 곳(상품 지식 · 운영 기준)은 각자의 구역 이름 옆에
 * 제 이름으로 이미 서 있었고, 한 화면에서 같은 곳으로 가는 문이 둘이면 둘 다 읽히지 않는다.
 *
 * <p><b>순서를 다시 해석하지 않는다.</b> 상품 지식은 서버가 답변 근거 → 마지막 사용 → 제목으로 줄
 * 세워 보내고, 이 화면은 받은 순서를 그대로 그린다. 상품 목록에서 끝낸 그 결함 — 서버와 화면이 서로
 * 다른 수량으로 줄을 세우던 것 — 이 여기서 되살아나지 않도록 화면 쪽 계약 테스트가 함께 선다.
 *
 * <p><b>내부 어휘는 끝까지 나오지 않는다.</b> chunk도, passage도, enum도 없다 —
 * `lib/knowledgeWords.ts`가 판매자가 읽는 말을 전부 쥐고 있고, 이름이 없는 토큰은 아무것도 그리지 않는다.
 */
export function KnowledgeHome() {
  const [documents, setDocuments] = useState<KnowledgeDocumentView[] | null>(null);
  const [candidates, setCandidates] = useState<KnowledgeCandidateView[] | null>(null);
  const [inventory, setInventory] = useState<KnowledgeInventoryView | null>(null);
  const [summary, setSummary] = useState<KnowledgeSummaryView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // The same conversation every other screen opens — the panel, not a second chat (Reports v1 §3).
  // WHAT TRAVELS IS THE SCREEN, and only the screen: there is no knowledge READ tool in the runtime's
  // catalogue, so a document id would be a hint no tool could turn into a fact.
  useAgentSurface({ surface: "knowledge", label: COPY.knowledgeTitle });

  const load = useCallback(async () => {
    const [docs, cands, inv, sum] = await Promise.all([
      api.getKnowledgeDocuments().catch(() => null),
      api.getKnowledgeCandidates().catch(() => null),
      api.getKnowledgeInventory().catch(() => null),
      api.getKnowledgeSummary().catch(() => null),
    ]);
    setDocuments(docs ?? []);
    setCandidates(cands ?? []);
    setInventory(inv);
    setSummary(sum);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function propose() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const next = await api.proposeKnowledgeCandidates();
      setCandidates(next);
      setNotice(next.length === 0 ? "반복 문장 없음" : `기준 후보 ${next.length}건`);
    } catch {
      setError("과거 답변 확인 실패 · 다시 시도");
    } finally {
      setBusy(false);
    }
  }

  const pending = candidates?.length ?? 0;
  const rules = inventory?.rules ?? [];
  const facts = inventory?.productKnowledge ?? [];
  const writtenTopics = topicsOf(rules).filter((topic) => topic.rules.length > 0).length;
  const showingAll = inventory !== null && facts.length >= inventory.productKnowledgeTotal;
  const unused = facts.filter((fact) => fact.citations === 0).length;

  return (
    <div className="space-y-6">
      {/* 제목 옆에 이 화면이 다루는 것의 크기 — 주문의 머리 숫자가 선 그 자리, 같은 모양이다. 여기 서는
          넷은 모두 이 회사가 가진 지식이고, 해 온 일은 한 칸도 섞이지 않는다. */}
      <PageHead
        title={COPY.knowledgeTitle}
        meta={
          <span aria-label="이 회사가 가진 지식" className="flex flex-wrap items-baseline gap-x-4 gap-y-1 text-sm">
            <Figure label="운영 기준" value={rules.length} />
            <Figure label="상품 지식" value={inventory?.productKnowledgeTotal ?? 0} />
            <Figure label={COPY.documentsTab} value={documents?.length ?? 0} />
            <Figure label={KNOWLEDGE_NOUN.needsConfirmation} value={pending} warn />
          </span>
        }
      />

      {error ? <p className="break-keep text-sm text-bad" role="alert">{error}</p> : null}
      {notice ? <p className="break-keep text-sm text-muted" role="status">{notice}</p> : null}

      <Block
        title={KNOWLEDGE_NOUN.needsConfirmation}
        action={
          summary && summary.pastAnswers > 0 ? (
            // Offered only when there is something to look through: a control whose one outcome is
            // 「없음」 is not an action, and on a first day it would be the loudest thing on the screen.
            <Btn size="sm" variant="ghost" onClick={() => void propose()} disabled={busy}>
              {busy ? "확인 중…" : COPY.findInPastAnswers}
            </Btn>
          ) : undefined
        }
      >
        {documents === null || candidates === null ? (
          <p className="py-2 text-sm text-muted">확인 중…</p>
        ) : (
          <KnowledgeInbox candidates={candidates} documents={documents} onChanged={load} />
        )}
      </Block>

      <Block
        title="운영 기준"
        note={inventory ? `주제 ${ORG_TOPICS.length}가지 가운데 ${writtenTopics}가지` : undefined}
        action={<Go to="/settings/policies">기준 추가</Go>}
      >
        {inventory === null ? (
          <p className="py-2 text-sm text-muted">불러오는 중…</p>
        ) : (
          <>
            <OperatingRuleTable rules={rules} />
            {/* 표를 읽고 나서야 쓰이는 말이므로 표 아래에 선다. 인용은 제 출처보다 오래 산다 — 기준이
                지워져도 그것을 보고 쓴 초안의 기록은 남고, 존재하지 않게 된 기준은 위의 표에 줄을 남기지
                않으므로 판매자가 다른 어디에서도 볼 수 없다. 어떤 기준이었는지는 말하지 않는다:
                evidence의 locator에서 이름을 되찾는 것은 외래 키가 받쳐 주지 않는 주장이다. */}
            <Foot>
              적혀 있지 않은 기준은 답변에 쓰이지 않습니다.
              {inventory.orphanRuleCitations > 0 ? (
                <>
                  {" · "}목록에 없는 기준을 인용한 답변 근거{" "}
                  <b className="font-semibold text-ink">{count(inventory.orphanRuleCitations)}건</b>
                </>
              ) : null}
            </Foot>
          </>
        )}
      </Block>

      <Block title="상품 지식" action={<Go to="/products">상품에서 추가</Go>}>
        {inventory === null ? (
          <p className="py-2 text-sm text-muted">불러오는 중…</p>
        ) : facts.length === 0 ? (
          <div className="py-4">
            <Empty
              compact
              title="아직 적어 둔 상품 지식이 없습니다"
              body="상품 화면에서 설명·자주 묻는 질문·사용법·정책을 적어 두면, 답변이 그 내용을 근거로 씁니다."
            />
          </div>
        ) : (
          <>
            <ProductFactTable facts={facts} />
            <Foot>
              {showingAll ? null : (
                <>
                  <b className="font-semibold text-ink">{count(inventory.productKnowledgeTotal)}건</b> 가운데{" "}
                  <b className="font-semibold text-ink">{count(facts.length)}건</b>을 보고 있습니다.{" "}
                </>
              )}
              {showingAll && unused > 0 ? (
                <>
                  <b className="font-semibold text-ink">{count(unused)}건</b>은 아직 답변 근거로 쓰인 적이 없고,{" "}
                </>
              ) : null}
              상품 <b className="font-semibold text-ink">{count(inventory.products)}개</b> 가운데{" "}
              <b className="font-semibold text-ink">{count(inventory.productsWithKnowledge)}개</b>에 상품 지식이
              있습니다.
            </Foot>
          </>
        )}
      </Block>

      <Block
        title={COPY.documentsTab}
        note="여기서 읽은 내용이 위의 기준과 지식이 됩니다"
        action={<KnowledgeDocumentAdd scope="ORG" onImported={load} label={COPY.addDocument} />}
      >
        {documents === null ? (
          <p className="py-2 text-sm text-muted">불러오는 중…</p>
        ) : (
          <KnowledgeDocumentList documents={documents} onChanged={load} />
        )}
      </Block>

      {/* 위의 셋은 이 회사가 가진 지식이고 이것은 참고일 뿐이다 — 같은 무게로 읽히지 않도록 이름만
          한 단계 조용히 둔다. */}
      <Block title="채널에서 읽어 온 것" note="공식 기준이 아니라, 답변을 만들 때 참고만 합니다">
        <LearnedKnowledge />
      </Block>
    </div>
  );
}

/** 한 구역 — 이름, 그 옆의 한 조각, 그 아래 선 하나. 주문·리포트와 같은 렌더러다. */
function Block({
  title,
  note,
  action,
  children,
}: {
  title: string;
  note?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className="space-y-2">
      {/* 머리말은 제목 안이 아니라 제목 아래에 선다 — 구역의 이름은 이름이고, 그 옆에 붙은 문장은 읽어
          주는 쪽에서 이름의 일부가 된다. */}
      <SectionHeader title={title} hint={note} action={action} />
      {children}
    </section>
  );
}

/** 표가 끝난 뒤에 오는 한 줄 — 표를 읽고 나서야 쓰이는 말의 자리. */
function Foot({ children }: { children: ReactNode }) {
  return <p className="break-keep border-t border-line py-2 text-xs text-muted">{children}</p>;
}

/** 머리의 수 하나. 지금 사람을 기다리는 하나에만 색이 있다. */
function Figure({ label, value, warn = false }: { label: string; value: number; warn?: boolean }) {
  const on = warn && value > 0;
  return (
    <span className={`inline-flex items-baseline gap-1.5 ${on ? "font-semibold text-warn" : "text-muted"}`}>
      <span>{label}</span>
      <b className={`font-bold tabular-nums ${on ? "text-warn" : "text-ink"}`}>{count(value)}</b>
    </span>
  );
}

/** 다른 화면으로 가는 길 — 글자이고, 단추가 아니다. */
function Go({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      to={to}
      className="rounded text-sm font-semibold text-brand-700 underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
    >
      {children}
    </Link>
  );
}
