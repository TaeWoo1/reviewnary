package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.knowledge.RetrievalQuery;
import com.sellerops.knowledge.memory.AnswerMemory;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.memory.AnswerMemoryService;
import com.sellerops.knowledge.memory.AnswerMemoryStrength;
import com.sellerops.knowledge.memory.dto.AnswerMemoryPassage;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.semantic.KnowledgeEmbeddingProperties;
import com.sellerops.knowledge.semantic.KnowledgeEmbeddingRepository;
import com.sellerops.knowledge.semantic.KnowledgeEmbeddingService;
import com.sellerops.knowledge.semantic.KnowledgeEvidenceEligibility;
import com.sellerops.knowledge.semantic.KnowledgeQuestionIntent;
import com.sellerops.knowledge.semantic.KnowledgeSemanticSearch;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.product.library.ProductKnowledgeChunkRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A reply the seller reworded comes back for the next review like it — found by meaning in a whole sentence,
 * never for a review of the opposite feeling, never from another seller</b> (2026-10-22 external review).
 *
 * <p>The long sentence below is the one that failed: on the lexical scorer alone, 「배송이 정말 빠르고 포장이
 * 꼼꼼해서 좋았어요」 does not reach the reply written for it — its content words outnumber the reply's, and the
 * absence gate ({@code KnowledgeRetriever.MIN_ASKABLE_RATIO}) refuses the question. It stays here as written.
 *
 * <p>The embedder is a fake that places each sentence by the concepts it names (fast or slow delivery, packing,
 * praise, apology). That proves the wiring — the whole sentence reaches the semantic lane, the rating band and the
 * organisation hold, the index grows across searches — and nothing about how well a real model reads Korean,
 * which is measured against the real model, not here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReviewReplyPrecedentRetrievalTest {

    static final String LONG_FIVE_STAR = "배송이 정말 빠르고 포장이 꼼꼼해서 좋았어요";
    static final String THANKS = "빠른 배송과 꼼꼼한 포장을 알아봐 주셔서 감사합니다. 다음에도 정성껏 포장해 보내 드리겠습니다.";
    static final String ONE_STAR = "배송이 너무 느려서 실망했어요";
    static final String APOLOGY = "배송이 늦어져 불편을 드려 죄송합니다. 다음에는 더 신경 쓰겠습니다.";

    @Autowired AnswerMemoryRepository memoryRows;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired KnowledgeEmbeddingRepository embeddingRows;

    private PrecedentFixture fixture;

    private final AtomicInteger embedded = new AtomicInteger();
    private AnswerMemoryService lexical;
    private AnswerMemoryService semantic;
    private ReviewReplyPrecedent precedent;
    private UUID org;
    private UUID channel;

    @BeforeEach
    void setUp() {
        fixture = new PrecedentFixture(channels, organizations);
        lexical = new AnswerMemoryService(memoryRows, orgChunks, productChunks);
        KnowledgeEmbeddingService embeddings = new KnowledgeEmbeddingService(
                new KnowledgeEmbeddingProperties(true, "*", "concept-fake", "k", CONCEPTS.length + 1), embeddingRows,
                null, conceptEmbedder());
        semantic = new AnswerMemoryService(memoryRows, orgChunks, productChunks,
                new KnowledgeSemanticSearch(embeddings, KnowledgeQuestionIntent.disabled()),
                KnowledgeEvidenceEligibility.disabled());
        precedent = new ReviewReplyPrecedent(memoryRows, reviews);
        org = organization("후기 답글 상점");
        channel = fixture.channel();
    }

    @Test
    void theWholeSentenceFindsTheReplyTheSellerStoodBehind() {
        remember(org, review(org, 5, "택배가 빨리 왔고 포장도 잘 되어 있었어요"), THANKS);

        assertThat(bodies(semantic.search(org, RetrievalQuery.ofCustomer(null, LONG_FIVE_STAR), null, null, null, 4,
                precedent.forReviewRated(org, 5)).passages())).containsExactly(THANKS);
    }

    @Test
    void aReplyToPraiseIsNeverOfferedForAComplaintNorTheOtherWayRound() {
        remember(org, review(org, 5, "택배가 빨리 왔고 포장도 잘 되어 있었어요"), THANKS);
        remember(org, review(org, 1, "택배가 늦게 와서 별로였어요"), APOLOGY);

        assertThat(bodies(semantic.search(org, RetrievalQuery.ofCustomer(null, ONE_STAR), null, null, null, 4,
                precedent.forReviewRated(org, 1)).passages())).containsExactly(APOLOGY);
        assertThat(bodies(semantic.search(org, RetrievalQuery.ofCustomer(null, LONG_FIVE_STAR), null, null, null, 4,
                precedent.forReviewRated(org, 5)).passages())).containsExactly(THANKS);
        assertThat(bodies(semantic.search(org, RetrievalQuery.ofCustomer(null, ONE_STAR), null, null, null, 4,
                precedent.forReviewRated(org, 5)).passages())).as("no praise reply for a complaint")
                .doesNotContain(APOLOGY);
    }

    @Test
    void anInquiryAnswerAndAnotherSellersReplyAreNotPrecedent() {
        UUID other = organization("다른 상점");
        remember(other, review(other, 5, "택배가 빨리 왔고 포장도 잘 되어 있었어요"), THANKS);
        memoryRows.save(inquiryAnswer(org, THANKS));

        Predicate<AnswerMemory> fiveStar = precedent.forReviewRated(org, 5);
        assertThat(semantic.search(org, RetrievalQuery.ofCustomer(null, LONG_FIVE_STAR), null, null, null, 4,
                fiveStar).passages()).isEmpty();
    }

    @Test
    void theIndexGrowsAcrossSearchesSoALongHistoryKeepsSemanticSearch() {
        for (int i = 0; i < 30; i++) {
            remember(org, review(org, 5, "기타 후기 " + i),
                    "후기 남겨 주셔서 고맙습니다 " + i + ". 상품이 마음에 드셨다니 기쁩니다 " + i + ". 또 들러 주세요 " + i + ".");
        }
        remember(org, review(org, 5, "택배가 빨리 왔고 포장도 잘 되어 있었어요"), THANKS);
        Predicate<AnswerMemory> fiveStar = precedent.forReviewRated(org, 5);

        // 92 sentences, 64 embedded per search: the first search sees part of the corpus and falls back to the
        // lexical scorer, which misses this sentence; the second has every vector and finds it.
        int before = embedded.get();
        semantic.search(org, RetrievalQuery.ofCustomer(null, LONG_FIVE_STAR), null, null, null, 4, fiveStar);
        assertThat(bodies(semantic.search(org, RetrievalQuery.ofCustomer(null, LONG_FIVE_STAR), null, null, null, 4,
                fiveStar).passages())).contains(THANKS);
        assertThat(embedded.get() - before).as("each sentence embedded once").isLessThanOrEqualTo(92 + 2);
    }

    // --- the fake embedder --------------------------------------------------------------------------------------

    /** Concepts and the stems that name them. The last dimension is a constant so no vector is zero. */
    private static final String[][] CONCEPTS = {
            {"빠르", "빨라", "빠른", "빨리"},
            {"느려", "느리", "늦"},
            {"포장"},
            {"좋았", "좋아", "만족", "감사", "고맙", "기쁩"},
            {"실망", "죄송", "불편", "별로"},
            {"배송", "택배"},
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentLlmTransport conceptEmbedder() {
        return (uri, headers, request) -> {
            try {
                JsonNode input = MAPPER.readTree(request).path("input");
                StringBuilder data = new StringBuilder("{\"data\":[");
                for (int i = 0; i < input.size(); i++) {
                    embedded.incrementAndGet();
                    data.append(i > 0 ? "," : "").append("{\"index\":").append(i).append(",\"embedding\":[")
                            .append(vector(input.get(i).asText())).append("]}");
                }
                return new AgentLlmTransport.Response(200, data.append("]}").toString());
            } catch (Exception e) {
                return new AgentLlmTransport.Response(500, "unparseable");
            }
        };
    }

    private static String vector(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String[] stems : CONCEPTS) {
            boolean named = false;
            for (String stem : stems) {
                named |= t.contains(stem);
            }
            out.append(named ? "1.0," : "0.0,");
        }
        return out.append("0.05").toString();
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private UUID organization(String name) {
        return fixture.organization(name);
    }

    private Review review(UUID orgId, int rating, String body) {
        Review r = new Review();
        r.setOrgId(orgId);
        r.setChannelId(channel);
        r.setExternalId(UUID.randomUUID().toString());
        r.setRating(rating);
        r.setBody(body);
        r.setReceivedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return reviews.save(r);
    }

    private void remember(UUID orgId, Review review, String reply) {
        lexical.remember(new AnswerMemoryService.RememberCommand(orgId,
                "review-approved:" + review.getId() + ":1", AnswerMemoryStrength.USER_APPROVED, review.getBody(),
                "리뷰 답글 · 별점 " + review.getRating() + "점", reply, null, "NAVER", null, null, null, null, 1, null,
                null, null, null, review.getId()));
    }

    private static AnswerMemory inquiryAnswer(UUID orgId, String body) {
        AnswerMemory m = new AnswerMemory();
        m.setOrgId(orgId);
        m.setOriginRef("inquiry-approved:" + UUID.randomUUID());
        m.setStrength(AnswerMemoryStrength.USER_APPROVED);
        m.setAnswerTitle("배송 문의");
        m.setAnswerBody(body);
        m.setTopicSignature("배송");
        m.setNormalized(body);
        m.setOriginInquiryId(UUID.randomUUID());
        return m;
    }

    private static List<String> bodies(List<AnswerMemoryPassage> passages) {
        return passages.stream().map(AnswerMemoryPassage::answerBody).toList();
    }
}
