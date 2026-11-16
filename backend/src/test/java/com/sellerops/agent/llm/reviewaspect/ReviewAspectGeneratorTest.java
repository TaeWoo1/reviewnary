package com.sellerops.agent.llm.reviewaspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.operator.AgentLlmWireFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the review-aspect model may and may not decide (2026-11-17): closed vocabularies, evidence that is a piece of the
 * very review, outlook items that cite only the evidence given — and a payload of words and stars by position only.
 */
class ReviewAspectGeneratorTest {

    private static final List<ReviewAspectGenerator.Item> ITEMS = List.of(
            new ReviewAspectGenerator.Item(5, "튼튼하고   설치가 쉬워요"),
            new ReviewAspectGenerator.Item(2, "배송이 너무 늦었어요"),
            new ReviewAspectGenerator.Item(null, "좋아요"));

    private static List<Optional<List<ReviewAspectGenerator.Opinion>>> parse(String json, Map<String, Integer> dropped) {
        return ReviewAspectGenerator.parseRead(json, ITEMS, dropped);
    }

    @Test
    @DisplayName("목록 밖 측면·평가, 리뷰에 없는 근거는 버리고, 공백만 다른 근거는 받는다")
    void closedVocabulariesAndVerbatimEvidence() {
        Map<String, Integer> dropped = new HashMap<>();
        var out = parse("""
                {"reviews":[
                 {"i":1,"opinions":[
                   {"aspect":"품질","polarity":"POSITIVE","problem":null,"evidence":"튼튼하고"},
                   {"aspect":"설치","polarity":"POSITIVE","problem":"파손","evidence":"설치가 쉬워요"},
                   {"aspect":"내구성","polarity":"POSITIVE","problem":null,"evidence":"튼튼하고"},
                   {"aspect":"품질","polarity":"NEUTRAL","problem":null,"evidence":"튼튼하고"},
                   {"aspect":"가격","polarity":"POSITIVE","problem":null,"evidence":"가성비 최고"},
                   {"aspect":"품질","polarity":"POSITIVE","problem":null,"evidence":"튼튼"}]},
                 {"i":2,"opinions":[{"aspect":"배송","polarity":"NEGATIVE","problem":"지연","evidence":"배송이 너무 늦었어요"}]},
                 {"i":9,"opinions":[]}
                ]}""", dropped);

        List<ReviewAspectGenerator.Opinion> first = out.get(0).orElseThrow();
        assertThat(first).extracting(ReviewAspectGenerator.Opinion::aspect).containsExactly("품질", "설치");
        assertThat(first.get(1).problem()).as("a POSITIVE opinion carries no problem").isNull();
        assertThat(out.get(1).orElseThrow().get(0).problem()).isEqualTo("지연");
        assertThat(out.get(2)).as("review 3 was not returned: unread, not 'no opinion'").isEmpty();
        assertThat(dropped).containsEntry("off_vocabulary_aspect", 1).containsEntry("off_vocabulary_polarity", 1)
                .containsEntry("evidence_not_in_review", 1).containsEntry("bad_index", 1);
    }

    @Test
    @DisplayName("부정의 문제는 목록에 있을 때만 남고, 「전반」은 측면으로 받는다; 깨진 답은 읽기 실패")
    void problemsAndOverall() {
        var out = parse("""
                {"reviews":[{"i":2,"opinions":[{"aspect":"배송","polarity":"NEGATIVE","problem":"느림","evidence":"늦었어요"}]},
                 {"i":3,"opinions":[{"aspect":"전반","polarity":"POSITIVE","problem":null,"evidence":"좋아요"}]}]}""",
                new HashMap<>());
        assertThat(out.get(1).orElseThrow().get(0).problem()).isNull();
        assertThat(out.get(2).orElseThrow().get(0).aspect()).isEqualTo("전반");
        assertThat(ReviewAspectGenerator.parseRead("not json", ITEMS, new HashMap<>())).isNull();
    }

    @Test
    @DisplayName("기회·위협은 주어진 key만 근거로 삼아야 남는다")
    void outlookCitesOnlyGivenKeys() throws Exception {
        var node = new ObjectMapper().readTree("""
                [{"title":"강점 앞세우기","basis":"튼튼하다는 말이 반복돼요","check":"비교","derivedFrom":["S:품질"]},
                 {"title":"지어낸 근거","basis":"경쟁사가 비싸요","check":"","derivedFrom":["S:시장"]},
                 {"title":"섞인 근거","basis":"…","check":"","derivedFrom":["S:품질","W:없음"]},
                 {"title":"근거 없음","basis":"…","check":"","derivedFrom":[]}]""");
        var written = ReviewAspectGenerator.written(node, Set.of("S:품질", "W:배송"));
        assertThat(written).singleElement().satisfies(w -> {
            assertThat(w.title()).isEqualTo("강점 앞세우기");
            assertThat(w.derivedFrom()).containsExactly("S:품질");
        });
    }

    @Test
    @DisplayName("따옴표는 근거 리뷰에 글자 그대로 있는 말에만 남고, 바꿔 쓴 말은 따옴표가 떨어진다")
    void quotesOnlyAroundRealWords() throws Exception {
        List<String> sources = List.of("[합성] 정리가 훨씬 깔끔해요", "[합성] 벽에  잘 붙어요");
        assertThat(ReviewAspectGenerator.unquoteUnlessVerbatim("'정리가 깔끔해졌다'는 칭찬이 많아요", sources))
                .isEqualTo("정리가 깔끔해졌다는 칭찬이 많아요");
        assertThat(ReviewAspectGenerator.unquoteUnlessVerbatim("「벽에 잘 붙어요」와 \"깔끔\"이 반복돼요", sources))
                .isEqualTo("「벽에 잘 붙어요」와 \"깔끔\"이 반복돼요");
        assertThat(ReviewAspectGenerator.unquoteUnlessVerbatim("“자꾸 떨어진다”는 불만", sources)).isEqualTo("자꾸 떨어진다는 불만");
        var node = new ObjectMapper().readTree(
                "[{\"title\":\"정리 효과 강조\",\"basis\":\"'정리가 깔끔해졌다', '훨씬 깔끔'\",\"check\":\"\",\"derivedFrom\":[\"S:기능\"]}]");
        var w = ReviewAspectGenerator.written(node, Map.of("S:기능", sources));
        assertThat(w.get(0).basis()).isEqualTo("정리가 깔끔해졌다, '훨씬 깔끔'");
    }

    @Test
    @DisplayName("보내는 것은 번호·별점·리뷰 글뿐 — 상품명·리뷰 id·고객 정보 자리가 없다 (줄바꿈은 한 줄로, 길면 자름)")
    void payloadFloor() throws Exception {
        List<String> sent = new ArrayList<>();
        AgentLlmTransport recording = (uri, headers, body) -> {
            sent.add(body);
            return new AgentLlmTransport.Response(500, "", 1);
        };
        ReviewAspectGenerator g = new ReviewAspectGenerator(recording, AgentLlmWireFormat.Vendor.OPENAI, "m", "k", 100,
                null, 10);
        var result = g.read(List.of(new ReviewAspectGenerator.Item(4, "첫 줄\n둘째 줄이 아주 길어요"),
                new ReviewAspectGenerator.Item(null, "짧음")));
        assertThat(result.reason()).isEqualTo("http:500");
        String user = new ObjectMapper().readTree(sent.get(0)).path("messages").get(1).path("content").asText();
        assertThat(user).isEqualTo("리뷰:\n1. (★4) 첫 줄 둘째 줄이\n2. 짧음");
        assertThat(sent.get(0)).doesNotContain("\"k\"");
    }
}
