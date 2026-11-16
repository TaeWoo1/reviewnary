package com.sellerops.agent.llm.reviewaspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmCallMetrics;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.operator.AgentLlmWireFormat;
import com.sellerops.agent.llm.operator.AgentOperatorResponseParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The review-aspect calls: one request reads a batch of reviews; another writes opportunities and threats from the
 * evidence those readings produced.
 *
 * <p><b>The model proposes; this class decides what counts.</b> An aspect, polarity or problem outside the closed
 * lists is dropped. An evidence string that is not a piece of that very review — after whitespace is collapsed — is
 * dropped: an opinion the customer did not write is not a customer's opinion. An outlook item that cites no given key,
 * or a key that was not given, is dropped. Nothing is mapped to a nearest match.
 *
 * <p>The payload is the reviews' words and star ratings by position — no review id, product name, customer name or
 * order. The outlook payload is aspect names, counts and the evidence quotes already shown on the screen.
 */
public class ReviewAspectGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentLlmTransport http;
    private final AgentLlmWireFormat.Vendor vendor;
    private final String modelId;
    private final String apiKey;
    private final int maxOutputTokens;
    private final String reasoningEffort;
    private final int maxTextChars;
    private final String readVersion;
    private final String outlookVersion;

    public ReviewAspectGenerator(AgentLlmTransport http, AgentLlmWireFormat.Vendor vendor, String modelId,
                                 String apiKey, int maxOutputTokens, String reasoningEffort, int maxTextChars) {
        this.http = http;
        this.vendor = vendor;
        this.modelId = modelId;
        this.apiKey = apiKey;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
        this.maxTextChars = maxTextChars;
        String tail = "+" + vendor.name().toLowerCase() + ":" + modelId + "+chars" + maxTextChars;
        this.readVersion = ReviewAspectPrompt.READ_VERSION + tail;
        this.outlookVersion = ReviewAspectPrompt.OUTLOOK_VERSION + tail;
    }

    /** Which reading a stored result came from; a different prompt or model is a different reading. */
    public String readVersion() {
        return readVersion;
    }

    public String outlookVersion() {
        return outlookVersion;
    }

    /** One review as it is sent: its stars (may be null) and its words. */
    public record Item(Integer rating, String text) {
    }

    /** One opinion the review states. {@code problem} only on a NEGATIVE one, and only from the closed list. */
    public record Opinion(String aspect, String polarity, String problem, String evidence) {
    }

    /**
     * The batch's readings by position. {@code readings.get(i)} is empty when the model did not return review i at
     * all (not stored, read again later); a present empty list means "read, no opinion in it".
     */
    public record ReadResult(List<Optional<List<Opinion>>> readings, String reason, Map<String, Integer> dropped,
                             AgentLlmCallMetrics metrics) {

        public boolean ok() {
            return "ok".equals(reason);
        }
    }

    public ReadResult read(List<Item> items) {
        AgentLlmTransport.Response response =
                http.post(vendor.endpoint(), AgentLlmWireFormat.headers(vendor, apiKey), readBody(items));
        AgentLlmCallMetrics metrics = AgentLlmCallMetrics.of(response);
        Optional<String> failure = failureOf(response);
        if (failure.isPresent()) {
            return new ReadResult(empty(items.size()), failure.get(), Map.of(), metrics);
        }
        String text = AgentOperatorResponseParser.assistantText(response.body()).orElse(null);
        if (text == null) {
            return new ReadResult(empty(items.size()), "no_message_text", Map.of(), metrics);
        }
        Map<String, Integer> dropped = new LinkedHashMap<>();
        List<Optional<List<Opinion>>> readings = parseRead(text, items, dropped);
        if (readings == null) {
            return new ReadResult(empty(items.size()), "unparseable_answer", Map.of(), metrics);
        }
        return new ReadResult(readings, "ok", Map.copyOf(dropped), metrics);
    }

    /** The whole outgoing payload. Package-private so the payload-floor test asserts the exact string. */
    String readBody(List<Item> items) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String stars = item.rating() == null ? "" : "(★" + item.rating() + ") ";
            lines.add((i + 1) + ". " + stars + oneLine(item.text()));
        }
        return AgentLlmWireFormat.body(vendor, modelId, ReviewAspectPrompt.readSystem(),
                ReviewAspectPrompt.readUser(lines), maxOutputTokens, reasoningEffort);
    }

    static List<Optional<List<Opinion>>> parseRead(String raw, List<Item> items, Map<String, Integer> dropped) {
        JsonNode root;
        try {
            root = MAPPER.readTree(AgentOperatorResponseParser.stripFence(raw));
        } catch (Exception e) {
            return null;
        }
        JsonNode reviews = root.path("reviews");
        if (!reviews.isArray()) {
            return null;
        }
        List<Optional<List<Opinion>>> out = empty(items.size());
        for (JsonNode r : reviews) {
            int i = r.path("i").asInt(0) - 1;
            if (i < 0 || i >= items.size() || out.get(i).isPresent()) {
                count(dropped, "bad_index");
                continue;
            }
            String haystack = squash(items.get(i).text());
            List<Opinion> opinions = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode o : r.path("opinions")) {
                String aspect = o.path("aspect").asText("").strip();
                String polarity = o.path("polarity").asText("").strip();
                String problem = o.path("problem").isTextual() ? o.path("problem").asText().strip() : null;
                String evidence = o.path("evidence").asText("").strip();
                if (!ReviewAspectPrompt.ASPECTS.contains(aspect) && !ReviewAspectPrompt.OVERALL.equals(aspect)) {
                    count(dropped, "off_vocabulary_aspect");
                    continue;
                }
                if (!polarity.equals("POSITIVE") && !polarity.equals("NEGATIVE")) {
                    count(dropped, "off_vocabulary_polarity");
                    continue;
                }
                if (evidence.isEmpty() || !haystack.contains(squash(evidence))) {
                    count(dropped, "evidence_not_in_review");
                    continue;
                }
                if (polarity.equals("POSITIVE") || (problem != null && !ReviewAspectPrompt.PROBLEMS.contains(problem))) {
                    if (problem != null && polarity.equals("NEGATIVE")) {
                        count(dropped, "off_vocabulary_problem");
                    }
                    problem = null;
                }
                if (!seen.add(aspect + "|" + polarity)) {
                    continue;
                }
                opinions.add(new Opinion(aspect, polarity, problem, evidence));
            }
            out.set(i, Optional.of(List.copyOf(opinions)));
        }
        return out;
    }

    // ------------------------------------------------------------------ outlook

    /** One observed S or W cell as the writer sees it. */
    public record EvidenceLine(String key, String title, long count, long denominator, List<String> quotes) {
    }

    /** One written O or T item, already checked against the given keys. */
    public record Written(String title, String basis, String check, List<String> derivedFrom) {
    }

    public record OutlookResult(List<Written> opportunities, List<Written> threats, String reason,
                                AgentLlmCallMetrics metrics) {

        public boolean ok() {
            return "ok".equals(reason);
        }
    }

    public OutlookResult writeOutlook(List<EvidenceLine> evidence) {
        AgentLlmTransport.Response response =
                http.post(vendor.endpoint(), AgentLlmWireFormat.headers(vendor, apiKey), outlookBody(evidence));
        AgentLlmCallMetrics metrics = AgentLlmCallMetrics.of(response);
        Optional<String> failure = failureOf(response);
        if (failure.isPresent()) {
            return new OutlookResult(List.of(), List.of(), failure.get(), metrics);
        }
        String text = AgentOperatorResponseParser.assistantText(response.body()).orElse(null);
        if (text == null) {
            return new OutlookResult(List.of(), List.of(), "no_message_text", metrics);
        }
        Map<String, List<String>> quotesByKey = new LinkedHashMap<>();
        evidence.forEach(e -> quotesByKey.put(e.key(), e.quotes()));
        try {
            JsonNode root = MAPPER.readTree(AgentOperatorResponseParser.stripFence(text));
            return new OutlookResult(written(root.path("opportunities"), quotesByKey),
                    written(root.path("threats"), quotesByKey), "ok", metrics);
        } catch (Exception e) {
            return new OutlookResult(List.of(), List.of(), "unparseable_answer", metrics);
        }
    }

    String outlookBody(List<EvidenceLine> evidence) {
        StringBuilder sb = new StringBuilder();
        for (EvidenceLine e : evidence) {
            sb.append("- key=").append(e.key()).append(" · ").append(e.title()).append(" · ")
                    .append(e.count()).append("/").append(e.denominator()).append("건\n");
            for (String q : e.quotes()) {
                sb.append("  · \"").append(oneLine(q)).append("\"\n");
            }
        }
        return AgentLlmWireFormat.body(vendor, modelId, ReviewAspectPrompt.outlookSystem(),
                ReviewAspectPrompt.outlookUser(sb.toString()), maxOutputTokens, reasoningEffort);
    }

    static List<Written> written(JsonNode list, Set<String> keys) {
        Map<String, List<String>> noQuotes = new LinkedHashMap<>();
        keys.forEach(k -> noQuotes.put(k, List.of()));
        return written(list, noQuotes);
    }

    /** As above, with each given key's quotes: a quotation the writer makes must be one of them, word for word. */
    static List<Written> written(JsonNode list, Map<String, List<String>> quotesByKey) {
        Set<String> keys = quotesByKey.keySet();
        List<Written> out = new ArrayList<>();
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode n : list) {
            String title = clip(n.path("title").asText(""), 40);
            String basis = clip(n.path("basis").asText(""), 160);
            String check = clip(n.path("check").asText(""), 140);
            List<String> from = new ArrayList<>();
            boolean foreign = false;
            for (JsonNode k : n.path("derivedFrom")) {
                String key = k.asText("");
                if (keys.contains(key)) {
                    if (!from.contains(key)) {
                        from.add(key);
                    }
                } else {
                    foreign = true;
                }
            }
            if (title.isEmpty() || basis.isEmpty() || from.isEmpty() || foreign) {
                continue;
            }
            List<String> sources = new ArrayList<>();
            from.forEach(k -> sources.addAll(quotesByKey.getOrDefault(k, List.of())));
            out.add(new Written(unquoteUnlessVerbatim(title, sources), unquoteUnlessVerbatim(basis, sources),
                    check.isEmpty() ? null : unquoteUnlessVerbatim(check, sources), List.copyOf(from)));
            if (out.size() == 4) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile(
            "['\u2018\u2019\"\u201C\u201D]([^'\u2018\u2019\"\u201C\u201D]{1,80})['\u2018\u2019\"\u201C\u201D]"
                    + "|\u300C([^\u300C\u300D]{1,80})\u300D|\u300E([^\u300E\u300F]{1,80})\u300F");

    /**
     * Quotation marks only around words a customer actually wrote. Measured on 나누리 (2026-11-17): every one of twelve
     * quoted phrases in a written outlook was a paraphrase, not a review's words — shown in quotes, a seller reads them
     * as what customers said (B205). A quoted span found word for word (whitespace aside) in one of the {@code sources}
     * keeps its marks; any other loses them and stays as the writer's own sentence.
     */
    public static String unquoteUnlessVerbatim(String text, List<String> sources) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> haystack = sources.stream().map(ReviewAspectGenerator::squash).toList();
        java.util.regex.Matcher m = QUOTED.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String needle = squash(inner);
            boolean verbatim = !needle.isEmpty() && haystack.stream().anyMatch(h -> h.contains(needle));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(verbatim ? m.group() : inner));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Optional<String> failureOf(AgentLlmTransport.Response response) {
        if (response.status() == 0) {
            return Optional.of("transport");
        }
        if (!response.ok()) {
            return Optional.of("http:" + response.status());
        }
        try {
            JsonNode envelope = MAPPER.readTree(response.body());
            String stop = vendor == AgentLlmWireFormat.Vendor.ANTHROPIC
                    ? envelope.path("stop_reason").asText(null)
                    : envelope.path("choices").path(0).path("finish_reason").asText(null);
            if ("length".equals(stop) || "max_tokens".equals(stop)) {
                return Optional.of("budget_exhausted");
            }
        } catch (Exception e) {
            return Optional.of("unreadable_envelope");
        }
        return Optional.empty();
    }

    private String oneLine(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return t.length() <= maxTextChars ? t : t.substring(0, maxTextChars).strip();
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static List<Optional<List<Opinion>>> empty(int n) {
        List<Optional<List<Opinion>>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(Optional.empty());
        }
        return out;
    }

    private static void count(Map<String, Integer> dropped, String reason) {
        dropped.merge(reason, 1, Integer::sum);
    }
}
