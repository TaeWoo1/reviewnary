/**
 * Channel text, as a seller should read it.
 *
 * <b>A defensive fallback as of 2026-10-05, not the boundary.</b> The backend's
 * `VocPreviewSanitizer` now makes every VOC body plain BEFORE it redacts and before it cuts to 60
 * characters, so what arrives here is already the sentence a person typed. This stays because it
 * costs nothing on text that is already plain and because it is the only thing standing between a
 * seller and a payload from a build that predates that change. It is NOT a place to fix a leak: a
 * leak here means the backend let markup out, and that is where it gets fixed — this pass could not
 * have repaired the one that prompted the change anyway, because `/<[^>]*>/g` does not match a tag
 * whose closing `>` was already truncated away.
 *
 * <b>Why this exists.</b> Cafe24's board carries the customer's message as HTML — the first inquiry
 * on this org's 문의 screen literally opens with {@code <meta charset="utf-8">}, and NAVER review
 * bodies arrive with {@code &ldquo;} where the customer typed a quotation mark. Both reached the
 * screen verbatim: the list preview, the detail body and the draft's own quoted question. A seller
 * reading their inbox should never have to read markup.
 *
 * <b>Escaped markup is TEXT, with one named exception.</b> A channel that sent
 * {@code &lt;b&gt;굵게&lt;/b&gt;} escaped it, so the author meant those characters to be read — this
 * function decodes them and leaves them standing, and a test fixes that. The exception is
 * {@link DOCUMENT_METADATA}: an editor's document preamble, which no customer types and which the
 * first pass cannot reach because it arrives escaped. Removing it is not a second generic strip —
 * a blanket pass over decoded text would also delete a customer's own 「<급함>」.
 *
 * <b>What it does NOT do.</b> It does not render HTML and it does not touch what is stored. The
 * source row keeps exactly what the channel sent — this is a presentation step, applied where text
 * is displayed, so nothing here can change what an approved draft was checked against. Tags are
 * removed rather than interpreted (no {@code innerHTML}, no parser), so no markup a channel sends
 * can become live markup here.
 */

/** The entities that actually appear in this org's channel text, plus the four structural ones. */
const NAMED: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: " ",
  ldquo: "“",
  rdquo: "”",
  lsquo: "‘",
  rsquo: "’",
  hellip: "…",
  middot: "·",
  ndash: "–",
  mdash: "—",
};

/**
 * Document-preamble elements — removed after decoding as well as before it.
 *
 * <b>A closed list of names, and it grows only on observation.</b> `meta` is what this org's channel
 * text actually carries: measured across every stored inquiry and review body, the only escaped tag
 * name present at all is `meta` (5 inquiries), and no body uses `&lt;` as a literal less-than. Adding
 * `html`/`head`/`style` because the same editor paste *could* produce them would be inventing data;
 * when one appears, it is one word here and a fixture beside it.
 */
const DOCUMENT_METADATA = /<\/?(?:meta)\b[^>]*>/gi;

/**
 * Strip channel markup and decode entities.
 *
 * <b>Order matters and it hid a defect.</b> Tags are removed BEFORE entities are decoded, which is
 * what keeps `&lt;b&gt;` readable — but it also means a preamble that arrived escaped survived the
 * tag pass and the decode pass then MATERIALIZED it: three Cafe24 inquiries, the two newest included,
 * opened with a visible `<meta charset="utf-8">` on the seller's screen. So the named preamble is
 * removed once more after decoding, and nothing else is.
 *
 * A closing block tag and a `<br>` become a newline, so a body that used tags for its
 * paragraphs keeps them; every other tag simply disappears. Runs of blank lines collapse to one, because a body built from
 * `<div>`s otherwise arrives as a column of empty space.
 */
export function plainText(value: string | null | undefined): string {
  if (!value) return "";
  const withBreaks = value
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|tr|h[1-6])\s*>/gi, "\n");
  const withoutTags = withBreaks.replace(/<[^>]*>/g, "");
  const decoded = withoutTags.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (whole, body: string) => {
    if (body.startsWith("#")) {
      const code = body[1] === "x" || body[1] === "X"
        ? Number.parseInt(body.slice(2), 16)
        : Number.parseInt(body.slice(1), 10);
      // Control characters are what a mangled entity decodes to; leaving those out keeps a broken
      // source from putting an invisible character into a body a seller is about to quote.
      return Number.isFinite(code) && code >= 32 ? String.fromCodePoint(code) : whole;
    }
    const named = NAMED[body.toLowerCase()];
    return named ?? whole;
  });
  return decoded
    // The one thing the decode above can create: a preamble that reached us escaped is a tag only now.
    .replace(DOCUMENT_METADATA, "")
    .replace(/[ \t ]+/g, " ")
    .replace(/[ \t]*\n[ \t]*/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/** The same text on one line — for a row preview, where a newline is just a wider gap. */
export function previewText(value: string | null | undefined): string {
  return plainText(value).replace(/\s*\n+\s*/g, " ").trim();
}
