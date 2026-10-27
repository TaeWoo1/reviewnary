/**
 * ★n, in 위험 for ★1–2 and 주의 for ★3 (2026-10-28), so the eye can tell a complaint from a thank-you without reading —
 * every rating was the same grey. The number is still the fact; the colour only repeats it.
 */
export function Stars({ rating }: { rating: number }) {
  return <span className={`font-semibold tabular-nums ${starTone(rating)}`}>★{rating}</span>;
}

/** The colour alone, for a row that writes ★n inside its own text. */
export function starTone(rating: number): string {
  return rating <= 2 ? "text-bad" : rating === 3 ? "text-warn" : "text-muted";
}
