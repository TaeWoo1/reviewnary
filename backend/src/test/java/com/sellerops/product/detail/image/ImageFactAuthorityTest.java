package com.sellerops.product.detail.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Association and authority — the deterministic half, and the half that decides whether the
 * 2026-08-26 defect can happen again.
 *
 * <p>That defect was a confident answer resting on an association nobody made. Every case here is
 * about refusing to make one.
 */
class ImageFactAuthorityTest {

    private static final List<String> OPTIONS = List.of("1호 (16x10mm)", "2호 (22x10mm)", "3호 / 화이트");

    private static ExtractedImageFacts facts(ExtractedImageFacts.Fact... rows) {
        return new ExtractedImageFacts(List.of(rows));
    }

    private static ExtractedImageFacts.Fact fact(String label, String attribute, String value) {
        return new ExtractedImageFacts.Fact(label, attribute, value);
    }

    @Test
    @DisplayName("exact whole-token match associates; a bare number does not")
    void exactMatchOnly() {
        assertThat(ImageFactAssociation.match("2호 (22x10mm)", OPTIONS)).contains("2호 (22x10mm)");
        // The defect in one assertion. "2" is not "2호", and the reasoning that would connect them —
        // "the label starts with the same digit" — is exactly the inference this lane forbids.
        assertThat(ImageFactAssociation.match("2", OPTIONS)).isEmpty();
        assertThat(ImageFactAssociation.match("22x10", OPTIONS)).isEmpty();
        assertThat(ImageFactAssociation.match("2호", OPTIONS))
                .as("a substring of one option name is not that option")
                .isEmpty();
    }

    @Test
    @DisplayName("an axis of a multi-axis option is a whole token; a fragment of one is not")
    void axisTokensMatch() {
        assertThat(ImageFactAssociation.match("3호", OPTIONS)).contains("3호 / 화이트");
        assertThat(ImageFactAssociation.match("화이트", OPTIONS)).contains("3호 / 화이트");
        assertThat(ImageFactAssociation.match("화이", OPTIONS)).isEmpty();
    }

    @Test
    @DisplayName("full-width digits are the same 규격 typed on another keyboard")
    void normalizationIsEncodingOnly() {
        assertThat(ImageFactAssociation.match("２호 / 화이트", List.of("2호 / 화이트")))
                .contains("2호 / 화이트");
        // A unit is not an encoding difference, and dropping one is how "2" starts matching "2호".
        assertThat(ImageFactAssociation.match("22", List.of("22mm"))).isEmpty();
    }

    /** The NAVER option shape of the 2026-11-19 live run: 4 colours × 1~5호, each axis named. */
    private static final List<String> NAVER_AXES = List.of(
            "색상: 그레이 / 사이즈: 1호", "색상: 그레이 / 사이즈: 2호",
            "색상: 블랙 / 사이즈: 1호", "색상: 블랙 / 사이즈: 2호",
            "색상: 우드 / 사이즈: 2호", "색상: 화이트 / 사이즈: 2호");

    @Test
    @DisplayName("one axis value names every option carrying it (owner, 2026-11-19) — the 사이즈 table of the live run")
    void axisValueNamesEveryOptionWithIt() {
        ImageFactAssociation.Group group = ImageFactAssociation.matchGroup("2호", NAVER_AXES).orElseThrow();
        assertThat(group.key()).isEqualTo("사이즈: 2호");
        assertThat(group.options()).containsExactly("색상: 그레이 / 사이즈: 2호", "색상: 블랙 / 사이즈: 2호",
                "색상: 우드 / 사이즈: 2호", "색상: 화이트 / 사이즈: 2호");
        // The axis written out is the same value, and an unnamed axis is identified by its position.
        assertThat(ImageFactAssociation.match("사이즈: 2호", NAVER_AXES)).contains("사이즈: 2호");
        assertThat(ImageFactAssociation.matchGroup("공통", List.of("공통 / 화이트", "공통 / 블랙")).orElseThrow()
                .options()).containsExactly("공통 / 화이트", "공통 / 블랙");
    }

    @Test
    @DisplayName("a label that is a value on two different axes still names nothing")
    void twoAxesRefuse() {
        assertThat(ImageFactAssociation.match("블랙", List.of("색상: 블랙 / 커버: 화이트", "색상: 화이트 / 커버: 블랙")))
                .isEmpty();
    }

    @Test
    @DisplayName("axis parsing drops nothing: 2호 is not 2호(10개), and a fragment of a value is not the value")
    void axisParsingIsExact() {
        assertThat(ImageFactAssociation.match("2호", List.of("그레이 / 2호(10개)", "화이트 / 2호(10개)"))).isEmpty();
        assertThat(ImageFactAssociation.match("2", NAVER_AXES)).isEmpty();
        assertThat(ImageFactAssociation.match("사이즈", NAVER_AXES)).isEmpty();
    }

    @Test
    @DisplayName("rule C per option: a value for 「2호」 and another for 「그레이 / 2호」 refuse each other")
    void groupAndSingleOptionContradictionRefusesBoth() {
        ImageFactAuthority.Finalized out = ImageFactAuthority.decide(List.of(
                new ImageFactAuthority.ImageFacts("a", facts(fact("2호", "D 내경 높이", "10"))),
                new ImageFactAuthority.ImageFacts("b",
                        facts(fact("색상: 그레이 / 사이즈: 2호", "D 내경 높이", "11")))), NAVER_AXES);
        assertThat(out.accepted()).isEmpty();
        assertThat(out.countOf(ImageFactAuthority.Verdict.REFUSED_CONFLICTING_VALUE)).isEqualTo(2);
    }

    @Test
    @DisplayName("the live table is accepted under its axis, once per row")
    void liveTableIsAcceptedUnderItsAxis() {
        ImageFactAuthority.Finalized out = ImageFactAuthority.decide(List.of(
                new ImageFactAuthority.ImageFacts("t", facts(fact("1호", "D 내경 높이", "9"),
                        fact("2호", "D 내경 높이", "10")))), NAVER_AXES);
        assertThat(out.accepted()).extracting(ImageFactAuthority.Judged::matchedOptionName)
                .containsExactly("사이즈: 1호", "사이즈: 2호");
    }

    @Test
    @DisplayName("rule A — a value with no 규격 governing it is refused")
    void ruleARefusesUnlabelledValues() {
        ImageFactAuthority.Finalized finalized = ImageFactAuthority.decide(
                List.of(new ImageFactAuthority.ImageFacts("sha-a",
                        facts(fact(null, "수용 가닥수", "3~4가닥")))), OPTIONS);

        assertThat(finalized.judged()).singleElement()
                .extracting(ImageFactAuthority.Judged::verdict)
                .isEqualTo(ImageFactAuthority.Verdict.REFUSED_NO_SPEC_LABEL);
        assertThat(finalized.accepted()).isEmpty();
    }

    @Test
    @DisplayName("rule B — a label no stored 규격 matches is unresolved, kept, and never quoted")
    void ruleBLeavesUnmatchedLabelsUnresolved() {
        ImageFactAuthority.Finalized finalized = ImageFactAuthority.decide(
                List.of(new ImageFactAuthority.ImageFacts("sha-a",
                        facts(fact("특대형", "수용 가닥수", "8가닥")))), OPTIONS);

        assertThat(finalized.countOf(ImageFactAuthority.Verdict.UNRESOLVED_NO_EXACT_VARIANT))
                .isEqualTo(1);
        assertThat(finalized.accepted()).isEmpty();
    }

    @Test
    @DisplayName("rule C — two pictures contradicting each other refuse BOTH, across images")
    void ruleCRefusesBothSidesOfAContradiction() {
        ImageFactAuthority.Finalized finalized = ImageFactAuthority.decide(List.of(
                new ImageFactAuthority.ImageFacts("sha-a",
                        facts(fact("1호 (16x10mm)", "수용 가닥수", "2가닥"))),
                new ImageFactAuthority.ImageFacts("sha-b",
                        facts(fact("1호 (16x10mm)", "수용 가닥수", "4가닥")))), OPTIONS);

        // Not "the newest wins" and not "the majority wins": an old table left below a new one is the
        // ordinary case on a 상세페이지, and we do not know which of the two it is.
        assertThat(finalized.accepted()).isEmpty();
        assertThat(finalized.countOf(ImageFactAuthority.Verdict.REFUSED_CONFLICTING_VALUE))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("rule D — exact 규격, no contradiction, and only then may it be stated")
    void ruleDAccepts() {
        ImageFactAuthority.Finalized finalized = ImageFactAuthority.decide(List.of(
                new ImageFactAuthority.ImageFacts("sha-a",
                        facts(fact("1호 (16x10mm)", "수용 가닥수", "2가닥"),
                                fact("2호 (22x10mm)", "수용 가닥수", "4가닥")))), OPTIONS);

        assertThat(finalized.accepted()).hasSize(2);
        assertThat(finalized.accepted()).allSatisfy(judged ->
                assertThat(judged.matchedOptionName()).isNotNull());
    }

    @Test
    @DisplayName("the same value stated twice is agreement, not a contradiction")
    void repeatedAgreementIsNotAConflict() {
        ImageFactAuthority.Finalized finalized = ImageFactAuthority.decide(List.of(
                new ImageFactAuthority.ImageFacts("sha-a",
                        facts(fact("1호 (16x10mm)", "수용 가닥수", "2가닥"))),
                new ImageFactAuthority.ImageFacts("sha-b",
                        facts(fact("1호 (16x10mm)", "수용 가닥수", "2가닥")))), OPTIONS);

        assertThat(finalized.accepted()).hasSize(2);
    }

    @Test
    @DisplayName("with no stored 규격 nothing can be accepted — which is why variants come first")
    void noVariantsMeansNothingIsQuotable() {
        ImageFactAuthority.Finalized finalized = ImageFactAuthority.decide(
                List.of(new ImageFactAuthority.ImageFacts("sha-a",
                        facts(fact("1호", "수용 가닥수", "2가닥")))), List.of());

        assertThat(finalized.accepted()).isEmpty();
    }
}
