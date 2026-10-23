import { useEffect, useMemo, useState } from "react";
import { PageHead } from "../../components/ui/PageHead";
import { Panel } from "../../components/ui/Panel";
import { Btn, BtnLink } from "../../components/ui/Btn";
import { api } from "../../lib/apiClient";
import { backendMessage } from "../../components/connect/channelShared";
import type {
  AnswerLength,
  AnswerStyleView,
  AnswerTone,
  AvoidPhrase,
  EmojiPolicy,
} from "../../lib/types";
import { isReviewTopicWord } from "../../lib/reviewTopicWords";

/**
 * AI 답변 스타일 — how this company words a reply.
 *
 * <b>The line this screen has to hold.</b> 답변 기준 (운영 정책 / 상품 지식) decides WHAT is true;
 * this decides HOW it is said. A seller who types a delivery promise into 「꼭 포함할 표현」 is trying
 * to put a fact in the wrong box, and the backend refuses it by name — so the copy here points at the
 * right box rather than leaving them to guess.
 *
 * <b>No AI vocabulary.</b> No prompt, no system, no temperature, no model. The person this product is
 * for runs a manufacturing company; the words on screen are the words they would use themselves.
 */
const TONES: Array<{ value: AnswerTone; label: string; hint: string }> = [
  { value: "POLITE", label: "정중하게", hint: "차분하고 예의 바른 존댓말 (기본)" },
  { value: "FRIENDLY", label: "친근하게", hint: "따뜻한 존댓말, 과장은 하지 않음" },
  { value: "CONCISE", label: "간결하게", hint: "군더더기 없이 짧은 존댓말" },
];

const LENGTHS: Array<{ value: AnswerLength; label: string; hint: string }> = [
  { value: "SHORT", label: "짧게", hint: "2문장 이내" },
  { value: "NORMAL", label: "보통", hint: "2~4문장 (기본)" },
  { value: "DETAILED", label: "자세히", hint: "4~6문장" },
];

const EMOJI: Array<{ value: EmojiPolicy; label: string }> = [
  { value: "NONE", label: "사용하지 않음" },
  { value: "LIMITED", label: "가끔 한 개까지" },
];

/** The synthetic question the preview is built on. Fixed, and never a real inquiry. */
const PREVIEW_QUESTION = "배송은 언제 되나요?";

export function AnswerStyle() {
  const [loaded, setLoaded] = useState<AnswerStyleView | null>(null);
  const [loadError, setLoadError] = useState(false);

  const [tone, setTone] = useState<AnswerTone>("POLITE");
  const [length, setLength] = useState<AnswerLength>("NORMAL");
  const [emoji, setEmoji] = useState<EmojiPolicy>("NONE");
  const [greeting, setGreeting] = useState("");
  const [closing, setClosing] = useState("");
  const [address, setAddress] = useState("");
  const [required, setRequired] = useState("");
  const [forbidden, setForbidden] = useState("");
  // 「되도록 피할 표현 → 대신 쓸 표현」 (owner decision 2026-10-23). Pairs, so not a textarea: each row is one phrase
  // and what to write instead, and an empty row is drawn to type into.
  const [avoid, setAvoid] = useState<AvoidPhrase[]>([]);
  const [fallback, setFallback] = useState("");

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let active = true;
    void api
      .getAnswerStyle()
      .then((style) => {
        if (!active) return;
        setLoaded(style);
        setTone(style.tone);
        setLength(style.lengthPreference);
        setEmoji(style.emojiPolicy);
        setGreeting(style.greeting ?? "");
        setClosing(style.closing ?? "");
        setAddress(style.customerAddress ?? "");
        setRequired(style.requiredPhrases.join("\n"));
        setForbidden(style.forbiddenPhrases.join("\n"));
        setAvoid(style.avoidPhrases ?? []);
        setFallback(style.unknownFallbackTemplate ?? "");
      })
      .catch(() => active && setLoadError(true));
    return () => {
      active = false;
    };
  }, []);

  const submit = async () => {
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const style = await api.saveAnswerStyle({
        tone,
        lengthPreference: length,
        emojiPolicy: emoji,
        greeting: greeting.trim() || null,
        closing: closing.trim() || null,
        customerAddress: address.trim() || null,
        requiredPhrases: lines(required),
        forbiddenPhrases: lines(forbidden),
        unknownFallbackTemplate: fallback.trim() || null,
        avoidPhrases: avoid
          .map((a) => ({ phrase: a.phrase.trim(), replacement: a.replacement?.trim() || null }))
          .filter((a) => a.phrase.length > 0),
      });
      setAvoid(style.avoidPhrases ?? []);
      setLoaded(style);
      setSaved(true);
    } catch (e) {
      // The server's own sentence, on purpose: a refusal here names which phrase was refused and
      // why, and replacing it with 「저장하지 못했습니다」 would throw away the only actionable part.
      setError(backendMessage(e) ?? "저장하지 못했습니다. 입력하신 내용을 확인해 주세요.");
    } finally {
      setSaving(false);
    }
  };

  const preview = useMemo(
    () => previewLines({ tone, length, greeting, closing, address, required, emoji }),
    [tone, length, greeting, closing, address, required, emoji],
  );

  return (
    <>
      <PageHead
        title="AI 답변 스타일"
        description="문의 답변의 말투와 표현 방식을 설정합니다. 상품 정보나 정책 등 사실 자체는 바뀌지 않습니다."
      />

      {loadError ? (
        <Panel title="AI 답변 스타일">
          <p className="text-warn">설정을 불러오지 못했습니다.</p>
        </Panel>
      ) : loaded === null ? (
        <Panel title="AI 답변 스타일">
          <p className="text-muted">불러오는 중…</p>
        </Panel>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="space-y-4">
            <Panel
              title="말투와 길이"
              description={
                loaded.configured
                  ? "저장된 설정입니다."
                  : "아직 설정하지 않으셨습니다. 지금은 기본값으로 답변합니다."
              }
            >
              <div className="grid gap-4 sm:grid-cols-2">
                <label className="block">
                  <span className="text-sm font-medium text-ink">답변 말투</span>
                  <select
                    value={tone}
                    onChange={(e) => setTone(e.target.value as AnswerTone)}
                    className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
                  >
                    {TONES.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label} — {t.hint}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block">
                  <span className="text-sm font-medium text-ink">답변 길이</span>
                  <select
                    value={length}
                    onChange={(e) => setLength(e.target.value as AnswerLength)}
                    className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
                  >
                    {LENGTHS.map((l) => (
                      <option key={l.value} value={l.value}>
                        {l.label} — {l.hint}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block">
                  <span className="text-sm font-medium text-ink">이모지</span>
                  <select
                    value={emoji}
                    onChange={(e) => setEmoji(e.target.value as EmojiPolicy)}
                    className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
                  >
                    {EMOJI.map((e2) => (
                      <option key={e2.value} value={e2.value}>
                        {e2.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block">
                  <span className="text-sm font-medium text-ink">고객 호칭</span>
                  <input
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                    maxLength={20}
                    placeholder="예: 고객님"
                    className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
                  />
                </label>
              </div>
            </Panel>

            <Panel title="인사말" description="비워 두시면 AI가 상황에 맞게 씁니다.">
              <div className="grid gap-4 sm:grid-cols-2">
                <label className="block">
                  <span className="text-sm font-medium text-ink">첫 인사</span>
                  <input
                    value={greeting}
                    onChange={(e) => setGreeting(e.target.value)}
                    maxLength={60}
                    placeholder="예: 안녕하세요. 선바로입니다."
                    className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
                  />
                </label>
                <label className="block">
                  <span className="text-sm font-medium text-ink">끝 인사</span>
                  <input
                    value={closing}
                    onChange={(e) => setClosing(e.target.value)}
                    maxLength={60}
                    placeholder="예: 감사합니다."
                    className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
                  />
                </label>
              </div>
            </Panel>

            <Panel
              title="꼭 포함할 표현"
              description="한 줄에 하나씩, 5개까지 적어 주세요."
            >
              {/* The hint is a SIBLING of the label, not inside it: a field's accessible name is everything the
                  label contains, and a two-sentence caveat read out as the name of the box is worse for the person
                  using a screen reader than no caveat at all. */}
              <label className="block">
                <span className="text-sm font-medium text-ink">꼭 포함할 표현</span>
                <textarea
                  value={required}
                  onChange={(e) => setRequired(e.target.value)}
                  rows={3}
                  placeholder={"예: 정성껏 준비하겠습니다"}
                  className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base leading-relaxed focus:border-brand focus:outline-none"
                />
              </label>
              <p className="mt-1 break-keep text-sm text-muted">
                배송·환불·재고처럼 사실을 단정하는 문장은 넣을 수 없습니다. 그런 내용은 「운영 기준」에
                등록하시면, 해당하는 문의에서 근거로 인용됩니다.
              </p>
            </Panel>

            {/* The split the owner approved on 2026-10-23: an absolute ban, and a preference with what to say
                instead. Both apply to inquiry answers and review replies. */}
            <Panel title="절대 쓰지 않을 표현" description="문의 답변과 리뷰 답글 모두에 적용됩니다. 한 줄에 하나씩, 10개까지.">
              <p>
                <span className="inline-block rounded-md bg-bad/10 px-1.5 py-px text-xs font-semibold text-bad">
                  발견되면 승인 · 등록 보류
                </span>
              </p>
              <label className="mt-2 block">
                <span className="sr-only">절대 쓰지 않을 표현</span>
                <textarea
                  value={forbidden}
                  onChange={(e) => setForbidden(e.target.value)}
                  rows={4}
                  placeholder={"예: 고객님 탓"}
                  className="w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base leading-relaxed focus:border-brand focus:outline-none"
                />
              </label>
              {lines(forbidden).filter(isReviewTopicWord).map((word) => (
                <p key={word} role="note" className="mt-2 break-keep rounded-xl bg-warn/10 px-3 py-2 text-sm leading-relaxed text-warn">
                  「{word}」처럼 낱말 하나만 적으면 「{word}{topicParticle(word)} 주문 내역에서 접수해 주세요」 같은 안내까지 막힐 수 있어요.
                  막고 싶은 문장을 구체적으로 적어 주세요 — 예: 「무조건 전액 {word}」. 이 줄은 리뷰에서는 적용하지 않습니다.
                </p>
              ))}
              <p className="mt-2 break-keep text-sm leading-relaxed text-muted">
                AI 초안을 쓸 때 피하도록 알려 줍니다. 그래도 들어가면 문의 답변은 그 초안을 쓰지 않고, 리뷰 답글은 한 번 다시
                써 본 뒤에도 남으면 초안을 그대로 두고 그 표현을 표시합니다 — 고치기 전에는 승인하거나 담당자에게 보낼 수
                없습니다. 판매자나 휴대폰에서 고친 문장에도 똑같이 검사합니다. 고객이 쓴 리뷰나 근거 문서에 들어 있는 것은
                상관없습니다.
              </p>
            </Panel>

            <Panel title="되도록 피할 표현" description="문의 답변과 리뷰 답글 모두에 적용됩니다. 10개까지.">
              <p>
                <span className="inline-block rounded-md bg-canvas px-1.5 py-px text-xs font-semibold text-muted">
                  대신 쓸 표현을 제안 · 그대로 둬도 승인 가능
                </span>
              </p>
              <ul className="mt-2 space-y-2" aria-label="되도록 피할 표현 목록">
                {[...avoid, { phrase: "", replacement: null }].map((pair, i) => {
                  const last = i === avoid.length;
                  const set = (next: AvoidPhrase) =>
                    setAvoid(last ? [...avoid, next] : avoid.map((a, j) => (j === i ? next : a)));
                  return (
                    <li key={i} className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)_auto] items-center gap-2">
                      <input
                        value={pair.phrase}
                        onChange={(e) => set({ ...pair, phrase: e.target.value })}
                        maxLength={40}
                        placeholder={last ? "예: 죄송하지만" : undefined}
                        aria-label={`피할 표현 ${i + 1}`}
                        className="w-full rounded-xl border border-line bg-surface px-3 py-2 text-base focus:border-brand focus:outline-none"
                      />
                      <span aria-hidden="true" className="text-muted">→</span>
                      <input
                        value={pair.replacement ?? ""}
                        onChange={(e) => set({ ...pair, replacement: e.target.value })}
                        maxLength={40}
                        placeholder={last ? "예: 번거로우시겠지만" : "비워 두면 표시만"}
                        aria-label={`대신 쓸 표현 ${i + 1}`}
                        className="w-full rounded-xl border border-line bg-surface px-3 py-2 text-base focus:border-brand focus:outline-none"
                      />
                      {last ? (
                        <span className="w-10" />
                      ) : (
                        <button
                          type="button"
                          onClick={() => setAvoid(avoid.filter((_, j) => j !== i))}
                          aria-label={`「${pair.phrase}」 빼기`}
                          className="w-10 rounded-lg px-1 py-2 text-sm font-semibold text-muted hover:text-ink"
                        >
                          빼기
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
              <p className="mt-2 break-keep text-sm leading-relaxed text-muted">
                말투 선호입니다. 들어가도 초안은 저장되고, 리뷰 답글에서는 그 표현을 표시해 대신 쓸 표현을 제안합니다. 그대로
                두고 승인해도 됩니다. 비워 두면 표시만 합니다.
              </p>
            </Panel>

            <Panel
              title="답을 모를 때 사용할 문구"
              description="등록된 답변 기준으로 답할 수 없는 문의에 쓸 문장입니다."
            >
              <textarea
                value={fallback}
                onChange={(e) => setFallback(e.target.value)}
                rows={3}
                maxLength={300}
                placeholder={"예: 정확한 확인이 필요한 내용입니다. 확인 후 다시 안내드리겠습니다."}
                className="w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base leading-relaxed focus:border-brand focus:outline-none"
                aria-label="답을 모를 때 사용할 문구"
              />
              <p className="mt-2 break-keep leading-relaxed text-muted">
                적어 두신 문장을 <b>그대로</b> 사용합니다. AI가 여기에 내용을 덧붙이지 않습니다. 비워
                두시면 답변 기준이 없는 문의에는 초안을 만들지 않고, 무엇이 부족한지만 알려드립니다.
              </p>
            </Panel>

            {error ? <p className="break-keep text-warn">{error}</p> : null}
            {saved ? <p className="break-keep text-good">저장했습니다.</p> : null}

            <div className="flex flex-wrap items-center gap-2">
              <Btn onClick={submit} disabled={saving}>
                {saving ? "저장 중…" : "저장"}
              </Btn>
            </div>

            {/*
              Where the OTHER half lives (Core Daily Loop UX Integration v1 §7).

              This screen decides how a reply is worded; what it may say comes from the 답변 기준, and
              a settings page that never names its own counterpart reads like a developer switch
              somebody left behind. Two links, no explanation beyond the sentence that separates them.
            */}
            <Panel
              title="답변의 내용은 어디서 오나요"
              description="말투는 여기서, 내용은 등록된 답변 기준에서 정해집니다."
            >
              <div className="flex flex-wrap gap-2">
                <BtnLink to="/settings/policies" size="sm" variant="outline">
                  운영 기준 관리
                </BtnLink>
                <BtnLink to="/products" size="sm" variant="outline">
                  상품별 답변 기준
                </BtnLink>
              </div>
            </Panel>
          </div>

          <Panel title="이렇게 보입니다" description="예시 문의로 만든 표시용 화면입니다.">
            <p className="break-keep text-sm text-muted">문의</p>
            <p className="mt-1 break-keep font-medium text-ink">{PREVIEW_QUESTION}</p>
            <p className="mt-4 break-keep text-sm text-muted">답변</p>
            <div className="mt-1 space-y-1 rounded-xl border border-line bg-canvas p-4 leading-relaxed text-ink">
              {preview.map((line, i) => (
                <p key={i} className={line.placeholder ? "break-keep text-muted" : "break-keep"}>
                  {line.text}
                </p>
              ))}
            </div>
            <p className="mt-3 break-keep leading-relaxed text-muted">
              가운데 본문은 실제 문의와 등록된 답변 기준으로 채워집니다. 이 미리보기는 말투와 인사만
              보여주며, 배송 기준 같은 내용을 지어내지 않습니다.
            </p>
          </Panel>
        </div>
      )}
    </>
  );
}

/** 은 after a final consonant, else 는 — 「환불은」, 「회수는」. */
function topicParticle(word: string): string {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  return code >= 0 && code < 11172 && code % 28 !== 0 ? "은" : "는";
}

/** Textarea to list. Empty lines are not phrases. */
function lines(value: string): string[] {
  return value
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/**
 * The preview, rendered here and nowhere else.
 *
 * <b>It calls nothing.</b> No model, no marketplace, no inquiry. The body is a placeholder rather
 * than a sentence, because the one thing a style preview must not do is show a shop owner a delivery
 * answer that reads like their own policy — they would believe it.
 */
export function previewLines(form: {
  tone: AnswerTone;
  length: AnswerLength;
  greeting: string;
  closing: string;
  address: string;
  required: string;
  emoji: EmojiPolicy;
}): Array<{ text: string; placeholder?: boolean }> {
  const out: Array<{ text: string; placeholder?: boolean }> = [];
  const greeting = form.greeting.trim();
  const address = form.address.trim();
  if (greeting) {
    out.push({ text: address ? `${greeting} ${address}, ` .trim() : greeting });
  } else if (address) {
    out.push({ text: `${address},` });
  }
  out.push({
    text: `(여기에 등록된 답변 기준으로 ${lengthWord(form.length)} 답변이 들어갑니다)`,
    placeholder: true,
  });
  for (const phrase of lines(form.required)) {
    out.push({ text: phrase });
  }
  const closing = form.closing.trim();
  if (closing) {
    out.push({ text: form.emoji === "LIMITED" ? `${closing} 🙂` : closing });
  }
  return out;
}

function lengthWord(length: AnswerLength): string {
  return length === "SHORT" ? "짧은" : length === "DETAILED" ? "자세한" : "보통 길이의";
}
