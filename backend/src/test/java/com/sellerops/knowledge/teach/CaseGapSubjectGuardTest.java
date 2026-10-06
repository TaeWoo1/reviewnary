package com.sellerops.knowledge.teach;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.KnowledgeTopic;
import com.sellerops.operationscase.CaseKnowledgeGap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A case never quotes a word it cannot stand behind</b> (2026-10-06, product-owner decision).
 *
 * <p>Measured on the demo org: {@code proactive_case.knowledge_gap} held
 * {@code {"missingSubject":"드립니다", ... ,"source":"RESOLUTION"}} for an inquiry titled 「문의 드립니다」.
 * The case screen drew 「「드립니다」에 대해 고객에게 안내할 기준이 없습니다.」 and a chip reading
 * 「드립니다 기준 없음」, and the knowledge the seller was about to write would have been filed under
 * 「드립니다 안내」.
 *
 * <p>The extractor has refused predicates since 2026-09-23 ({@code GapSubject} · {@code subjectNouns}), so
 * what this closes is the other end: a gap is <b>stored</b> when the case is decided and read for as long
 * as the case is open, and a row written by an older build still says what it said then. The guard is the
 * extractor's own rule applied where the word is USED, and it adds no word list of its own.
 */
class CaseGapSubjectGuardTest {

    private static CaseKnowledgeGap gap(String subject, String topic) {
        return new CaseKnowledgeGap("NO_ANSWER_BASIS", subject, "ORG", topic, null, "RESOLUTION");
    }

    @Test
    @DisplayName("a stored predicate is not quoted — the fact is stated without a name")
    void predicateIsNotQuoted() {
        CaseKnowledgeGap stored = gap("드립니다", "EXCHANGE_RETURN");

        assertThat(stored.quotableSubject()).isNull();
        assertThat(CaseKnowledgeService.gapSentence(stored))
                .isEqualTo("이 문의에 답할 판매자 안내 기준이 없습니다.")
                .doesNotContain("드립니다");
    }

    @Test
    @DisplayName("generic titles go the same way — 문의 드립니다 · 안녕하세요 name nothing the seller can write")
    void genericTitlesAreNotQuoted() {
        for (String fragment : java.util.List.of("문의 드립니다", "문의드립니다", "질문드립니다", "안녕하세요")) {
            assertThat(gap(fragment, "EXCHANGE_RETURN").quotableSubject()).as(fragment).isNull();
        }
    }

    @Test
    @DisplayName("a real subject is quoted exactly as it was stored")
    void nounPhrasesAreQuoted() {
        CaseKnowledgeGap stored = gap("교환 가능 기간", "EXCHANGE_RETURN");

        assertThat(stored.quotableSubject()).isEqualTo("교환 가능 기간");
        assertThat(CaseKnowledgeService.gapSentence(stored))
                .isEqualTo("「교환 가능 기간」에 대해 고객에게 안내할 기준이 없습니다.");
        assertThat(gap("엘보 구간에 쓸 사이즈", null).quotableSubject()).isEqualTo("엘보 구간에 쓸 사이즈");
    }

    /**
     * The heading outlives the case: it is what the company's own knowledge is filed under, and what the next
     * customer's question has to find. 「드립니다 안내」 would have been permanent.
     */
    @Test
    @DisplayName("the taught knowledge is never titled with a word that cannot be quoted")
    void teachTitleFallsBackToTheTopic() {
        assertThat(CaseKnowledgeService.teachSubject(gap("드립니다", "EXCHANGE_RETURN")))
                .isEqualTo(KnowledgeTopic.EXCHANGE_RETURN.labelKo());
        // No topic either: the sink titles it 「고객 안내 기준」, as it always has for a gap without a subject.
        assertThat(CaseKnowledgeService.teachSubject(gap("드립니다", null))).isNull();
        assertThat(CaseKnowledgeService.teachSubject(gap("드립니다", "NOT_A_TOPIC"))).isNull();
        // And a real subject is still the title.
        assertThat(CaseKnowledgeService.teachSubject(gap("교환 가능 기간", "EXCHANGE_RETURN")))
                .isEqualTo("교환 가능 기간");
    }
}
