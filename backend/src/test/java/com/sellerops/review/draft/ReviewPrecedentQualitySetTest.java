package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.JdkAgentLlmTransport;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.knowledge.RetrievalQuery;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>Review Precedent Quality Set v1</b> — does the next review find the reply the seller stood behind for one like
 * it, written the way customers actually write (2026-10-22 external review)?
 *
 * <p>Eight past replies, each approved for a review with a rating, and twelve new reviews in whole sentences: the
 * same topic in other words, the same words with the opposite feeling, and reviews nothing in the record answers.
 * Four counts, apart because they fail apart: the right reply found first ({@code hit}), nothing found where a reply
 * was owed ({@code miss}), a reply found that is not the right one or where none was owed ({@code wrong}), and a
 * reply from another rating band ({@code bandLeak}, which the filter makes structurally zero).
 *
 * <p><b>The lexical arm runs in CI and reports; it is not held to a recall.</b> It is the arm whose limit started
 * this set. The semantic arm calls the real embedding model and runs only with {@code RUN_REVIEW_PRECEDENT_LIVE=true}
 * and {@code SELLEROPS_KNOWLEDGE_EMBEDDING_API_KEY} — the sentences are synthetic, and on a seller-PC build they pass
 * {@code LlmSendGate} like any other model call. Results land in {@code build/review-precedent-quality.json}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReviewPrecedentQualitySetTest {

    record Past(String key, int rating, String review, String reply) {}

    record Case(int rating, String review, String expected) {}

    static final List<Past> PAST = List.of(
            new Past("fast", 5, "배송이 빨라서 좋았어요", "빠르게 받아 보셨다니 다행입니다. 앞으로도 신속하게 보내 드리겠습니다."),
            new Past("packing", 5, "포장이 꼼꼼해서 선물하기 좋았어요",
                    "꼼꼼한 포장을 알아봐 주셔서 감사합니다. 선물로 기쁘게 전해지길 바랍니다."),
            new Past("fits", 4, "사이즈가 딱 맞아요", "사이즈가 잘 맞으셨다니 기쁩니다. 편하게 오래 입어 주세요."),
            new Past("colour", 5, "색감이 사진이랑 똑같아요 예뻐요", "사진과 같은 색감으로 받아 보셨다니 다행입니다. 예쁘게 사용해 주세요."),
            new Past("slow", 1, "배송이 너무 늦게 왔어요", "배송이 늦어 오래 기다리시게 해 죄송합니다. 출고 과정을 다시 살피겠습니다."),
            new Past("torn", 2, "포장이 찢어져서 왔어요", "포장이 손상된 채 도착해 속상하셨을 것 같습니다. 죄송합니다."),
            new Past("small", 1, "사이즈가 너무 작아요", "사이즈가 맞지 않아 불편을 드려 죄송합니다. 다음 주문 때 참고하실 수 있게 치수 안내를 살피겠습니다."),
            new Past("so-so", 3, "그냥 무난해요", "솔직한 후기 감사합니다. 더 만족하실 수 있도록 노력하겠습니다."));

    static final List<Case> CASES = List.of(
            new Case(5, "주문하고 이틀 만에 도착해서 정말 놀랐어요 배송이 엄청 빠르네요", "fast"),
            new Case(5, "친구 생일 선물로 샀는데 포장이 너무 정성스러워서 기분이 좋았습니다", "packing"),
            new Case(4, "평소 입는 사이즈로 시켰는데 딱 맞고 편해요", "fits"),
            new Case(5, "화면에서 본 색이랑 실물이 거의 같아서 만족스러워요", "colour"),
            new Case(1, "일주일이 넘게 걸려서 받았어요 배송이 너무 느립니다", "slow"),
            new Case(2, "상자가 다 찌그러지고 테이프도 뜯어진 채로 왔어요", "torn"),
            new Case(1, "평소 사이즈로 샀는데 너무 작아서 입을 수가 없네요", "small"),
            new Case(5, "배송이 늦을까 걱정했는데 생각보다 금방 왔어요", "fast"),
            new Case(1, "배송은 빨랐는데 제품이 고장나서 왔어요", null),
            new Case(5, "향이 은은하고 좋아요", null),
            new Case(3, "가격 대비 괜찮은 편이에요", "so-so"),
            new Case(1, "색이 사진이랑 너무 달라요", null));

    record Counts(int hit, int miss, int wrong, int bandLeak, List<String> notes) {}

    @Autowired AnswerMemoryRepository memoryRows;
    @Autowired ReviewRepository reviews;
    @Autowired ChannelRepository channels;
    @Autowired OrganizationRepository organizations;
    @Autowired OrgKnowledgeChunkRepository orgChunks;
    @Autowired ProductKnowledgeChunkRepository productChunks;
    @Autowired KnowledgeEmbeddingRepository embeddingRows;

    private PrecedentFixture fixture;

    private UUID org;
    private ReviewReplyPrecedent precedent;
    private final Map<UUID, String> keyOfMemory = new HashMap<>();
    private final Map<String, Integer> ratingOfKey = new HashMap<>();

    @BeforeEach
    void seed() {
        fixture = new PrecedentFixture(channels, organizations);
        org = fixture.organization("후기 품질 측정 상점");
        UUID channel = fixture.channel();
        AnswerMemoryService writer = new AnswerMemoryService(memoryRows, orgChunks, productChunks);
        for (Past p : PAST) {
            Review r = new Review();
            r.setOrgId(org);
            r.setChannelId(channel);
            r.setExternalId(UUID.randomUUID().toString());
            r.setRating(p.rating());
            r.setBody(p.review());
            r.setReceivedAt(Instant.parse("2026-10-01T00:00:00Z"));
            UUID reviewId = reviews.save(r).getId();
            writer.remember(new AnswerMemoryService.RememberCommand(org, "review-approved:" + reviewId + ":1",
                    AnswerMemoryStrength.USER_APPROVED, p.review(), "리뷰 답글 · 별점 " + p.rating() + "점", p.reply(),
                    null, "NAVER", null, null, null, null, 1, null, null, null, null, reviewId))
                    .ifPresent(m -> keyOfMemory.put(m.getId(), p.key()));
            ratingOfKey.put(p.key(), p.rating());
        }
        precedent = new ReviewReplyPrecedent(memoryRows, reviews);
    }

    @Test
    void lexicalArm() throws Exception {
        Counts counts = run(new AnswerMemoryService(memoryRows, orgChunks, productChunks));
        write("lexical", counts);
        assertThat(counts.bandLeak()).isZero();
    }

    @Test
    void semanticArm() throws Exception {
        if (!"true".equals(System.getenv("RUN_REVIEW_PRECEDENT_LIVE"))) {
            return;
        }
        String key = System.getenv("SELLEROPS_KNOWLEDGE_EMBEDDING_API_KEY");
        assertThat(key).as("SELLEROPS_KNOWLEDGE_EMBEDDING_API_KEY").isNotBlank();
        KnowledgeEmbeddingService embeddings = new KnowledgeEmbeddingService(
                new KnowledgeEmbeddingProperties(true, "*", "text-embedding-3-large", key, 1024), embeddingRows,
                null, new JdkAgentLlmTransport());
        Counts counts = run(new AnswerMemoryService(memoryRows, orgChunks, productChunks,
                new KnowledgeSemanticSearch(embeddings, KnowledgeQuestionIntent.disabled()),
                KnowledgeEvidenceEligibility.disabled()));
        write("semantic", counts);
        assertThat(counts.bandLeak()).isZero();
    }

    private Counts run(AnswerMemoryService search) {
        int hit = 0;
        int miss = 0;
        int wrong = 0;
        int leak = 0;
        List<String> notes = new ArrayList<>();
        for (Case c : CASES) {
            List<AnswerMemoryPassage> found = search.search(org, RetrievalQuery.ofCustomer(null, c.review()), null,
                    null, null, 4, precedent.forReviewRated(org, c.rating())).passages();
            List<String> keys = found.stream().map(p -> keyOfMemory.getOrDefault(p.memoryId(), "?")).toList();
            for (String k : keys) {
                if (ReviewReplyPrecedent.band(ratingOfKey.getOrDefault(k, -9)) != ReviewReplyPrecedent.band(c.rating())) {
                    leak++;
                }
            }
            String first = keys.isEmpty() ? null : keys.get(0);
            if (c.expected() == null) {
                if (first != null) {
                    wrong++;
                    notes.add("wrong: " + c.review() + " → " + first);
                }
            } else if (first == null) {
                miss++;
                notes.add("miss: " + c.review() + " (expected " + c.expected() + ")");
            } else if (first.equals(c.expected())) {
                hit++;
            } else {
                wrong++;
                notes.add("wrong: " + c.review() + " → " + first + " (expected " + c.expected() + ")");
            }
        }
        return new Counts(hit, miss, wrong, leak, notes);
    }

    private static void write(String arm, Counts counts) throws Exception {
        long owed = CASES.stream().filter(c -> c.expected() != null).count();
        String line = String.format("{\"arm\":\"%s\",\"cases\":%d,\"owed\":%d,\"hit\":%d,\"miss\":%d,\"wrong\":%d,"
                        + "\"bandLeak\":%d,\"notes\":[%s]}", arm, CASES.size(), owed, counts.hit(), counts.miss(),
                counts.wrong(), counts.bandLeak(),
                String.join(",", counts.notes().stream().map(n -> "\"" + n.replace("\"", "'") + "\"").toList()));
        System.out.println("REVIEW_PRECEDENT_QUALITY " + line);
        Path out = Path.of("build", "review-precedent-quality-" + arm + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, line + "\n");
    }
}
