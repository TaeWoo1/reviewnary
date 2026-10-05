import { useState } from "react";
import { isAxiosError } from "axios";
import { Btn } from "../ui/Btn";
import { ListBox } from "../ui/Section";
import { KnowledgeQuickAdd } from "./KnowledgeQuickAdd";
import { api } from "../../lib/apiClient";
import { KNOWLEDGE_NOUN, scopeLabel } from "../../lib/knowledgeWords";
import { COPY, shortDate } from "../../lib/copy/customerOps";
import type { KnowledgeCandidateView, KnowledgeDocumentView } from "../../lib/types";

/**
 * <b>확인 필요 — 지금 사람을 기다리는 것 전부, 한 자리에.</b>
 * (Knowledge Setup &amp; Inbox UX v1 §3, §8)
 *
 * <p>세 가지이고, 세 가지인 이유는 읽는 법이 셋이기 때문이다:
 *
 * <ul>
 *   <li><b>정보 부족</b> — 초안이 부딪힌 질문. 판매자가 사실을 적는다. 저장된 글은 <b>질문</b>이므로
 *       질문으로만 보이고 답으로 제안되지 않는다 — 「입력」은 <b>빈</b> 편집기를 연다. 전에는
 *       아무것도 열지 않았고 「기준 등록」이 그 질문 자체를 회사의 공식 지식으로 저장했다.</li>
 *   <li><b>기준 후보</b> — 이 판매자가 고객에게 여러 번 쓴 문장. 그들의 것이므로 편집기가 그 문장을
 *       담은 채 열린다.</li>
 *   <li><b>자료 문제</b> — 오늘 확실히 알 수 있는 것 하나: 인용할 문장이 하나도 나오지 않은 자료는
 *       답변에 쓰일 수 없고, 그 자료를 정상으로 그리는 목록은 그 사실을 숨기는 것이다.</li>
 * </ul>
 *
 * <p><b>한 건이 한 줄이다.</b> 전에는 한 건이 태그·출처·본문·조치를 각각 제 줄에 펼쳤고, 두 건이 첫
 * 화면의 윗부분을 가져갔다 — 그 아래 「무엇을 알고 있나」가 한 줄도 보이지 않은 채로. 질문은 끝까지
 * 읽어야 답을 쓸 수 있으므로 두 줄까지 접어 보여 주고, 나머지는 한 줄에 선다.
 *
 * <p>여기서 저절로 승격되는 것은 없다. 모든 쓰기는 사람의 누름이다.
 */
export function KnowledgeInbox({
  candidates,
  documents,
  onChanged,
}: {
  candidates: KnowledgeCandidateView[];
  documents: KnowledgeDocumentView[];
  onChanged: () => Promise<void> | void;
}) {
  const [error, setError] = useState<string | null>(null);
  const gaps = candidates.filter((c) => c.origin === "DRAFT_GAP");
  const repeats = candidates.filter((c) => c.origin !== "DRAFT_GAP");
  const unusable = documents.filter((d) => d.active && d.passages === 0);

  if (gaps.length === 0 && repeats.length === 0 && unusable.length === 0) {
    return (
      <p className="break-keep px-1 text-sm text-muted">
        {KNOWLEDGE_NOUN.needsConfirmation} {COPY.none}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {error ? <p className="break-keep text-sm text-bad" role="alert">{error}</p> : null}
      {/* 구역의 이름은 바깥 Section이 이미 달고 있다 — 같은 이름을 두 번 달면 읽어 주는 쪽에 상자가 둘로 들린다. */}
      <ListBox>
        <ul className="divide-y divide-line/70" data-testid="knowledge-inbox">
          {/* 두 줄은 같은 상태다 — 어느 쪽도 선택돼 있지 않고, 어느 쪽을 먼저 해야 한다는 근거도 없다.
              한쪽만 solid로 그리면 그 줄이 골라져 있다는 뜻이 되므로, 위계는 같게 둔다. */}
          {gaps.map((candidate) => (
            <CandidateRow key={candidate.id} candidate={candidate} onChanged={onChanged} onError={setError} />
          ))}
          {repeats.map((candidate) => (
            <CandidateRow key={candidate.id} candidate={candidate} onChanged={onChanged} onError={setError} />
          ))}
          {unusable.map((document) => (
            <li key={document.sourceId} className="flex items-center gap-3 px-5 py-2">
              <Tag tone="warn">자료 문제</Tag>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm text-ink">{document.fileName ?? document.title}</span>
                <span className="block truncate text-xs text-muted">
                  {scopeLabel(document.scope, document.productName)} · {COPY.noContent}
                </span>
              </span>
              <Btn
                size="sm"
                variant="outline"
                onClick={async () => {
                  try {
                    await api.setKnowledgeDocumentActive(document.sourceId, false);
                    await onChanged();
                  } catch {
                    setError("상태 변경 실패 · 다시 시도");
                  }
                }}
              >
                {COPY.stopUsing}
              </Btn>
            </li>
          ))}
        </ul>
      </ListBox>
    </div>
  );
}

function CandidateRow({
  candidate,
  onChanged,
  onError,
}: {
  candidate: KnowledgeCandidateView;
  onChanged: () => Promise<void> | void;
  onError: (message: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const isGap = candidate.origin === "DRAFT_GAP";
  const scope = candidate.scope === "PRODUCT" && candidate.productId ? "PRODUCT" : "ORG";
  const source = [scopeLabel(candidate.scope, candidate.productName), shortDate(candidate.createdAt)]
    .filter(Boolean)
    .join(" · ");

  return (
    <li className="px-5 py-2">
      <div className="flex items-start gap-3">
        <Tag tone={isGap ? "info" : "neutral"}>{isGap ? "정보 부족" : "기준 후보"}</Tag>
        <span className="min-w-0 flex-1">
          {/* 질문은 잘라내지 않는다 — 여기 적힌 것을 읽어야 답을 쓸 수 있다. 두 줄에서 접는다. */}
          <span className="line-clamp-2 break-keep text-sm leading-relaxed text-ink">{candidate.content}</span>
          <span className="block truncate text-xs text-muted">
            {source}
            {!isGap && candidate.evidenceCount > 0 ? ` · 과거 답변 ${candidate.evidenceCount}건` : null}
          </span>
        </span>
        {open ? null : (
          <span className="flex shrink-0 items-center gap-1.5">
            <Btn
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                onError(null);
                try {
                  await api.dismissKnowledgeCandidate(candidate.id);
                  await onChanged();
                } catch (e) {
                  onError(
                    isAxiosError(e) && e.response?.status === 409 ? "이미 처리한 항목" : "처리 실패 · 다시 시도",
                  );
                } finally {
                  setBusy(false);
                }
              }}
            >
              {COPY.defer}
            </Btn>
            <Btn
              size="sm"
              variant="outline"
              onClick={() => {
                onError(null);
                setOpen(true);
              }}
              disabled={busy}
            >
              {isGap ? COPY.enter : COPY.registerRule}
            </Btn>
          </span>
        )}
      </div>
      {open ? (
        <div className="mt-2">
          <KnowledgeQuickAdd
            scope={scope}
            productId={candidate.productId}
            productName={candidate.productName}
            body={isGap ? "" : candidate.content}
            saveLabel={COPY.registerRule}
            onSave={async (value) => {
              await api.acceptKnowledgeCandidate(candidate.id, {
                title: value.title,
                content: value.body,
                variantId: value.variantId,
                ...(scope === "PRODUCT"
                  ? { sourceType: value.topic as string }
                  : { orgType: value.topic as string }),
              });
              setOpen(false);
              await onChanged();
            }}
            onCancel={() => setOpen(false)}
          />
        </div>
      ) : null}
    </li>
  );
}

/** 왜 이 줄이 기다리고 있는지, 한 단어로. 색은 종류를 말할 뿐 급함을 말하지 않는다. */
function Tag({ tone, children }: { tone: "info" | "neutral" | "warn"; children: React.ReactNode }) {
  const palette =
    tone === "warn"
      ? "bg-warn/10 text-warn"
      : tone === "info"
        ? "bg-brand-700/10 text-brand-700"
        : "bg-canvas text-muted";
  return <span className={`mt-0.5 shrink-0 rounded-md px-2 py-0.5 text-xs font-semibold ${palette}`}>{children}</span>;
}
