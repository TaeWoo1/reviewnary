package com.sellerops.operationspolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.operationscase.CaseDecider;
import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.operationscase.RequiredAuthority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What a seller-declared policy may not reach</b> — asserted on the source, because four of the five
 * properties are absences and an absence has no runtime to test.
 *
 * <p>The failure mode this guards is not dramatic. Someone adds «and if the seller said to, just resolve it» to
 * make a queue look shorter, or passes a correction id into the declaration so «기존 교정을 그대로 정책으로» becomes
 * one line — and from then on the product has a rule nobody declared, closing work nobody looked at. The
 * declaration screen still says 판매자가 정한 기준, so nothing looks wrong, which is exactly why this is a fence and
 * not a code review note. {@code AnswerMemoryWriteFenceTest} is the same instrument for the same reason.
 */
class SellerOperationsPolicyFenceTest {

    private static final Path POLICY_PACKAGE = Paths.get("src/main/java/com/sellerops/operationspolicy");

    /* ─────────────────────── 1. nothing is promoted into a policy ─────────────────────── */

    /**
     * The three stores whose meaning Seller-declared Operations Policy v1 does NOT change, and which therefore
     * must be unreachable from the package that writes policies. {@code seller_guidance} in particular keeps its
     * exact meaning — «다음에도 참고» is context a judgement may be shown, never a rule.
     */
    private static final List<String> NOT_PROMOTABLE = List.of(
            "SellerGuidance", "TriageCorrection", "AnswerMemory", "SellerCorrectionState", "CorrectionDisposition");

    @Test
    @DisplayName("no correction, guidance or memory can reach the policy writer")
    void nothingIsPromotedIntoAPolicy() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources(POLICY_PACKAGE)) {
            String code = stripComments(Files.readString(source));
            for (String forbidden : NOT_PROMOTABLE) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " → " + forbidden);
                }
            }
        }
        assertThat(offenders)
                .as("a policy exists because a person chose an action, never because something else was promoted")
                .isEmpty();
    }

    /* ─────────────────────── 2. no execution, no approval, no mint ─────────────────────── */

    private static final List<String> NO_EXECUTION = List.of(
            "ReviewReplyApproval", "ReviewReplyExecution", "SubmissionRef", "ActionWindow", "Cafe24",
            "InquiryPublish", "InquiryExecution", "InquiryActionIntent");

    @Test
    @DisplayName("the policy package holds no approval, execution, mint or marketplace collaborator")
    void aPolicyCannotExecuteAnything() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources(POLICY_PACKAGE)) {
            String code = stripComments(Files.readString(source));
            for (String forbidden : NO_EXECUTION) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " → " + forbidden);
                }
            }
        }
        assertThat(offenders)
                .as("a policy names a recommendation; the send is a seller's own press and nothing here produces one")
                .isEmpty();
    }

    /* ─────────────────────── 3. no triage, no reply state, no evidence priority ─────────────────────── */

    private static final List<String> NO_GENERIC_OVERRIDE = List.of(
            "ReviewTriageRules", "ReviewTriageTier", "TriageDisplayDecision", "TRIAGE_TIER_RANK",
            "FINAL_TIER_RANK", "setReplyState", "KnowledgeAuthority", "KnowledgeSpineService");

    @Test
    @DisplayName("a policy cannot move a triage tier, a reply state, or an evidence priority")
    void aPolicyCannotOverrideTheGenericLayers() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources(POLICY_PACKAGE)) {
            String code = stripComments(Files.readString(source));
            for (String forbidden : NO_GENERIC_OVERRIDE) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " → " + forbidden);
                }
            }
        }
        assertThat(offenders)
                .as("handling only: the tier, the queue's order, the reply state and the authority ranks are not a "
                        + "policy's to move")
                .isEmpty();
    }

    /* ─────────────────────── 4. the one place a policy touches a case ─────────────────────── */

    @Test
    @DisplayName("applyPolicy is the only writer of CaseDecider.SELLER, and it writes no closing field")
    void applyPolicyIsTheOnlySeam() throws IOException {
        Path main = Paths.get("src/main/java/com/sellerops");
        List<String> writers = new ArrayList<>();
        for (Path source : sources(main)) {
            String code = stripComments(Files.readString(source));
            if (code.contains("CaseDecider.SELLER")) {
                writers.add(source.getFileName().toString());
            }
        }
        assertThat(writers)
                .as("the processor applies it; the redecider clears it; nothing else may stamp a case SELLER")
                .containsExactlyInAnyOrder("OperationsCaseProcessor.java", "OperationsPolicyRedecider.java");

        // The method body itself: one handling field and the provenance, and none of the words that end work.
        String processor = Files.readString(
                Paths.get("src/main/java/com/sellerops/operationscase/OperationsCaseProcessor.java"));
        String body = between(processor, "public boolean applyPolicy(", "\n    }");
        assertThat(body)
                .as("a rule chooses the handling; it never closes a case, resolves it, or lowers what it needs")
                .doesNotContain("AUTO_RESOLVED")
                .doesNotContain("setStatus")
                .doesNotContain("setClosedAt")
                .doesNotContain("setCloseReason")
                .doesNotContain("setDisposition")
                .doesNotContain("RequiredAuthority.AUTO");
        assertThat(body).contains("setRecommendedActionType").contains("CaseDecider.SELLER");
    }

    /* ─────────────────────── 5. the vocabulary cannot drift open ─────────────────────── */

    @Test
    @DisplayName("every action a policy may name still needs a person, derived and not hand-kept")
    void everyAllowedActionNeedsAPerson() {
        assertThat(OperationsPolicyFence.allowedActions()).isNotEmpty();
        assertThat(OperationsPolicyFence.allowedActions())
                .as("a new AUTO member of RecommendedActionType is governed the day it is added")
                .allSatisfy(action -> assertThat(action.authority()).isEqualTo(RequiredAuthority.HUMAN));
        for (RecommendedActionType action : RecommendedActionType.values()) {
            assertThat(OperationsPolicyFence.refuse(action).isEmpty())
                    .as("refuse(%s)", action)
                    .isEqualTo(action.authority() == RequiredAuthority.HUMAN);
        }
        assertThat(OperationsPolicyFence.refuse(null))
                .as("no action chosen is not an invitation to pick one")
                .isPresent();
    }

    @Test
    @DisplayName("SELLER sits between RULE and AGENT — the order the layers speak in")
    void theDeciderOrderIsTheLayerOrder() {
        assertThat(CaseDecider.values())
                .containsExactly(CaseDecider.RULE, CaseDecider.SELLER, CaseDecider.AGENT);
    }

    @Test
    @DisplayName("every refusal is a sentence the seller can read — no token, no table name")
    void refusalsAreSellerFacing() {
        for (OperationsPolicyFence.Protected property : OperationsPolicyFence.Protected.values()) {
            assertThat(property.reasonKo())
                    .as("%s", property)
                    .isNotBlank()
                    .doesNotContain("_")
                    .doesNotContain("null");
        }
    }

    /* ─────────────────────── helpers ─────────────────────── */

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    // The fence names what it forbids, so it would always fail its own checks.
                    .filter(p -> !p.getFileName().toString().equals("SellerOperationsPolicyFenceTest.java"))
                    .toList();
        }
    }

    /**
     * Strip comments before matching.
     *
     * <p>Load-bearing: this package's javadoc <em>names</em> the stores and the layers it may not reach, which is
     * most of how the reasoning is recorded. A fence that matched prose would force the explanation out of the
     * code to keep itself green, and the explanation is the more valuable half.
     */
    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String between(String code, String from, String to) {
        int start = code.indexOf(from);
        assertThat(start).as("applyPolicy must exist").isNotNegative();
        int end = code.indexOf(to, start);
        assertThat(end).as("applyPolicy must terminate").isNotNegative();
        return code.substring(start, end);
    }
}
