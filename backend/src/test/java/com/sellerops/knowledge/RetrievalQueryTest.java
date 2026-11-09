package com.sellerops.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retrieval &amp; Grounding Correctness v1 — the bounded candidates one question is asked as.
 *
 * <p>The rule under test is not a scorer change: every candidate meets the unchanged gates. What is
 * pinned is that a planner's sentence and a customer's thread yield a SUBJECT form that says what the
 * two words that matter say, and nothing the reader was addressed with.
 */
class RetrievalQueryTest {

    @Test
    @DisplayName("a planner's instruction sentence reduces to its subject; the instruction words are gone")
    void plannerSentenceReducesToItsSubject() {
        String subject = RetrievalQuery.subjectOf("이 상품의 교환이나 반품이 가능한 조건이 명시돼 있는지 확인해줘");
        assertThat(subject).contains("교환").contains("반품").contains("조건");
        assertThat(subject).doesNotContain("확인").doesNotContain("명시").doesNotContain("가능").doesNotContain("있는지");
        // The planner also writes the inflected form (live 2026-08-30): an inflection of a stop word is a stop word.
        String inflected = RetrievalQuery.subjectOf("이 상품의 반품 조건이 명시되어 있는지 확인");
        assertThat(inflected).contains("반품").contains("조건").doesNotContain("명시되어");
    }

    static final String[] PLANNER_SENTENCES = PlannerSentences.ABOUT_THE_RETURN_DOCUMENT;

    @Test
    @DisplayName("candidate agreement: the seller's noun, the seller's sentence and the planner's three sentences share one SUBJECT")
    void candidateAgreement() {
        String[] inputs = {"반품 조건", "이 상품의 교환이나 반품이 가능한 조건이 명시돼 있는지 확인해줘",
                PLANNER_SENTENCES[0], PLANNER_SENTENCES[1], PLANNER_SENTENCES[2]};
        for (String in : inputs) {
            String subject = RetrievalQuery.subjectOf(in);
            assertThat(subject).as(in).contains("반품").contains("조건");
            // Nothing the planner addressed to us, and nothing that names the artefact, is a topic word.
            for (String meta : new String[] {"문서", "설명", "faq", "정책", "판매자", "작성", "상품의", "문장", "안내문",
                    "핵심", "내용", "명시", "확인", "있는", "가능", "대해", "해당"}) {
                assertThat(subject).as(in + " keeps " + meta).doesNotContain(meta);
            }
        }
        // The seller's own what-did-we-say sentence reduces to the same subject (live 2026-08-30: the memory lane
        // missed 「QA 전선몰딩 문의에 반품 조건 예전에 뭐라고 답했어?」 on 문의에·뭐라고·답했어 counted as topic).
        assertThat(RetrievalQuery.subjectOf("QA 전선몰딩 문의에 반품 조건 예전에 뭐라고 답했어?")).isEqualTo("qa 전선몰딩 반품 조건");
        // D: a question about another topic is not touched — its own words are its subject.
        assertThat(RetrievalQuery.subjectOf("배송 기간")).isEqualTo("배송 기간");
    }

    @Test
    @DisplayName("a structured topic is the first candidate and part of the text the topic gate classifies")
    void structuredTopicFirst() {
        RetrievalQuery q = RetrievalQuery.of("교환 반품 환불", null, PLANNER_SENTENCES[0]);
        assertThat(q.candidates().get(0).origin()).isEqualTo(RetrievalQuery.Origin.TOPIC);
        assertThat(q.candidates().get(0).text()).isEqualTo("교환 반품 환불");
        assertThat(q.text()).startsWith("교환 반품 환불 ");
        assertThat(q.full()).isEqualTo(PLANNER_SENTENCES[0]);
        assertThat(q.candidates()).hasSizeLessThanOrEqualTo(RetrievalQuery.MAX_CANDIDATES);
    }

    @Test
    @DisplayName("candidates: topic → title → subject → full, deduped, never more than four")
    void candidatesInOrder() {
        RetrievalQuery q = RetrievalQuery.of("배송", "배송 문의",
                "안녕하세요 배송은 보통 며칠 걸리나요? 급해서 문의드립니다.");
        List<RetrievalQuery.Origin> origins = q.candidates().stream().map(RetrievalQuery.Candidate::origin).toList();
        assertThat(origins).containsExactly(RetrievalQuery.Origin.TOPIC, RetrievalQuery.Origin.TITLE,
                RetrievalQuery.Origin.SUBJECT, RetrievalQuery.Origin.FULL);
        assertThat(q.candidates()).hasSizeLessThanOrEqualTo(RetrievalQuery.MAX_CANDIDATES);
        assertThat(q.full()).startsWith("배송 문의 안녕하세요");
    }

    @Test
    @DisplayName("a short question that IS its own subject collapses to one candidate — no duplicate searches")
    void shortQuestionCollapses() {
        RetrievalQuery q = RetrievalQuery.ofText("반품 조건");
        assertThat(q.candidates()).hasSize(1);
        assertThat(q.candidates().get(0).text()).isEqualTo("반품 조건");
    }

    @Test
    @DisplayName("the FULL candidate is bounded to the head of a long thread")
    void fullIsBounded() {
        String thread = "배송 문의 " + "이전 메일 내용입니다. ".repeat(100);
        RetrievalQuery q = RetrievalQuery.of(null, "배송 문의", thread);
        assertThat(q.full().length()).isLessThanOrEqualTo(RetrievalQuery.FULL_CHARS);
    }

    @Test
    @DisplayName("blank input yields no candidates rather than an empty query")
    void blankYieldsNothing() {
        assertThat(RetrievalQuery.of(null, null, "  ").candidates()).isEmpty();
    }

    @Test
    @DisplayName("an element written out as visible characters is not something the customer asked about")
    void markupLiteralsLeaveTheQuestion() {
        // Live (2026-09-23): a Cafe24 post whose body had been escaped twice arrived, correctly, as
        // the characters <meta charset="utf-8"> in front of the question. On a screen that is the
        // honest rendering of what the channel sent; in a query it is words nobody asked about,
        // counted by the absence ratio and embedded with the sentence.
        RetrievalQuery q = RetrievalQuery.ofCustomer("문의 드립니다",
                "<meta charset=\"utf-8\">교환이나 반품은 언제까지 가능한가요?");
        assertThat(q.full()).isEqualTo("문의 드립니다 교환이나 반품은 언제까지 가능한가요?");
        assertThat(q.candidates()).noneMatch(c -> c.text().contains("meta"));
    }

    @Test
    @DisplayName("a customer comparing two numbers keeps their sentence")
    void arithmeticIsNotMarkup() {
        // The rule is a tight shape — an element name straight after the bracket — precisely so a
        // question is never silently rewritten into a different question.
        assertThat(RetrievalQuery.ofText("두께가 2 < 3 인가요?").full()).isEqualTo("두께가 2 < 3 인가요?");
        assertThat(RetrievalQuery.ofText("<3 처럼 보이는 기호").full()).isEqualTo("<3 처럼 보이는 기호");
    }
    /**
     * <b>A subject may be quoted back to the seller only if it is one</b> (2026-10-06).
     *
     * <p>Observed on the demo org: {@code proactive_case.knowledge_gap} held
     * {@code "missingSubject":"드립니다"} — a row written before {@link RetrievalQuery#subjectNouns} learned
     * the rule (2026-09-23) — and the case screen quoted it as the name of the company standard that was
     * missing. The same word would then have titled the knowledge the seller wrote.
     */
    @Test
    @DisplayName("generic titles and predicates can never be quoted as a subject")
    void genericTitlesAreNotSubjects() {
        for (String fragment : java.util.List.of("드립니다", "문의 드립니다", "문의드립니다", "질문드립니다",
                "안녕하세요", "부탁드립니다", "알려주세요", "가능한가요")) {
            assertThat(RetrievalQuery.quotableSubject(fragment)).as(fragment).isNull();
        }
    }

    @Test
    @DisplayName("a real noun phrase is returned exactly as it came — the word the seller would have written")
    void nounPhrasesSurvive() {
        assertThat(RetrievalQuery.quotableSubject("엘보 구간에 쓸 사이즈")).isEqualTo("엘보 구간에 쓸 사이즈");
        assertThat(RetrievalQuery.quotableSubject("교환 가능 여부")).isEqualTo("교환 가능 여부");
        assertThat(RetrievalQuery.quotableSubject("교환 가능 기간")).isEqualTo("교환 가능 기간");
        assertThat(RetrievalQuery.quotableSubject("방수")).isEqualTo("방수");
        assertThat(RetrievalQuery.quotableSubject("  가닥 ")).isEqualTo("가닥");
    }

    @Test
    @DisplayName("nothing is a subject when there is nothing")
    void nothingIsNotASubject() {
        assertThat(RetrievalQuery.quotableSubject(null)).isNull();
        assertThat(RetrievalQuery.quotableSubject("   ")).isNull();
    }

    /** Synthetic product A of the case evaluation (tools/eval/case-eval/synthetic_store.py) — fifteen notes, one each. */
    private static final String[][] PRODUCT_A = {{"모서리", "모서리는 45도로 잘라 맞추면 틈이 적습니다."},
            {"청소", "물걸레로 가볍게 닦아 주세요. 세제는 쓰지 마세요."}, {"두께", "두께는 8mm입니다."},
            {"겨울철 부착", "기온이 10도 이하이면 접착력이 약해질 수 있어 실내를 데운 뒤 붙여 주세요."},
            {"떼어낼 때", "떼어낼 때는 드라이어로 데운 뒤 천천히 떼어 주세요."}, {"수량", "한 박스에 10줄이 들어 있습니다."},
            {"포장", "포장은 종이 박스이며 완충재가 들어 있습니다."}, {"부착 전 준비", "부착 전 벽면의 먼지를 마른 천으로 닦아 주세요."},
            {"보관 방법", "직사광선을 피해 서늘한 곳에 보관해 주세요."}, {"길이", "한 줄의 길이는 2.4m입니다."},
            {"색상", "색상은 화이트 · 그레이 · 우드 세 가지입니다."}, {"자르기", "몰딩은 커터칼로 여러 번 그어 자르면 깔끔하게 잘립니다."},
            {"무게", "한 줄의 무게는 120g입니다."}, {"인증", "실내용 접착제를 사용했습니다."},
            {"도배지에 붙일 때", "실크 벽지처럼 표면이 매끄럽지 않은 도배지에서는 접착이 약할 수 있습니다."}};

    /** The first candidate that finds anything, as the product library's search tries them. */
    private static List<String> firstHit(String need) {
        List<KnowledgeRetriever.Candidate<String>> corpus = new java.util.ArrayList<>();
        for (String[] note : PRODUCT_A) {
            corpus.add(new KnowledgeRetriever.Candidate<>(note[0],
                    KnowledgeText.normalize(note[0]) + KnowledgeText.normalize(note[1])));
        }
        for (RetrievalQuery.Candidate c : RetrievalQuery.ofText(need).candidates()) {
            List<String> found = KnowledgeRetriever.rank(c.text(), corpus, "몰딩").stream()
                    .map(KnowledgeRetriever.Hit::ref).toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    @Test
    @DisplayName("a planner's 「선택 가능한 색상 종류」 finds the seller's 색상 note — the listing words do not count as its topic")
    void listingWordsDoNotSinkTheTopic() {
        // case-eval-r10 synthetic Q5: fifteen notes is over the whole-library limit, so the need's own search had to find
        // 색상, and 「선택 색상 종류」 (색상 two of six characters) fell under the askable bar.
        assertThat(RetrievalQuery.subjectOf("선택 가능한 색상 종류")).isEqualTo("색상");
        assertThat(firstHit("선택 가능한 색상 종류")).containsExactly("색상");
        assertThat(firstHit("선택 가능한 색상 목록")).containsExactly("색상");
        assertThat(firstHit("색상 옵션 종류")).containsExactly("색상");
        // The absence gate is unchanged: a topic the library never wrote about still finds nothing.
        assertThat(firstHit("욕실 방수 가능 여부")).isEmpty();
        assertThat(firstHit("선택 가능한 사이즈 종류")).isEmpty();
    }
}
