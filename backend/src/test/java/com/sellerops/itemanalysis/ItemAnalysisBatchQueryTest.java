package com.sellerops.itemanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.itemanalysis.dto.RunResult;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * A batch's analysis asks the database a fixed number of questions, however many rows it carries.
 *
 * <p>It used to ask "already analysed?" once per row. Each of those queries flushed the session first, and a flush
 * dirty-checks every row the session holds — the batch's own reviews and the analyses written so far — so a
 * 5,000-review relay page took tens of seconds here, growing with the square of its size (measured 2026-10-26 with
 * {@code tools/dev/seller-pc-qa-perf.mjs}, the PC's profile showing 96% of the pull's CPU on that line).
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemAnalysisBatchQueryTest {

    @Autowired InquiryRepository inquiries;
    @Autowired ReviewRepository reviews;
    @Autowired ItemAnalysisRepository analyses;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;

    private ItemAnalysisService service;
    private final UUID org = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ItemAnalysisService(inquiries, reviews, analyses, new RuleBasedInboxItemAnalyzer());
    }

    @Test
    void aBatchIsReadWithTheSameFewQueriesAtAnySize() {
        assertThat(statementsToAnalyse(5)).isEqualTo(statementsToAnalyse(60));
    }

    @Test
    void aBatchStillSkipsWhatIsAnalysedAndAnalysesARepeatedIdOnce() {
        List<UUID> ids = saveReviews(3);
        service.analyzeForSources(org, "REVIEW", List.of(ids.get(0)));

        RunResult result = service.analyzeForSources(org, "REVIEW", List.of(ids.get(0), ids.get(1), ids.get(2), ids.get(1)));

        assertThat(result.analyzed()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        em.flush();
        assertThat(analyses.findAnalysedSourceIds(org, "REVIEW", ids)).containsExactlyInAnyOrderElementsOf(ids);
    }

    /** Statements the analysis of {@code n} fresh reviews prepares before its writes are flushed. */
    private long statementsToAnalyse(int n) {
        List<UUID> ids = saveReviews(n);
        em.flush();
        em.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        RunResult result = service.analyzeForSources(org, "REVIEW", ids);

        long statements = stats.getPrepareStatementCount();
        assertThat(result.analyzed()).isEqualTo(n);
        em.flush();
        em.clear();
        return statements;
    }

    private List<UUID> saveReviews(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Review r = new Review();
            r.setOrgId(org);
            r.setChannelId(UUID.randomUUID());
            r.setRating(i % 5 + 1);
            r.setBody("배송이 빨라요 " + i);
            r.setNegative(i % 5 < 2);
            r.setReceivedAt(Instant.parse("2026-06-09T00:00:00Z"));
            ids.add(reviews.save(r).getId());
        }
        return ids;
    }
}
