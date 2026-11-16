package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.dto.DraftEvidenceView;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.review.draft.ReviewDraftRecheck;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Approval and registration read a cited schedule's dates again</b> (2026-11-16 §7.57): a draft written while its date
 * was ahead is approved after the date passed — the document and the line are unchanged, and the plan they stood for is
 * history. The same rule as generation: only for a question about the plan.
 */
class InquiryDraftRecheckPastScheduleTest {

    private final UUID org = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID tableId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private final InquiryRepository inquiries = mock(InquiryRepository.class);
    private final InquiryDraftEvidenceRepository evidence = mock(InquiryDraftEvidenceRepository.class);
    private final DraftEvidenceSnippets snippets = mock(DraftEvidenceSnippets.class);
    private final OrgKnowledgeSourceRepository orgSources = mock(OrgKnowledgeSourceRepository.class);
    private final ProductKnowledgeSourceRepository productSources = mock(ProductKnowledgeSourceRepository.class);
    private final KnowledgeScheduleRepository schedules = mock(KnowledgeScheduleRepository.class);
    private final AnswerStyleService styles = mock(AnswerStyleService.class);
    private final InquiryWorkItem item = new InquiryWorkItem();
    private final OrgKnowledgeSource table = new OrgKnowledgeSource();
    private final KnowledgeSchedule line = new KnowledgeSchedule();
    private final Inquiry inquiry = new Inquiry();
    private InquiryDraftRecheck recheck;

    private static final String REPLY = "그레이 색상은 11월 25일 입고 예정이며 바뀔 수 있습니다.";

    private static String table(String date) {
        return "| 상품 | 옵션 | 상태 | 날짜 | 구분 |\n|---|---|---|---|---|\n| 면 침구 세트(퀸) | 그레이 | 재입고 대기 | " + date
                + " 입고 | 예정 |\n";
    }

    @BeforeEach
    void setUp() {
        recheck = new InquiryDraftRecheck(inquiries, evidence, snippets, orgSources, productSources, styles);
        recheck.setCurrency(null, schedules);
        item.setId(UUID.randomUUID());
        item.setOrgId(org);
        item.setInquiryId(UUID.randomUUID());
        inquiry.setOrgId(org);
        inquiry.setProductId(productId);
        inquiry.setBody("그레이 재입고 언제 되나요?");
        when(inquiries.findById(item.getInquiryId())).thenReturn(Optional.of(inquiry));
        when(styles.profileFor(org)).thenReturn(AnswerStyleProfile.defaults());
        table.setId(tableId);
        table.setOrgId(org);
        table.setTitle("입고 일정");
        table.setActive(true);
        when(orgSources.findById(tableId)).thenReturn(Optional.of(table));
        when(orgSources.findAllByOrgIdOrderByCreatedAtAsc(org)).thenReturn(List.of(table));
        line.setId(lineId);
        line.setOrgId(org);
        line.setProductId(productId);
        line.setOptionName("그레이");
        line.setKind(KnowledgeSchedule.Kind.values()[0]);
        line.setStatus(KnowledgeSchedule.Status.PLANNED);
        line.setActive(true);
        when(schedules.findById(lineId)).thenReturn(Optional.of(line));
        when(snippets.viewOf(any(InquiryDraftEvidence.class))).thenReturn(
                new DraftEvidenceView("X", "x", "x", null, null, null, "본문", false));
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(eq(item.getId()), eq(2))).thenReturn(List.of());
    }

    private void cites(String kind, UUID id, String title) {
        InquiryDraftEvidence row = new InquiryDraftEvidence();
        row.setKind(kind);
        row.setSourceId(id);
        row.setTitle(title);
        when(evidence.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(item.getId(), 1)).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("a cited document table whose dates have all passed is an expired schedule — named as such")
    void aPastTableNoLongerStands() {
        cites(InquiryDraftEvidence.KIND_ORG_POLICY, tableId, "입고 일정");
        table.setBody(table("2020-11-25"));
        InquiryDraftRecheck.Result r = recheck.check(item, 1, REPLY, "MODEL");
        // Its own code, not a changed citation (B180): the send of an approved answer drops SOURCE_CHANGED alone.
        assertThat(r.codes()).containsExactly(InquiryDraftRecheck.SCHEDULE_EXPIRED);
        assertThat(r.changed()).containsExactly("입고 일정 (날짜가 모두 지난 일정)");
        table.setBody(table("2099-11-25"));
        assertThat(recheck.check(item, 1, REPLY, "MODEL").changed()).as("a date still ahead stands").isEmpty();
    }

    @Test
    @DisplayName("a cited structured line whose date passed is changed the same way; a line with no date stands")
    void aPastLineNoLongerStands() {
        cites(InquiryDraftEvidence.KIND_SCHEDULE, lineId, "입고·출고 일정 · 그레이");
        line.setScheduleDate(LocalDate.parse("2020-11-25"));
        InquiryDraftRecheck.Result r = recheck.check(item, 1, REPLY, "MODEL");
        assertThat(r.changed()).containsExactly("입고·출고 일정 · 그레이 (날짜가 모두 지난 일정)");
        assertThat(r.codes()).containsExactly(InquiryDraftRecheck.SCHEDULE_EXPIRED);
        line.setScheduleDate(null);
        assertThat(recheck.check(item, 1, REPLY, "MODEL").changed()).isEmpty();
    }

    @Test
    @DisplayName("kept: a question about before reads the old table as it was")
    void aQuestionAboutBeforeKeepsIt() {
        cites(InquiryDraftEvidence.KIND_ORG_POLICY, tableId, "입고 일정");
        table.setBody(table("2020-11-25"));
        inquiry.setBody("지난번엔 그레이가 언제 들어왔었나요?");
        assertThat(recheck.check(item, 1, "지난번 그레이는 2020년 11월 25일 입고 예정이었습니다.", "MODEL").changed()).isEmpty();
    }
}
