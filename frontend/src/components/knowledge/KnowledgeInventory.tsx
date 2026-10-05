import { Link } from "react-router-dom";
import { count } from "../../lib/format";
import { shortDate } from "../../lib/copy/customerOps";
import { ORG_TOPICS, topicLabel } from "../../lib/knowledgeWords";
import type { OperatingRuleRow, ProductFactRow } from "../../lib/types";

/**
 * 지식 목록 — 회사가 적어 둔 것 한 건이 한 줄이고, 적지 않은 것은 빈 줄이다.
 *
 * <p><b>왜 표인가.</b> 이 화면은 「상품 지식 10 · 운영 기준 1」이라는 수를 적어 놓고, 그 열 건도 그 한
 * 건도 보여 주지 않았다. 기준은 설정 안에, 상품 지식은 상품 열 곳에, 그리고 지식이라는 이름의 화면이
 * 목록으로 가진 것은 올린 파일 셋뿐이었다. 상품 목록이 같은 문제를 같은 방법으로 풀었다 — 열은 레코드의
 * 속성이고, 한 줄은 한 건이며, 빈 칸은 값이 없다는 뜻이다. 참조는 그때와 같은 Attio의 table view,
 * 그리고 지식 한 건이 「어디에 쓰이는지」를 열로 세우는 Intercom의 Articles 목록이다.
 *
 * <p><b>빈 주제가 행으로 선다.</b> 운영 기준은 등록된 것만 그리면 비어 있다는 사실이 화면에서 사라진다.
 * 데모 회사는 살아 있는 문의 121건 가운데 세금계산서가 19건인데 그 주제에 적힌 기준이 없고, 그것을
 * 보여 줄 수 있는 유일한 방법은 세금계산서라는 줄이 비어 있는 채로 서 있는 것이다. 주제 목록은
 * {@link ORG_TOPICS} — 편집기가 고르게 하는 바로 그 여덟 가지이고, 화면이 여섯 번째를 지어내지 못한다.
 *
 * <p><b>「답변 근거」는 저장된 근거 관계의 수다.</b> 발송이 아니고, 승인도, 고객이 읽은 횟수도 아니다.
 * 그래서 열 이름이 「사용」이 아니라 문의·리뷰 레인이 쓰는 그 말 그대로 「답변 근거」이고, 숫자 옆에
 * 아무 해석도 붙지 않는다.
 *
 * <p><b>0은 빈 칸, 읽지 못한 값은 「—」.</b> 상품 목록에서 고정한 문법 그대로다. 아직 한 번도 쓰이지
 * 않은 지식은 이 화면의 절반이므로, 빈 칸이 조용히 많은 것이 맞는 그림이다.
 *
 * <p><b>순서는 서버의 것이다.</b> 상품 목록에서 끝낸 결함 — 서버가 한 수량으로 고르고 화면이 다른
 * 수량으로 다시 줄 세우던 것 — 이 여기서 되살아나지 않도록, 화면은 받은 순서를 그대로 그린다.
 */

const USED = "w-[88px] shrink-0 text-right tabular-nums";
const WHEN = "w-[96px] shrink-0 text-right tabular-nums text-xs text-muted";
const HEAD = "flex items-center gap-4 border-b border-line bg-canvas/40 px-5 py-2 text-xs text-muted";
const ROW = "flex items-center gap-4 px-5 py-1.5";

/* ─────────────────────────────── 운영 기준 ─────────────────────────────── */

/** 한 주제와, 그 주제에 적힌 기준들 — 하나도 없으면 빈 줄 하나. */
type Topic = { value: string; label: string; rules: OperatingRuleRow[] };

/**
 * 여덟 주제 × 거기 적힌 기준. 서버가 주제 순으로 보내 주므로 화면은 자리만 만든다.
 *
 * 저장된 주제 가운데 {@link ORG_TOPICS}에 이름이 없는 것이 섞여 들어오면(옛 enum 값 같은) 버리지 않고
 * 맨 뒤에 제 이름으로 세운다 — 적혀 있는 기준이 화면에서 사라지는 것이 더 나쁜 일이다.
 */
export function topicsOf(rules: OperatingRuleRow[]): Topic[] {
  const known = ORG_TOPICS.map((topic) => ({
    value: topic.value as string,
    label: topic.label,
    rules: rules.filter((rule) => rule.knowledgeType === topic.value),
  }));
  const strays = rules.filter((rule) => !ORG_TOPICS.some((t) => (t.value as string) === rule.knowledgeType));
  return [
    ...known,
    ...strays.map((rule) => ({
      value: rule.knowledgeType ?? rule.id,
      label: topicLabel(rule.knowledgeType) ?? "분류 없음",
      rules: [rule],
    })),
  ];
}

export function OperatingRuleTable({ rules }: { rules: OperatingRuleRow[] }) {
  const topics = topicsOf(rules);
  return (
    <>
      <div aria-hidden="true" className={HEAD}>
        <span className="w-[150px] shrink-0">주제</span>
        <span className="min-w-0 flex-1">기준</span>
        <span className="w-[170px] shrink-0">출처</span>
        <span className={USED}>답변 근거</span>
        <span className={WHEN}>마지막 사용</span>
      </div>
      <ul className="divide-y divide-line/70">
        {topics.map((topic) =>
          topic.rules.length === 0 ? (
            <li key={topic.value} className={ROW}>
              <span className="w-[150px] shrink-0 truncate text-sm text-ink">{topic.label}</span>
              {/* 빈 줄. 「없음」이라고 적지 않는 이유는 표의 빈 칸이 이미 그 뜻이기 때문이고, 여덟 줄
                  가운데 여섯 줄에 같은 단어를 적으면 적혀 있는 둘이 묻히기 때문이다. */}
              <span className="min-w-0 flex-1">
                <span className="sr-only">적혀 있는 기준 없음</span>
              </span>
              <span className="w-[170px] shrink-0" />
              <Used label={`${topic.label} 답변 근거`} value={0} />
              <span className={WHEN} />
            </li>
          ) : (
            topic.rules.map((rule, i) => (
              <li key={rule.id} className={ROW}>
                <span className="w-[150px] shrink-0 truncate text-sm text-ink">
                  {i === 0 ? topic.label : <span className="sr-only">{topic.label}</span>}
                </span>
                <Link
                  to="/settings/policies"
                  className="min-w-0 flex-1 truncate rounded text-sm font-medium text-ink underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                >
                  {rule.title}
                  {rule.active ? null : <span className="ml-2 text-xs text-muted">사용 안 함</span>}
                </Link>
                <Origin documentName={rule.documentName} />
                <Used label={`${topic.label} 답변 근거`} value={rule.citations} />
                <When at={rule.lastUsedAt} />
              </li>
            ))
          ),
        )}
      </ul>
    </>
  );
}

/* ─────────────────────────────── 상품 지식 ─────────────────────────────── */

export function ProductFactTable({ facts }: { facts: ProductFactRow[] }) {
  return (
    <>
      <div aria-hidden="true" className={HEAD}>
        <span className="min-w-0 flex-1">지식</span>
        <span className="w-[110px] shrink-0">종류</span>
        <span className="w-[230px] shrink-0">상품</span>
        <span className="w-[150px] shrink-0">출처</span>
        <span className={USED}>답변 근거</span>
        <span className={WHEN}>마지막 사용</span>
      </div>
      <ul className="divide-y divide-line/70">
        {facts.map((fact) => (
          <li key={fact.id} className={ROW}>
            {/* 상품 지식이 편집되는 자리는 그 상품의 화면 하나뿐이다 — 이 목록은 고르는 곳이다.
                그 화면이 열리지 않는 상품(제조된 상품은 카탈로그가 내주지 않는다)에는 문을 그리지 않는다.
                이름은 그래도 부른다 — 이름과 문은 다른 질문이다. */}
            {fact.productReachable ? (
              <Link
                to={`/products/${fact.productId}`}
                className="min-w-0 flex-1 truncate rounded text-sm font-medium text-ink underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                {fact.title}
                {fact.active ? null : <span className="ml-2 text-xs text-muted">사용 안 함</span>}
              </Link>
            ) : (
              <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">
                {fact.title}
                {fact.active ? null : <span className="ml-2 text-xs text-muted">사용 안 함</span>}
              </span>
            )}
            <span className="w-[110px] shrink-0 truncate text-xs text-muted">
              {topicLabel(fact.sourceType) ?? ""}
            </span>
            {/* 빈 칸이 아니라 문장이다. 이 열의 빈 칸은 「상품이 없다」로 읽히는데, 사실은 지식은 한
                상품에 붙어 있고 그 상품을 이 화면이 이름으로 부를 수 없다는 뜻이다. */}
            <span className="w-[230px] shrink-0 truncate text-xs text-muted">
              {fact.productName ?? "상품을 확인할 수 없음"}
            </span>
            <Origin documentName={fact.documentName} />
            <Used label="답변 근거" value={fact.citations} />
            <When at={fact.lastUsedAt} />
          </li>
        ))}
      </ul>
    </>
  );
}

/* ─────────────────────────────── cells ─────────────────────────────── */

/** 손으로 쓴 것인지, 넘긴 파일에서 온 것인지. 파일이면 그 파일 이름을 말한다 — 인용이 그렇게 말하므로. */
function Origin({ documentName }: { documentName: string | null }) {
  return (
    <span className="w-[150px] shrink-0 truncate text-xs text-muted" title={documentName ?? undefined}>
      {documentName ? `자료 · ${documentName}` : "직접 작성"}
    </span>
  );
}

/**
 * 몇 번이나 답변의 근거가 됐는지. 0은 빈 칸이고, 읽지 못한 값은 「—」다.
 *
 * 0을 회색 숫자로 적으면 여덟 줄 가운데 여섯 줄이 0이 되어, 스물한 번 쓰인 한 줄이 그 안에 묻힌다.
 * 화면이 읽히지 않은 값을 0으로 그리는 일은 끝까지 없다.
 */
function Used({ label, value }: { label: string; value: number | null }) {
  if (value === null || value === undefined) {
    return (
      <span className={USED}>
        <span className="sr-only">{label} 읽지 못했습니다</span>
        <span aria-hidden="true" className="text-sm text-muted">
          —
        </span>
      </span>
    );
  }
  if (value === 0) {
    return (
      <span className={USED}>
        <span className="sr-only">{label} 0건</span>
      </span>
    );
  }
  return (
    <span className={USED}>
      <span className="sr-only">{label} </span>
      <span className="text-sm font-medium text-ink">{count(value)}</span>
    </span>
  );
}

function When({ at }: { at: string | null }) {
  return <span className={WHEN}>{at ? shortDate(at) : ""}</span>;
}
