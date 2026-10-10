package com.sellerops.knowledge.teach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.common.VocPreviewSanitizer;
import com.sellerops.inquiry.Inquiry;
import com.sellerops.inquiry.InquiryRepository;
import com.sellerops.inquiry.draft.InquiryDraftComposer;
import com.sellerops.inquiry.draft.InquiryEvidenceRetriever;
import com.sellerops.inquiry.reply.InquiryReplyDraftService;
import com.sellerops.inquiry.reply.dto.ReplyDraftView;
import com.sellerops.knowledge.KnowledgeText;
import com.sellerops.knowledge.KnowledgeTopic;
import com.sellerops.knowledge.candidate.KnowledgeCandidateService;
import com.sellerops.knowledge.guidance.SellerGuidance;
import com.sellerops.knowledge.guidance.SellerGuidanceService;
import com.sellerops.knowledge.memory.AnswerMemory;
import com.sellerops.knowledge.memory.AnswerMemoryRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.spine.KnowledgeEntry;
import com.sellerops.knowledge.spine.SpineSourceType;
import com.sellerops.knowledge.spine.KnowledgeSpineService;
import com.sellerops.knowledge.teach.dto.CaseCorrectionRequest;
import com.sellerops.knowledge.teach.dto.CaseDetailView;
import com.sellerops.knowledge.teach.dto.CaseDraftEditRequest;
import com.sellerops.knowledge.teach.dto.CaseTeachRequest;
import com.sellerops.operationscase.CaseDisposition;
import com.sellerops.operationscase.CaseEventActor;
import com.sellerops.operationscase.CaseEventKind;
import com.sellerops.operationscase.CaseKnowledgeGap;
import com.sellerops.operationscase.CaseResolutionReader;
import com.sellerops.operationscase.OperationsCase;
import com.sellerops.operationscase.OperationsCaseEvent;
import com.sellerops.operationscase.OperationsCaseEventRepository;
import com.sellerops.operationscase.OperationsCaseProcessor;
import com.sellerops.operationscase.OperationsCaseRepository;
import com.sellerops.operationscase.OperationsSubjectKind;
import com.sellerops.operationscase.RecommendedActionType;
import com.sellerops.operationscase.investigation.CaseDraftPreparer;
import com.sellerops.operationscase.investigation.CaseInvestigator;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.ProductRepository;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * <b>The seller's side of a customer-operations case</b> (Knowledge &amp; Intelligence Closure v1): read it, teach the
 * knowledge it is missing, rewrite its draft, correct its recommendation — and choose what Reviewnary keeps.
 *
 * <p><b>Why it is not in {@code operationscase}.</b> That package is the responsibility runtime, fenced to never write
 * policy, knowledge or a draft of its own accord. Everything here starts with a seller's press on the case screen: a
 * seller who types 「방수 안 됩니다」 into [정보 알려주기] is writing company knowledge on purpose, through the same writer
 * the Knowledge Inbox uses ({@link KnowledgeCandidateService#teach}), and a seller who presses 「다음에도 참고」 is asking
 * for exactly one guidance row. Nothing is inferred and no rule, threshold or policy is mutated.
 *
 * <p><b>Teach closes the loop on the same case.</b> After the knowledge is saved, the case is re-investigated through
 * the same investigator and re-drafted through the same production draft path the scheduled run used, and both
 * outcomes are recorded by the processor's own recording methods — so a taught case reads exactly as if the knowledge
 * had been there the first time. The next similar case needs no teaching: the knowledge is ordinary company knowledge
 * in the Knowledge Spine.
 *
 * <p>Nothing here approves, sends or reaches a marketplace; a draft remains a draft on the inquiry screen.
 */
@Service
public class CaseKnowledgeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_BODY = 1200;

    private final OperationsCaseRepository cases;
    private final OperationsCaseEventRepository events;
    private final OperationsCaseProcessor processor;
    private final CaseInvestigator investigator;
    private final CaseDraftPreparer preparer;
    private final InquiryRepository inquiries;
    private final ReviewRepository reviews;
    private final ChannelRepository channels;
    private final ProductRepository products;
    private final InquiryEvidenceRetriever retriever;
    private final KnowledgeCandidateService candidates;
    private final SellerGuidanceService guidance;
    private final KnowledgeSpineService spine;
    private final InquiryReplyDraftService drafts;
    private final InquiryDraftComposer composer;
    private final AnswerMemoryRepository memories;
    /** What the answer lifecycle observed for this case's work item — quoted, never re-derived. */
    private final com.sellerops.inquiry.publish.AnswerDeliveryTruthReader deliveries;
    private CaseResolutionReader resolutions;
    private com.sellerops.operationspolicy.SellerOperationsPolicyService policies;
    private com.sellerops.operationspolicy.SellerPolicyOverlay policyOverlay;
    private com.sellerops.operationspolicy.OperationsPolicyRedecider redecider;

    public CaseKnowledgeService(OperationsCaseRepository cases, OperationsCaseEventRepository events,
                                OperationsCaseProcessor processor, CaseInvestigator investigator,
                                CaseDraftPreparer preparer, InquiryRepository inquiries, ReviewRepository reviews,
                                ChannelRepository channels, ProductRepository products,
                                InquiryEvidenceRetriever retriever, KnowledgeCandidateService candidates,
                                SellerGuidanceService guidance, KnowledgeSpineService spine,
                                InquiryReplyDraftService drafts, InquiryDraftComposer composer,
                                AnswerMemoryRepository memories,
                                com.sellerops.inquiry.publish.AnswerDeliveryTruthReader deliveries) {
        this.deliveries = deliveries;
        this.cases = cases;
        this.events = events;
        this.processor = processor;
        this.investigator = investigator;
        this.preparer = preparer;
        this.inquiries = inquiries;
        this.reviews = reviews;
        this.channels = channels;
        this.products = products;
        this.retriever = retriever;
        this.candidates = candidates;
        this.guidance = guidance;
        this.spine = spine;
        this.drafts = drafts;
        this.composer = composer;
        this.memories = memories;
    }

    /**
     * <b>Seller-declared Operations Policy v1's three collaborators</b>, or null in a context that has none.
     *
     * <p>Optional together and used together: without them 「앞으로 같은 문제도 이렇게 처리」 is refused with a
     * sentence rather than silently ignored ({@code declarePolicy}), and every other path on this screen — the
     * correction itself, 「다음에도 참고하기」, teach, the draft edit — behaves exactly as it did before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPolicies(com.sellerops.operationspolicy.SellerOperationsPolicyService policies,
                            com.sellerops.operationspolicy.SellerPolicyOverlay policyOverlay,
                            com.sellerops.operationspolicy.OperationsPolicyRedecider redecider) {
        this.policies = policies;
        this.policyOverlay = policyOverlay;
        this.redecider = redecider;
    }

    /**
     * The Customer Goal loop's door, when this deployment has one. Optional for the same reason the processor's is:
     * with nothing reading messages into goals there is no resolution to re-run, and teach behaves exactly as it did
     * before — knowledge is saved, the case is re-investigated and re-drafted.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResolutions(CaseResolutionReader resolutions) {
        this.resolutions = resolutions;
    }

    // ── read ────────────────────────────────────────────────────────────────────────────────────────────────────

    public CaseDetailView detail(UUID orgId, UUID caseId) {
        OperationsCase c = requireCase(orgId, caseId);
        String channelName = c.getChannelId() == null ? null
                : channels.findById(c.getChannelId()).map(Channel::getNameKo).orElse(null);
        UUID namedProduct = retriever.namedProduct(orgId, c.getProductId());
        String productName = namedProduct == null ? null
                : products.findById(namedProduct).map(OperatorProductName::displayNameOrNull).orElse(null);
        Subject subject = subject(orgId, c);
        CaseKnowledgeGap gap = gapOf(c);
        return new CaseDetailView(c.getId(), c.isOpen(), c.getSubjectKind().name(), channelName, productName,
                namedProduct != null, subject.receivedOn(), subject.rating(), subject.title(), subject.body(),
                com.sellerops.operationscase.CaseReason.noteFor(c),
                c.getDisposition() == null ? null : c.getDisposition().name(),
                c.getDecidedBy() == null ? null : c.getDecidedBy().name(), c.getSummary(),
                c.getRecommendedActionType() == null ? null : c.getRecommendedActionType().name(),
                c.getRecommendedAction(), strings(c.getMissingInformation()), whyDecisionNeeded(c, gap),
                investigated(orgId, c), knowledgeUsed(orgId, c, namedProduct),
                gap == null ? null : new CaseDetailView.Gap(gap.quotableSubject(), gapSentence(gap),
                        namedProduct == null ? "ORG" : gap.suggestedScope(),
                        gap.needs() == null ? prefillOf(orgId, c, namedProduct, gap)
                                : combinedPrefill(needLines(orgId, c, namedProduct, gap)),
                        needLines(orgId, c, namedProduct, gap)),
                draft(orgId, c), c.getSubjectKind() == OperationsSubjectKind.INQUIRY
                        ? "/inquiries/" + c.getSubjectId() : "/reviews/reply/" + c.getSubjectId(),
                media(orgId, c),
                // The elapsed-time fallback, carried so the pane can apply the same contract the row applies.
                // It is never preferred over `receivedOn`: see `elapsedSource` on the frontend.
                c.getCreatedAt(),
                answerStateNote(orgId, c),
                appliedPolicy(orgId, c),
                // The problem a NEW rule from this case would be keyed on, or null when there is none. The form
                // offers 「앞으로 같은 문제도 이렇게 처리」 only where this is present, so a seller is never shown a
                // tick the server would refuse.
                policyOverlay == null || c.getSubjectKind() != OperationsSubjectKind.REVIEW ? null
                        : policyOverlay.candidate(orgId, c.getSubjectId())
                                .map(com.sellerops.operationspolicy.SellerPolicyOverlay.ProblemKey::titleKo)
                                .orElse(null));
    }

    /**
     * The standing rule that decided this case, or null.
     *
     * <p>Read from the overlay rather than from the {@code POLICY_APPLIED} event, and gated on
     * {@code decidedBy == SELLER}: the event says a rule decided it at some past moment, and the overlay says
     * which rule stands NOW. A seller who retired the rule and reopened the pane must see the card as it is, not
     * as it was — and the {@code clear} path has already taken the recommendation off it.
     */
    private CaseDetailView.AppliedPolicy appliedPolicy(UUID orgId, OperationsCase c) {
        if (policyOverlay == null || c.getDecidedBy() != com.sellerops.operationscase.CaseDecider.SELLER
                || c.getSubjectKind() != OperationsSubjectKind.REVIEW) {
            return null;
        }
        return policyOverlay.forReview(orgId, c.getSubjectId(), c.getProductId())
                .map(applied -> new CaseDetailView.AppliedPolicy(applied.policyId(), applied.version(),
                        applied.scope().name(), titleKoOf(applied.problem()),
                        applied.action() == null ? null : applied.action().name()))
                .orElse(null);
    }

    /** {@code 배송:지연} → {@code 배송 지연}. Vocabulary only; a key that no longer parses reads as itself. */
    private static String titleKoOf(String signatureKey) {
        int split = signatureKey == null ? -1 : signatureKey.indexOf(':');
        return split <= 0 ? signatureKey
                : signatureKey.substring(0, split) + " " + signatureKey.substring(split + 1);
    }

    /**
     * The answer-state lane. Optional, exactly as the review-photo lane is: a context without it shows
     * a case with no note, which is what every caller saw before 2026-10-03.
     */
    private com.sellerops.inquiry.publish.InquiryTargetStateReader targetState;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTargetState(com.sellerops.inquiry.publish.InquiryTargetStateReader targetState) {
        this.targetState = targetState;
    }

    /**
     * <b>「이 문의에 이미 답변이 달렸는지 지금은 확인할 수 없습니다」 — on the case too</b> (product-owner
     * decision, 2026-10-03).
     *
     * <p>확인할 일 opens an inquiry-origin case in a pane whose floor carries the way to the send
     * screen, and the inquiry screen has warned beside that control since the publish package. The
     * case did not, because the case read never asked — so the same seller, one press apart, was told
     * on one screen and not on the other.
     *
     * <p><b>One read, and it reads what collection already recorded.</b> {@link
     * com.sellerops.inquiry.publish.InquiryTargetStateReader} asks the coverage rows this channel
     * already has; there is no new endpoint and no marketplace call, which is the contract that
     * component was built under and is unchanged here. The sentence itself is {@link
     * com.sellerops.inquiry.publish.PreSendCheck#noteKo()}, so the two screens cannot drift.
     *
     * <p>Null for a review (the question is about an inquiry's answer), for a case with no channel,
     * and wherever the reader is absent.
     */
    private String answerStateNote(UUID orgId, OperationsCase c) {
        return answerStateNote(targetState, orgId, c);
    }

    /** The rule itself, with the reader handed in so it can be asserted without a container. */
    static String answerStateNote(com.sellerops.inquiry.publish.InquiryTargetStateReader reader,
                                  UUID orgId, OperationsCase c) {
        if (reader == null || c.getSubjectKind() != OperationsSubjectKind.INQUIRY || c.getChannelId() == null) {
            return null;
        }
        return reader.read(orgId, c.getChannelId()).noteKo();
    }

    /** The review-photo lane. Optional: a context without it shows a case with no photos, as before. */
    private com.sellerops.review.media.ReviewMediaRepository reviewMedia;
    private com.sellerops.review.media.ReviewMediaInspector mediaInspector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReviewMedia(com.sellerops.review.media.ReviewMediaRepository reviewMedia,
                        com.sellerops.review.media.ReviewMediaInspector mediaInspector) {
        this.reviewMedia = reviewMedia;
        this.mediaInspector = mediaInspector;
    }

    private List<CaseDetailView.Media> media(UUID orgId, OperationsCase c) {
        if (reviewMedia == null || c.getSubjectKind() != OperationsSubjectKind.REVIEW) {
            return List.of();
        }
        boolean on = mediaInspector != null && mediaInspector.enabledFor(orgId);
        return reviewMedia.findByOrgIdAndReviewIdOrderByOrdinalAsc(orgId, c.getSubjectId()).stream()
                .map(m -> {
                    boolean inspected = m.getInspectionStatus()
                            == com.sellerops.review.media.ReviewMedia.InspectionStatus.INSPECTED;
                    boolean video = m.getMediaKind() == com.sellerops.review.media.ReviewMedia.Kind.VIDEO;
                    return new CaseDetailView.Media(m.getOrdinal(), m.getMediaKind().name(), inspected,
                            mediaStatusKo(m.getInspectionStatus(), on), inspected ? m.getDepicts() : null,
                            inspected && m.getProblemVisible() != null ? m.getProblemVisible().name() : null,
                            inspected ? m.getProblemDescription() : null,
                            video ? null : "/api/responsibilities/customer-operations/cases/" + c.getId()
                                    + "/media/" + m.getOrdinal());
                }).toList();
    }

    static String mediaStatusKo(com.sellerops.review.media.ReviewMedia.InspectionStatus status, boolean on) {
        return switch (status) {
            case INSPECTED -> "Reviewnary가 사진을 확인했습니다.";
            case NOT_INSPECTED -> on ? "아직 사진을 확인하지 않았습니다." : "사진 확인 기능이 꺼져 있어 사진 내용은 보지 않았습니다.";
            case FETCH_FAILED -> "사진을 가져오지 못해 내용은 보지 않았습니다.";
            case MODEL_FAILED -> "사진을 확인하지 못했습니다.";
            case NOT_AN_IMAGE -> "사진이 아니어서 내용은 보지 않았습니다.";
        };
    }

    /**
     * One of this case's review photos, fetched for the seller's own screen under the image fetch policy. Empty when
     * the case is not this organisation's review, the ordinal has no photo, or the fetch was refused.
     */
    public java.util.Optional<com.sellerops.product.detail.image.DetailImageFetcher.Loaded> mediaImage(
            UUID orgId, UUID caseId, int ordinal) {
        OperationsCase c = requireCase(orgId, caseId);
        if (reviewMedia == null || mediaInspector == null || c.getSubjectKind() != OperationsSubjectKind.REVIEW) {
            return java.util.Optional.empty();
        }
        return reviewMedia.findByOrgIdAndReviewIdOrderByOrdinalAsc(orgId, c.getSubjectId()).stream()
                .filter(m -> m.getOrdinal() == ordinal
                        && m.getMediaKind() != com.sellerops.review.media.ReviewMedia.Kind.VIDEO)
                .findFirst()
                .flatMap(mediaInspector::loadForSeller);
    }

    // ── teach ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Save the seller's answer to the case's knowledge gap as seller-confirmed knowledge, then re-investigate and
     * re-draft the case through the production paths.
     */
    public CaseDetailView teach(UUID orgId, UUID caseId, CaseTeachRequest request, UUID userId, String userName) {
        OperationsCase c = requireCase(orgId, caseId);
        if (!c.isOpen() || c.getSubjectKind() != OperationsSubjectKind.INQUIRY) {
            throw ApiException.conflict("정보를 알려 줄 수 있는 건이 아닙니다.");
        }
        CaseKnowledgeGap gap = gapOf(c);
        if (gap == null) {
            throw ApiException.conflict("이 건에는 부족한 정보가 없습니다.");
        }
        UUID namedProduct = retriever.namedProduct(orgId, c.getProductId());
        String scope = "PRODUCT".equals(request.scope()) && namedProduct != null ? "PRODUCT" : "ORG";
        candidates.teach(orgId, scope, namedProduct, teachSubject(gap), gap.candidateId(), request.content(),
                orgTypeFor(gap.topic()), userId, userName);
        Map<String, Object> taught = new LinkedHashMap<>();
        taught.put("scope", scope);
        taught.put("topic", gap.topic());
        // Which past answer the seller started from, and whether they kept it word for word — an id and a boolean,
        // never the text. The knowledge itself is the seller's: it was saved because they confirmed it.
        CaseDetailView.Prefill offered = prefillOf(orgId, c, namedProduct, gap);
        if (offered != null) {
            taught.put("precedentMemoryId", gap.precedentMemoryId().toString());
            taught.put("precedentUnchanged",
                    KnowledgeText.normalize(offered.text()).equals(KnowledgeText.normalize(request.content())));
        }
        events.save(OperationsCaseEvent.of(c, c.getLastRunId(), CaseEventActor.SELLER, CaseEventKind.KNOWLEDGE_TAUGHT,
                json(taught)));
        rerun(orgId, c);
        return detail(orgId, caseId);
    }

    /**
     * Re-resolve, then re-investigate (when the organisation has the capability), then re-draft when the case is a
     * reply.
     *
     * <p><b>The resolution runs first, and it is the step that knows what just changed.</b> The seller has altered
     * what this organisation knows, and the deterministic resolvers are the only thing here that reads that: the
     * knowledge lanes answered {@code NEEDS_SELLER} because they searched and found nothing, and now they may find
     * something. Without this the case kept recommending {@code ADD_KNOWLEDGE} after the seller had added exactly
     * the knowledge it asked for.
     *
     * <p>It costs <b>no vendor call</b>. The interpretation is keyed on the customer's text, which did not change,
     * so the stored reading is reused; only the context around it is rebuilt.
     */
    private void rerun(UUID orgId, OperationsCase c) {
        OperationsCase current = c;
        if (resolutions != null && current.getSubjectKind() == OperationsSubjectKind.INQUIRY) {
            CaseResolutionReader.Reading reading = resolutions.read(orgId, current.getSubjectId());
            if (processor.recordResolution(current, reading) != null) {
                current = cases.saveAndFlush(current);
                Map<String, Object> reread = new LinkedHashMap<>();
                reread.put("decidedBy", "RULE");
                reread.put("disposition", String.valueOf(current.getDisposition()));
                reread.put("resolution", reading.view());
                events.save(OperationsCaseEvent.of(current, current.getLastRunId(), CaseEventActor.SYSTEM,
                        CaseEventKind.CONTEXT_UPDATED, json(reread)));
            }
        }
        CaseInvestigator.Outcome outcome = investigator.investigate(current, current.getLastRunId());
        current = processor.recordInvestigation(current, current.getLastRunId(), outcome);
        boolean reply = outcome.kind() != CaseInvestigator.Kind.CONCLUDED
                || (current.getDisposition() == CaseDisposition.NEEDS_DECISION
                        && current.getRecommendedActionType() == RecommendedActionType.REPLY_TO_CUSTOMER);
        if (reply && current.getWorkItemId() != null) {
            processor.recordPrepared(current, current.getLastRunId(), preparer.prepare(orgId, current.getWorkItemId()));
        }
    }

    // ── corrections ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The seller rewrote the prepared draft; with 「다음에도 참고」, the rewrite is kept as guidance. */
    public CaseDetailView editDraft(UUID orgId, UUID caseId, CaseDraftEditRequest request, UUID userId,
                                    String userName) {
        OperationsCase c = requireCase(orgId, caseId);
        if (c.getWorkItemId() == null || c.getDraftVersion() == null) {
            throw ApiException.conflict("고칠 초안이 없습니다.");
        }
        ReplyDraftView current = drafts.latestView(c.getWorkItemId());
        if (current == null) {
            throw ApiException.conflict("고칠 초안이 없습니다.");
        }
        boolean material = !KnowledgeText.normalize(request.body()).equals(KnowledgeText.normalize(current.comments()));
        if (!material) {
            throw ApiException.badRequest("고친 내용이 없습니다.");
        }
        drafts.save(orgId, c.getWorkItemId(), userId, current.title(), request.body(), current.version());
        events.save(OperationsCaseEvent.of(c, c.getLastRunId(), CaseEventActor.SELLER,
                CaseEventKind.SELLER_EDITED_DRAFT, json(Map.of("fromVersion", current.version()))));
        if (request.remember()) {
            remember(orgId, c, SellerGuidance.Kind.DRAFT_CORRECTION, null, request.body().strip(), request.scope(),
                    userId, userName);
        }
        return detail(orgId, caseId);
    }

    /** The seller said a different action is right; with 「다음에도 참고」, that judgement is kept as guidance. */
    public CaseDetailView correct(UUID orgId, UUID caseId, CaseCorrectionRequest request, UUID userId,
                                  String userName) {
        OperationsCase c = requireCase(orgId, caseId);
        RecommendedActionType corrected = parseAction(request.correctedActionType());
        String note = request.note() == null ? "" : request.note().strip();
        if (corrected == null && note.isEmpty()) {
            throw ApiException.badRequest("맞는 처리 방법을 고르거나 적어 주세요.");
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("from", c.getRecommendedActionType() == null ? null : c.getRecommendedActionType().name());
        provenance.put("to", corrected == null ? null : corrected.name());
        events.save(OperationsCaseEvent.of(c, c.getLastRunId(), CaseEventActor.SELLER, CaseEventKind.SELLER_CORRECTED,
                json(provenance)));
        if (request.remember()) {
            String text = note.isEmpty() ? "이런 건은 「" + actionKo(corrected) + "」로 처리합니다."
                    : corrected == null ? note : note + " (처리: " + actionKo(corrected) + ")";
            remember(orgId, c, SellerGuidance.Kind.DECISION_CORRECTION, corrected == null ? null : corrected.name(),
                    text, request.scope(), userId, userName);
        }
        // <b>A second, independent act on the same form.</b> 「다음에도 참고하기」 above wrote context a later
        // judgement may be shown; this writes a RULE that decides future cases. Neither implies the other and the
        // request carries two separate ticks and two separate scopes, so a seller cannot reach one by choosing the
        // other. Ordered after the guidance only so that a refused policy leaves the correction and the guidance
        // standing — the seller's statement about THIS case is never lost to a rule they could not declare.
        if (request.applyToFuture()) {
            declarePolicy(orgId, c, corrected, note, request.policyScope(), userId);
        }
        return detail(orgId, caseId);
    }

    /**
     * Declare 「앞으로 같은 문제도 이렇게 처리」 from this case, and re-decide the open work it reaches.
     *
     * <p>Everything about WHAT may be declared is the policy package's — the vocabulary key, the HUMAN-authority
     * fence, the no-silent-widening refusal, the versioning. This method only supplies what the case knows: which
     * problem it is about. Three things are refused here because they are about the CASE rather than the policy,
     * and each says so in the seller's language:
     *
     * <ul>
     *   <li><b>No action chosen.</b> A rule with no handling is not a rule. The note alone is still recorded as
     *   the correction above.</li>
     *   <li><b>No recognised problem.</b> A review the extractor found nothing in, an inquiry, or a review whose
     *   problems the seller dismissed — there is no key, so there is nothing «같은 문제» could mean.</li>
     *   <li><b>No policy capability.</b> Said rather than silently skipped: a tick that appears accepted and does
     *   nothing is the failure {@code AnswerStyleSafetyFloor} exists to avoid on the other screen.</li>
     * </ul>
     *
     * <p>The seller's note travels as the policy's note — their own sentence, data, displayed and never prompted
     * with. The redecision is {@code OperationsPolicyRedecider}'s, with its four fences; nothing here closes a
     * case, approves anything or reaches a marketplace.
     */
    private void declarePolicy(UUID orgId, OperationsCase c, RecommendedActionType action, String note,
                               String requestedScope, UUID userId) {
        if (policies == null || policyOverlay == null || redecider == null) {
            throw ApiException.badRequest("처리 기준을 저장하는 기능이 켜져 있지 않습니다.");
        }
        if (action == null) {
            throw ApiException.badRequest("앞으로의 기준으로 삼으려면 처리 방법을 선택해 주세요.");
        }
        if (c.getSubjectKind() != OperationsSubjectKind.REVIEW) {
            throw ApiException.badRequest("이 건은 반복되는 문제로 기록된 것이 없어 기준으로 삼을 수 없습니다.");
        }
        com.sellerops.operationspolicy.SellerPolicyOverlay.ProblemKey key =
                policyOverlay.candidate(orgId, c.getSubjectId())
                        .orElseThrow(() -> ApiException.badRequest(
                                "이 건은 반복되는 문제로 기록된 것이 없어 기준으로 삼을 수 없습니다."));
        com.sellerops.operationspolicy.OperationsPolicyScope scope =
                com.sellerops.operationspolicy.OperationsPolicyScope.parse(requestedScope);
        // The product comes from the CASE, and only for a PRODUCT request. A null here is what makes the policy
        // service refuse rather than widen — the case's own product is not substituted for a missing choice.
        UUID productId = scope == com.sellerops.operationspolicy.OperationsPolicyScope.PRODUCT
                ? c.getProductId() : null;
        com.sellerops.operationspolicy.SellerOperationsPolicyService.Declared declared = policies.declare(
                new com.sellerops.operationspolicy.SellerOperationsPolicyService.Declaration(orgId, scope, productId,
                        key.aspect(), key.problem(), action, note.isEmpty() ? null : note, userId));
        if (declared.changed()) {
            redecider.redecide(declared.policy());
        }
    }

    private void remember(UUID orgId, OperationsCase c, SellerGuidance.Kind kind, String correctedAction, String text,
                          String requestedScope, UUID userId, String userName) {
        UUID namedProduct = retriever.namedProduct(orgId, c.getProductId());
        UUID productId = "ORG".equals(requestedScope) || namedProduct == null ? null : namedProduct;
        Subject subject = subject(orgId, c);
        guidance.record(new SellerGuidanceService.Record(orgId, productId, kind, c.getSubjectKind().name(), c.getId(),
                correctedAction, (subject.title() == null ? "" : subject.title()) + " "
                        + (subject.body() == null ? "" : subject.body()),
                text, userId, userName));
        events.save(OperationsCaseEvent.of(c, c.getLastRunId(), CaseEventActor.SELLER,
                CaseEventKind.SELLER_GUIDANCE_RECORDED,
                json(Map.of("kind", kind.name(), "scope", productId == null ? "ORG" : "PRODUCT"))));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private OperationsCase requireCase(UUID orgId, UUID caseId) {
        return cases.findByIdAndOrgId(caseId, orgId)
                .orElseThrow(() -> ApiException.notFound("해당 건을 찾을 수 없습니다."));
    }

    private record Subject(String title, String body, Integer rating, LocalDate receivedOn) {
    }

    private Subject subject(UUID orgId, OperationsCase c) {
        if (c.getSubjectKind() == OperationsSubjectKind.INQUIRY) {
            Inquiry i = inquiries.findById(c.getSubjectId()).filter(x -> orgId.equals(x.getOrgId())).orElse(null);
            if (i == null) {
                return new Subject(null, null, null, null);
            }
            return new Subject(cap(VocPreviewSanitizer.redactFullBody(i.getTitle()).text()),
                    cap(VocPreviewSanitizer.redactFullBody(i.getBody()).text()), null,
                    i.getReceivedAt() == null ? null : i.getReceivedAt().atZone(KST).toLocalDate());
        }
        if (c.getSubjectKind() == OperationsSubjectKind.REVIEW) {
            Review r = reviews.findById(c.getSubjectId()).filter(x -> orgId.equals(x.getOrgId())).orElse(null);
            if (r == null) {
                return new Subject(null, null, null, null);
            }
            return new Subject(null, cap(VocPreviewSanitizer.redactFullBody(r.getBody()).text()),
                    r.getRating(), r.getReceivedAt() == null ? null : r.getReceivedAt().atZone(KST).toLocalDate());
        }
        return new Subject(null, null, null, null);
    }

    static CaseKnowledgeGap gapOf(OperationsCase c) {
        if (c.getKnowledgeGap() == null || c.getKnowledgeGap().isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(c.getKnowledgeGap(), CaseKnowledgeGap.class);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * The past answer the retrieval found for this gap, re-read now (Past Answer Prefill v1). Null — the empty box, as
     * before — when there was none, when it is gone, or when it no longer passes the fences it was found under.
     */
    /** The need list of an Inquiry Decision v2 gap, with each uncovered need's REUSABLE precedent re-read and fenced. */
    private List<CaseDetailView.NeedLine> needLines(UUID orgId, OperationsCase c, UUID namedProduct,
                                                    CaseKnowledgeGap gap) {
        if (gap.needs() == null) {
            return null;
        }
        boolean open = c.isOpen() && c.getSubjectKind() == OperationsSubjectKind.INQUIRY && memories != null;
        return gap.needs().stream().map(n -> {
            boolean covered = "FULL".equals(n.status()) || "CONDITIONAL_ON_CUSTOMER".equals(n.status());
            CaseDetailView.Prefill prefill = covered || !open || n.precedents() == null ? null
                    : n.precedents().stream().map(memories::findById).flatMap(java.util.Optional::stream)
                            .map(m -> prefill(orgId, c.getSubjectId(), namedProduct, m, sameOrder(c.getSubjectId())))
                            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return new CaseDetailView.NeedLine(n.ask(), n.status(), n.statusKo(), covered,
                    n.evidence() == null ? List.of() : n.evidence(), n.missing(), n.askCustomer(), prefill,
                    n.acquirable());
        }).toList();
    }

    /**
     * The seller's starting text for a partial Teach: the precedents of the uncovered needs — one need, its answer as
     * it was; several, each under its need so the seller can see which part answers what. Null when there is none.
     */
    static CaseDetailView.Prefill combinedPrefill(List<CaseDetailView.NeedLine> needs) {
        List<CaseDetailView.NeedLine> offered = needs.stream().filter(n -> !n.covered() && n.prefill() != null)
                .toList();
        if (offered.isEmpty()) {
            return null;
        }
        if (offered.size() == 1) {
            return offered.get(0).prefill();
        }
        StringBuilder text = new StringBuilder();
        for (CaseDetailView.NeedLine n : offered) {
            text.append("[").append(n.ask()).append("]\n").append(n.prefill().text()).append("\n\n");
        }
        CaseDetailView.Prefill first = offered.get(0).prefill();
        return new CaseDetailView.Prefill(text.toString().strip(), first.strengthKo(), first.answeredOn());
    }

    private CaseDetailView.Prefill prefillOf(UUID orgId, OperationsCase c, UUID namedProduct, CaseKnowledgeGap gap) {
        if (!c.isOpen() || c.getSubjectKind() != OperationsSubjectKind.INQUIRY || gap.precedentMemoryId() == null
                || memories == null) {
            return null;
        }
        return memories.findById(gap.precedentMemoryId())
                .map(m -> prefill(orgId, c.getSubjectId(), namedProduct, m, sameOrder(c.getSubjectId())))
                .orElse(null);
    }

    /** Whether a past answer's own inquiry was about the same order as this Case's — through the two inquiries. */
    private java.util.function.Predicate<AnswerMemory> sameOrder(UUID inquiryId) {
        com.sellerops.inquiry.decision.PrecedentReuse.OrderKey current = inquiryId == null ? null
                : inquiries.findById(inquiryId).map(com.sellerops.inquiry.decision.PrecedentReuse.OrderKey::of)
                        .orElse(null);
        return m -> current != null && m.getOriginInquiryId() != null && current.equals(inquiries
                .findById(m.getOriginInquiryId()).map(com.sellerops.inquiry.decision.PrecedentReuse.OrderKey::of)
                .orElse(null));
    }

    /**
     * The fences a precedent is re-checked against when it is shown: this organisation's, not another product's (an
     * unbound answer names no product, so it is about none in particular), not this inquiry's own answer, and not
     * empty. The retrieval applied all of these when it found the answer; the case outlives that retrieval.
     */
    static CaseDetailView.Prefill prefill(UUID orgId, UUID inquiryId, UUID namedProduct, AnswerMemory m) {
        return prefill(orgId, inquiryId, namedProduct, m, x -> false);
    }

    /**
     * ... and the answer's DECLARED reuse scope (Inquiry Decision v2.1, {@code PrecedentReuse}): REUSABLE crosses
     * Cases, ORDER_ONLY only when {@code sameOrder} says the two inquiries share an order, CASE_ONLY and UNKNOWN never —
     * this inquiry's own answer is already excluded above them. Existing rows are UNKNOWN until a seller says otherwise.
     */
    static CaseDetailView.Prefill prefill(UUID orgId, UUID inquiryId, UUID namedProduct, AnswerMemory m,
                                          java.util.function.Predicate<AnswerMemory> sameOrder) {
        if (m == null || !reuseAdmits(m, inquiryId, sameOrder)) {
            return null;
        }
        if (!orgId.equals(m.getOrgId()) || m.getAnswerBody() == null || m.getAnswerBody().isBlank()
                || (m.getProductId() != null && !m.getProductId().equals(namedProduct))
                || (inquiryId != null && inquiryId.equals(m.getOriginInquiryId()))) {
            return null;
        }
        return new CaseDetailView.Prefill(m.getAnswerBody().strip(),
                m.getStrength() == null ? null : m.getStrength().labelKo(),
                m.getUpdatedAt() == null ? null : m.getUpdatedAt().atZone(KST).toLocalDate());
    }

    private static boolean reuseAdmits(AnswerMemory m, UUID inquiryId,
                                       java.util.function.Predicate<AnswerMemory> sameOrder) {
        return switch (m.getReuseScope() == null ? com.sellerops.knowledge.memory.AnswerMemoryReuseScope.UNKNOWN
                : m.getReuseScope()) {
            case REUSABLE -> true;
            case ORDER_ONLY -> sameOrder.test(m);
            case CASE_ONLY, UNKNOWN -> inquiryId != null && inquiryId.equals(m.getOriginInquiryId());
        };
    }

    /**
     * What the taught knowledge is titled with: the uncovered needs in the seller's words when the gap has a need list
     * (Inquiry Decision v2) — so the next customer asking the same need finds it under its own name — else the gap's
     * subject, as before.
     */
    static String teachSubject(CaseKnowledgeGap gap) {
        if (gap.needs() != null) {
            String asks = gap.needs().stream()
                    .filter(n -> !"FULL".equals(n.status()) && !"CONDITIONAL_ON_CUSTOMER".equals(n.status()))
                    .map(com.sellerops.inquiry.draft.dto.NeedCoverageView::ask).distinct()
                    .collect(java.util.stream.Collectors.joining(" · "));
            if (!asks.isBlank()) {
                return asks.length() > 180 ? asks.substring(0, 180) : asks;
            }
        }
        /*
          <b>And never a word that cannot be quoted</b> (2026-10-06). This string becomes the HEADING of the
          company knowledge the seller is about to write — 「드립니다 안내」 would have been filed, permanently,
          under a word from a greeting. Falling back to the topic's own label is the same fallback
          {@link CaseKnowledgeGap} already makes when nothing named a subject; with no topic either, the sink
          titles it 「고객 안내 기준」, which it has always done for a gap without a subject.
        */
        String subject = gap.quotableSubject();
        if (subject != null) {
            return subject;
        }
        return topicLabel(gap.topic());
    }

    /** The stored topic's seller-facing label, or null — an unknown or absent topic names nothing. */
    private static String topicLabel(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        try {
            return com.sellerops.knowledge.KnowledgeTopic.valueOf(topic).labelKo();
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * <b>A subject is quoted only when it is one</b> ({@link CaseKnowledgeGap#quotableSubject()}, 2026-10-06).
     *
     * <p>Measured on the demo org: a case from a post titled 「문의 드립니다」 held
     * {@code missingSubject: "드립니다"} and this sentence read 「「드립니다」에 대해 고객에게 안내할 기준이
     * 없습니다.」 — the customer's manners quoted to the seller as the name of their own company standard.
     * The fact the sentence states does not change; what is dropped is a name we cannot stand behind.
     */
    static String gapSentence(CaseKnowledgeGap gap) {
        String subject = gap.quotableSubject();
        return subject == null
                ? "이 문의에 답할 판매자 안내 기준이 없습니다."
                : "「" + subject + "」에 대해 고객에게 안내할 기준이 없습니다.";
    }

    private static String whyDecisionNeeded(OperationsCase c, CaseKnowledgeGap gap) {
        if (!c.isOpen() || c.getDisposition() != CaseDisposition.NEEDS_DECISION) {
            return null;
        }
        if (gap != null) {
            return "답변에 필요한 회사 정보가 없어 Reviewnary가 답을 만들 수 없습니다. 정보를 알려 주시면 다시 준비합니다.";
        }
        if (c.getRecommendedActionType() != null && c.getRecommendedActionType().authority()
                == com.sellerops.operationscase.RequiredAuthority.HUMAN) {
            return "고객에게 무엇을 말하거나 약속할지는 판매자가 정합니다.";
        }
        return "Reviewnary가 확신할 수 없어 판매자 판단이 필요합니다.";
    }

    private List<CaseDetailView.Investigated> investigated(UUID orgId, OperationsCase c) {
        List<OperationsCaseEvent> history = events.findByOrgIdAndCaseIdOrderByCreatedAtAsc(orgId, c.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            OperationsCaseEvent e = history.get(i);
            if (e.getKind() != CaseEventKind.INVESTIGATED) {
                continue;
            }
            List<CaseDetailView.Investigated> out = new ArrayList<>();
            try {
                for (JsonNode call : MAPPER.readTree(e.getProvenance()).path("tools")) {
                    String tool = call.path("tool").asText();
                    if ("getReviewMedia".equals(tool)) {
                        out.addAll(photosLooked(orgId, c));
                        continue;
                    }
                    String label = toolKo(tool);
                    if (label != null) {
                        out.add(new CaseDetailView.Investigated(label, call.path("results").asInt()));
                    }
                }
            } catch (Exception unreadable) {
                return List.of();
            }
            return out;
        }
        return List.of();
    }

    /**
     * The photos, as the canonical media rows say now: how many a vision model actually looked at, and — separately —
     * how many it did not. A photo only counted or only addressed is never listed as looked at.
     */
    private List<CaseDetailView.Investigated> photosLooked(UUID orgId, OperationsCase c) {
        if (reviewMedia == null || c.getSubjectKind() != OperationsSubjectKind.REVIEW) {
            return List.of();
        }
        return photoLines(reviewMedia.findByOrgIdAndReviewIdOrderByOrdinalAsc(orgId, c.getSubjectId()));
    }

    static List<CaseDetailView.Investigated> photoLines(List<com.sellerops.review.media.ReviewMedia> rows) {
        long seen = rows.stream().filter(m -> m.getInspectionStatus()
                == com.sellerops.review.media.ReviewMedia.InspectionStatus.INSPECTED).count();
        List<CaseDetailView.Investigated> out = new ArrayList<>();
        if (seen > 0) {
            out.add(new CaseDetailView.Investigated("고객이 올린 사진", (int) seen));
        }
        if (rows.size() > seen) {
            out.add(new CaseDetailView.Investigated("보지 못한 사진", (int) (rows.size() - seen)));
        }
        return out;
    }

    private static String toolKo(String tool) {
        return switch (tool) {
            case "getSubject" -> "고객이 남긴 내용";
            case "getProductContext" -> "연결된 상품";
            case "getOrderContext" -> "주문 정보";
            case "assessKnowledge" -> "회사·상품 지식";
            case "getRelatedIssues" -> "반복되는 리뷰 문제";
            case "getRecentSimilarCases" -> "같은 상품의 최근 비슷한 건";
            case "getPastSellerDecisions" -> "판매자의 지난 결정";
            default -> null;
        };
    }

    private List<CaseDetailView.KnowledgeUsed> knowledgeUsed(UUID orgId, OperationsCase c, UUID namedProduct) {
        if (c.getKnowledgeUsed() == null || c.getKnowledgeUsed().isBlank()) {
            return List.of();
        }
        List<CaseInvestigator.UsedKnowledge> used;
        try {
            used = MAPPER.readValue(c.getKnowledgeUsed(), new TypeReference<List<CaseInvestigator.UsedKnowledge>>() { });
        } catch (Exception unreadable) {
            return List.of();
        }
        // Text is re-read from the raw source: the case stores which entries, never a second copy of what they say.
        Map<String, KnowledgeEntry> live = new LinkedHashMap<>();
        spine.entries(orgId, namedProduct).forEach(e -> live.put(e.entryId(), e));
        List<CaseDetailView.KnowledgeUsed> out = new ArrayList<>();
        for (CaseInvestigator.UsedKnowledge u : used) {
            KnowledgeEntry entry = live.get(u.entryId());
            if (entry == null) {
                continue;
            }
            String flat = entry.text() == null ? "" : entry.text().replaceAll("\\s+", " ").strip();
            boolean pastAnswer = entry.sourceType() == SpineSourceType.INQUIRY_ANSWER
                    || entry.sourceType() == SpineSourceType.REVIEW_REPLY;
            out.add(new CaseDetailView.KnowledgeUsed(entry.authority().labelKo(), entry.provenance(), entry.title(),
                    flat.length() > 200 ? flat.substring(0, 200) + "…" : flat,
                    entry.capturedAt() == null ? null : entry.capturedAt().atZone(KST).toLocalDate(), u.cited(),
                    entry.scope().name(), pastAnswer,
                    pastAnswer && entry.text() != null ? entry.text().strip() : null));
        }
        return out;
    }

    private CaseDetailView.Draft draft(UUID orgId, OperationsCase c) {
        if (c.getWorkItemId() == null) {
            return null;
        }
        ReplyDraftView latest = drafts.latestView(c.getWorkItemId());
        if (latest == null) {
            return null;
        }
        return new CaseDetailView.Draft(latest.version(), latest.title(), latest.comments(), latest.authorKind(),
                latest.answerBasis(), composer.evidenceFor(orgId, c.getWorkItemId(), latest.version()),
                deliveries == null ? null : deliveries.view(orgId, c.getWorkItemId()).orElse(null));
    }

    static OrgKnowledgeType orgTypeFor(String topic) {
        if (topic == null) {
            return OrgKnowledgeType.GENERAL_CS_FAQ;
        }
        try {
            return switch (KnowledgeTopic.valueOf(topic)) {
                case SHIPPING -> OrgKnowledgeType.SHIPPING_POLICY;
                case EXCHANGE_RETURN -> OrgKnowledgeType.EXCHANGE_REFUND_POLICY;
                case CANCELLATION -> OrgKnowledgeType.CANCELLATION_POLICY;
                case PAYMENT -> OrgKnowledgeType.PAYMENT_POLICY;
                case TAX_INVOICE -> OrgKnowledgeType.TAX_INVOICE;
                case CASH_RECEIPT -> OrgKnowledgeType.CASH_RECEIPT;
            };
        } catch (IllegalArgumentException unknown) {
            return OrgKnowledgeType.GENERAL_CS_FAQ;
        }
    }

    private static RecommendedActionType parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RecommendedActionType.valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("지원하지 않는 처리 방법입니다.");
        }
    }

    static String actionKo(RecommendedActionType action) {
        if (action == null) {
            return "판매자 판단";
        }
        return switch (action) {
            case NO_ACTION -> "할 일 없음";
            case MONITOR_REPEAT_ISSUE -> "반복되는지 지켜보기";
            case REPLY_TO_CUSTOMER -> "고객에게 답변";
            case CONTACT_CUSTOMER -> "고객에게 따로 연락";
            case REFUND_OR_COMPENSATION -> "환불·보상 검토";
            case CANCEL_OR_EXCHANGE -> "취소·교환 검토";
            case ADD_KNOWLEDGE -> "회사 지식 보충";
            case REVIEW_PRODUCT_LISTING -> "상품 정보 점검";
        };
    }

    private static List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String cap(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) + "…" : text;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
