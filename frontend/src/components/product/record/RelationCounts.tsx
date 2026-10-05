import { Link } from "react-router-dom";
import { count } from "../../../lib/format";

/**
 * 이 상품이 가진 것들의 수 — 그리고 그 각각으로 가는 문.
 *
 * <b>카드가 아니다.</b> 전에는 리뷰·문의·미답변이 각각 테두리와 배경을 가진 상자 셋이었고, 그 상자가
 * 화면에서 제일 큰 물건이었다. 상자가 말하는 것은 숫자 하나뿐인데 상자의 크기는 「이것이 이 화면의
 * 주제」라고 말한다. 여기서는 제목 바로 아래 한 줄로 서서, 이 레코드가 무엇을 얼마나 갖고 있는지만
 * 알려 주고 각 섹션에 자리를 넘긴다.
 *
 * <b>지금 사람이 할 일 하나만 한 단계 위에 있다.</b> 답변 대기는 다른 다섯과 달리 쌓인 양이 아니라
 * 기다리는 사람의 수다. 색은 거기에만 쓴다.
 *
 * <b>0은 사실이고 문이 아니다.</b> 빈 목록을 여는 컨트롤은 지키지 못할 약속이므로, 0인 항목은 숫자만
 * 남기고 링크가 되지 않는다 (Product Operations Continuity v1 §1).
 */
export type RelationCount = {
  label: string;
  value: number;
  /** 같은 술어로 센 목록으로 가는 길. 없으면(또는 0이면) 이 항목은 사실로만 선다. */
  to?: string;
  /** 지금 사람을 기다리는 것 하나 — 한 단계 강조. */
  emphasis?: boolean;
};

export function RelationCounts({
  items,
  // 지식 화면이 같은 띠를 쓴다 — 거기서 세는 것은 한 상품이 가진 것이 아니라 회사가 가진 지식이므로,
  // 읽어 주는 이름만 부를 수 있게 열어 둔다. 기본값은 이 컴포넌트가 처음 선 자리의 이름 그대로다.
  ariaLabel = "이 상품이 가진 것",
  // 제목 아래 제 줄에 설 때는 아래 선이 본문과의 경계가 된다. 제목과 같은 줄에 설 때는 그 선이 제목을
  // 가로질러 긋는 선이 되므로 없다 — 띠의 일이 바뀐 것이 아니라 서 있는 자리가 다른 것이다.
  bare = false,
}: {
  items: RelationCount[];
  ariaLabel?: string;
  bare?: boolean;
}) {
  return (
    <nav
      aria-label={ariaLabel}
      className={`flex flex-wrap items-center gap-x-5 gap-y-2 ${bare ? "" : "border-b border-line pb-3"}`}
    >
      {items.map((item) => (
        <Relation key={item.label} item={item} />
      ))}
    </nav>
  );
}

function Relation({ item }: { item: RelationCount }) {
  const emphasis = item.emphasis && item.value > 0;
  const body = (
    <>
      <span>{item.label}</span>
      <b
        className={`rounded-[5px] px-1.5 py-px text-xs font-bold tabular-nums ${
          emphasis ? "bg-warn/10 text-warn" : "bg-canvas text-ink"
        }`}
      >
        {count(item.value)}
      </b>
    </>
  );
  const shell = `flex items-center gap-1.5 text-sm ${emphasis ? "font-semibold text-warn" : "text-muted"}`;
  if (!item.to || item.value === 0) {
    return <span className={shell}>{body}</span>;
  }
  return (
    <Link
      to={item.to}
      aria-label={`${item.label} ${count(item.value)}건 보기`}
      className={`${shell} rounded transition hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700`}
    >
      {body}
    </Link>
  );
}
