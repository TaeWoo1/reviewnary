import type { ReactNode } from "react";
import { count } from "../../../lib/format";
import { priceLabel, sellingStatusLabel } from "../../../lib/productVocabulary";
import type { KnowledgeCoverageView, ProductKnowledgeView } from "../../../lib/types";

/**
 * 상품 속성 레일 — 이 레코드가 무엇인지, 한 눈에.
 *
 * <b>레일에는 단일값·정적 사실만 산다</b> (상품 상세 canonical, 2026-10-05). 채널이 하나일 때 채널·
 * 가격·판매 상태는 이 상품의 값이지만, 채널이 둘이면 그것은 더 이상 값이 아니라 목록이다. 목록을
 * 320px 레일에 밀어 넣으면 두 채널의 가격이 서로를 가리게 되므로, 그때는 본문 섹션으로 올라간다
 * ({@link ProductDetail}이 같은 조건으로 판단한다). 옵션도 같은 규칙을 따른다.
 *
 * <b>기술 출처는 쓰지 않는다.</b> 「NAVER:PRODUCT_API:v1」은 우리가 디버깅할 때 읽는 문자열이지
 * 판매자가 읽을 문장이 아니다. 판매자가 알아야 하는 것은 이 값을 마지막으로 확인한 날이고, 그 줄은
 * 「최근 확인」으로 남는다.
 */
export function ProductRail({ data }: { data: ProductKnowledgeView }) {
  // 레일이 값을 말할 수 있는 경우는 리스팅이 정확히 하나일 때뿐이다.
  const only = data.listings.length === 1 ? data.listings[0] : null;
  const fact = (key: string) => data.facts.find((f) => f.factKey === key)?.value ?? null;

  return (
    <div className="space-y-7">
      <section aria-label="상품 정보">
        <RailHead>상품 정보</RailHead>
        <dl>
          {only ? (
            <>
              <Attr label="채널">{only.channelNameKo ?? only.channelCode}</Attr>
              <Attr label="가격">
                {only.price == null ? "—" : priceLabel(count(only.price), only.currency)}
              </Attr>
              <Attr label="판매 상태">{sellingStatusLabel(only.sellingStatus)}</Attr>
            </>
          ) : null}
          <Attr label="브랜드">{fact("taxonomy:brand")}</Attr>
          <Attr label="분류">{fact("taxonomy:category")?.replace(/>/g, " > ")}</Attr>
          <Attr label="제조사">{fact("taxonomy:manufacturer")}</Attr>
          <Attr label="상품코드">{data.sku}</Attr>
          {only ? <Attr label="최근 확인">{only.observedAt?.slice(0, 10)}</Attr> : null}
        </dl>
      </section>

      <section aria-label="우리가 갖고 있는 정보">
        <RailHead>우리가 갖고 있는 정보</RailHead>
        <dl>
          {GROUPS.map(({ coverage, label }) => {
            const facets = data.knowledgeCoverage
              .filter((row) => row.coverage === coverage)
              .map((row) => FACET_KO[row.facet] ?? row.facet);
            return facets.length === 0 ? null : (
              <Attr key={coverage} label={label}>
                {facets.join(" · ")}
              </Attr>
            );
          })}
        </dl>
        {/* 보유하지 않는다는 것과 존재하지 않는다는 것의 차이 — 서버와 Agent가 지키는 구분을
            화면도 같은 문장으로 지킨다. */}
        <p className="mt-2 break-keep text-xs leading-relaxed text-muted">
          「갖고 있지 않음」은 reviewnary가 그 정보를 보유하고 있지 않다는 뜻이며, 상품에 그런 정보가
          없다는 뜻이 아닙니다.
        </p>
      </section>
    </div>
  );
}

/** 보유 등급을 판매자의 말로 — `ProductDetail`이 쓰던 칩의 어휘 그대로. */
const GROUPS: Array<{ coverage: KnowledgeCoverageView["coverage"]; label: string }> = [
  { coverage: "AVAILABLE", label: "있음" },
  { coverage: "PARTIAL", label: "일부" },
  { coverage: "STALE", label: "오래됨" },
  { coverage: "UNAVAILABLE", label: "갖고 있지 않음" },
];

const FACET_KO: Record<string, string> = {
  IDENTITY: "이름·코드",
  LISTING: "채널 리스팅",
  PRICE: "가격",
  VARIANT: "옵션",
  TAXONOMY: "브랜드·분류",
  DESCRIPTION: "상세 설명",
  SPEC: "규격",
  SIGNALS: "신호",
};

function RailHead({ children }: { children: ReactNode }) {
  return (
    <h2 className="border-b border-line pb-[7px] text-xs font-bold text-muted">{children}</h2>
  );
}

/** 값이 없으면 줄도 없다 — 「—」로 자리를 채우면 모르는 것이 아는 것처럼 보인다. */
function Attr({ label, children }: { label: string; children: ReactNode }) {
  if (children == null || children === "") return null;
  return (
    <div className="flex items-baseline gap-3 py-[7px]">
      <dt className="w-[86px] shrink-0 text-xs text-muted">{label}</dt>
      <dd className="min-w-0 break-keep text-sm text-ink">{children}</dd>
    </div>
  );
}
