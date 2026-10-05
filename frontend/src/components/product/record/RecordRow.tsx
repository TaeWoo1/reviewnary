import type { ReactNode } from "react";
import { Link } from "react-router-dom";

/**
 * 레코드 화면의 한 행 — 한 객체는 한 줄이다.
 *
 * <b>Why a primitive rather than four hand-made lists.</b> 상품 상세는 이제 record page이고, 그 위에
 * 서는 목록이 다섯이다 (기다리는 문의 · 반복되는 문제 · 근거 문장 · 최근 리뷰 · 상품 지식). 다섯이
 * 제각기 패딩과 분할선을 가지면 그것은 다섯 개의 디자인이지 하나의 화면이 아니다. 행의 높이·분할선·
 * 좌우 배치는 여기 한 곳에만 쓰여 있고, 각 섹션은 그 안에 무엇을 넣을지만 고른다.
 *
 * <b>행 전체가 문이다.</b> 행 안에 또 다른 링크를 두지 않는다 — 한 행이 한 객체를 가리키는데 그 안에
 * 두 개의 목적지가 있으면 어느 쪽이 이 행인지 읽는 사람이 알 수 없다. 예외는 근거 문장으로, 거기서
 * 가리키는 것은 리뷰이고 행 자체는 문장이라 링크를 문장 아래 메타 줄에 둔다.
 */
export function RecordRows({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <ul className="divide-y divide-line/70" aria-label={label}>
      {children}
    </ul>
  );
}

export function RecordRow({
  to,
  onClick,
  children,
}: {
  to?: string;
  /** 다른 화면이 아니라 이 자리에서 열리는 것 — 상품 지식의 편집기가 그렇다. */
  onClick?: () => void;
  children: ReactNode;
}) {
  const body = (
    <div className="flex items-center justify-between gap-4 py-[11px]">{children}</div>
  );
  const open = "block w-full rounded-lg text-left transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700";
  if (to) {
    return (
      <li>
        <Link to={to} className={open}>
          {body}
        </Link>
      </li>
    );
  }
  if (onClick) {
    return (
      <li>
        <button type="button" onClick={onClick} className={open}>
          {body}
        </button>
      </li>
    );
  }
  return <li>{body}</li>;
}

/** 행의 왼쪽 — 분류 표시, 이름, 그리고 남는 자리를 차지하는 한 줄 미리보기. */
export function RowMain({ children }: { children: ReactNode }) {
  return <div className="flex min-w-0 flex-1 items-baseline gap-[9px]">{children}</div>;
}

/** 행의 오른쪽 — 숫자와 날짜. 줄바꿈하지 않는다. */
export function RowMeta({ children }: { children: ReactNode }) {
  return (
    <div className="flex shrink-0 items-baseline gap-3 text-sm tabular-nums text-muted">
      {children}
    </div>
  );
}

/** 이름 — 한 줄로 서고 줄지 않는다. */
export function RowTitle({ children }: { children: ReactNode }) {
  return <span className="shrink-0 whitespace-nowrap font-semibold text-ink">{children}</span>;
}

/**
 * 한 줄 미리보기 — 넘치면 자른다.
 *
 * 고객이 쓴 문장을 여기서 접는 이유는 분량이 아니라 종류다. 이 줄은 「무엇에 대한 것인가」를 알려
 * 주는 자리이고, 문장을 끝까지 읽는 자리는 그 객체의 화면이다.
 */
export function RowPreview({ children, strong }: { children: ReactNode; strong?: boolean }) {
  return (
    <span className={`min-w-0 flex-1 truncate text-sm ${strong ? "text-ink" : "text-muted"}`}>
      {children}
    </span>
  );
}

/** 채널·종류처럼 행이 속한 갈래 — 값이지 상태가 아니므로 색을 쓰지 않는다. */
export function RowTag({ children }: { children: ReactNode }) {
  return (
    <span className="shrink-0 rounded-md bg-canvas px-[7px] py-0.5 text-xs text-muted">
      {children}
    </span>
  );
}

/**
 * 상태 — 조치 중만 색을 쓴다.
 *
 * 관찰 중은 reviewnary가 기본으로 두는 상태라 모든 행이 색을 가지면 색이 아무 말도 하지 않게 된다.
 * 사람이 이미 손을 댄 문제 하나만 눈에 걸리면 된다.
 */
export function RowState({ children, acting }: { children: ReactNode; acting: boolean }) {
  return (
    <span
      className={`shrink-0 rounded-full border px-2 py-px text-xs ${
        acting ? "border-warn/30 bg-warn/[0.07] text-warn" : "border-line text-muted"
      }`}
    >
      {children}
    </span>
  );
}
