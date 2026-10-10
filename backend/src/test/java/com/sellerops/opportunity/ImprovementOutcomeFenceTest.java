package com.sellerops.opportunity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An outcome is a fact the next judgement may CITE, never a rule that changes it.</b>
 *
 * <p>This is the fence for the one thing Learning &amp; Outcome Loop v1 could most easily have got wrong. The
 * arc's own brief forbids it in three words — «Seller Policy 자동 생성, generic rule 자동 변경, marketplace WRITE
 * 확대는 금지» — and all three are absences, so they are asserted on the source the way
 * {@code SellerOperationsPolicyFenceTest} asserts its five.
 *
 * <p>The failure mode is attractive, which is why it needs a fence rather than a review note. Somebody notices
 * that 「개선됨」 outcomes are a perfect signal and has the reader write a {@code seller_operations_policy} for
 * every remediation that worked, or raise the triage tier of a problem whose outcome was 「근거 늘었습니다」.
 * Every screen would look right, and the product would have standing rules nobody declared — the exact thing
 * {@code OperationsPolicyFence} exists to prevent, arrived at from the other side.
 */
class ImprovementOutcomeFenceTest {

    private static final Path PACKAGE = Paths.get("src/main/java/com/sellerops/opportunity");

    /**
     * What a measurement may not reach. The first three are the arc's named prohibitions; the last two are the
     * mechanisms a «good outcome» would be tempting to feed.
     */
    private static final List<String> FORBIDDEN = List.of(
            "SellerOperationsPolicy", "OperationsPolicyService", "SellerPolicyOverlay", "OperationsPolicyFence",
            "SellerGuidance", "TriageCorrection", "ReviewTriageRules", "ReviewTriageTier", "TriageDisplayDecision",
            "setReplyState", "KnowledgeAuthority", "OperationsCase", "CaseDecider", "RecommendedActionType");

    @Test
    @DisplayName("nothing in the opportunity package can write a seller policy, a guidance, a triage tier or a case")
    void anOutcomeChangesNoRule() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources()) {
            String code = stripComments(Files.readString(source));
            for (String forbidden : FORBIDDEN) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " → " + forbidden);
                }
            }
        }
        assertThat(offenders)
                .as("an outcome is evidence an investigation may cite; it is not a rule and it writes none")
                .isEmpty();
    }

    /**
     * The one thing in this package that touches the issue memory, and the shape of that touch.
     *
     * <p>Applying an improvement records remediation — a transition a PERSON caused, through the service that
     * owns the lifecycle. What it must never do is reach {@code RESOLVED}, which is the one state the product
     * only ever concludes from observed quiet weeks, or {@code dismiss}, which would let an application hide a
     * problem. Both would turn an act into a conclusion.
     */
    @Test
    @DisplayName("applying records remediation through the lifecycle service, and can reach neither 해결됨 nor 중요하지 않음")
    void anApplicationConcludesNothing() throws IOException {
        String service = stripComments(Files.readString(PACKAGE.resolve("OpportunityService.java")));
        assertThat(service).contains("lifecycle.recordRemediation(");
        for (String forbidden : List.of("RESOLVED", "lifecycle.dismiss(", "setLifecycleState",
                "setDismissed", "runAutomaticPass")) {
            assertThat(service).as("apply must not reach %s", forbidden).doesNotContain(forbidden);
        }

        // And the reading of a window writes nothing but its own verdict.
        String reader = stripComments(Files.readString(PACKAGE.resolve("ImprovementOutcomeService.java")));
        for (String forbidden : List.of("lifecycle", "RESOLVED", "setStatus", "decisions.save")) {
            assertThat(reader).as("reading a window must not reach %s", forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("the measurement's premise is frozen in the migration, not only in the service")
    void theAnchorIsFrozenAtTheDatabase() throws IOException {
        String migration = Files.readString(
                Paths.get("src/main/resources/db/migration/V131__improvement_outcome.sql"));
        assertThat(migration).contains("improvement_outcome_anchor_frozen");
        for (String column : List.of("applied_on", "baseline_from", "baseline_to", "baseline_evidence",
                "baseline_reviews", "observe_days")) {
            assertThat(migration)
                    .as("%s is part of the premise and must be refused on update", column)
                    .contains("new." + column + " is distinct from old." + column);
        }
    }

    private static List<Path> sources() throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(PACKAGE)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
