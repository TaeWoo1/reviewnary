import { useEffect, useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { MOBILE_TABS, navItemOwns } from "../../lib/nav.v2";
import { NavIcon } from "../icons/NavIcon";
import { useTodayWorkCount } from "../../lib/useTodayWorkCount";

const TAB_BASE =
  "flex min-h-[56px] flex-1 flex-col items-center justify-center gap-1 px-1 text-xs font-medium transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700";

/**
 * Mobile tab bar: four destinations plus a 더보기 trigger.
 *
 * The destinations are selected from the same nav model the side nav renders — a tab is never a
 * separate declaration. 더보기 is a button, not a route, so it opens the drawer rather than
 * navigating; that keeps the bar at five targets without inventing a fifth destination.
 */
export function MobileBottomNav({ onMore }: { onMore: () => void }) {
  const { pathname, search } = useLocation();
  const work = useTodayWorkCount();
  const typing = useKeyboardOpen();
  return (
    <nav
      aria-label="모바일 메뉴"
      className={`fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface md:hidden ${typing ? "hidden" : "flex"}`}
      data-testid="mobile-bottom-nav"
    >
      {MOBILE_TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={() =>
            `${TAB_BASE} ${navItemOwns(tab, pathname, search) ? "text-brand-700" : "text-muted"}`
          }
        >
          <span className="relative">
            <NavIcon name={tab.icon} />
            {tab.badge === "work" && work ? (
              <span className="absolute -right-3 -top-1.5 rounded-full bg-brand-700 px-1 text-[10px] font-semibold leading-4 tabular-nums text-white">
                {work > 99 ? "99+" : work}
              </span>
            ) : null}
          </span>
          <span>
            {tab.short ?? tab.label}
            {tab.badge === "work" && work ? <span className="sr-only"> {work}건</span> : null}
          </span>
        </NavLink>
      ))}
      <button
        type="button"
        onClick={onMore}
        aria-haspopup="dialog"
        className={`${TAB_BASE} text-muted`}
      >
        <NavIcon name="more" />
        <span>더보기</span>
      </button>
    </nav>
  );
}

/** Inputs that open a keyboard. A checkbox, a button or a picker does not. */
const NO_KEYBOARD = new Set(["button", "checkbox", "radio", "range", "submit", "reset", "file", "color", "image", "hidden"]);

export function isTextEntry(el: Element | null): boolean {
  if (!el) return false;
  if (el instanceof HTMLTextAreaElement) return !el.readOnly && !el.disabled;
  if (el instanceof HTMLInputElement) return !NO_KEYBOARD.has(el.type) && !el.readOnly && !el.disabled;
  return el instanceof HTMLElement && el.isContentEditable === true;
}

/**
 * How much the visible screen has to lose before it counts as a keyboard. A phone keyboard takes 250px or more (Galaxy
 * S24: 331–395px of 748); the browser's own address bar folding away is about 56px.
 */
export const KEYBOARD_MIN_PX = 150;

/**
 * Whether the phone's keyboard is up: a text field holds the focus AND the visible screen is at least
 * {@link KEYBOARD_MIN_PX} shorter than it is at rest. Focus alone is not the keyboard — Android's back key (and the
 * keyboard's own hide key) closes the keyboard and leaves the field focused, and the bar stayed away until the seller
 * tapped something else (Galaxy S24, 2026-11-14). A hardware keyboard, which takes no screen, never hides the bar.
 */
export function keyboardOpen(textFocused: boolean, visibleHeight: number, restingHeight: number): boolean {
  return textFocused && restingHeight - visibleHeight >= KEYBOARD_MIN_PX;
}

/**
 * The height the page can see now. With `interactive-widget=resizes-content` (index.html) Android Chrome shrinks the
 * window itself, so `innerHeight` drops; Safari ignores that setting and shrinks only the visual viewport. The smaller
 * of the two covers both. `visualViewport.height` also shrinks on a pinch-zoom, so it is read back at scale 1.
 */
function visibleHeight(): number {
  const vv = window.visualViewport;
  return Math.min(window.innerHeight, vv ? vv.height * vv.scale : window.innerHeight);
}

/**
 * <b>The bar steps aside while the keyboard is up</b> (휴대폰 문의 상세, 2026-11-14). With a phone's keyboard open the
 * screen left above it is ~400px, and a fixed 56px bar took an eighth of it — on top of the field's own 저장 / 확인
 * buttons, which sit right under the field. It comes back the moment the keyboard goes down, whether the focus left
 * the field or not. Below `md` only: the bar is not drawn above it.
 *
 * <p>"At rest" is the tallest the screen has been at this width — a turn of the phone starts it over.
 */
function useKeyboardOpen(): boolean {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let width = window.innerWidth;
    let resting = visibleHeight();
    const sync = () => {
      const height = visibleHeight();
      if (window.innerWidth !== width) {
        width = window.innerWidth;
        resting = height;
      } else if (height > resting) {
        resting = height;
      }
      setOpen(keyboardOpen(isTextEntry(document.activeElement), height, resting));
    };
    // `focusout` fires before the next element takes the focus; read it once the move has landed.
    const out = () => window.setTimeout(sync, 0);
    // When the keyboard takes the bottom of the screen, the field being typed in stays in what is left. Browsers usually
    // do this themselves; a field in an inner scroller (every page here) is where they have been seen not to.
    const resized = () => {
      sync();
      const el = document.activeElement;
      if (window.innerWidth >= 768 || !isTextEntry(el)) return;
      const r = (el as HTMLElement).getBoundingClientRect();
      if (r.top < 0 || r.bottom > visibleHeight()) (el as HTMLElement).scrollIntoView({ block: "nearest" });
    };
    document.addEventListener("focusin", sync);
    document.addEventListener("focusout", out);
    window.addEventListener("resize", resized);
    window.visualViewport?.addEventListener("resize", resized);
    return () => {
      document.removeEventListener("focusin", sync);
      document.removeEventListener("focusout", out);
      window.removeEventListener("resize", resized);
      window.visualViewport?.removeEventListener("resize", resized);
    };
  }, []);
  return open;
}
