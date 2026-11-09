package com.sellerops.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Optional;

/**
 * Turn a vendor response into a draft, or into nothing.
 *
 * <p><b>Fail closed, field by field.</b> Every check below is a way a model has actually been seen to
 * answer: a code fence around the JSON, a category it invented, an empty body, a "sure, here you go"
 * preamble. The alternative to refusing each of them is a partial candidate — and a candidate with an
 * empty body or an unknown category is worse than none, because it reaches a human as a draft that
 * looks reviewed and is not. There is no repair pass and no second call: a malformed answer is a
 * refusal, and the caller falls back to the deterministic rule drafter.
 *
 * <p><b>Nothing here is logged.</b> The parsed values ARE the seller's draft; a parse failure is
 * reported as an enum by the caller, never as the text that failed to parse.
 */
public final class AgentDraftResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The most a starter draft may be. Longer means the model wrote an essay, or ran away. */
    private static final int MAX_TITLE = 200;
    private static final int MAX_COMMENTS = 2000;

    private AgentDraftResponseParser() {
    }

    /**
     * The assistant text out of either vendor's envelope. Anthropic puts it in
     * the {@code text} blocks of {@code content}; OpenAI at {@code choices[0].message.content}. Both spellings are read
     * because the vendor is configuration ({@code sellerops.agent.draft.vendor}), exactly as it is
     * for triage — the adapter that happened to be written first must not become the decision.
     */
    public static Optional<String> assistantText(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            // Anthropic: the text blocks, in order. A current model thinks before it answers, so the first block is
            // usually a thinking block, not the answer (2026-10-29: reading content[0] would find no text on every call).
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                boolean any = false;
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                        text.append(block.path("text").asText());
                        any = true;
                    }
                }
                if (any) {
                    return Optional.of(text.toString());
                }
                return Optional.empty();
            }
            JsonNode openai = root.path("choices").path(0).path("message").path("content");
            if (openai.isTextual()) {
                return Optional.of(openai.asText());
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parse the assistant's text into a validated draft.
     *
     * <p>A leading/trailing code fence is stripped first — it is the single most common deviation and
     * it changes nothing about the content, so refusing it would be refusing a well-formed answer for
     * a wrapper. Anything else that is not exactly the three-field object is refused.
     */
    public static Optional<ParsedDraft> parse(String assistantText) {
        if (assistantText == null) {
            return Optional.empty();
        }
        String text = stripFence(assistantText.trim());
        if (!text.startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (!node.isObject()) {
                return Optional.empty();
            }
            String category = text(node, "category");
            String title = text(node, "title");
            String comments = text(node, "comments");
            if (category == null || title == null || comments == null) {
                return Optional.empty();
            }
            if (Arrays.stream(AgentDraftPrompt.CATEGORIES).noneMatch(category::equals)) {
                return Optional.empty();
            }
            if (title.length() > MAX_TITLE || comments.length() > MAX_COMMENTS) {
                return Optional.empty();
            }
            return Optional.of(new ParsedDraft(category, title, comments));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * The review lane's answer: <b>one field, and only one</b> (Grounded Review Drafting v1).
     *
     * <p>A public review reply has no subject line and needs no category — the template key already
     * decided which wording the org uses, and asking a model to re-state it would create a second
     * classification of the same review that nothing reconciles. So this parser accepts
     * {@code {"comments": …}} and refuses everything else, including an object that also carries a
     * title: an extra field means the model answered a different contract than the one it was given.
     */
    /** Strict for the figure check: one document, no field written twice (second review, 2026-10-28). */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final java.util.Set<String> FIGURE_VERDICTS = java.util.Set.of("SUPPORTED", "SAID_BACK",
            "NOT_SUPPORTED", "CONFLICT");

    private static final java.util.Set<String> CLAIM_VERDICTS = java.util.Set.of("SUPPORTED", "NOT_SUPPORTED",
            "CONFLICT");

    /**
     * Whether the draft tried to answer the question at all (claim check v7, 2026-11-01 eighth review): ANSWERED or
     * NOT_ANSWERED, required — an answer without it is no answer.
     */
    public static Optional<Boolean> parseAnswered(String assistantText) {
        JsonNode root;
        try {
            root = STRICT.readTree(assistantText);
        } catch (Exception e) {
            return Optional.empty();
        }
        String v = root == null ? null : root.path("answered").asText(null);
        return "ANSWERED".equals(v) ? Optional.of(true) : "NOT_ANSWERED".equals(v) ? Optional.of(false) : Optional.empty();
    }

    private static final java.util.Set<String> COVERAGE = java.util.Set.of("ANSWERED", "ASKED", "NOT_COVERED");

    /**
     * Whether the draft took up each need it was told to (claim check v11, 2026-11-02): need id → ANSWERED · ASKED ·
     * NOT_COVERED. An explicit {@code {}} is no list to read; a missing field, a non-object or a word outside the three is no
     * answer. Ids are not checked here — the caller matches them against the ids it sent, and an answer that leaves one out
     * or names one it never sent is a check that did not finish (InquiryDraftComposer.coverageAnswered).
     */
    public static Optional<java.util.Map<String, String>> parseCovered(String assistantText) {
        JsonNode root;
        try {
            root = STRICT.readTree(assistantText);
        } catch (Exception e) {
            return Optional.empty();
        }
        JsonNode node = root == null ? null : root.get("covered");
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (var it = node.fields(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().isTextual() || !COVERAGE.contains(e.getValue().asText())) {
                return Optional.empty();
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return Optional.of(java.util.Map.copyOf(out));
    }

    /**
     * The claim check's other half: every fact, condition or promise it found, each a short excerpt and one closed
     * verdict. An explicit empty list is a draft that states none; a missing list or a malformed entry is no answer.
     */
    public static Optional<java.util.List<AgentDraftGenerator.ClaimVerdict>> parseClaimVerdicts(String assistantText) {
        JsonNode root;
        try {
            root = STRICT.readTree(assistantText);
        } catch (Exception e) {
            return Optional.empty();
        }
        JsonNode list = root == null ? null : root.path("claims");
        // Required (third outside review, 2026-10-28): 「claims」 left out has not said the draft states no fact — only
        // an explicit [] says that. Whether the model found every fact is the evaluation's to measure.
        if (list == null || !list.isArray()) {
            return Optional.empty();
        }
        java.util.List<AgentDraftGenerator.ClaimVerdict> out = new java.util.ArrayList<>();
        for (JsonNode c : list) {
            if (!c.isObject() || !c.path("text").isTextual() || !c.path("verdict").isTextual()) {
                return Optional.empty();
            }
            String text = c.path("text").asText().strip();
            String verdict = c.path("verdict").asText();
            if (text.isEmpty() || text.length() > 200 || !CLAIM_VERDICTS.contains(verdict)) {
                return Optional.empty();
            }
            out.add(new AgentDraftGenerator.ClaimVerdict(text, verdict));
        }
        return Optional.of(java.util.List.copyOf(out));
    }

    /**
     * The figure check's answer: exactly one closed verdict for each of the {@code asked} figures, numbered 1..asked.
     * Anything else — a figure missing or answered twice, a number outside the list, a verdict outside the three — is
     * no answer, and the caller refuses the draft.
     */
    public static Optional<java.util.List<AgentDraftGenerator.FigureVerdict>> parseFigureVerdicts(String assistantText,
                                                                                                int asked) {
        JsonNode root;
        try {
            root = STRICT.readTree(assistantText);
        } catch (Exception e) {
            return Optional.empty();
        }
        JsonNode list = root == null ? null : root.path("figures");
        if (list == null || !list.isArray() || list.size() != asked) {
            return Optional.empty();
        }
        java.util.Map<Integer, AgentDraftGenerator.FigureVerdict> byN = new java.util.TreeMap<>();
        for (JsonNode f : list) {
            if (!f.isObject() || !f.path("n").isInt() || !f.path("verdict").isTextual()) {
                return Optional.empty();
            }
            int n = f.path("n").asInt();
            String verdict = f.path("verdict").asText();
            if (n < 1 || n > asked || !FIGURE_VERDICTS.contains(verdict) || byN.containsKey(n)) {
                return Optional.empty();
            }
            byN.put(n, new AgentDraftGenerator.FigureVerdict(n, verdict, passageOf(f.path("source")),
                    sourceOf(f.path("source")), shortText(f.path("about"), 80), shortText(f.path("when"), 120)));
        }
        return Optional.of(java.util.List.copyOf(byN.values()));
    }

    private static final java.util.regex.Pattern PASSAGE_SOURCE = java.util.regex.Pattern.compile("근거\\s*(\\d{1,3})");

    /** 「근거 3」 → 3. Anything else — absent, 「주문」, 「문의」, a malformed name — is no passage. */
    private static Integer passageOf(JsonNode source) {
        if (!source.isTextual()) {
            return null;
        }
        java.util.regex.Matcher m = PASSAGE_SOURCE.matcher(source.asText().strip());
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    /** 근거 · 주문 · 문의, or null: an optional field, read only when it is one of the three. */
    private static String sourceOf(JsonNode source) {
        if (!source.isTextual()) {
            return null;
        }
        String s = source.asText().strip();
        if (PASSAGE_SOURCE.matcher(s).matches()) {
            return "근거";
        }
        return "주문".equals(s) || "문의".equals(s) ? s : null;
    }

    private static String shortText(JsonNode node, int max) {
        if (!node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        String s = node.asText().strip();
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static Optional<ParsedDraft> parseReviewDraft(String assistantText) {
        // The review lane has no category and no title, and this record has both fields. They are
        // NULL rather than filled with a plausible value: a category nobody chose and a subject line
        // a public reply does not have are two facts this parser must not invent to fit a shape.
        return parseReview(assistantText).map(body -> new ParsedDraft(null, null, body));
    }

    public static Optional<String> parseReview(String assistantText) {
        if (assistantText == null) {
            return Optional.empty();
        }
        String text = stripFence(assistantText.trim());
        if (!text.startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (!node.isObject()) {
                return Optional.empty();
            }
            String comments = text(node, "comments");
            if (comments == null || comments.length() > MAX_COMMENTS) {
                return Optional.empty();
            }
            return Optional.of(comments);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** Strip a ```json … ``` (or bare ``` … ```) wrapper. Returns the input unchanged when there is none. */
    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence).trim();
    }

    /**
     * A validated draft. From {@link #parse}, every field is non-blank and {@code category} is one of
     * the closed set; from {@link #parseReviewDraft}, {@code category} and {@code title} are null
     * because a public review reply has neither.
     */
    public record ParsedDraft(String category, String title, String comments) {
    }
}
