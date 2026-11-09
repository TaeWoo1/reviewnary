package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.knowledge.KnowledgeRetriever;
import com.sellerops.knowledge.KnowledgeText;
import com.sellerops.knowledge.KnowledgeTopic;
import com.sellerops.knowledge.RetrievalQuery;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.memory.dto.AnswerMemorySearchResponse;
import com.sellerops.knowledge.org.OrgKnowledgeChunk;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeSearchResponse;
import com.sellerops.product.library.KnowledgeVariantScope;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.dto.KnowledgeSearchResponse;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>Where a review's draft loses the store's material — without a model</b> (2026-10-31, first real-model round: four
 * of four review drafts cited nothing). For each listed review, the same retrieval the reply agent's inputs come from
 * ({@link ReviewDraftComposer#agentInputs}), stage by stage: the complaint clauses and query forms, the topics asked,
 * the product library, the org's rules (raw lexical reach per form, then the topic gate), the past answers with and
 * without the rating-band filter, and what reached the model.
 *
 * <p>Writes one JSON row per review to {@code TRACE_OUT}: counts, closed outcomes, topic names, scores, the seller's own
 * rule titles. <b>No customer text and no passage text.</b> Calls no model and writes nothing.
 *
 * <p>Off unless {@code RUN_REVIEW_RETRIEVAL_TRACE=true}; boots the application against the database the environment
 * names (the evaluation copy) with every model capability off.
 *
 * <p><b>Whether the material exists, apart from whether it was found</b> (2026-10-31 external review: 「이 설정에서 못
 * 찾았다」 is not 「쓸 자료가 없었다」). With {@code TRACE_MATERIAL_OUT} set it also writes, per review, the redacted review
 * as the model reads it and EVERY active item the store has for it — the org's policy passages and this product's
 * knowledge passages, each with an id — and which of them reached the model. A separate judge then says which items an
 * answer needs ({@code tools/eval/case-eval/material_need.py}). That file holds customer words and the seller's own text:
 * it may only be written under {@code ~/workspace/sellerops/holds/}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_REVIEW_RETRIEVAL_TRACE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReviewRetrievalTraceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private ReviewDraftComposer composer;
    @Autowired
    private SellerOperationsKnowledgeService orgKnowledge;
    @Autowired
    private OrgKnowledgeSourceRepository orgSources;
    @Autowired
    private OrgKnowledgeChunkRepository orgChunks;
    @Autowired
    private ProductKnowledgeLibraryService productKnowledge;
    @Autowired
    private AnswerMemoryService answerMemory;
    @Autowired
    private ReviewReplyPrecedent precedent;
    @Autowired
    private com.sellerops.product.library.ProductKnowledgeSourceRepository productSources;
    @Autowired
    private com.sellerops.product.library.ProductKnowledgeChunkRepository productChunks;

    @Test
    void trace() throws Exception {
        UUID org = UUID.fromString(System.getenv("TRACE_ORG"));
        List<String> rows = new ArrayList<>();
        for (String id : System.getenv("TRACE_REVIEWS").split(",")) {
            Review review = reviews.findByIdAndOrgId(UUID.fromString(id.trim()), org).orElseThrow();
            rows.add(JSON.writeValueAsString(trace(org, review)));
        }
        Files.write(Path.of(System.getenv("TRACE_OUT")), rows, StandardCharsets.UTF_8);
        assertThat(rows).isNotEmpty();
        String materialOut = System.getenv("TRACE_MATERIAL_OUT");
        if (materialOut != null && !materialOut.isBlank()) {
            Path out = Path.of(materialOut).toAbsolutePath().normalize();
            assertThat(out.toString()).as("customer words leave only for the holds folder")
                    .startsWith(Path.of(System.getProperty("user.home"), "workspace", "sellerops", "holds").toString());
            List<String> material = new ArrayList<>();
            for (String id : System.getenv("TRACE_REVIEWS").split(",")) {
                Review review = reviews.findByIdAndOrgId(UUID.fromString(id.trim()), org).orElseThrow();
                material.add(JSON.writeValueAsString(material(org, review)));
            }
            Files.write(out, material, StandardCharsets.UTF_8);
        }
    }

    /** Every active item the store has for this review, with ids, and the ids that reached the model. */
    private Map<String, Object> material(UUID org, Review review) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("review", review.getId().toString().substring(0, 8));
        row.put("rating", review.getRating());
        String body = VocPreviewSanitizer.redactFullBody(review.getBody()).text();
        row.put("body", body == null ? "" : body);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<UUID, String> idOfChunk = new LinkedHashMap<>();
        Map<UUID, OrgKnowledgeSource> orgById = new LinkedHashMap<>();
        orgSources.findAllByOrgIdOrderByCreatedAtAsc(org).stream().filter(OrgKnowledgeSource::isActive)
                .forEach(s -> orgById.put(s.getId(), s));
        for (OrgKnowledgeChunk chunk : orgChunks.findAllByOrgId(org)) {
            OrgKnowledgeSource s = orgById.get(chunk.getSourceId());
            if (s != null) {
                String itemId = "O" + (items.size() + 1);
                idOfChunk.put(chunk.getId(), itemId);
                items.add(Map.of("id", itemId, "scope", "ORG_POLICY", "title", s.getTitle(), "text", chunk.getContent()));
            }
        }
        if (review.getProductId() != null) {
            Map<UUID, com.sellerops.product.library.ProductKnowledgeSource> productById = new LinkedHashMap<>();
            productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(org, review.getProductId()).stream()
                    .filter(com.sellerops.product.library.ProductKnowledgeSource::isActive)
                    .forEach(s -> productById.put(s.getId(), s));
            for (com.sellerops.product.library.ProductKnowledgeChunk chunk
                    : productChunks.findAllByOrgIdAndProductId(org, review.getProductId())) {
                com.sellerops.product.library.ProductKnowledgeSource s = productById.get(chunk.getSourceId());
                if (s != null) {
                    String itemId = "P" + (items.size() + 1);
                    idOfChunk.put(chunk.getId(), itemId);
                    items.add(Map.of("id", itemId, "scope", "PRODUCT", "title", String.valueOf(s.getTitle()),
                            "text", chunk.getContent()));
                }
            }
        }
        row.put("items", items);
        ReviewDraftComposer.AgentInputs in = composer.agentInputs(org, review);
        row.put("reachedModel", in.cited().stream().map(p -> idOfChunk.getOrDefault(p.chunkId(),
                p.scope().name())).toList());
        row.put("knowledgeState", String.valueOf(in.knowledgeState()));
        // The small bundle (2026-11-01): which items the writer was offered, and which the latest saved version cites —
        // the writer's own choice, as the backend verified it. Ids only.
        row.put("bundleLeftOut", Map.of("policies", in.policiesLeftOut(), "product", in.productLeftOut()));
        row.put("inBundle", in.bundle().stream().map(i -> idOfChunk.getOrDefault(i.passage().chunkId(),
                i.passage().scope().name())).toList());
        var latest = replyDrafts.findTopByReviewIdOrderByVersionDesc(review.getId());
        row.put("savedVersion", latest.map(com.sellerops.attention.reply.ReviewReplyDraft::getVersion).orElse(null));
        row.put("savedAuthor", latest.map(com.sellerops.attention.reply.ReviewReplyDraft::getAuthorKind).orElse(null));
        row.put("cited", latest.map(v -> draftEvidence.findByOrgIdAndReviewIdAndDraftVersionOrderByOrdinalAsc(org,
                review.getId(), v.getVersion()).stream().map(e -> idOfChunk.getOrDefault(e.getChunkId(), e.getKind()))
                .toList()).orElse(List.of()));
        return row;
    }

    @Autowired
    com.sellerops.attention.reply.ReviewReplyDraftRepository replyDrafts;
    @Autowired
    com.sellerops.review.draft.ReviewDraftEvidenceRepository draftEvidence;

    private Map<String, Object> trace(UUID org, Review review) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("review", review.getId().toString().substring(0, 8));
        row.put("rating", review.getRating());
        String body = VocPreviewSanitizer.redactFullBody(review.getBody()).text();
        body = body == null ? "" : body;
        row.put("bodyChars", body.length());
        List<String> clauses = ReviewComplaintClauses.of(body);
        row.put("complaintClauses", clauses.size());
        RetrievalQuery question = RetrievalQuery.ofReview(clauses, body);
        row.put("queryForms", question.candidates().stream().map(c -> c.origin().name()).toList());
        row.put("topicsAsked", KnowledgeTopic.of(question.text()).stream().map(Enum::name).sorted().toList());

        if (review.getProductId() != null) {
            KnowledgeSearchResponse product = productKnowledge.search(org, review.getProductId(), question, 4,
                    KnowledgeVariantScope.unresolved());
            row.put("product", Map.of("documents", product.documentsSearched(), "passages", product.passages().size(),
                    "outcome", String.valueOf(product.outcome()), "rejected", product.rejectedNotApplicable()));
        }

        // The rules: what each query form reaches lexically BEFORE the topic gate, then the service's own answer.
        List<OrgKnowledgeSource> sources = orgSources.findAllByOrgIdOrderByCreatedAtAsc(org).stream()
                .filter(OrgKnowledgeSource::isActive).toList();
        Map<UUID, OrgKnowledgeSource> byId = new LinkedHashMap<>();
        sources.forEach(s -> byId.put(s.getId(), s));
        List<KnowledgeRetriever.Candidate<OrgKnowledgeChunk>> corpus = new ArrayList<>();
        for (OrgKnowledgeChunk chunk : orgChunks.findAllByOrgId(org)) {
            OrgKnowledgeSource s = byId.get(chunk.getSourceId());
            if (s != null) {
                corpus.add(new KnowledgeRetriever.Candidate<>(chunk,
                        KnowledgeText.normalize(s.getTitle()) + chunk.getNormalized(), s.getTitle() + "\n" + chunk.getContent()));
            }
        }
        List<Map<String, Object>> forms = new ArrayList<>();
        for (RetrievalQuery.Candidate form : question.candidates()) {
            KnowledgeText.Weighing w = KnowledgeText.weigh(form.text(),
                    corpus.stream().map(KnowledgeRetriever.Candidate::searchable).toList(), "");
            double best = 0;
            for (KnowledgeRetriever.Candidate<OrgKnowledgeChunk> c : corpus) {
                best = Math.max(best, w.assess(c.searchable()).coverage());
            }
            forms.add(Map.of("origin", form.origin().name(), "askableRatio", round(w.askableRatio()),
                    "askable", w.askable(KnowledgeRetriever.MIN_ASKABLE_RATIO), "bestCoverage", round(best),
                    "lexicalHits", KnowledgeRetriever.rank(form.text(), corpus, null).size()));
        }
        OrgKnowledgeSearchResponse rules = orgKnowledge.search(org, question, 4);
        row.put("rules", Map.of("documents", sources.stream().map(OrgKnowledgeSource::getTitle).toList(),
                "declaredTopics", rules.topicsDeclared().stream().map(Enum::name).sorted().toList(),
                "perForm", forms, "outcome", String.valueOf(rules.outcome()), "passages", rules.passages().size(),
                "rejectedByTopic", rules.rejectedNotApplicable()));

        AnswerMemorySearchResponse all = answerMemory.search(org, question, review.getProductId(), null, null, 4,
                m -> true);
        AnswerMemorySearchResponse band = answerMemory.search(org, question, review.getProductId(), null, null, 4,
                precedent.forReviewRated(org, review.getRating()));
        row.put("pastAnswers", Map.of("searched", all.memoriesSearched(), "anyBand", all.passages().size(),
                "sameRatingBand", band.passages().size(), "outcome", String.valueOf(band.outcome())));

        ReviewDraftComposer.AgentInputs in = composer.agentInputs(org, review);
        row.put("toModel", Map.of("passages", in.passages().size(), "knowledgeState", String.valueOf(in.knowledgeState()),
                "cited", in.cited().stream().map(p -> p.scope().name()).toList()));
        return row;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
