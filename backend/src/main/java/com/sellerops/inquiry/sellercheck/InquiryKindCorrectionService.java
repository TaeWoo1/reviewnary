package com.sellerops.inquiry.sellercheck;

import com.sellerops.common.ApiException;
import com.sellerops.inquiry.decision.InquiryKind;
import com.sellerops.inquiry.workitem.InquiryWorkItem;
import com.sellerops.inquiry.workitem.InquiryWorkItemPhase;
import com.sellerops.inquiry.workitem.InquiryWorkItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「판단 고치기」 (2026-11-05 seventh review) — the seller says what kind of inquiry this is: 방법 안내, 상태 확인 or 조치
 * 요청. The service's reading and the seller's are both kept; the reply is then drafted again under the seller's
 * ({@link InquiryKind#apply}), through the same production path and checks as any draft.
 *
 * <p>Only while the reply is the seller's to write: an approved answer stays as it was approved, and a reply taken back
 * after approval is open again and may be corrected then.
 */
@Service
public class InquiryKindCorrectionService {

    public static final String STALE = "다른 곳에서 판단을 먼저 고쳤어요. 새로 불러온 판단을 확인한 뒤 다시 골라 주세요.";
    public static final String DECIDED = "승인한 답변은 그대로 둬요. 판단은 답변을 다시 만들 수 있을 때 고칠 수 있어요.";
    public static final String PICK = "이 문의가 어떤 문의인지 하나 골라 주세요.";

    /**
     * {@code judged}: the service's own reading (null before a decided draft); {@code corrected}: the seller's, null when
     * none stands; {@code correctionId}: the standing correction a new one must name; {@code draftMatches}: whether the
     * newest answer was written after it (null with no correction or no answer) — an answer written under the reading
     * the seller corrected is not approved (2026-11-05 real-screen check).
     */
    public record KindView(String judged, String judgedKo, String corrected, String correctedKo, UUID correctionId,
                           Boolean draftMatches) {
        static KindView of(String judged, InquiryKindCorrection c, Boolean draftMatches) {
            InquiryKind j = InquiryKind.parse(judged);
            InquiryKind k = c == null ? null : InquiryKind.parse(c.getKind());
            return new KindView(j == null ? null : j.name(), j == null ? null : j.labelKo(),
                    k == null ? null : k.name(), k == null ? null : k.labelKo(), c == null ? null : c.getId(),
                    draftMatches);
        }

        /** The kind the reply is drafted under now. */
        public String effective() {
            return corrected != null ? corrected : judged;
        }
    }

    private final EntityManager em;
    private final InquiryKindCorrectionRepository corrections;
    private final InquiryWorkItemRepository workItems;
    private com.sellerops.inquiry.reply.InquiryReplyDraftRepository drafts;

    public InquiryKindCorrectionService(EntityManager em, InquiryKindCorrectionRepository corrections,
                                        InquiryWorkItemRepository workItems) {
        this.em = em;
        this.corrections = corrections;
        this.workItems = workItems;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDrafts(com.sellerops.inquiry.reply.InquiryReplyDraftRepository drafts) {
        this.drafts = drafts;
    }

    @Transactional(readOnly = true)
    public Optional<KindView> view(UUID orgId, UUID workItemId) {
        Optional<InquiryWorkItem> item = workItems.findById(workItemId).filter(w -> orgId.equals(w.getOrgId()));
        if (item.isEmpty()) {
            return Optional.empty();
        }
        InquiryKindCorrection standing = standing(orgId, workItemId).orElse(null);
        if (item.get().getJudgedKind() == null && standing == null) {
            return Optional.empty();
        }
        return Optional.of(KindView.of(item.get().getJudgedKind(), standing, writtenUnder(workItemId, standing)));
    }

    /**
     * Whether the newest answer was written under {@code standing}: the correction it names is that one (2026-11-05
     * eighth review). Null when there is no correction or no answer. Never a save time — an older answer kept open on
     * another device and saved after the correction names the correction it was written under, and that is not this one.
     */
    public Boolean writtenUnder(UUID workItemId, InquiryKindCorrection standing) {
        if (standing == null || drafts == null) {
            return null;
        }
        return drafts.findTopByWorkItemIdOrderByVersionDesc(workItemId)
                .map(d -> standing.getId().equals(d.getKindCorrectionId()))
                .orElse(null);
    }

    /** The standing correction, or empty — read by the approval gate. */
    @Transactional(readOnly = true)
    public Optional<InquiryKindCorrection> standingFor(UUID orgId, UUID workItemId) {
        return standing(orgId, workItemId);
    }

    /**
     * Keeps the seller's kind, superseding the standing correction the screen named ({@code replaces}; null when it
     * showed none). Refused when the reply is no longer the seller's to write, or when the screen was out of date.
     */
    @Transactional
    public KindView correct(UUID orgId, UUID workItemId, String kind, UUID replaces, String actor) {
        InquiryKind k = InquiryKind.parse(kind);
        if (k == null) {
            throw ApiException.badRequest(PICK);
        }
        InquiryWorkItem item = em.find(InquiryWorkItem.class, workItemId, LockModeType.PESSIMISTIC_WRITE);
        if (item == null || !orgId.equals(item.getOrgId())) {
            throw ApiException.notFound("문의를 찾을 수 없어요.");
        }
        if (!InquiryWorkItemPhase.AWAITING_SELLER.contains(item.getPhase())) {
            throw ApiException.conflict(DECIDED);
        }
        InquiryKindCorrection standing = standing(orgId, workItemId).orElse(null);
        UUID shown = standing == null ? null : standing.getId();
        if (!java.util.Objects.equals(shown, replaces)) {
            throw ApiException.conflict(STALE);
        }
        if (standing != null && k.name().equals(standing.getKind())) {
            return KindView.of(item.getJudgedKind(), standing, writtenUnder(workItemId, standing));
        }
        Instant now = Instant.now();
        if (standing != null) {
            standing.setSupersededAt(now);
            corrections.saveAndFlush(standing);
        }
        InquiryKindCorrection c = new InquiryKindCorrection();
        c.setOrgId(orgId);
        c.setWorkItemId(workItemId);
        c.setKind(k.name());
        c.setJudgedKind(item.getJudgedKind());
        c.setCorrectedBy(actor);
        c.setCorrectedAt(now);
        corrections.saveAndFlush(c);
        return KindView.of(item.getJudgedKind(), c, writtenUnder(workItemId, c));
    }

    private Optional<InquiryKindCorrection> standing(UUID orgId, UUID workItemId) {
        return corrections.findFirstByOrgIdAndWorkItemIdAndSupersededAtIsNullOrderByCorrectedAtDesc(orgId, workItemId);
    }
}
