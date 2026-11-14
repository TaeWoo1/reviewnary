package com.sellerops.operations.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.attention.reply.ReviewReplyApproval;
import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.reply.ReviewReplyApprovalState;
import com.sellerops.auth.JwtTokenProvider;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.common.DeliveryMode;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.publish.InquiryExecution;
import com.sellerops.inquiry.publish.InquiryExecutionRepository;
import com.sellerops.inquiry.publish.InquiryPublishBindingWriter;
import com.sellerops.inquiry.queue.InquiryQueueService;
import com.sellerops.inquiry.reply.InquiryReplyDraft;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheck;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository;
import com.sellerops.inquiry.sellercheck.InquirySellerCheckService;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.operationscase.CustomerOperationsHomeService;
import com.sellerops.operationscase.CaseDecider;
import com.sellerops.operationscase.CaseDisposition;
import com.sellerops.operationscase.CasePreparedAction;
import com.sellerops.operationscase.CasePriority;
import com.sellerops.operationscase.CaseReason;
import com.sellerops.operationscase.OperationsCase;
import com.sellerops.operationscase.OperationsCaseKind;
import com.sellerops.operationscase.OperationsCaseRepository;
import com.sellerops.operationscase.OperationsCaseStatus;
import com.sellerops.operationscase.OperationsSubjectKind;
import com.sellerops.operationscase.RequiredAuthority;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import com.sellerops.responsibility.Responsibility;
import com.sellerops.responsibility.ResponsibilityRepository;
import com.sellerops.responsibility.ResponsibilityRollout;
import com.sellerops.responsibility.ResponsibilityRun;
import com.sellerops.responsibility.ResponsibilityRunRepository;
import com.sellerops.responsibility.ResponsibilityStatus;
import com.sellerops.responsibility.ResponsibilityTemplate;
import com.sellerops.responsibility.RunStatus;
import com.sellerops.responsibility.RunTrigger;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Disposable-Postgres proof of the Home's read API (2026-11-09, demo handoff §7.12) — the real endpoints, the real
 * reads, Flyway's real schema (V187 included). Every row is synthetic: no customer text, no channel, no model.
 *
 * <p>What it shows, against the defects the screen merge had: 8 open cases are 8 (the Home said 5+); 60 open
 * inquiries are all listed and the ten longest-waiting are not cut off; the server sorts the whole set and its pages
 * hold every row once, even when a new inquiry lands between two pages; a failed source withholds its counts rather
 * than reading as zero; a 20-day-old record whose check day is today is today's work; and an execution's route —
 * real, mock or unknown — is the one its row recorded.
 *
 * <p><b>Opt-in only</b> ({@code SELLEROPS_PG_PROOF=1}, {@code SELLEROPS_PG_URL} at a throwaway database).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class HomeBoardPostgresProofIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("SELLEROPS_PG_URL", "jdbc:postgresql://localhost:55432/sellerops");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("sellerops.seed.enabled", () -> "false");
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired ChannelRepository channels;
    @Autowired SellerAccountRepository accounts;
    @Autowired InquiryRepository inquiries;
    @Autowired InquiryWorkItemRepository workItems;
    @Autowired InquiryReplyDraftRepository inquiryDrafts;
    @Autowired InquirySellerCheckRepository sellerChecks;
    @Autowired InquirySellerCheckService sellerCheckService;
    @Autowired InquiryPublishBindingWriter binding;
    @Autowired InquiryExecutionRepository executions;
    @Autowired ReviewRepository reviews;
    @Autowired ReviewReplyApprovalRepository reviewApprovals;
    @Autowired ResponsibilityRepository responsibilities;
    @Autowired ResponsibilityRunRepository runs;
    @Autowired OperationsCaseRepository cases;
    @SpyBean ResponsibilityRollout rollout;
    @SpyBean InquiryQueueService queue;

    private final Instant now = Instant.now();
    private UUID org;
    private Channel channel;
    private SellerAccount account;
    private String bearer;
    private int runSeq;

    @BeforeEach
    void seed() {
        doReturn(true).when(rollout).allows(any());
        Organization o = new Organization();
        o.setName("hb-" + UUID.randomUUID().toString().substring(0, 8));
        org = organizations.saveAndFlush(o).getId();
        bearer = "Bearer " + tokens.createToken(UUID.randomUUID(), org, "hb@example.test");
        channel = channels.findByCode("HB-PROOF").orElseGet(() -> {
            Channel c = new Channel();
            c.setCode("HB-PROOF");
            c.setNameKo("HB-PROOF");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSortOrder(0);
            return channels.saveAndFlush(c);
        });
        SellerAccount a = new SellerAccount();
        a.setOrgId(org);
        a.setChannelId(channel.getId());
        a.setConnectionStatus(ChannelStatus.CONNECTED);
        account = accounts.saveAndFlush(a);
    }

    @AfterEach
    void unspy() {
        reset(queue);
    }

    // ---- the proofs ----

    @Test
    void eightCasesAndSixtyInquiriesAreCountedAndListedWhole() throws Exception {
        List<UUID> caseReviews = seedReviewCases(8);
        List<UUID> recent = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            recent.add(seedInquiry(now.minus(Duration.ofHours(3L * i + 1)), InquiryWorkItemPhase.OPEN).getInquiryId());
        }
        for (int i = 0; i < 3; i++) {
            seedInquiry(now.minus(Duration.ofDays(20 + i)), InquiryWorkItemPhase.OPEN);
        }
        UUID dueInquiry = seedDueFollowUp(now.minus(Duration.ofDays(20)));

        // What the screen merge received: five cases, the newest fifty inquiries.
        JsonNode legacyCases = read("/api/responsibilities/customer-operations/home");
        assertThat(legacyCases.at("/decisions/total").asInt()).isEqualTo(8);
        assertThat(legacyCases.at("/decisions/rows").size()).as("the old Home's case slice").isEqualTo(5);
        JsonNode legacyQueue = read("/api/inquiries?size=50");
        assertThat(legacyQueue.at("/content").size()).as("the old Home's inquiry slice").isEqualTo(50);

        JsonNode board = read("/api/operations/home-board");
        keep("it-69-home-board", board);
        keep("it-69-work-page1", read("/api/operations/work?size=20"));
        keep("it-69-work-longest-page1", read("/api/operations/work?size=20&sort=longest"));
        assertThat(board.at("/complete").asBoolean()).isTrue();
        assertThat(board.at("/todo/total/count").asInt()).isEqualTo(8 + 60 + 1);
        assertThat(board.at("/todo/needsLook/count").asInt()).isEqualTo(8);
        assertThat(board.at("/todo/needsReply/count").asInt()).isEqualTo(60);
        assertThat(board.at("/todo/checkAgain/count").asInt())
                .as("a 20-day-old record whose check day is today is today's work").isEqualTo(1);
        assertThat(board.at("/past/total/count").asInt()).isEqualTo(3);
        int sum = 0;
        for (String b : List.of("safety", "approvable", "needsInfo", "needsReply", "needsLook", "checkAgain")) {
            sum += board.at("/todo/" + b + "/count").asInt();
        }
        assertThat(sum).as("the buckets partition the total").isEqualTo(69);

        List<JsonNode> all = pages("/api/operations/work?bucket=all&sort=importance&size=20");
        List<String> keys = all.stream().map(r -> r.get("key").asText()).toList();
        assertThat(keys).hasSize(69).doesNotHaveDuplicates();
        assertThat(keys.stream().filter(k -> k.startsWith("case:"))).as("all eight cases, not five").hasSize(8);
        Set<String> listed = new HashSet<>(all.stream().map(r -> r.get("subjectId").asText()).toList());
        assertThat(listed).as("the ten longest-waiting of the sixty are listed too")
                .containsAll(recent.stream().map(UUID::toString).toList());
        assertThat(all.subList(0, 61)).allSatisfy(r -> assertThat(r.get("subject").asText()).isEqualTo("INQUIRY"));
        assertThat(all.subList(61, 69)).allSatisfy(r -> assertThat(r.get("kind").asText()).isEqualTo("CASE"));
        List<Instant> inquiryTimes = all.subList(0, 61).stream().map(r -> Instant.parse(r.get("since").asText()))
                .toList();
        assertThat(inquiryTimes).as("inquiries newest first (owner, 2026-11-09)")
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(all.get(60).get("subjectId").asText()).as("the due check's record is 20 days old")
                .isEqualTo(dueInquiry.toString());

        List<JsonNode> longest = pages("/api/operations/work?bucket=all&sort=longest&size=25");
        assertThat(longest).hasSize(69);
        assertThat(longest.get(0).get("subjectId").asText()).isEqualTo(dueInquiry.toString());

        List<JsonNode> past = pages("/api/operations/work?bucket=past&size=50");
        assertThat(past).hasSize(3).allSatisfy(r -> assertThat(r.get("past").asBoolean()).isTrue());

        JsonNode other = read("/api/operations/home-board", "Bearer " + tokens.createToken(UUID.randomUUID(),
                otherOrg(), "x@example.test"));
        assertThat(other.at("/todo/total/count").asInt()).as("another org sees none of it").isZero();
    }

    @Test
    void aPageBoundaryHoldsWhenANewInquiryArrivesBetweenPages() throws Exception {
        for (int i = 0; i < 30; i++) {
            seedInquiry(now.minus(Duration.ofHours(i + 1)), InquiryWorkItemPhase.OPEN);
        }
        JsonNode first = read("/api/operations/work?size=10");
        List<String> seen = new ArrayList<>(first.get("rows").findValuesAsText("key"));
        seedInquiry(now, InquiryWorkItemPhase.OPEN); // newer than everything: sorts before the cursor
        String cursor = first.get("nextCursor").asText();
        while (cursor != null && !cursor.isEmpty()) {
            JsonNode p = read("/api/operations/work?size=10&cursor=" + cursor);
            seen.addAll(p.get("rows").findValuesAsText("key"));
            cursor = p.get("nextCursor").isNull() ? null : p.get("nextCursor").asText();
        }
        assertThat(seen).as("the original thirty, each once").hasSize(30).doesNotHaveDuplicates();
    }

    @Test
    void aHundredAndFiftyInquiriesWithOneCreationTimeAreEachCountedOnce() throws Exception {
        // A sync writes many work items at once. Ordered by creation time alone, an offset page walk over equal times
        // may hand one row to two pages and none to another; the Home now reads them in one page (2026-11-10 review).
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            seeded.add(seedInquiry(now.minus(Duration.ofMinutes(i + 1)), InquiryWorkItemPhase.OPEN).getInquiryId());
        }
        jdbc.update("update inquiry_work_item set created_at = ? where org_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofHours(1))), org);

        JsonNode board = read("/api/operations/home-board");
        assertThat(board.at("/complete").asBoolean()).isTrue();
        assertThat(board.at("/todo/total/count").asInt()).isEqualTo(150);
        List<JsonNode> all = pages("/api/operations/work?size=40");
        assertThat(all.stream().map(r -> r.get("subjectId").asText()).toList())
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(seeded.stream().map(UUID::toString)
                        .toList());
    }

    @Test
    void anOldInquiryStillTheSellersKeepsTheCheckDueToday() throws Exception {
        // 2026-11-10 review: a record of 「접수·진행 중」 can be kept while the reply is still the seller's to write. On its
        // check day the follow-up read (registered or completed items only) did not see it, and the queue row — the
        // customer's own 20-day-old time — was sent to 지난 일. One row stands for the inquiry; the check rides on it.
        InquiryWorkItem w = seedInquiry(now.minus(Duration.ofDays(20)), InquiryWorkItemPhase.OPEN);
        var check = sellerCheckService.record(org, w.getId(), "IN_PROGRESS", "합성-진행 기록", "SELLER:hb", null,
                sellerCheckService.today());
        // Beside it: two fresh inquiries, and a check due today on a reply already registered — its own row, as before.
        seedInquiry(now.minus(Duration.ofHours(2)), InquiryWorkItemPhase.OPEN);
        seedInquiry(now.minus(Duration.ofHours(5)), InquiryWorkItemPhase.OPEN);
        UUID alone = seedDueFollowUp(now.minus(Duration.ofDays(3)));

        JsonNode board = read("/api/operations/home-board");
        keep("it-due-before-reply-home-board", board);
        assertThat(board.at("/complete").asBoolean()).isTrue();
        assertThat(board.at("/todo/total/count").asInt()).as("the old inquiry is today's work, not 지난 일")
                .isEqualTo(4);
        assertThat(board.at("/past/total/count").asInt()).isZero();
        assertThat(board.at("/todo/needsReply/count").asInt()).as("the reply is the row's own work").isEqualTo(3);
        assertThat(board.at("/todo/checkAgain/count").asInt()).as("only the check standing alone; one item, once")
                .isEqualTo(1);
        assertThat(board.at("/todo/alsoDueToday/count").asInt()).as("the check riding on a reply, beside the buckets")
                .isEqualTo(1);

        JsonNode page = read("/api/operations/work?size=20");
        keep("it-due-before-reply-work", page);
        assertThat(page.get("rows")).hasSize(4);
        assertThat(page.get("rows").get(2).get("subjectId").asText()).isEqualTo(alone.toString());
        JsonNode row = page.get("rows").get(3);
        assertThat(row.get("subjectId").asText()).as("the order is the existing one: newest first, so it is last")
                .isEqualTo(w.getInquiryId().toString());
        assertThat(row.get("past").asBoolean()).isFalse();
        assertThat(row.get("dueToday").asBoolean()).isTrue();
        assertThat(row.get("workItemId").asText()).isEqualTo(w.getId().toString());
        assertThat(row.get("also")).hasSize(1);
        JsonNode due = row.get("also").get(0);
        assertThat(due.get("action").asText()).isEqualTo("CHECK_AGAIN");
        assertThat(due.get("workItemId").asText()).isEqualTo(w.getId().toString());
        assertThat(due.get("checkId").asText()).isEqualTo(check.id().toString());
        assertThat(due.get("nextCheckOn").asText()).isEqualTo(sellerCheckService.today().toString());
        assertThat(due.get("dueToday").asBoolean()).isTrue();
        assertThat(read("/api/operations/work?bucket=past&size=20").get("rows")).isEmpty();
        assertThat(board.at("/todo/todayChecks/count").asInt()).as("the check alone and the one riding").isEqualTo(2);
        JsonNode today = read("/api/operations/work?bucket=todayChecks&size=20");
        keep("it-due-before-reply-today-checks", today);
        assertThat(today.at("/total/count").asInt()).isEqualTo(2);
        assertThat(today.get("rows").findValuesAsText("subjectId"))
                .containsExactlyInAnyOrder(alone.toString(), w.getInquiryId().toString());
        assertThat(due.get("to").asText()).isEqualTo("/inquiries/" + w.getInquiryId());
    }

    @Test
    void autoResolvedCasesAloneReachingTheScanMarkTheCaseReadIncomplete() throws Exception {
        // 2026-11-10 review: 200 cases auto-resolved in the last day, and only three open ones. The open scan is far from
        // full, but a 201st auto-resolved case — whose raw review would come back as work — may exist past the scan.
        seedReviewCases(3);
        seedReviewCases(CustomerOperationsHomeService.SCAN, CaseDisposition.AUTO_RESOLVED);

        JsonNode board = read("/api/operations/home-board");
        keep("it-auto-resolved-cap-home-board", board);
        JsonNode casesRead = null;
        for (JsonNode s : board.get("sources")) {
            if ("CASES".equals(s.get("name").asText())) {
                casesRead = s;
            }
        }
        assertThat(casesRead).isNotNull();
        assertThat(casesRead.get("read").asBoolean()).as("the cases were read").isTrue();
        assertThat(casesRead.get("complete").asBoolean()).as("but not all of them").isFalse();
        assertThat(board.at("/complete").asBoolean()).isFalse();
        assertThat(board.at("/todo/total/count").isNull()).as("no exact total").isTrue();
        assertThat(board.at("/todo/total/atLeast").asInt()).as("and no floor: 「—」, not 「3+」").isZero();
        assertThat(board.at("/todo/needsLook/count").isNull()).isTrue();
        assertThat(board.at("/past/total/count").isNull()).isTrue();
        JsonNode page = read("/api/operations/work?size=50");
        keep("it-auto-resolved-cap-work", page);
        assertThat(page.at("/total/count").isNull()).isTrue();
        assertThat(page.at("/total/atLeast").asInt()).isZero();
        assertThat(page.get("rows")).as("the three open cases are still listed").hasSize(3);
    }

    @Test
    void aHandledCaseDoesNotHideTheSellersCheckDueToday() throws Exception {
        // 2026-11-10: one synthetic scene. A case about an inquiry closed itself today (AUTO_RESOLVED), and the seller's
        // own 「접수·진행 중」 record on that inquiry's registered reply is due today. The case settles the inquiry; it does
        // not settle a check the seller set for themselves.
        UUID inquiry = seedDueFollowUp(now.minus(Duration.ofDays(2)));
        seedInquiryCase(inquiry, CaseDisposition.AUTO_RESOLVED);

        JsonNode board = read("/api/operations/home-board");
        keep("it-handled-case-check-home-board", board);
        assertThat(board.at("/complete").asBoolean()).isTrue();
        assertThat(board.at("/todo/total/count").asInt()).as("the check is today's work").isEqualTo(1);
        assertThat(board.at("/todo/checkAgain/count").asInt()).isEqualTo(1);
        assertThat(board.at("/todo/todayChecks/count").asInt()).isEqualTo(1);
        JsonNode handledPage = read("/api/operations/work?size=20");
        keep("it-handled-case-check-work", handledPage);
        JsonNode rows = handledPage.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("key").asText()).as("the check alone, not the inquiry the case settled")
                .startsWith("follow:");
        assertThat(rows.get(0).get("subjectId").asText()).isEqualTo(inquiry.toString());
    }

    @Test
    void aFailedSourceIsWithheldNotZero() throws Exception {
        seedReviewCases(3);
        for (int i = 0; i < 4; i++) {
            seedInquiry(now.minus(Duration.ofHours(i + 1)), InquiryWorkItemPhase.OPEN);
        }
        doThrow(new IllegalStateException("synthetic outage")).when(queue)
                .queueUpTo(any(), ArgumentMatchers.<Set<InquiryWorkItemPhase>>any(), anyInt());

        JsonNode board = read("/api/operations/home-board");
        keep("it-partial-home-board", board);
        assertThat(board.at("/complete").asBoolean()).isFalse();
        assertThat(board.at("/todo/total/count").isNull()).as("no total when inquiries were not read").isTrue();
        assertThat(board.at("/todo/total/atLeast").asInt()).isEqualTo(3);
        assertThat(board.at("/todo/needsReply/count").isNull()).isTrue();
        // 판단 필요 reads every source since 2026-11-14 (HomeBoardController.dependsOn): an inquiry's held draft lands
        // there too, so with inquiries unread it is no exact count — but reviews and cases were read, so 3 is a floor.
        assertThat(board.at("/todo/needsLook/count").isNull()).as("no exact count when inquiries were not read").isTrue();
        assertThat(board.at("/todo/needsLook/atLeast").asInt()).as("reviews and cases were read").isEqualTo(3);
        JsonNode page = read("/api/operations/work?size=50");
        keep("it-partial-work", page);
        assertThat(page.at("/total/count").isNull()).isTrue();
        assertThat(page.get("rows").size()).isEqualTo(3);
        boolean inquiriesUnread = false;
        for (JsonNode s : board.get("sources")) {
            if ("INQUIRIES".equals(s.get("name").asText())) {
                inquiriesUnread = !s.get("read").asBoolean();
            }
        }
        assertThat(inquiriesUnread).isTrue();
    }

    @Test
    void eachExecutionKeepsTheRouteItRecorded() throws Exception {
        InquiryExecution simulated = approve(DeliveryMode.SIMULATED);
        InquiryExecution real = approve(DeliveryMode.REAL);
        InquiryExecution unknown = approve(null); // the pre-V187 overload: nothing recorded

        assertThat(mode(simulated.getId())).isEqualTo("SIMULATED");
        assertThat(mode(real.getId())).isEqualTo("REAL");
        assertThat(mode(unknown.getId())).isEqualTo("UNKNOWN");

        // Written once: an update through the entity does not move it.
        InquiryExecution loaded = executions.findById(simulated.getId()).orElseThrow();
        loaded.setDeliveryMode(DeliveryMode.REAL);
        executions.saveAndFlush(loaded);
        assertThat(mode(simulated.getId())).isEqualTo("SIMULATED");
        // And the column refuses a value outside the three.
        assertThatThrownBy(() -> jdbc.update(
                "update inquiry_execution set delivery_mode = 'FAKE' where id = ?", simulated.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select column_default from information_schema.columns where table_name = "
                + "'inquiry_execution' and column_name = 'delivery_mode'", String.class))
                .as("a row written before V187 reads UNKNOWN").contains("UNKNOWN");

        Review review = seedReview(now.minus(Duration.ofHours(2)));
        ReviewReplyApproval approval = new ReviewReplyApproval();
        approval.setOrgId(org);
        approval.setReviewId(review.getId());
        approval.setState(ReviewReplyApprovalState.APPROVED);
        approval.setApprovedVersion(1);
        approval.setApprovedFingerprint("a".repeat(64));
        approval.setDecidedBy("SELLER:" + UUID.randomUUID());
        approval.setDecidedAt(now);
        reviewApprovals.saveAndFlush(approval);

        JsonNode activity = read("/api/operations/activity?since=" + now.minus(Duration.ofMinutes(5)));
        List<String> modes = new ArrayList<>();
        boolean reviewApproved = false;
        for (JsonNode a : activity.get("rows")) {
            if ("INQUIRY_APPROVED".equals(a.get("kind").asText())) {
                assertThat(a.get("result").asText()).isEqualTo("REGISTRATION_REQUESTED");
                modes.add(a.get("deliveryMode").asText());
            }
            if ("REVIEW_REPLY_APPROVED".equals(a.get("kind").asText())) {
                reviewApproved = true;
                assertThat(a.get("result").asText()).as("확정 is not a registration").isEqualTo("APPROVED_NOT_REGISTERED");
                assertThat(a.at("/subject/title").asText()).doesNotContain("@");
            }
        }
        assertThat(modes).containsExactlyInAnyOrder("SIMULATED", "REAL", "UNKNOWN");
        assertThat(reviewApproved).isTrue();
    }

    // ---- seeds (all synthetic) ----

    private JsonNode read(String path) throws Exception {
        return read(path, bearer);
    }

    private JsonNode read(String path, String auth) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", auth)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** Keeps one response for the mockup when {@code SELLEROPS_HOME_BOARD_DUMP} names a folder. Synthetic rows only. */
    private void keep(String name, JsonNode body) throws Exception {
        String dir = System.getenv("SELLEROPS_HOME_BOARD_DUMP");
        if (dir != null && !dir.isBlank()) {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of(dir, name + ".json").toFile(), body);
        }
    }

    private List<JsonNode> pages(String first) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        String path = first;
        for (int guard = 0; guard < 50; guard++) {
            JsonNode p = read(path);
            p.get("rows").forEach(out::add);
            if (p.get("nextCursor").isNull()) {
                return out;
            }
            path = first + "&cursor=" + p.get("nextCursor").asText();
        }
        throw new AssertionError("paging did not end");
    }

    private String mode(UUID executionId) {
        return jdbc.queryForObject("select delivery_mode from inquiry_execution where id = ?", String.class,
                executionId);
    }

    private UUID otherOrg() {
        Organization o = new Organization();
        o.setName("hb-other-" + UUID.randomUUID().toString().substring(0, 8));
        return organizations.saveAndFlush(o).getId();
    }

    private InquiryWorkItem seedInquiry(Instant receivedAt, InquiryWorkItemPhase phase) {
        Inquiry q = new Inquiry();
        q.setOrgId(org);
        q.setChannelId(channel.getId());
        q.setSellerAccountId(account.getId());
        q.setExternalId("hb-" + UUID.randomUUID());
        q.setTitle("합성 문의 " + receivedAt.getEpochSecond());
        q.setBody("합성-문의-본문");
        q.setStatus("UNANSWERED");
        q.setReceivedAt(receivedAt);
        q = inquiries.saveAndFlush(q);
        InquiryWorkItem w = new InquiryWorkItem();
        w.setOrgId(org);
        w.setInquiryId(q.getId());
        w.setSellerAccountId(account.getId());
        w.setChannelId(channel.getId());
        w.setPhase(phase);
        return workItems.saveAndFlush(w);
    }

    private UUID seedDueFollowUp(Instant recordedAt) {
        InquiryWorkItem w = seedInquiry(recordedAt.minus(Duration.ofDays(1)), InquiryWorkItemPhase.COMPLETED);
        InquirySellerCheck c = new InquirySellerCheck();
        c.setId(UUID.randomUUID());
        c.setOrgId(org);
        c.setWorkItemId(w.getId());
        c.setOutcome("IN_PROGRESS");
        c.setNote("합성-진행 기록");
        c.setConfirmedBy("SELLER");
        c.setRecordedBy("SELLER:" + UUID.randomUUID());
        c.setRecordedAt(recordedAt);
        c.setNextCheckOn(sellerCheckService.today());
        sellerChecks.saveAndFlush(c);
        return w.getInquiryId();
    }

    private Review seedReview(Instant receivedAt) {
        Review r = new Review();
        r.setOrgId(org);
        r.setChannelId(channel.getId());
        r.setRating(1);
        r.setBody("합성-리뷰-본문 " + receivedAt.getEpochSecond());
        r.setNegative(true);
        r.setReplyState(ReviewReplyState.PENDING);
        r.setReceivedAt(receivedAt);
        return reviews.saveAndFlush(r);
    }

    private List<UUID> seedReviewCases(int n) {
        return seedReviewCases(n, CaseDisposition.NEEDS_DECISION);
    }

    private List<UUID> seedReviewCases(int n, CaseDisposition disposition) {
        Responsibility resp = responsibilities.findByOrgIdAndTemplateCode(org,
                ResponsibilityTemplate.CUSTOMER_OPERATIONS_V1).orElseGet(() -> {
                    Responsibility r = new Responsibility();
                    r.setOrgId(org);
                    r.setTemplateCode(ResponsibilityTemplate.CUSTOMER_OPERATIONS_V1);
                    r.setTemplateVersion(1);
                    r.setStatus(ResponsibilityStatus.ACTIVE);
                    return responsibilities.saveAndFlush(r);
                });
        ResponsibilityRun run = new ResponsibilityRun();
        run.setOrgId(org);
        run.setResponsibilityId(resp.getId());
        run.setTemplateVersion(1);
        int seq = runSeq++; // one run per seeding call; the window is unique per responsibility
        run.setWindowStart(now.minus(Duration.ofHours(2)).minus(Duration.ofMinutes(seq)));
        run.setWindowEnd(now.minus(Duration.ofHours(1)).minus(Duration.ofMinutes(seq)));
        run.setRunTrigger(RunTrigger.SCHEDULED);
        run.setStatus(RunStatus.SUCCESS);
        run.setAttempt(1);
        run = runs.saveAndFlush(run);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Review review = seedReview(now.minus(Duration.ofHours(i + 1)));
            OperationsCase c = new OperationsCase();
            c.setOrgId(org);
            c.setResponsibilityId(resp.getId());
            c.setOriginRunId(run.getId());
            c.setLastRunId(run.getId());
            c.setCaseKind(OperationsCaseKind.CUSTOMER_WORK);
            c.setSubjectKind(OperationsSubjectKind.REVIEW);
            c.setSubjectId(review.getId());
            c.setSignature("hb-" + review.getId());
            c.setSourceState("{}");
            c.setStatus(OperationsCaseStatus.PREPARED);
            c.setPriority(CasePriority.HIGH);
            c.setReason(CaseReason.REVIEW_NEEDS_ATTENTION);
            c.setReasonNote(CaseReason.REVIEW_NEEDS_ATTENTION.noteKo());
            c.setDisposition(disposition);
            if (disposition == CaseDisposition.AUTO_RESOLVED) {
                // As the processor closes one (OperationsCaseProcessor.markClosed): not open, so not in the open scan.
                c.setStatus(OperationsCaseStatus.CLOSED);
                c.setResolutionReason(com.sellerops.operationscase.CaseResolution.RULE_NO_ACTION);
                c.setClosedAt(now);
                c.setPriority(CasePriority.NORMAL);
                c.setReason(CaseReason.REVIEW_NEEDS_ATTENTION);
            }
            c.setRequiredAuthority(disposition == CaseDisposition.AUTO_RESOLVED ? RequiredAuthority.AUTO
                    : RequiredAuthority.HUMAN);
            c.setPreparedAction(CasePreparedAction.RECOMMENDATION_ONLY);
            c.setDecidedBy(CaseDecider.RULE);
            cases.saveAndFlush(c);
            ids.add(review.getId());
        }
        return ids;
    }

    private void seedInquiryCase(UUID inquiryId, CaseDisposition disposition) {
        seedReviewCases(0); // the responsibility, if this org has none yet
        Responsibility resp = responsibilities.findByOrgIdAndTemplateCode(org,
                ResponsibilityTemplate.CUSTOMER_OPERATIONS_V1).orElseThrow();
        UUID run = jdbc.queryForObject("select id from responsibility_run where responsibility_id = ? limit 1",
                UUID.class, resp.getId());
        OperationsCase c = new OperationsCase();
        c.setOrgId(org);
        c.setResponsibilityId(resp.getId());
        c.setOriginRunId(run);
        c.setLastRunId(run);
        c.setCaseKind(OperationsCaseKind.CUSTOMER_WORK);
        c.setSubjectKind(OperationsSubjectKind.INQUIRY);
        c.setSubjectId(inquiryId);
        c.setSignature("hb-" + inquiryId);
        c.setSourceState("{}");
        c.setStatus(disposition == CaseDisposition.AUTO_RESOLVED ? OperationsCaseStatus.CLOSED
                : OperationsCaseStatus.PREPARED);
        if (disposition == CaseDisposition.AUTO_RESOLVED) {
            c.setResolutionReason(com.sellerops.operationscase.CaseResolution.RULE_NO_ACTION);
            c.setClosedAt(now);
        }
        c.setPriority(CasePriority.NORMAL);
        c.setReason(CaseReason.INQUIRY_ALREADY_ANSWERED);
        c.setReasonNote(CaseReason.INQUIRY_ALREADY_ANSWERED.noteKo());
        c.setDisposition(disposition);
        c.setRequiredAuthority(RequiredAuthority.AUTO);
        c.setPreparedAction(CasePreparedAction.RECOMMENDATION_ONLY);
        c.setDecidedBy(CaseDecider.RULE);
        cases.saveAndFlush(c);
    }

    private InquiryExecution approve(DeliveryMode mode) {
        InquiryWorkItem w = seedInquiry(now.minus(Duration.ofHours(1)), InquiryWorkItemPhase.PROPOSED);
        InquiryReplyDraft d = new InquiryReplyDraft();
        d.setOrgId(org);
        d.setWorkItemId(w.getId());
        d.setVersion(1);
        d.setAnswerStatus(1);
        d.setTitle("합성 답변");
        d.setComments("합성-답변-본문");
        d.setContentFingerprint(UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        d.setFingerprintAlgorithm("SHA-256");
        d.setCreatedBy("SELLER:" + UUID.randomUUID());
        d = inquiryDrafts.saveAndFlush(d);
        InquiryPublishBindingWriter.ApprovalTarget target = new InquiryPublishBindingWriter.ApprovalTarget(
                account.getId(), channel.getId(), "hb-ext-" + UUID.randomUUID(), null);
        String command = UUID.randomUUID().toString();
        return mode == null ? binding.bind(w, d, target, command, "SELLER:x")
                : binding.bind(w, d, target, command, "SELLER:x", mode);
    }
}
