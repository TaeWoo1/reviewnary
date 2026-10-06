import type { ReactNode } from "react";
import { useCaseVariant } from "../../workspace/CaseLayout";

/**
 * One labelled block of the repeated-problem pane.
 *
 * <b>A label, a hairline and air — no card.</b> The pane is already a surface; a bordered box inside it
 * is a second edge saying the same thing (§4). The label is the quiet step, not a heading in the title
 * scale, because the thing a seller reads in a block is its content and not its name.
 *
 * <b>`side` is for what acts on the block</b> — 답변 기준 채우기, 모두 보기. Never the screen's primary
 * action, which is docked at the floor of the pane.
 *
 * <b>The label takes the scale of the reading it is in</b> (반복 문제 canonical, 2026-10-05). In a 440px
 * pane the blocks are steps inside something else and the label is the quiet `h3`; on the problem's own
 * page they are the page's sections, and a 12px `h3` under an `h1` is both the wrong step and a heading
 * level that skips one. The caller does not choose — {@link useCaseVariant} already knows which reading
 * drew it.
 *
 * <b>그리고 페이지에서 그 단계는 제품 공통의 구역 단계다</b> (2026-10-07). `sm/700 muted`는 이 페이지만
 * 쓰던 여섯 번째 `h2` 크기였고, 같은 열에 선 「근거」는 또 `base/700`이었다 — 한 화면 안에서도 구역의
 * 이름이 두 크기였다. 18/600 ink는 주문·지식·설정·리포트의 `h2`가 쓰는 그 단계이고, 여기서만 다를
 * 이유가 없다. pane 쪽은 한 글자도 바뀌지 않는다.
 */
export function PaneBlock({
  label,
  side,
  children,
}: {
  label: string;
  side?: ReactNode;
  children: ReactNode;
}) {
  const page = useCaseVariant() === "page";
  const H = page ? "h2" : "h3";
  return (
    <section aria-label={label} className={`border-t border-line ${page ? "pt-4" : "pt-3"}`}>
      <div className="flex items-baseline justify-between gap-3">
        <H className={page ? "text-lg font-semibold text-ink" : "text-xs font-semibold text-muted"}>{label}</H>
        {side ? <div className={`shrink-0 ${page ? "text-sm" : "text-xs"}`}>{side}</div> : null}
      </div>
      <div className={page ? "mt-2.5 space-y-2" : "mt-2 space-y-2"}>{children}</div>
    </section>
  );
}
