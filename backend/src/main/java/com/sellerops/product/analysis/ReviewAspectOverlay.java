package com.sellerops.product.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.reviewaspect.ReviewAspectGenerator;
import com.sellerops.agent.llm.reviewaspect.ReviewAspectPrompt;
import com.sellerops.agent.llm.reviewaspect.ReviewAspectReader;
import com.sellerops.product.analysis.ProductAnalysisView.AspectRow;
import com.sellerops.product.analysis.ProductAnalysisView.Cell;
import com.sellerops.product.analysis.ProductAnalysisView.InquiryTopicRow;
import com.sellerops.product.analysis.ProductAnalysisView.Quote;
import com.sellerops.product.analysis.ProductAnalysisView.Swot;
import com.sellerops.review.Review;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the AI reading adds to the analysis, read-only: each review's stored aspect opinions, and a product's written
 * opportunities and threats when they were written from exactly the evidence on the screen now.
 *
 * <p><b>Nothing here calls a model.</b> It reads what {@link ProductAspectReadingService} stored when the seller pressed
 * 「AI로 읽기」. A review with no reading under the current reader version is read by the word lists as before, and the
 * screen says how many were read which way. A written outlook whose evidence has since changed is not shown — the
 * template cells are, until the seller has it written again.
 */
@Component
public class ReviewAspectOverlay {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int IN_CHUNK = 500;

    /** The label of a complaint the model read with no listed problem. */
    public static final String UNLISTED_PROBLEM = "기타";

    private final ReviewAspectReadingRepository readings;
    private final ProductOutlookWritingRepository outlooks;
    private final ReviewAspectReader reader;

    public ReviewAspectOverlay(ReviewAspectReadingRepository readings, ProductOutlookWritingRepository outlooks,
                               ReviewAspectReader reader) {
        this.readings = readings;
        this.outlooks = outlooks;
        this.reader = reader;
    }

    public String readerVersion() {
        return reader.readVersion();
    }

    public String outlookVersion() {
        return reader.outlookVersion();
    }

    /** The fingerprint a review's words are stored under. */
    public static String sha(String body) {
        return hex(body == null ? "" : body);
    }

    /** Each review's opinions as the analysis counts them, for the reviews that have a reading. */
    public Map<UUID, List<ReviewOpinionReader.Opinion>> readingsFor(UUID orgId, List<Review> reviews) {
        Map<String, List<UUID>> bySha = new HashMap<>();
        for (Review r : reviews) {
            if (r.getBody() != null && !r.getBody().isBlank()) {
                bySha.computeIfAbsent(sha(r.getBody()), k -> new ArrayList<>()).add(r.getId());
            }
        }
        Map<UUID, List<ReviewOpinionReader.Opinion>> out = new HashMap<>();
        if (bySha.isEmpty()) {
            return out;
        }
        String version = readerVersion();
        List<String> shas = new ArrayList<>(bySha.keySet());
        for (int i = 0; i < shas.size(); i += IN_CHUNK) {
            for (ReviewAspectReading reading : readings.findAllByOrgIdAndReaderVersionAndBodySha256In(orgId, version,
                    shas.subList(i, Math.min(shas.size(), i + IN_CHUNK)))) {
                List<ReviewOpinionReader.Opinion> opinions = opinions(reading.getOpinions());
                for (UUID id : bySha.getOrDefault(reading.getBodySha256(), List.of())) {
                    out.put(id, opinions);
                }
            }
        }
        return out;
    }

    /** Stored opinions as the rule reader's {@code Opinion}: 「전반」 has no aspect; an unlisted problem is 「기타」. */
    static List<ReviewOpinionReader.Opinion> opinions(String json) {
        List<ReviewOpinionReader.Opinion> out = new ArrayList<>();
        try {
            int i = 0;
            for (JsonNode n : MAPPER.readTree(json)) {
                String aspect = n.path("aspect").asText("");
                boolean praise = "POSITIVE".equals(n.path("polarity").asText(""));
                String problem = n.path("problem").isTextual() ? n.path("problem").asText() : null;
                out.add(new ReviewOpinionReader.Opinion(i++, ReviewAspectPrompt.OVERALL.equals(aspect) ? null : aspect,
                        praise ? null : (problem == null ? UNLISTED_PROBLEM : problem), praise,
                        n.path("evidence").asText("")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    static String toJson(List<ReviewAspectGenerator.Opinion> opinions) {
        var array = MAPPER.createArrayNode();
        for (ReviewAspectGenerator.Opinion o : opinions) {
            var n = array.addObject();
            n.put("aspect", o.aspect());
            n.put("polarity", o.polarity());
            if (o.problem() == null) {
                n.putNull("problem");
            } else {
                n.put("problem", o.problem());
            }
            n.put("evidence", o.evidence());
        }
        return array.toString();
    }

    // ------------------------------------------------------------------ outlook

    /** The observed cells with their quotes — what the outlook writer is given, and what its fingerprint covers. */
    static List<ReviewAspectGenerator.EvidenceLine> evidence(Swot swot, List<AspectRow> aspects,
                                                            List<InquiryTopicRow> topics) {
        Map<String, AspectRow> byAspect = new HashMap<>();
        aspects.forEach(a -> byAspect.put(a.aspect(), a));
        Map<String, InquiryTopicRow> byTopic = new HashMap<>();
        topics.forEach(t -> byTopic.put(t.topic(), t));
        List<ReviewAspectGenerator.EvidenceLine> out = new ArrayList<>();
        for (Cell c : swot.strengths()) {
            AspectRow a = byAspect.get(c.key().substring(2));
            out.add(new ReviewAspectGenerator.EvidenceLine(c.key(), c.title(), c.count(), c.denominator(),
                    a == null ? List.of() : a.praiseQuotes().stream().map(Quote::text).toList()));
        }
        for (Cell c : swot.weaknesses()) {
            List<String> quotes;
            if (c.key().startsWith("W:inquiry:")) {
                InquiryTopicRow t = byTopic.get(c.key().substring("W:inquiry:".length()));
                quotes = t == null ? List.of() : t.examples().stream().map(ProductAnalysisView.InquiryRef::text).toList();
            } else {
                AspectRow a = byAspect.get(c.key().substring(2));
                quotes = a == null ? List.of() : a.complaintQuotes().stream().map(Quote::text).toList();
            }
            out.add(new ReviewAspectGenerator.EvidenceLine(c.key(), c.title(), c.count(), c.denominator(), quotes));
        }
        return out;
    }

    /**
     * Which evidence a writing stands on: the observed cells and their counts — not the example quotes. Which three
     * sentences are shown depends on the order of reviews received at the same moment, which the database does not fix
     * (measured on 나누리, 2026-11-17: 252 reviews over 79 distinct times), so a fingerprint over quotes changed on every
     * read and a writing was never found again (B204).
     */
    String fingerprint(List<ReviewAspectGenerator.EvidenceLine> lines) {
        StringBuilder sb = new StringBuilder(outlookVersion()).append('\n');
        for (ReviewAspectGenerator.EvidenceLine l : lines) {
            sb.append(l.key()).append('|').append(l.count()).append('/').append(l.denominator()).append('\n');
        }
        return hex(sb.toString());
    }

    /**
     * The swot with the written opportunities and threats in place of the template ones, when a writing exists for
     * exactly this evidence; otherwise unchanged.
     */
    public Swot withWrittenOutlook(UUID orgId, UUID productId, Swot swot, List<AspectRow> aspects,
                                   List<InquiryTopicRow> topics) {
        List<ReviewAspectGenerator.EvidenceLine> lines = evidence(swot, aspects, topics);
        if (lines.isEmpty()) {
            return swot;
        }
        Optional<ProductOutlookWriting> written = outlooks.findFirstByOrgIdAndProductIdAndInputFingerprintAndWriterVersion(
                orgId, productId, fingerprint(lines), outlookVersion());
        if (written.isEmpty()) {
            return swot;
        }
        Map<String, Cell> observed = new HashMap<>();
        swot.strengths().forEach(c -> observed.put(c.key(), c));
        swot.weaknesses().forEach(c -> observed.put(c.key(), c));
        // The quotes on screen now: a writing stored before B205 is held to the same rule when it is shown.
        Map<String, List<String>> quotes = new HashMap<>();
        lines.forEach(l -> quotes.put(l.key(), l.quotes()));
        try {
            JsonNode root = MAPPER.readTree(written.get().getCells());
            return new Swot(swot.strengths(), swot.weaknesses(), cells(root.path("opportunities"), "O", observed, quotes),
                    cells(root.path("threats"), "T", observed, quotes), swot.insufficient());
        } catch (Exception e) {
            return swot;
        }
    }

    private static List<Cell> cells(JsonNode list, String side, Map<String, Cell> observed,
                                    Map<String, List<String>> quotes) {
        List<Cell> out = new ArrayList<>();
        int n = 0;
        for (JsonNode item : list) {
            List<String> from = new ArrayList<>();
            item.path("derivedFrom").forEach(k -> from.add(k.asText()));
            // The basis a written cell stands on: the largest observation it names.
            Cell base = from.stream().map(observed::get).filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.comparingLong(Cell::count)).orElse(null);
            if (base == null) {
                continue;
            }
            List<String> sources = new ArrayList<>();
            from.forEach(k -> sources.addAll(quotes.getOrDefault(k, List.of())));
            out.add(new Cell(side + ":ai:" + (++n), "HYPOTHESIS", "AI",
                    ReviewAspectGenerator.unquoteUnlessVerbatim(item.path("title").asText(), sources),
                    ReviewAspectGenerator.unquoteUnlessVerbatim(item.path("basis").asText(), sources),
                    base.count(), base.denominator(), List.copyOf(from),
                    item.path("check").isTextual()
                            ? ReviewAspectGenerator.unquoteUnlessVerbatim(item.path("check").asText(), sources) : null));
        }
        return List.copyOf(out);
    }

    static String outlookJson(ReviewAspectGenerator.OutlookResult result) {
        var root = MAPPER.createObjectNode();
        put(root.putArray("opportunities"), result.opportunities());
        put(root.putArray("threats"), result.threats());
        return root.toString();
    }

    private static void put(com.fasterxml.jackson.databind.node.ArrayNode array, List<ReviewAspectGenerator.Written> items) {
        for (ReviewAspectGenerator.Written w : items) {
            var n = array.addObject();
            n.put("title", w.title());
            n.put("basis", w.basis());
            if (w.check() == null) {
                n.putNull("check");
            } else {
                n.put("check", w.check());
            }
            var from = n.putArray("derivedFrom");
            w.derivedFrom().forEach(from::add);
        }
    }

    private static String hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
