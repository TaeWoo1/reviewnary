package com.sellerops.talktalk;

import com.sellerops.common.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The lane's reads — every one scoped by the principal's org. */
@Service
public class TalkTalkQueryService {

    static final String LANE_NOTICE = "톡톡 챗봇 API 연동은 합성 시험까지만 확인했어요. 실제 톡톡 계정으로는 아직 연결·전송해 보지 않았어요.";

    private final TalkTalkConnectionService connections;
    private final TalkTalkGuidanceService guidance;
    private final TalkTalkConversationRepository conversations;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkDraftRepository drafts;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkAccess access;
    private final TalkTalkClock clock;

    public TalkTalkQueryService(TalkTalkConnectionService connections, TalkTalkGuidanceService guidance,
                                TalkTalkConversationRepository conversations, TalkTalkMessageRepository messages,
                                TalkTalkDraftRepository drafts, TalkTalkOutboundRepository outbound,
                                TalkTalkAccess access, TalkTalkClock clock) {
        this.connections = connections;
        this.guidance = guidance;
        this.conversations = conversations;
        this.messages = messages;
        this.drafts = drafts;
        this.outbound = outbound;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TalkTalkViews.StatusView status(UUID orgId, UUID userId) {
        access.member(orgId, userId);
        TalkTalkConnection c = connections.find(orgId).orElse(null);
        TalkTalkConnectionService.Readiness r = connections.readiness(c);
        TalkTalkViews.ReadinessView readiness = new TalkTalkViews.ReadinessView(r.ready(),
                r.items().stream().map(i -> new TalkTalkViews.ReadinessItemView(i.code(), i.met(), i.label())).toList(),
                r.residualRisk());
        TalkTalkViews.ConnectionView cv = c == null
                ? new TalkTalkViews.ConnectionView(false, "NONE", null, true, "연동 안 됨", "—", null, null)
                : new TalkTalkViews.ConnectionView(c.getStatus() == TalkTalkConnection.Status.CONNECTED,
                        c.getStatus().name(), c.getTransportMode().name(),
                        c.getTransportMode() == TalkTalkConnection.Mode.FAKE,
                        c.getTransportMode() == TalkTalkConnection.Mode.FAKE ? "모의 전송 — 실제 톡톡으로 가지 않아요"
                                : "실제 전송 방식", TalkTalkHashes.maskPartner(c.getPartnerId()), c.getLastEventAt(),
                        c.getCreatedAt());
        TalkTalkGuidance current = guidance.current(orgId).orElse(null);
        TalkTalkGuidance draft = guidance.versions(orgId).stream()
                .filter(g -> g.getStatus() == TalkTalkGuidance.Status.DRAFT).findFirst().orElse(null);
        TalkTalkGuidanceService.DelegationState d = guidance.delegation(orgId);
        Integer boundVersion = null;
        if (d.row() != null && d.row().getGuidanceId() != null) {
            boundVersion = guidance.byId(orgId, d.row().getGuidanceId()).map(TalkTalkGuidance::getVersion).orElse(null);
        }
        TalkTalkViews.DelegationView dv = new TalkTalkViews.DelegationView(d.active(), TalkTalkViews.Reason.of(d.reason()),
                boundVersion, d.row() == null ? null : d.row().getCreatedAt(),
                TalkTalkViews.ANSWERED_AUTOMATICALLY, TalkTalkViews.HANDED_TO_PERSON);
        return new TalkTalkViews.StatusView(cv, readiness, view(current), view(draft), dv, LANE_NOTICE);
    }

    @Transactional(readOnly = true)
    public TalkTalkViews.SummaryView summary(UUID orgId, UUID userId) {
        access.member(orgId, userId);
        TalkTalkConnection c = connections.find(orgId).orElse(null);
        boolean connected = c != null && c.getStatus() == TalkTalkConnection.Status.CONNECTED;
        long pending = conversations.countByOrgIdAndNeedsHumanTrue(orgId);
        long unknown = outbound.countByOrgIdAndStatusIn(orgId, java.util.EnumSet.of(TalkTalkOutbound.Status.UNKNOWN));
        long queued = outbound.countByOrgIdAndStatusIn(orgId,
                java.util.EnumSet.of(TalkTalkOutbound.Status.QUEUED, TalkTalkOutbound.Status.SENDING));
        return new TalkTalkViews.SummaryView(connected, c == null || c.getTransportMode() == TalkTalkConnection.Mode.FAKE,
                pending, unknown, queued, "홈의 문의·리뷰 숫자에는 톡톡 상담이 들어가지 않아요. 톡톡은 대화 단위로 따로 세요.");
    }

    public TalkTalkViews.GuidanceView view(TalkTalkGuidance g) {
        if (g == null) {
            return null;
        }
        List<TalkTalkViews.ClosedDateView> closed = TalkTalkGuidanceRules.closed(g.getClosedDates()).stream()
                .map(c -> new TalkTalkViews.ClosedDateView(c.date(), c.note())).toList();
        List<String> days = g.getDispatchDays().isBlank() ? List.of() : Arrays.asList(g.getDispatchDays().split(","));
        return new TalkTalkViews.GuidanceView(g.getId(), g.getVersion(), g.getStatus().name(), g.getCutoffHour(),
                g.getCutoffMinute(), days, closed, g.getConfirmedThrough(), g.getFingerprint(),
                TalkTalkGuidanceRules.previewFingerprint(g), previews(g), g.getConfirmedAt());
    }

    /** The customer text at three moments: now, the next weekday morning, and the next closed day. */
    List<TalkTalkViews.PreviewSample> previews(TalkTalkGuidance g) {
        Instant now = clock.now();
        LocalDate today = now.atZone(TalkTalkGuidanceRules.KST).toLocalDate();
        List<TalkTalkViews.PreviewSample> out = new ArrayList<>();
        out.add(sample("지금 물으면", g, now));
        LocalDate d = today.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        out.add(sample(TalkTalkGuidanceRules.dayLabel(d) + " 오전 9시에 물으면", g,
                d.atTime(LocalTime.of(9, 0)).atZone(TalkTalkGuidanceRules.KST).toInstant()));
        TalkTalkGuidanceRules.closed(g.getClosedDates()).stream().map(TalkTalkGuidanceRules.ClosedDate::date)
                .filter(x -> x.isAfter(today)).findFirst()
                .ifPresent(x -> out.add(sample(TalkTalkGuidanceRules.dayLabel(x) + "(휴무) 오전 10시에 물으면", g,
                        x.atTime(LocalTime.of(10, 0)).atZone(TalkTalkGuidanceRules.KST).toInstant())));
        return out;
    }

    private static TalkTalkViews.PreviewSample sample(String label, TalkTalkGuidance g, Instant at) {
        TalkTalkGuidanceRules.Rendered r = TalkTalkGuidanceRules.render(g, at);
        return new TalkTalkViews.PreviewSample(label, r.text(), TalkTalkViews.Reason.of(r.refusal()));
    }

    @Transactional(readOnly = true)
    public List<TalkTalkViews.ConversationRow> conversations(UUID orgId, UUID userId) {
        access.member(orgId, userId);
        List<TalkTalkConversation> list = conversations.findByOrgIdOrderByLastInboundAtDesc(orgId,
                PageRequest.of(0, 100));
        List<TalkTalkViews.ConversationRow> out = new ArrayList<>();
        for (TalkTalkConversation c : list) {
            out.add(row(c));
        }
        // Conversations with no customer message yet sort last.
        out.sort((a, b) -> {
            if (a.lastInboundAt() == null) {
                return b.lastInboundAt() == null ? 0 : 1;
            }
            return b.lastInboundAt() == null ? -1 : b.lastInboundAt().compareTo(a.lastInboundAt());
        });
        return out;
    }

    @Transactional(readOnly = true)
    public TalkTalkViews.ConversationDetail detail(UUID orgId, UUID userId, UUID conversationId) {
        access.member(orgId, userId);
        TalkTalkConversation c = conversations.findByIdAndOrgId(conversationId, orgId)
                .orElseThrow(() -> ApiException.notFound("대화를 찾을 수 없어요."));
        List<TalkTalkOutbound> sent = outbound.findByConversationIdOrderByCreatedAtDesc(c.getId());
        Map<UUID, TalkTalkOutbound> byId = sent.stream()
                .collect(Collectors.toMap(TalkTalkOutbound::getId, Function.identity()));
        List<TalkTalkViews.MessageView> lines = messages.findByConversationIdOrderByOccurredAtAscCreatedAtAsc(c.getId())
                .stream().map(m -> {
                    TalkTalkOutbound o = m.getOutboundId() == null ? null : byId.get(m.getOutboundId());
                    return new TalkTalkViews.MessageView(m.getId(), m.getAuthor().name(),
                            TalkTalkViews.authorLabel(m.getAuthor()), m.getTextContent(), m.getOccurredAt(),
                            m.isDuplicateSuspect(), m.isStandby(), m.getPurgedAt() != null,
                            o == null ? null : o.getStatus().name(), o == null ? null : TalkTalkViews.statusLabel(o.getStatus()),
                            m.getTextContent() == null ? TalkTalkViews.nonTextNote(m.getInputType()) : null);
                }).toList();
        TalkTalkMessage latest = messages
                .findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
                        c.getId(), TalkTalkMessage.Author.CUSTOMER).orElse(null);
        TalkTalkViews.DraftView draft = drafts.findFirstByConversationIdOrderByVersionDesc(c.getId()).map(d ->
                new TalkTalkViews.DraftView(d.getVersion(), d.getAuthorKind().name(), d.getTextContent(),
                        d.getContentFingerprint(), d.getBasis().name(),
                        d.getHoldReasons() == null ? List.of() : Arrays.stream(d.getHoldReasons().split(","))
                                .map(TalkTalkViews.Reason::of).toList(),
                        TalkTalkDraftService.evidence(d).stream()
                                .map(e -> new TalkTalkViews.EvidenceView(e.kind(), e.title())).toList(),
                        d.getCreatedAt(), latest != null && latest.getId().equals(d.getTriggerMessageId())))
                .orElse(null);
        List<TalkTalkViews.OutboundView> ov = sent.stream().map(o -> new TalkTalkViews.OutboundView(o.getId(),
                o.getKind().name(), TalkTalkViews.kindLabel(o.getKind()), o.getStatus().name(),
                TalkTalkViews.statusLabel(o.getStatus()), TalkTalkViews.Reason.of(o.getFailureCode()), o.getResultCode(),
                o.getTransportMode().name(), o.getEchoSeenAt() != null, o.getCreatedAt(), o.getFinishedAt(),
                o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE)).toList();
        boolean fake = connections.find(orgId).map(x -> x.getTransportMode() == TalkTalkConnection.Mode.FAKE)
                .orElse(true);
        return new TalkTalkViews.ConversationDetail(row(c), c.getLastProductName(), c.getLeftAt(),
                fake ? "FAKE" : "LIVE", fake, lines, draft, ov);
    }

    private TalkTalkViews.ConversationRow row(TalkTalkConversation c) {
        String preview = messages
                .findFirstByConversationIdAndAuthorOrderByOccurredAtDescCreatedAtDesc(c.getId(),
                        TalkTalkMessage.Author.CUSTOMER)
                .map(m -> m.getTextContent() != null ? m.getTextContent()
                        : TalkTalkViews.nonTextNote(m.getInputType()) != null ? "(사진·카드 메시지 — 파트너센터에서 확인)" : null)
                .map(t -> t.length() > 60 ? t.substring(0, 60) + "…" : t)
                .orElse(null);
        String lastStatus = outbound.findByConversationIdOrderByCreatedAtDesc(c.getId()).stream().findFirst()
                .map(o -> o.getStatus().name()).orElse(null);
        return new TalkTalkViews.ConversationRow(c.getId(), c.getAlias(), c.getThreadOwner().name(),
                TalkTalkViews.ownerLabel(c.getThreadOwner()), c.isNeedsHuman(),
                TalkTalkViews.Reason.of(c.getHumanReason()), TalkTalkViews.Reason.of(c.getAutomationNote()),
                c.getLastInboundAt(), preview, lastStatus);
    }
}
