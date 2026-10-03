/**
 * <b>A filter that is not a row of boxes</b> (리뷰 canonical mockup, 2026-10-03).
 *
 * <p>The record carried three segmented groups — channel, tier, sort — stacked above the list, which is
 * three grey slabs and ~120px before a seller reads one customer's sentence. The tier is the screen's own
 * axis and became the list's tabs; these two are not an axis, they are settings on it, so they read as
 * what they are: the current value, quietly, with the whole set one press away.
 *
 * <p>A native {@code select} rather than a menu built here: the options, the keyboard, the screen reader
 * and the touch target are the platform's, and nothing about this control is special enough to re-make
 * them. Only the box around it is removed.
 */
export function QuietSelect({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: readonly { value: string; label: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <span className="relative inline-flex items-center">
      <select
        aria-label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="min-h-[36px] cursor-pointer appearance-none rounded-md bg-transparent pr-5 text-sm font-semibold text-muted transition hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <svg
        aria-hidden="true"
        viewBox="0 0 24 24"
        className="pointer-events-none absolute right-0 h-3 w-3 stroke-current text-muted"
        fill="none"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="m6 9 6 6 6-6" />
      </svg>
    </span>
  );
}
