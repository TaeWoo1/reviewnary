package com.sellerops.knowledge.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.attention.reply.ReviewReplyApprovalRepository;
import com.sellerops.attention.reply.ReviewReplyDraft;
import com.sellerops.attention.reply.ReviewReplyDraftRepository;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryClaimGuard;
import com.sellerops.inquiry.draft.InquiryDraftComposer;
import com.sellerops.inquiry.draft.InquiryDraftEvidence;
import com.sellerops.inquiry.draft.InquiryDraftEvidenceRepository;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.inquiry.reply.InquiryReplyDraft;
import com.sellerops.inquiry.reply.InquiryReplyDraftRepository;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The waiting drafts checked again after a policy change</b> (seller decision 2026-11-01: 「정책 변경으로 영향을 받는
 * 대기 초안은 재검증」). No model is called and nothing is written: every review draft not yet approved goes through
 * {@link ReviewDraftRecheck} — the check a sent-back reply gets — and every inquiry draft still waiting goes through
 * {@link InquiryClaimGuard} against the cited documents as they now stand, with whether each cited passage still exists.
 *
 * <p>Off unless {@code RUN_POLICY_RECHECK=true}; the database comes from the environment of the subprocess that runs it
 * ({@code tools/eval/case-eval/README.md}). The file names ids, codes and matched words only — no draft or customer text
 * — and is still written only under {@code ~/workspace/sellerops/holds/}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_POLICY_RECHECK", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyChangeRecheckIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<InquiryWorkItemPhase> WAITING =
            Set.of(InquiryWorkItemPhase.PROPOSED, InquiryWorkItemPhase.ACTION_PENDING);

    @Autowired private ReviewRepository reviews;
    @Autowired private ReviewReplyDraftRepository reviewDrafts;
    @Autowired private ReviewReplyApprovalRepository approvals;
    @Autowired private ReviewDraftRecheck recheck;
    @Autowired private InquiryWorkItemRepository workItems;
    @Autowired private InquiryRepository inquiries;
    @Autowired private InquiryReplyDraftRepository inquiryDrafts;
    @Autowired private InquiryDraftEvidenceRepository inquiryEvidence;
    @Autowired private InquiryDraftComposer inquiryComposer;
    @Autowired private OrgKnowledgeSourceRepository orgSources;
    @Autowired private ProductKnowledgeSourceRepository productSources;

    @Test
    @Transactional(readOnly = true)
    void recheck() throws Exception {
        Path out = Path.of(System.getenv("RECHECK_OUT")).toAbsolutePath().normalize();
        assertThat(out.toString()).as("results leave only for the holds folder")
                .startsWith(Path.of(System.getProperty("user.home"), "workspace", "sellerops", "holds").toString());
        UUID org = UUID.fromString(System.getenv("REPLAY_ORG"));
        List<String> rows = new ArrayList<>();

        List<UUID> drafted = reviewDrafts.findAll().stream().filter(d -> org.equals(d.getOrgId()))
                .map(ReviewReplyDraft::getReviewId).distinct().sorted().toList();
        for (UUID reviewId : drafted) {
            if (approvals.findByOrgIdAndReviewId(org, reviewId).isPresent()) {
                continue;
            }
            Review review = reviews.findById(reviewId).orElseThrow();
            ReviewReplyDraft head = reviewDrafts.findTopByReviewIdOrderByVersionDesc(reviewId).orElseThrow();
            ReviewDraftRecheck.Result result = recheck.check(org, review,
                    VocPreviewSanitizer.redactFullBody(review.getBody()).text(), head.getBody());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lane", "review");
            row.put("id", reviewId.toString().substring(0, 8));
            row.put("version", head.getVersion());
            row.put("passed", result.passed());
            row.put("codes", result.codes());
            row.put("changed", result.changed().stream().map(ReviewDraftRecheck.Changed::title).toList());
            row.put("promises", result.promises());
            rows.add(JSON.writeValueAsString(row));
        }

        for (InquiryWorkItem item : workItems.findAll().stream()
                .sorted(java.util.Comparator.comparing(InquiryWorkItem::getId)).toList()) {
            if (!org.equals(item.getOrgId()) || !WAITING.contains(item.getPhase())) {
                continue;
            }
            InquiryReplyDraft head = inquiryDrafts.findTopByWorkItemIdOrderByVersionDesc(item.getId()).orElse(null);
            if (head == null) {
                continue;
            }
            Inquiry inquiry = inquiries.findById(item.getInquiryId()).orElseThrow();
            List<InquiryDraftEvidence> cited =
                    inquiryEvidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), head.getVersion());
            List<String> current = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (InquiryDraftEvidence e : cited) {
                if (e.getSourceId() == null) {
                    continue;
                }
                if (InquiryDraftEvidence.KIND_ORG_POLICY.equals(e.getKind())) {
                    orgSources.findById(e.getSourceId()).filter(OrgKnowledgeSource::isActive).ifPresent(s -> {
                        current.add(s.getTitle() + "\n" + s.getBody());
                        titles.add(s.getTitle() + " v" + s.getVersion());
                    });
                } else if (InquiryDraftEvidence.KIND_PRODUCT_KNOWLEDGE.equals(e.getKind())) {
                    productSources.findById(e.getSourceId()).ifPresent(s -> current.add(s.getTitle() + "\n" + s.getBody()));
                }
            }
            String asked = String.join(" ", String.valueOf(inquiry.getTitle()), String.valueOf(inquiry.getBody()));
            List<InquiryClaimGuard.Violation> found =
                    InquiryClaimGuard.unsupportedClaims(head.getComments(), current, List.of(), asked);
            List<DraftEvidenceView> views = inquiryComposer.evidenceFor(org, item.getId(), head.getVersion());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lane", "inquiry");
            row.put("id", item.getId().toString().substring(0, 8));
            row.put("version", head.getVersion());
            row.put("passed", found.isEmpty() && views.stream().noneMatch(DraftEvidenceView::gone));
            row.put("violations", found.stream().map(v -> v.kind() + ":" + v.matched()).toList());
            row.put("citedPolicies", titles.stream().distinct().toList());
            row.put("goneCitations", views.stream().filter(DraftEvidenceView::gone).map(DraftEvidenceView::title).toList());
            rows.add(JSON.writeValueAsString(row));
        }
        Files.write(out, rows, StandardCharsets.UTF_8);
    }
}
