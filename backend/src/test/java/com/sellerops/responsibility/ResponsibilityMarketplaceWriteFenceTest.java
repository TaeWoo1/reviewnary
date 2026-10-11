package com.sellerops.responsibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.responsibility.aside.AsideRecipe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Responsibility Runtime works a seller's windows while nobody is watching. This is the list of
 * things it has no way to do</b> — asserted on the source, because every property here is an ABSENCE and
 * an absence has no runtime to test.
 *
 * <p>Why the source and not a bean: {@link com.sellerops.responsibility.aside.AsideMarketplaceGateTest}
 * already proves that the marketplace lane is refused for an organisation nobody named, and that is a
 * test of a gate — it answers 「is the lane open」. It cannot answer 「could this package send anything if
 * the lane were open」, because the honest reason it cannot is that no send path is imported anywhere in
 * it. That reason survives only as long as nobody adds a convenient import, which is what this file is.
 *
 * <p><b>The distinction this fence draws is READ vs WRITE, never «marketplace vs not».</b> A channel name
 * is not contraband here: {@code AsideRecipe.COUPANG_REVIEW_OBSERVE_V1} and
 * {@code NAVER_REVIEW_OBSERVE_V1} are the whole point of the marketplace lane, and a fence that banned
 * the word «Coupang» would be measuring the wrong thing and would have to be relaxed the first time the
 * lane grew a recipe. What may never appear is a way to SEND: an execution row, an approval, a reply
 * adapter, a channel answer client, or an HTTP client the package drives itself.
 *
 * <p><b>Scope is both halves of Package B</b> — the runtime ({@code responsibility/}) and the case
 * investigator ({@code operationscase/investigation/}), which is the LLM capability the runtime reaches.
 * The investigator is the more interesting half: it legitimately imports
 * {@code inquiry.publish.ReplyDecisionHistoryReader}, so «does it touch the publish package» is not the
 * question. Reading what a seller decided before is evidence; reaching the transport is a send.
 */
class ResponsibilityMarketplaceWriteFenceTest {

    private static final Path RUNTIME = Path.of("src/main/java/com/sellerops/responsibility");
    private static final Path INVESTIGATION =
            Path.of("src/main/java/com/sellerops/operationscase/investigation");
    /**
     * <b>The lane that actually runs unattended in an ordinary installation</b> — the seller's own standing
     * 「새 리뷰를 자동으로 확인합니다」 setting and the walk it starts.
     *
     * <p>Added 2026-10-11 (Pilot Reliability v1) because the fence above measured the wrong deployment. The
     * responsibility runtime's marketplace lane needs a deployment to name an organisation AND an account; the
     * standing lane needs neither, because its authorisation is the seller's own setting — so in an ordinary
     * install the code reading a marketplace screen every hour with nobody watching was these two packages, and
     * they were outside every fence in this file.
     */
    private static final Path AUTO_CHECK = Path.of("src/main/java/com/sellerops/autocheck");
    private static final Path CATCH_UP = Path.of("src/main/java/com/sellerops/coverage/catchup");

    /**
     * Every symbol by which something in this codebase actually sends to a marketplace.
     *
     * <p>Each name is a real type, field or method on the send path — not a keyword chosen for how it
     * sounds. A fence built from plausible-sounding words is a fence that fires on a rename and misses
     * the thing it was written for.
     */
    private static final List<String> SEND_PATH = List.of(
            // The inquiry answer lane: intent → approval → execution → verification.
            "InquiryActionIntent", "InquiryApproval", "InquiryExecution", "InquiryVerification",
            "InquiryPublishService", "InquiryPublishBindingWriter", "InquiryReplyTransport",
            "ConfirmPublishRequest",
            // The review reply lane, which is a SECOND execution lane with its own flag and approval id.
            "ReviewReplyExecution", "ReviewPublishExecutionWiring", "ReviewExecutionLane",
            // The adapters and clients that hold the actual channel write.
            "ChannelReplyAdapter", "Cafe24ReviewCommentAdapter", "Cafe24ReviewCommentClient",
            "Cafe24AnswerExecutionGrant", "CoupangInquiryReplyClient", "EsmAnswerClient",
            "NaverAnswerHttpClient",
            // A transport the package drives itself — the way round every adapter above.
            "HttpClient", "HttpRequest", "HttpURLConnection", "RestTemplate", "WebClient", "postJson",
            // The single-use live-run approval. A background loop that could present one would make the
            // approval contract a value in a config file rather than a person's decision in a turn.
            "LIVE_APPROVAL_ID", "ensureLiveWriteAllowed");

    @Test
    @DisplayName("nothing in the Responsibility Runtime can reach a marketplace write")
    void theRuntimeHoldsNoSendPath() throws IOException {
        assertNoSendPath(RUNTIME);
    }

    @Test
    @DisplayName("nothing in the case investigator can reach a marketplace write either")
    void theInvestigatorHoldsNoSendPath() throws IOException {
        assertNoSendPath(INVESTIGATION);
    }

    private static void assertNoSendPath(Path pkg) throws IOException {
        for (Path source : javaFiles(pkg)) {
            String text = code(source);
            for (String symbol : SEND_PATH) {
                assertThat(text)
                        .as("%s: this runtime prepares work and records what it found. A send from here "
                                + "would be a marketplace WRITE performed while nobody is present, on an "
                                + "approval nobody gave.", source)
                        .doesNotContain(symbol);
            }
        }
    }

    @Test
    @DisplayName("nothing in the seller's standing automatic-check lane can reach a marketplace write")
    void theStandingLaneHoldsNoSendPath() throws IOException {
        assertNoSendPath(AUTO_CHECK);
        assertNoSendPath(CATCH_UP);
    }

    /**
     * <b>A 72-hour unattended run makes the cost of every repeated question visible, and a whole-table read is
     * the one whose cost grows with the age of the installation rather than with the work.</b>
     *
     * <p>Measured rather than imagined: {@code ScheduledAsideJobService.hasLiveWork} answered «is this one desk
     * busy» by loading every job the deployment had ever handed out as entities, and it is asked on every
     * dispatch and every 지금 수집 screen poll. The standing lane alone adds a row per account per hour, so the
     * table only grows — nothing deletes from it — and a short test cannot tell the difference.
     *
     * <p>{@code channels.findAll()} is the one allowed read and the exception explains the rule: {@code channels}
     * is a fixed reference list this product ships (three rows, and the visible channel set is frozen), not a
     * ledger that one more unattended read makes longer.
     */
    @Test
    @DisplayName("nothing on the unattended path reads a growing table whole")
    void theUnattendedPathHoldsNoWholeTableRead() throws IOException {
        for (Path pkg : List.of(RUNTIME, AUTO_CHECK, CATCH_UP)) {
            for (Path source : javaFiles(pkg)) {
                String text = code(source);
                int at = text.indexOf("findAll()");
                while (at >= 0) {
                    assertThat(text.substring(Math.max(0, at - 9), at))
                            .as("%s: a loop that runs for days must not ask a question whose cost is the size "
                                    + "of a table nothing deletes from. Add a repository query scoped to what "
                                    + "is being asked about.", source)
                            .endsWith("channels.");
                    at = text.indexOf("findAll()", at + 1);
                }
            }
        }
    }

    @Test
    @DisplayName("the investigator may read what the seller decided before, and that is not a send")
    void readingTheDecisionHistoryIsStillAllowed() throws IOException {
        // The positive half of the fence above. Without it, the cheapest way to make this file pass
        // would be to delete the evidence the investigator is grounded in — so the thing that must NOT
        // be removed is named here, next to the things that must not be added.
        String text = code(INVESTIGATION.resolve("CaseInvestigationTools.java"));
        assertThat(text)
                .as("a case is judged against the seller's own answered history; losing that read would "
                        + "make the investigator guess where it used to cite")
                .contains("ReplyDecisionHistoryReader");
    }

    @Test
    @DisplayName("every aside recipe is an OBSERVE, and the enum is the whole instruction")
    void theRecipeListCannotNameAnAction() {
        for (AsideRecipe recipe : AsideRecipe.values()) {
            assertThat(recipe.name())
                    .as("%s: the helper resolves a recipe NAME to one route and reads one page. A recipe "
                            + "called ..._REPLY_V1 or ..._SUBMIT_V1 would be an unattended write with a "
                            + "name, and the allow-list is a schema CHECK — it would be admitted.", recipe)
                    .endsWith("_OBSERVE_V1");
        }
    }

    @Test
    @DisplayName("a queued aside job carries no URL, prompt, script or credential")
    void theJobCarriesNoInstruction() throws IOException {
        // The recipe name is the whole instruction (application.yml says so in as many words). A column
        // that could carry a target is a column that could carry someone else's target.
        String job = code(RUNTIME.resolve("aside/ScheduledAsideJob.java"));
        for (String field : List.of("url", "Url", "URL", "prompt", "Prompt", "script", "Script",
                "credential", "Credential", "password", "Password", "token", "Token")) {
            assertThat(job)
                    .as("ScheduledAsideJob.%s would make the job a carrier of what to do, and the "
                            + "recipe allow-list would stop deciding anything", field)
                    .doesNotContain(field);
        }
    }

    @Test
    @DisplayName("the marketplace lane is off by default and has no wildcard — three names, all required")
    void theLaneCannotBeWidenedByConfiguration() throws IOException {
        // AsideMarketplaceGateTest asserts the runtime behaviour of these; this asserts the DEFAULTS,
        // which is a different failure: a shipped build whose default was `true` would need no
        // misconfiguration to run.
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        for (String expected : List.of(
                "${SELLEROPS_RESPONSIBILITY_ASIDE_MARKETPLACE_ENABLED:false}",
                "${SELLEROPS_RESPONSIBILITY_ASIDE_MARKETPLACE_ORG_IDS:}",
                "${SELLEROPS_RESPONSIBILITY_ASIDE_MARKETPLACE_ACCOUNT_IDS:}",
                "${SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED:false}",
                "${RESPONSIBILITY_RUNTIME_ORG_IDS:}",
                // The standing lane's one deployment decision — «does this lane run here at all». A shipped
                // build whose default was true would read a seller's store on its own on first boot.
                "${SELLEROPS_REVIEW_AUTO_CHECK_ENABLED:false}")) {
            assertThat(yml).as("fail-closed default: %s", expected).contains(expected);
        }
    }

    /** One Java file with its comments removed — the ban is on doing these things, not naming them. */
    private static String code(Path source) throws IOException {
        return Files.readString(source)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static List<Path> javaFiles(Path pkg) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(pkg)) {
            walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add);
        }
        assertThat(out).as("%s holds Java sources — an empty walk would pass every assertion", pkg)
                .isNotEmpty();
        return out;
    }
}
