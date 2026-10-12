/**
 * <b>One member's source text, bounded by structure — never by a comment or a neighbour's name.</b>
 *
 * The source-scanning fences read a slice of a `.ts` file and assert a property of it. Three times
 * now the slice has been wrong in a way that **passed**:
 *
 *   §B10  a flat `readdirSync` never descended into `graph/`, so the write fence read less than it
 *         said and an injected `confirmPublish` went unnoticed.
 *   §B12  `slice(start, indexOf("\n  private ", …))` returned `-1` for the class's LAST member, so
 *         `slice(start, -1)` ran to EOF and the fence read 1,186 lines while saying it read 120.
 *   §B16  a `< 400` line cap stopped meaning anything once the file it guarded shrank to 295.
 *
 * And the one that made this file necessary: `conversationWriteFence` ended its `decideCapture`
 * slice at the **docblock sentence** `"/** Which objects a draft/send sentence points at"`. On
 * 2026-10-12 a refactor moved `resolveTargets` — and its docblock — into `targetResolution.ts`.
 * The marker vanished, `indexOf` returned `-1`, the slice widened from ~200 lines to ~1,500, and
 * **1,054 tests stayed green.** The pinned-symbol counts did not catch it either: they count
 * symbols, and the boundary was a comment. See `docs/FINDINGS.md` §B18.
 *
 * So a boundary here is only ever a **structural** feature of the thing being read — the next
 * member at class indent, or the class's own closing brace — and a boundary that cannot be found
 * is an **error**, never a widening.
 */

const CLASS_MEMBER_STARTS = [
  "\n  private ",
  "\n  public ",
  "\n  protected ",
  "\n  async ",
  "\n  get ",
  "\n  set ",
];

/** Where `signature`'s member ends: the next member at class indent, or the class's closing brace. */
export function memberEnd(source: string, from: number): number {
  const ends = [...CLASS_MEMBER_STARTS, "\n}"]
    .map((marker) => source.indexOf(marker, from))
    .filter((at) => at > -1);
  if (ends.length === 0) {
    throw new Error(
      "no structural end boundary after this member — the slice would run to EOF. " +
        "Fix the boundary; do not widen the fence (docs/FINDINGS.md §B18).",
    );
  }
  return Math.min(...ends);
}

/**
 * The text of the member that `signature` starts, and only that.
 *
 * `signature` must be the declaration as written in the source, e.g. `"private async routeSend("`.
 * A signature that is not present throws: a fence that stopped reading its subject is a broken
 * fence, not a passing one.
 */
export function memberBody(source: string, signature: string): string {
  const start = source.indexOf(signature);
  if (start === -1) {
    throw new Error(
      `\`${signature}\` is not in this source — the member was renamed or moved, so this fence is ` +
        "no longer reading it (docs/FINDINGS.md §B18).",
    );
  }
  return source.slice(start, memberEnd(source, start + signature.length));
}

/** How many lines `memberBody` would return — for a fence that wants to state the size it reads. */
export function memberLineCount(source: string, signature: string): number {
  return memberBody(source, signature).split("\n").length;
}

/**
 * The text between two markers, where **both must be present and in this order**.
 *
 * A negative assertion over a slice is only as good as the slice: `expect(s).not.toContain(x)`
 * passes for the empty string. `slice(indexOf(a), indexOf(b))` returns `""` the moment `b` moves
 * above `a` in the file, so a fence written this way can stop reading anything and keep passing.
 * That is the same green failure as §B10 — this throws instead. See `docs/FINDINGS.md` §B18.
 */
export function between(source: string, startMarker: string, endMarker: string): string {
  const start = source.indexOf(startMarker);
  if (start === -1) {
    throw new Error(`\`${startMarker}\` is not in this source — this fence lost its start.`);
  }
  const end = source.indexOf(endMarker, start + startMarker.length);
  if (end === -1) {
    throw new Error(
      `\`${endMarker}\` does not follow \`${startMarker}\` — the slice would run to EOF. ` +
        "Fix the boundary; do not widen the fence (docs/FINDINGS.md §B18).",
    );
  }
  return source.slice(start, end);
}
