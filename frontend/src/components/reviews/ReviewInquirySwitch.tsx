import { Link } from "react-router-dom";

/**
 * <b>리뷰 | 문의</b> — the one switch at the head of 리뷰·문의 (menu decision, 2026-10-27).
 *
 * <p>The menu holds one destination for both; the two record screens stay two routes, so every link and bookmark
 * that names /reviews or /inquiries still opens what it named. Coral draws a choice of two to four as a groove
 * pressed into the paper with a card riding in the chosen stop — links, not tabs: each side is a page.
 */
export function ReviewInquirySwitch({ current }: { current: "reviews" | "inquiries" }) {
  const item = (to: string, label: string, on: boolean) => (
    <Link
      to={to}
      aria-current={on ? "page" : undefined}
      className={`rounded-lg px-3 py-1 text-sm transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
        on ? "bg-surface font-semibold text-ink shadow-card" : "font-medium text-muted hover:text-ink"
      }`}
    >
      {label}
    </Link>
  );
  return (
    <nav aria-label="리뷰·문의" className="inline-flex gap-0.5 rounded-xl bg-well p-0.5 shadow-[var(--press-groove)]">
      {item("/reviews", "리뷰", current === "reviews")}
      {item("/inquiries", "문의", current === "inquiries")}
    </nav>
  );
}
