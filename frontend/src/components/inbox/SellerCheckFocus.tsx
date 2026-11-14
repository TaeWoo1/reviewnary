import { useEffect, useRef, type ReactNode } from "react";
import { useInRouterContext, useSearchParams } from "react-router-dom";

/**
 * <b>Arriving on a record from the Home</b> (2026-11-10, Home FE connection).
 *
 * <p>A check whose day has come is listed on the Home with 「진행 결과 적기」, which opens this inquiry at
 * `?focus=seller-check&checkId=<the record>`. Once the record is on screen this scrolls to it and moves focus onto it, so
 * the seller lands on the thing they pressed and a keyboard or screen-reader user is put there too — once per arrival,
 * never again on a re-render.
 *
 * <p>When the record standing now is not the one the Home showed (it was rewritten in the meantime), it says so above
 * the record rather than opening the new one silently as if it were the same.
 */
interface FocusProps {
  /** The record has been read — present, absent or failed — so there is somewhere to land. */
  ready: boolean;
  /** The record standing now, or null when there is none. */
  currentCheckId: string | null;
  children: ReactNode;
}

export function SellerCheckFocus(props: FocusProps) {
  // Outside a router there is no address to arrive from (the panel drawn on its own): the record, as it was.
  return useInRouterContext() ? <RoutedFocus {...props} /> : <>{props.children}</>;
}

function RoutedFocus({ ready, currentCheckId, children }: FocusProps) {
  const [params] = useSearchParams();
  const wanted = params.get("focus") === "seller-check";
  const asked = params.get("checkId");
  const target = useRef<HTMLDivElement>(null);
  const landed = useRef(false);

  useEffect(() => {
    if (!wanted || !ready || landed.current || !target.current) return;
    landed.current = true;
    target.current.scrollIntoView?.({ block: "center", behavior: "smooth" });
    target.current.focus({ preventScroll: true });
  }, [wanted, ready]);

  const changed = wanted && ready && asked != null && currentCheckId != null && asked !== currentCheckId;
  return (
    <div
      ref={target}
      id="seller-check"
      tabIndex={wanted ? -1 : undefined}
      data-testid="seller-check-focus"
      className={wanted ? "rounded-lg outline-none focus:ring-2 focus:ring-warn focus:ring-offset-4" : undefined}
    >
      {changed ? (
        <p role="status" className="mb-2 break-keep text-sm text-muted" data-testid="seller-check-changed">
          홈에서 본 뒤 기록이 새로 적혔어요. 지금 기록을 보여 드려요.
        </p>
      ) : null}
      {children}
    </div>
  );
}
