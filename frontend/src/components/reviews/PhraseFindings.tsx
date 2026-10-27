import { useState } from "react";
import type { ReviewReplyPhraseFinding } from "../../lib/types";

/**
 * <b>The company's phrases in the saved draft, marked where they stand</b> (owner decision 2026-10-23, the approved
 * draft-panel mockup ②).
 *
 * <p>Two kinds, never merged. 「절대 쓰지 않을 표현」 is a ban: it is underlined in red, the row offers to drop its
 * sentence or to fix it by hand, and approval and sending stay shut until it is gone — the server refuses them on the
 * same check, so this is the screen saying why, not the control. 「되도록 피할 표현」 is a preference: dotted in orange,
 * the row offers the seller's own replacement, and 「그대로 두기」 is a full answer.
 *
 * <p><b>Every change is the seller's press, and it is saved as their version.</b> Nothing here rewrites the text on
 * its own: dropping a sentence or swapping a phrase is a button, and the result goes through the same save as typing.
 */
export function PhraseFindings({
  body,
  findings,
  disabled,
  onApply,
  onEdit,
}: {
  /** The SAVED draft the findings were read from. */
  body: string;
  findings: ReviewReplyPhraseFinding[];
  disabled: boolean;
  /** Save this text as the seller's next version. */
  onApply: (next: string) => void;
  /** Put the cursor on this phrase in the editor. */
  onEdit: (phrase: string) => void;
}) {
  const [kept, setKept] = useState<string[]>([]);
  const shown = findings.filter((f) => f.kind === "FORBIDDEN" || !kept.includes(f.phrase));
  if (shown.length === 0) return null;
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-line bg-white p-3" aria-label="걸린 표현">
      <p className="whitespace-pre-wrap break-keep text-sm leading-relaxed text-ink">
        {segments(body, shown).map((s, i) =>
          s.kind == null ? (
            <span key={i}>{s.text}</span>
          ) : (
            <mark
              key={i}
              data-kind={s.kind}
              className={
                s.kind === "FORBIDDEN"
                  ? "border-b-2 border-bad bg-bad/10 font-semibold text-ink"
                  : "border-b-2 border-dotted border-warn bg-warn/10 text-ink"
              }
            >
              {s.text}
            </mark>
          ),
        )}
      </p>
      <ul className="flex flex-col gap-3 border-t border-line pt-3">
        {shown.map((f) => (
          <li key={`${f.kind}:${f.phrase}`} className="flex flex-wrap items-start gap-2 text-sm leading-relaxed">
            <span
              className={`shrink-0 rounded-md px-1.5 py-px text-xs font-semibold ${
                f.kind === "FORBIDDEN" ? "bg-bad/10 text-bad" : "bg-warn/10 text-warn"
              }`}
            >
              {f.kind === "FORBIDDEN" ? "절대 쓰지 않을 표현" : "되도록 피할 표현"}
            </span>
            <div className="min-w-0 flex-1">
              <p className="break-keep text-ink">
                {f.kind === "FORBIDDEN"
                  ? `「${f.phrase}」 — 이 문장을 고쳐야 확정할 수 있어요.`
                  : f.replacement
                    ? `「${f.phrase}」 → 「${f.replacement}」`
                    : `「${f.phrase}」 — 되도록 피하기로 한 표현이에요.`}
              </p>
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {f.kind === "FORBIDDEN" ? (
                  <>
                    {f.sentence ? (
                      <Press disabled={disabled} onClick={() => onApply(withoutSentence(body, f.sentence!))}>
                        이 문장 빼기
                      </Press>
                    ) : null}
                    <Press disabled={disabled} onClick={() => onEdit(f.phrase)}>
                      직접 고치기
                    </Press>
                  </>
                ) : (
                  <>
                    {f.replacement ? (
                      <Press
                        disabled={disabled}
                        onClick={() => onApply(replacePhrase(body, f.phrase, f.replacement!))}
                      >
                        제안대로 바꾸기
                      </Press>
                    ) : null}
                    <Press disabled={disabled} onClick={() => setKept([...kept, f.phrase])}>
                      그대로 두기
                    </Press>
                  </>
                )}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Press({ disabled, onClick, children }: { disabled: boolean; onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      aria-disabled={disabled}
      onClick={() => !disabled && onClick()}
      className={`rounded-lg px-2.5 py-1 text-sm font-semibold ring-1 ring-line ${
        disabled ? "bg-canvas text-muted opacity-40" : "bg-white text-ink"
      }`}
    >
      {children}
    </button>
  );
}

/**
 * The sentence under the approve buttons: what must change before approval, and what may stay. Null when nothing
 * is banned — a preference alone stops nothing and needs no reason.
 */
export function phraseBlockSentence(findings: ReviewReplyPhraseFinding[]): string | null {
  const banned = findings.filter((f) => f.kind === "FORBIDDEN").map((f) => f.phrase);
  if (banned.length === 0) return null;
  const kept = findings.filter((f) => f.kind === "AVOID").map((f) => f.phrase);
  const quoted = (words: string[]) => words.map((w) => `「${w}」`).join(" · ");
  return `${quoted(banned)}${josa(banned[banned.length - 1], "을", "를")} 고치면 확정할 수 있어요.`
    + (kept.length ? ` ${quoted(kept)}${josa(kept[kept.length - 1], "은", "는")} 그대로 둬도 돼요.` : "");
}

/** The particle after {@code word}: the first after a final consonant, else the second. */
function josa(word: string, afterConsonant: string, afterVowel: string): string {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  return code >= 0 && code < 11172 && code % 28 !== 0 ? afterConsonant : afterVowel;
}

/** The phrase as a pattern that ignores spacing — the server matched it the same way. */
function loose(phrase: string): RegExp {
  const chars = [...phrase.replace(/\s+/g, "")].map((c) => c.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return new RegExp(chars.join("\\s*"), "gi");
}

/** {@code body} with every occurrence of {@code phrase} replaced by {@code replacement}. */
export function replacePhrase(body: string, phrase: string, replacement: string): string {
  return body.replace(loose(phrase), replacement);
}

/** {@code body} without {@code sentence}, the gap it leaves closed. */
export function withoutSentence(body: string, sentence: string): string {
  const at = body.indexOf(sentence);
  if (at < 0) return body;
  return (body.slice(0, at) + body.slice(at + sentence.length)).replace(/[ \t]{2,}/g, " ").replace(/^\s+|\s+$/g, "");
}

type Segment = { text: string; kind: "FORBIDDEN" | "AVOID" | null };

/** {@code body} cut into plain and marked pieces; a ban wins where two marks would overlap. */
export function segments(body: string, findings: ReviewReplyPhraseFinding[]): Segment[] {
  const spans: Array<{ from: number; to: number; kind: "FORBIDDEN" | "AVOID" }> = [];
  for (const f of [...findings].sort((a, b) => (a.kind === b.kind ? 0 : a.kind === "FORBIDDEN" ? -1 : 1))) {
    for (const m of body.matchAll(loose(f.phrase))) {
      const from = m.index ?? 0;
      const to = from + m[0].length;
      if (m[0].length > 0 && spans.every((s) => to <= s.from || from >= s.to)) {
        spans.push({ from, to, kind: f.kind });
      }
    }
  }
  spans.sort((a, b) => a.from - b.from);
  const out: Segment[] = [];
  let at = 0;
  for (const s of spans) {
    if (s.from > at) out.push({ text: body.slice(at, s.from), kind: null });
    out.push({ text: body.slice(s.from, s.to), kind: s.kind });
    at = s.to;
  }
  if (at < body.length) out.push({ text: body.slice(at), kind: null });
  return out;
}
