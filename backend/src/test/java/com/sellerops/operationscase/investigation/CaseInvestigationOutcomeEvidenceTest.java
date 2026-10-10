package com.sellerops.operationscase.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an investigation is told about what this company already DID, and how it did not work out.
 *
 * <p>Before Learning &amp; Outcome Loop v1 the context carried decisions and no results:
 * {@code getPastSellerDecisions} said this seller replies to 접착 complaints and never that they changed the
 * adhesive in August and complaints fell by four fifths. The {@code [o]} lines are that second half, and these
 * tests are about the <b>restraint</b> in them more than the information — an outcome that could be read as
 * proof the act worked is worse than no outcome at all.
 */
class CaseInvestigationOutcomeEvidenceTest {

    private static String lines(Set<String> refs, CaseInvestigationTools.PastOutcome... outcomes) {
        StringBuilder text = new StringBuilder();
        CaseInvestigator.appendOutcomes(text, refs, List.of(outcomes));
        return text.toString();
    }

    @Test
    @DisplayName("a settled outcome is one citable line carrying the act, the date, both counts and the rule")
    void aSettledOutcomeIsCitable() {
        Set<String> refs = new LinkedHashSet<>();
        String text = lines(refs, new CaseInvestigationTools.PastOutcome(
                "접착 탈락", "상품 상세·안내 보완", LocalDate.of(2026, 9, 1), "근거 줄었습니다",
                "같은 문제가 적게 들어왔습니다", 12, 2));

        assertThat(text).isEqualTo("[o1] 이 회사가 「접착 탈락」에 한 조치(상품 상세·안내 보완) 2026-09-01 뒤 결과: "
                + "근거 줄었습니다 — 적용 전 4주 12건 → 뒤 4주 2건 (같은 문제가 적게 들어왔습니다)\n");
        assertThat(refs).containsExactly("o1");
        // No causal word anywhere on the line the model reads.
        assertThat(text).doesNotContain("해결").doesNotContain("효과");
    }

    @Test
    @DisplayName("a withheld verdict prints its counts AND its reason — «판단 보류» alone invites a model to read a weak yes")
    void aWithheldVerdictCarriesWhy() {
        Set<String> refs = new LinkedHashSet<>();
        String text = lines(refs, new CaseInvestigationTools.PastOutcome(
                "배송 지연", "운영 기준 보완", LocalDate.of(2026, 8, 10), "판단 보류",
                "그 기간에 들어온 리뷰가 없어 비교할 수 없습니다", 11, 0));

        assertThat(text).contains("판단 보류");
        assertThat(text)
                .as("the refusal is the valuable half of this line")
                .contains("그 기간에 들어온 리뷰가 없어 비교할 수 없습니다");
        assertThat(text).contains("적용 전 4주 11건 → 뒤 4주 0건");
    }

    @Test
    @DisplayName("several outcomes number independently, and nothing is printed when the company has done nothing")
    void boundedAndEmptyByDefault() {
        Set<String> refs = new LinkedHashSet<>();
        String text = lines(refs,
                new CaseInvestigationTools.PastOutcome("접착 탈락", "FAQ 보완", LocalDate.of(2026, 9, 1),
                        "근거 늘었습니다", "같은 문제가 더 들어왔습니다", 10, 25),
                new CaseInvestigationTools.PastOutcome("포장 파손", null, null,
                        "변화 없습니다", "같은 문제가 비슷하게 들어왔습니다", 9, 8));

        assertThat(refs).containsExactly("o1", "o2");
        assertThat(text.lines()).hasSize(2);
        // A row whose kind or date could not be resolved says less rather than guessing.
        assertThat(text.lines().toList().get(1))
                .isEqualTo("[o2] 이 회사가 「포장 파손」에 한 조치 뒤 결과: 변화 없습니다 — 적용 전 4주 9건 → 뒤 4주 8건 "
                        + "(같은 문제가 비슷하게 들어왔습니다)");

        Set<String> none = new LinkedHashSet<>();
        assertThat(lines(none)).isEmpty();
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("the prompt tells the model what an outcome is not, and the versions say the context changed shape")
    void thePromptCarriesTheRestraint() {
        String system = CaseInvestigationPrompt.system();
        assertThat(system).contains("11. [o]");
        assertThat(system).contains("그 조치가 문제를 해결했다고 단정하지 않습니다");
        assertThat(system)
                .as("판단 보류 is the most common verdict by design — a model must not read it as a weak yes")
                .contains("「판단 보류」는 아직 모른다는 뜻이고");
        assertThat(system)
                .as("an investigation must not tell a seller to start what they are already doing")
                .contains("판매자 상태");

        assertThat(CaseInvestigationPrompt.PROMPT_VERSION).isEqualTo("case-investigation-prompt/v5");
        assertThat(CaseInvestigationPrompt.TOOL_VERSION).isEqualTo("case-tools/v3");
        assertThat(CaseInvestigationPrompt.EVIDENCE_VERSION).isEqualTo("case-evidence/v3");
    }
}
