package com.sellerops.inquiry.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * coverage-judge/v8 (2026-11-15 real eval q06): v7 plus one rule — a need answered by a common rule also cites this
 * product's own exception to it — and nothing else. The default since then, in code and in application.yml.
 */
class CoverageJudgeV8Test {

    @Test
    @DisplayName("v8 adds exactly one rule line to v7, keeps v7's answer shape, and is the default")
    void oneRuleAdded() {
        List<String> v7 = List.of(InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V7).split("\n"));
        List<String> v8 = new ArrayList<>(List.of(InquiryDecisionPrompt.judgeSystem(InquiryDecisionPrompt.JUDGE_V8)
                .split("\n")));
        List<String> added = new ArrayList<>(v8);
        added.removeAll(v7);
        assertThat(added).singleElement().asString().contains("이 상품에 대한 자료").contains("예외")
                .contains("evidence에 넣습니다").contains("status를 낮추지 않습니다");
        v8.removeAll(added);
        assertThat(v8).isEqualTo(v7);
        String v7Schema = InquiryDecisionPrompt.judgeSchema(InquiryDecisionPrompt.JUDGE_V7, List.of("N1"), List.of("E1"),
                List.of()).toString();
        String v8Schema = InquiryDecisionPrompt.judgeSchema(InquiryDecisionPrompt.JUDGE_V8, List.of("N1"), List.of("E1"),
                List.of()).toString();
        assertThat(v8Schema).contains("coverage_judge_v8").contains("\"settled_by\"");
        assertThat(v8Schema.replace("coverage_judge_v8", "coverage_judge_v7")).as("the same shape as v7's")
                .isEqualTo(v7Schema);
        assertThat(new InquiryDecisionProperties(true, "", "m", "k", 0, 0, null).judgePrompt())
                .isEqualTo(InquiryDecisionPrompt.JUDGE_V8);
        assertThat(new InquiryDecisionProperties(true, "", "m", "k", 0, 0, null, InquiryDecisionPrompt.JUDGE_V7, "")
                .judgePrompt()).as("v7 stays selectable, to reproduce the 2026-11-15 baseline")
                .isEqualTo(InquiryDecisionPrompt.JUDGE_V7);
    }

    @Test
    @DisplayName("the running service's default is the code's: application.yml names the same judge prompt")
    void theYamlDefaultIsTheCodes() throws Exception {
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml"));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*judge-prompt:\\s*\\$\\{SELLEROPS_INQUIRY_DECISION_JUDGE_PROMPT:([^}]+)}").matcher(yml);
        assertThat(m.find()).as("the judge-prompt line is where this test reads it").isTrue();
        assertThat(m.group(1).strip()).isEqualTo(InquiryDecisionPrompt.JUDGE_V8);
        assertThat(m.find()).as("one judge-prompt line only").isFalse();
    }
}
