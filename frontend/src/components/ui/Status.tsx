import type { ReactNode } from "react";

/**
 * The only way a state is coloured (docs/reviewnary_design.md §5).
 *
 * Five tones and each means exactly one thing: `good` is a PROVEN state (connected, grounded, verified),
 * `warn` is "look before acting", `bad` is failed / negative / disconnected, `info` is "reviewnary
 * prepared something", `neutral` is reference. Every chip carries a word — colour is never the only
 * carrier — and a chip never carries a number alone.
 */
export type StatusTone = "good" | "warn" | "bad" | "info" | "neutral";

const CHIP: Record<StatusTone, string> = {
  good: "bg-good/10 text-good",
  warn: "bg-warn/10 text-warn",
  bad: "bg-bad/10 text-bad",
  info: "bg-brand-50 text-brand-700",
  neutral: "bg-canvas text-muted",
};

const WORD: Record<StatusTone, string> = {
  good: "text-good",
  warn: "text-warn",
  bad: "text-bad",
  info: "text-brand-700",
  neutral: "text-muted",
};

const DOT: Record<StatusTone, string> = {
  good: "bg-good",
  warn: "bg-warn",
  bad: "bg-bad",
  info: "bg-brand-700",
  neutral: "bg-muted",
};

export function Status({
  tone = "neutral",
  variant = "chip",
  children,
  className = "",
}: {
  tone?: StatusTone;
  /**
   * `chip` — tinted pill; `word` — the coloured word alone; `badge` — the tinted mark an inbox row's
   * lead column carries (Home visual target, 2026-10-01).
   *
   * <p>`badge` is `chip` at the row's own type size and with §4's 8px edge instead of a stadium. It is a
   * variant rather than a `className` on `chip` because every one of those three values would have had to
   * be overridden, and two Tailwind utilities for one property resolve by stylesheet order, not by the
   * order they are written in.
   */
  variant?: "chip" | "word" | "badge";
  children: ReactNode;
  className?: string;
}) {
  if (variant === "word") {
    // <b>Three emphasis carriers for one word was one too many.</b> The dot and the colour already say
    // "this is a state"; the bold was the third, and on a work queue — where every row is work by
    // definition — it put twenty identical marks at the same weight as the customer sentences beside
    // them, which are the content. Measured on `/inquiries`, 2026-09-04: nineteen 「답변 필요」 and two
    // 「초안 준비됨」. Colour and word are unchanged, so nothing a seller reads is different; the row's
    // largest, heaviest text is now the thing they came to read.
    return (
      <span className={`inline-flex items-center gap-1.5 whitespace-nowrap text-sm font-medium ${WORD[tone]} ${className}`}>
        <span aria-hidden="true" className={`h-1.5 w-1.5 shrink-0 rounded-full ${DOT[tone]}`} />
        {children}
      </span>
    );
  }
  if (variant === "badge") {
    return (
      <span
        className={`inline-flex items-center gap-1 whitespace-nowrap rounded-lg px-2 py-1 text-sm font-semibold ${CHIP[tone]} ${className}`}
      >
        {children}
      </span>
    );
  }
  return (
    <span
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold ${CHIP[tone]} ${className}`}
    >
      {children}
    </span>
  );
}
