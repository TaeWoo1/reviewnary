package com.sellerops.knowledge.defaults;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.KnowledgeBlanks;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.knowledge.org.dto.OrgKnowledgeView;
import com.sellerops.talktalk.TalkTalkAccess;
import com.sellerops.talktalk.TalkTalkClock;
import com.sellerops.talktalk.TalkTalkGuidance;
import com.sellerops.talktalk.TalkTalkGuidanceListener;
import com.sellerops.talktalk.TalkTalkGuidanceRepository;
import com.sellerops.talktalk.TalkTalkGuidanceRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매뉴얼 없는 판매자의 기본 설정 (owner decision 2026-11-17).
 *
 * <p><b>Only confirmed answers become the shop's word.</b> A typed answer is a draft and nothing reads it. Confirming
 * an item writes it into that topic's shop-standard document ({@link DefaultPolicyGroup}) through the same org
 * knowledge path a seller's own rule takes — indexed for retrieval, compared by the 자료끼리 다른 값 check, versioned
 * with its revisions kept. An item never confirmed is simply absent, so a question that needs it finds no evidence
 * and goes to a person, as every unanswered question does. No 【확인: …】 blank is ever written into the document: a
 * passage carrying one is skipped whole, and it would take the confirmed lines beside it down with it.
 *
 * <p><b>출고 마감 and 휴무 have one home</b>, the TalkTalk shipping guidance. They are read from it and written into the
 * shipping document, and rewritten whenever the guidance is confirmed or taken down ({@link #guidanceChanged}).
 */
@Service
public class DefaultPolicyService implements TalkTalkGuidanceListener {

    private final DefaultPolicyAnswerRepository answers;
    private final DefaultPolicySourceRepository mapped;
    private final OrgKnowledgeSourceRepository orgSources;
    private final SellerOperationsKnowledgeService orgKnowledge;
    private final TalkTalkGuidanceRepository guidance;
    private final TalkTalkAccess access;
    private final TalkTalkClock clock;

    public DefaultPolicyService(DefaultPolicyAnswerRepository answers, DefaultPolicySourceRepository mapped,
                                OrgKnowledgeSourceRepository orgSources, SellerOperationsKnowledgeService orgKnowledge,
                                TalkTalkGuidanceRepository guidance, TalkTalkAccess access, TalkTalkClock clock) {
        this.answers = answers;
        this.mapped = mapped;
        this.orgSources = orgSources;
        this.orgKnowledge = orgKnowledge;
        this.guidance = guidance;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DefaultPolicyViews.Overview overview(UUID orgId) {
        Map<DefaultPolicyItem, DefaultPolicyAnswer> byItem = answersOf(orgId);
        TalkTalkGuidance g = confirmedGuidance(orgId);
        LocalDate today = today();
        List<DefaultPolicyViews.Item> items = new ArrayList<>();
        int confirmed = 0;
        for (DefaultPolicyItem item : DefaultPolicyItem.values()) {
            DefaultPolicyViews.Item view = item.shared() ? sharedView(item, g, today) : view(item, byItem.get(item));
            if ("CONFIRMED".equals(view.status()) || "CHANGED".equals(view.status())) {
                confirmed++;
            }
            items.add(view);
        }
        List<DefaultPolicyViews.Document> documents = new ArrayList<>();
        for (DefaultPolicySource m : mapped.findAllByOrgId(orgId)) {
            DefaultPolicyGroup group = DefaultPolicyGroup.valueOf(m.getGroupKey());
            orgSources.findByIdAndOrgId(m.getSourceId(), orgId).ifPresent(s -> documents.add(
                    new DefaultPolicyViews.Document(group.name(), group.labelKo(), s.getId(), s.getTitle(),
                            s.isActive(), s.getVersion())));
        }
        documents.sort((a, b) -> DefaultPolicyGroup.valueOf(a.group()).compareTo(DefaultPolicyGroup.valueOf(b.group())));
        return new DefaultPolicyViews.Overview(items, confirmed, items.size(), documents);
    }

    /** Save what the seller typed. Nothing answers from it until it is confirmed. */
    @Transactional
    public DefaultPolicyViews.Overview saveDraft(UUID orgId, UUID userId, String key, Integer number, String text) {
        access.member(orgId, userId);
        DefaultPolicyItem item = typed(key);
        String cleaned = clean(item, number, text);
        DefaultPolicyAnswer a = answers.findByOrgIdAndItemKey(orgId, item.name()).orElseGet(() -> {
            DefaultPolicyAnswer fresh = new DefaultPolicyAnswer();
            fresh.setOrgId(orgId);
            fresh.setItemKey(item.name());
            return fresh;
        });
        a.setDraftNumber(item.kind() == DefaultPolicyItem.Kind.TEXT ? null : number);
        a.setDraftText(cleaned);
        a.setUpdatedBy(userId);
        answers.save(a);
        return overview(orgId);
    }

    /**
     * Confirm the draft the seller is looking at. The request repeats it, so a draft changed in another tab since the
     * screen loaded is refused rather than confirmed unseen.
     */
    @Transactional
    public DefaultPolicyViews.Overview confirm(UUID orgId, UUID userId, String key, Integer number, String text) {
        access.owner(orgId, userId);
        DefaultPolicyItem item = typed(key);
        DefaultPolicyAnswer a = answers.findByOrgIdAndItemKey(orgId, item.name())
                .orElseThrow(() -> ApiException.conflict("DEFAULT_EMPTY", "먼저 답을 적고 저장해 주세요."));
        Integer seenNumber = item.kind() == DefaultPolicyItem.Kind.TEXT ? null : number;
        if (!Objects.equals(seenNumber, a.getDraftNumber()) || !Objects.equals(blankToNull(text), a.getDraftText())) {
            throw ApiException.conflict("DEFAULT_CHANGED", "저장된 답이 화면과 달라요. 새로 불러온 뒤 다시 확인해 주세요.");
        }
        if (item.render(a.getDraftNumber(), a.getDraftText()) == null) {
            throw ApiException.conflict("DEFAULT_EMPTY", "답이 비어 있어 확정할 수 없어요.");
        }
        a.setConfirmedNumber(a.getDraftNumber());
        a.setConfirmedText(a.getDraftText());
        a.setConfirmed(true);
        a.setConfirmedBy(userId);
        a.setConfirmedAt(clock.now());
        answers.save(a);
        refresh(orgId);
        return overview(orgId);
    }

    /** Take a confirmed answer out of use. The draft stays so the seller can fix and confirm it again. */
    @Transactional
    public DefaultPolicyViews.Overview unconfirm(UUID orgId, UUID userId, String key) {
        access.owner(orgId, userId);
        DefaultPolicyItem item = typed(key);
        answers.findByOrgIdAndItemKey(orgId, item.name()).ifPresent(a -> {
            a.setConfirmed(false);
            a.setConfirmedNumber(null);
            a.setConfirmedText(null);
            a.setConfirmedBy(null);
            a.setConfirmedAt(null);
            answers.save(a);
        });
        refresh(orgId);
        return overview(orgId);
    }

    @Override
    @Transactional
    public void guidanceChanged(UUID orgId) {
        refresh(orgId);
    }

    /**
     * Rewrite each group's document from what is confirmed now. A group with nothing confirmed keeps its row but is
     * switched off, so citations that stood on it still resolve and it answers nothing.
     */
    void refresh(UUID orgId) {
        Map<DefaultPolicyItem, DefaultPolicyAnswer> byItem = answersOf(orgId);
        TalkTalkGuidance g = confirmedGuidance(orgId);
        LocalDate today = today();
        for (DefaultPolicyGroup group : DefaultPolicyGroup.values()) {
            String body = body(group, byItem, g, today);
            DefaultPolicySource m = mapped.findByOrgIdAndGroupKey(orgId, group.name()).orElse(null);
            OrgKnowledgeSource existing = m == null ? null : orgSources.findByIdAndOrgId(m.getSourceId(), orgId).orElse(null);
            if (body == null) {
                if (existing != null && existing.isActive()) {
                    existing.setActive(false);
                    orgSources.save(existing);
                }
                continue;
            }
            if (existing != null) {
                orgKnowledge.update(orgId, existing.getId(),
                        new OrgKnowledgeRequest(group.type(), existing.getTitle(), body, null));
                OrgKnowledgeSource fresh = orgSources.findByIdAndOrgId(existing.getId(), orgId).orElseThrow();
                if (!fresh.isActive()) {
                    fresh.setActive(true);
                    orgSources.save(fresh);
                }
                continue;
            }
            OrgKnowledgeView created = orgKnowledge.create(orgId,
                    new OrgKnowledgeRequest(group.type(), freeTitle(orgId, group.title()), body, null),
                    null, "기본 설정");
            DefaultPolicySource link = m != null ? m : new DefaultPolicySource();
            link.setOrgId(orgId);
            link.setGroupKey(group.name());
            link.setSourceId(created.id());
            mapped.save(link);
        }
    }

    /** The document text of one group, or null when nothing in it is confirmed. */
    static String body(DefaultPolicyGroup group, Map<DefaultPolicyItem, DefaultPolicyAnswer> byItem,
                       TalkTalkGuidance g, LocalDate today) {
        List<String> lines = new ArrayList<>();
        for (DefaultPolicyItem item : DefaultPolicyItem.values()) {
            if (item.group() != group) {
                continue;
            }
            String sentence;
            if (item.shared()) {
                sentence = sharedSentence(item, g, today);
            } else {
                DefaultPolicyAnswer a = byItem.get(item);
                sentence = a == null || !a.isConfirmed() ? null : item.render(a.getConfirmedNumber(), a.getConfirmedText());
            }
            if (sentence != null) {
                lines.add(sentence);
            }
        }
        return lines.isEmpty() ? null : String.join("\n\n", lines);
    }

    /** The sentence a shared item contributes, from the confirmed guidance only. */
    static String sharedSentence(DefaultPolicyItem item, TalkTalkGuidance g, LocalDate today) {
        if (g == null) {
            return null;
        }
        if (item == DefaultPolicyItem.DISPATCH_CUTOFF) {
            return g.getCutoffHour() == null || g.getDispatchDays() == null || g.getDispatchDays().isBlank()
                    ? null : TalkTalkGuidanceRules.ruleSentence(g);
        }
        if (item == DefaultPolicyItem.HOLIDAYS) {
            LocalDate through = g.getConfirmedThrough();
            List<String> dates = TalkTalkGuidanceRules.closed(g.getClosedDates()).stream()
                    .filter(c -> !c.date().isBefore(today) && (through == null || !c.date().isAfter(through)))
                    .map(c -> TalkTalkGuidanceRules.dayLabel(c.date())
                            + (c.note() == null || c.note().isBlank() ? "" : " " + c.note().strip()))
                    .toList();
            if (through == null) {
                return dates.isEmpty() ? null : "휴무일(출고 없음): " + String.join(", ", dates) + ".";
            }
            String until = through.getYear() + "년 " + through.getMonthValue() + "월 " + through.getDayOfMonth() + "일";
            return dates.isEmpty()
                    ? until + "까지 출고 요일 외에 따로 쉬는 날은 없어요."
                    : "휴무일(출고 없음): " + String.join(", ", dates) + ". 휴무일은 " + until + "까지 확인했어요.";
        }
        return null;
    }

    private DefaultPolicyViews.Item view(DefaultPolicyItem item, DefaultPolicyAnswer a) {
        Integer dn = a == null ? null : a.getDraftNumber();
        String dt = a == null ? null : a.getDraftText();
        boolean hasDraft = item.render(dn, dt) != null;
        boolean confirmed = a != null && a.isConfirmed();
        String status = confirmed
                ? (Objects.equals(dn, a.getConfirmedNumber()) && Objects.equals(dt, a.getConfirmedText()) ? "CONFIRMED" : "CHANGED")
                : hasDraft ? "DRAFT" : "EMPTY";
        return new DefaultPolicyViews.Item(item.name(), item.group().name(), item.group().labelKo(), item.label(),
                item.question(), item.kind().name(), item.placeholder(), item.legal(), false, status, dn, dt,
                confirmed ? a.getConfirmedNumber() : null, confirmed ? a.getConfirmedText() : null,
                confirmed ? item.render(a.getConfirmedNumber(), a.getConfirmedText()) : null,
                hasDraft ? item.render(dn, dt) : null, item.warning(dn), confirmed ? a.getConfirmedAt() : null);
    }

    private static DefaultPolicyViews.Item sharedView(DefaultPolicyItem item, TalkTalkGuidance g, LocalDate today) {
        String sentence = sharedSentence(item, g, today);
        return new DefaultPolicyViews.Item(item.name(), item.group().name(), item.group().labelKo(), item.label(),
                item.question(), item.kind().name(), null, item.legal(), true, sentence == null ? "EMPTY" : "CONFIRMED",
                null, null, null, null, sentence, null, null, g == null ? null : g.getConfirmedAt());
    }

    private Map<DefaultPolicyItem, DefaultPolicyAnswer> answersOf(UUID orgId) {
        Map<DefaultPolicyItem, DefaultPolicyAnswer> out = new EnumMap<>(DefaultPolicyItem.class);
        for (DefaultPolicyAnswer a : answers.findAllByOrgId(orgId)) {
            try {
                out.put(DefaultPolicyItem.valueOf(a.getItemKey()), a);
            } catch (IllegalArgumentException retired) {
                // An item later removed from the list: its old answer is simply not shown or used.
            }
        }
        return out;
    }

    private TalkTalkGuidance confirmedGuidance(UUID orgId) {
        return guidance.findFirstByOrgIdAndStatus(orgId, TalkTalkGuidance.Status.CONFIRMED).orElse(null);
    }

    private LocalDate today() {
        return clock.now().atZone(TalkTalkGuidanceRules.KST).toLocalDate();
    }

    /** The group's title, or — when the seller already wrote a rule with that exact title — a numbered one beside it. */
    private String freeTitle(UUID orgId, String title) {
        String candidate = title;
        for (int i = 2; orgSources.findByOrgIdAndTitle(orgId, candidate).isPresent(); i++) {
            candidate = title + " " + i;
        }
        return candidate;
    }

    private static DefaultPolicyItem typed(String key) {
        DefaultPolicyItem item;
        try {
            item = DefaultPolicyItem.valueOf(key);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.notFound("없는 질문이에요.");
        }
        if (item.shared()) {
            throw ApiException.conflict("DEFAULT_SHARED", "출고 마감과 휴무일은 톡톡 설정의 배송 기준에서 바꿔요.");
        }
        return item;
    }

    /** Validate one answer and return its stored text. */
    static String clean(DefaultPolicyItem item, Integer number, String text) {
        String t = blankToNull(text);
        if (t != null && t.length() > DefaultPolicyItem.MAX_TEXT) {
            throw ApiException.badRequest("답은 " + DefaultPolicyItem.MAX_TEXT + "자까지 적을 수 있어요.");
        }
        if (KnowledgeBlanks.carries(t)) {
            throw ApiException.badRequest("「【확인」 표시는 답에 쓸 수 없어요. 정해진 내용만 적어 주세요.");
        }
        if (item.kind() == DefaultPolicyItem.Kind.DAYS && number != null
                && (number < 1 || number > DefaultPolicyItem.MAX_DAYS)) {
            throw ApiException.badRequest("날 수는 1에서 " + DefaultPolicyItem.MAX_DAYS + " 사이로 적어 주세요.");
        }
        if (item.kind() == DefaultPolicyItem.Kind.WON && number != null
                && (number < 0 || number > DefaultPolicyItem.MAX_WON)) {
            throw ApiException.badRequest("금액은 0원에서 " + String.format("%,d", DefaultPolicyItem.MAX_WON) + "원 사이로 적어 주세요.");
        }
        return t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
