package com.sellerops.talktalk;

import com.sellerops.common.ApiException;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A signed-in seller's reply or handover, queued for the dispatcher (lane contract §5). The send names the exact
 * reviewed version: head version and fingerprint must match, the draft may not be held, and what it was based on is
 * rechecked. One command id is one outbound; the same command with the same content returns it, any other content
 * is refused.
 */
@Service
public class TalkTalkSendService {

    private final TalkTalkConversationRepository conversations;
    private final TalkTalkDraftRepository drafts;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkConnectionService connections;
    private final TalkTalkDraftService draftService;
    private final TalkTalkAccess access;
    private final TalkTalkClock clock;
    private final ApplicationEventPublisher publisher;

    public TalkTalkSendService(TalkTalkConversationRepository conversations, TalkTalkDraftRepository drafts,
                               TalkTalkOutboundRepository outbound, TalkTalkMessageRepository messages,
                               TalkTalkConnectionService connections, TalkTalkDraftService draftService,
                               TalkTalkAccess access, TalkTalkClock clock, ApplicationEventPublisher publisher) {
        this.conversations = conversations;
        this.drafts = drafts;
        this.outbound = outbound;
        this.messages = messages;
        this.connections = connections;
        this.draftService = draftService;
        this.access = access;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional
    public TalkTalkOutbound send(UUID orgId, UUID userId, UUID conversationId, String commandId, Integer draftVersion,
                                 String expectedFingerprint, UUID acknowledgeUnknownOf) {
        return send(orgId, userId, conversationId, commandId, draftVersion, expectedFingerprint, acknowledgeUnknownOf,
                false);
    }

    /**
     * @param confirmHolds the seller saw the holds on their own sentence and sends it as it is — accepted only for a
     *                     seller-written draft, recorded on the outbound; a model or guidance draft with a hold must
     *                     be rewritten
     */
    @Transactional
    public TalkTalkOutbound send(UUID orgId, UUID userId, UUID conversationId, String commandId, Integer draftVersion,
                                 String expectedFingerprint, UUID acknowledgeUnknownOf, boolean confirmHolds) {
        access.member(orgId, userId);
        String cmd = command(commandId);
        // Lock order: connection, then conversation — the same as intake, processing and dispatch. Two sends with
        // different command ids for one conversation are serialized here; the second sees the first (review item 12).
        TalkTalkConnection c = connections.lockConnected(orgId);
        TalkTalkConversation conv = conversations.lockByIdAndOrgId(conversationId, orgId)
                .orElseThrow(() -> ApiException.notFound("대화를 찾을 수 없어요."));
        TalkTalkOutbound existing = outbound.findByOrgIdAndCommandId(orgId, cmd).orElse(null);
        if (existing != null) {
            if (existing.getConversationId().equals(conv.getId())
                    && existing.getContentFingerprint().equals(expectedFingerprint)) {
                return existing;
            }
            throw ApiException.conflict("COMMAND_REUSED", "이미 다른 내용으로 쓴 요청이에요. 새로고침 후 다시 보내 주세요.");
        }
        TalkTalkDraft head = drafts.findFirstByConversationIdOrderByVersionDesc(conv.getId())
                .orElseThrow(() -> ApiException.conflict("NO_DRAFT", "보낼 답변이 없어요."));
        if (draftVersion == null || head.getVersion() != draftVersion || expectedFingerprint == null
                || !head.getContentFingerprint().equals(expectedFingerprint)) {
            throw ApiException.conflict("DRAFT_CHANGED", "확인하신 뒤 답변이 바뀌었어요. 바뀐 답변을 보고 다시 보내 주세요.");
        }
        if (head.getTextContent() == null || head.getTextContent().isBlank()) {
            throw ApiException.conflict("NO_DRAFT", "보낼 문장이 없어요.");
        }
        java.util.Set<String> holds = holds(head);
        boolean fixFirst = holds.stream().anyMatch(TalkTalkDraftService.FIX_FIRST::contains);
        boolean confirmable = head.getAuthorKind() == TalkTalkDraft.AuthorKind.SELLER_EDIT && confirmHolds && !fixFirst;
        if (!holds.isEmpty() && !confirmable) {
            throw ApiException.conflict("DRAFT_HELD", fixFirst
                    ? "가게 자료와 다른 내용이 있어요. 문장을 고친 뒤 보내 주세요."
                    : head.getAuthorKind() == TalkTalkDraft.AuthorKind.SELLER_EDIT
                            ? "확인이 필요한 문장이에요. 표시된 내용을 보고 이번 건만 예외로 보낼지 확인해 주세요."
                            : "확인이 필요한 답변이에요. 문장을 고친 뒤 보내 주세요.");
        }
        List<String> recheck = draftService.recheck(orgId, head).stream()
                .filter(code -> !(confirmable && holds.contains(code))).toList();
        if (!recheck.isEmpty()) {
            throw ApiException.conflict("RECHECK_REQUIRED", "참고한 내용이 바뀌었어요: "
                    + String.join(" · ", recheck.stream().map(TalkTalkReasons::label).toList()));
        }
        if (!c.getPartnerId().equals(conv.getPartnerId())) {
            throw ApiException.conflict("PARTNER_CHANGED", "다른 톡톡 계정으로 다시 연결해서 이 대화로는 보낼 수 없어요.");
        }
        TalkTalkMessage latest = messages
                .findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
                        conv.getId(), TalkTalkMessage.Author.CUSTOMER).orElse(null);
        if (latest != null && head.getTriggerMessageId() != null && !latest.getId().equals(head.getTriggerMessageId())) {
            throw ApiException.conflict("NEWER_MESSAGE", "답변을 준비한 뒤 고객이 새 메시지를 보냈어요. 새 메시지를 보고 다시 준비해 주세요.");
        }
        // Keyed by the conversation, not the draft: a new draft version with the same words must not slip past an
        // earlier send whose result is unknown (review item 9). Each UNKNOWN reply needs its own acknowledgement.
        List<TalkTalkOutbound> priors = outbound.findByConversationIdOrderByCreatedAtDesc(conv.getId());
        java.util.Set<UUID> acknowledged = new java.util.HashSet<>();
        for (TalkTalkOutbound p : priors) {
            if (p.getAcknowledgedUnknownOf() != null) {
                acknowledged.add(p.getAcknowledgedUnknownOf());
            }
        }
        for (TalkTalkOutbound prior : priors) {
            if (prior.getKind() != TalkTalkOutbound.Kind.MANUAL_REPLY) {
                continue;
            }
            switch (prior.getStatus()) {
                case QUEUED, SENDING -> throw ApiException.conflict("IN_FLIGHT", "이 대화에 보내는 중인 답변이 있어요.");
                case API_ACCEPTED, FAKE_RECORDED -> {
                    if (prior.getContentFingerprint().equals(head.getContentFingerprint())
                            && java.util.Objects.equals(prior.getTriggerMessageId(), head.getTriggerMessageId())) {
                        throw ApiException.conflict("ALREADY_SENT", "같은 메시지에 같은 답변을 이미 보냈어요.");
                    }
                }
                case UNKNOWN -> {
                    if (!acknowledged.contains(prior.getId()) && !prior.getId().equals(acknowledgeUnknownOf)) {
                        throw ApiException.conflict("UNKNOWN_PENDING",
                                "앞서 보낸 답변의 결과를 몰라요. 톡톡에서 실제로 가지 않았는지 확인한 뒤 다시 보내 주세요.");
                    }
                }
                default -> {
                }
            }
        }
        if (acknowledgeUnknownOf != null && priors.stream().noneMatch(p -> p.getId().equals(acknowledgeUnknownOf)
                && p.getStatus() == TalkTalkOutbound.Status.UNKNOWN)) {
            throw ApiException.badRequest("확인한 발신을 찾을 수 없어요.");
        }
        TalkTalkOutbound o = new TalkTalkOutbound();
        o.setOrgId(orgId);
        o.setConversationId(conv.getId());
        o.setKind(TalkTalkOutbound.Kind.MANUAL_REPLY);
        o.setTextContent(head.getTextContent());
        o.setContentFingerprint(head.getContentFingerprint());
        o.setCommandId(cmd);
        o.setDraftId(head.getId());
        o.setDraftVersion(head.getVersion());
        o.setTriggerMessageId(head.getTriggerMessageId());
        o.setGuidanceId(head.getGuidanceId());
        o.setActorUserId(userId);
        o.setAcknowledgedUnknownOf(acknowledgeUnknownOf);
        o.setHoldConfirmed(holds.isEmpty() ? null : String.join(",", holds));
        o.setTransportMode(c.getTransportMode());
        o.setConnectionGeneration(c.getGeneration());
        o.setStatus(TalkTalkOutbound.Status.QUEUED);
        o.setCreatedAt(clock.now());
        TalkTalkOutbound saved = outbound.save(o);
        // The seller answered: whatever automation still had queued for this conversation stops.
        outbound.cancelQueuedAuto(conv.getId(), "ALREADY_ANSWERED", clock.now());
        publisher.publishEvent(new TalkTalkWorkSignal());
        return saved;
    }

    @Transactional
    public TalkTalkOutbound handover(UUID orgId, UUID userId, UUID conversationId, String commandId, String direction) {
        access.member(orgId, userId);
        String cmd = command(commandId);
        TalkTalkConnection c = connections.lockConnected(orgId);
        TalkTalkConversation conv = conversations.lockByIdAndOrgId(conversationId, orgId)
                .orElseThrow(() -> ApiException.notFound("대화를 찾을 수 없어요."));
        TalkTalkOutbound.Kind kind = switch (direction == null ? "" : direction) {
            case "PASS" -> TalkTalkOutbound.Kind.HANDOVER_PASS;
            case "TAKE" -> TalkTalkOutbound.Kind.HANDOVER_TAKE;
            default -> throw ApiException.badRequest("넘기기 방향을 골라 주세요.");
        };
        String fingerprint = TalkTalkHashes.sha256("talktalk-handover:" + kind + ":" + conv.getId());
        TalkTalkOutbound existing = outbound.findByOrgIdAndCommandId(orgId, cmd).orElse(null);
        if (existing != null) {
            if (existing.getContentFingerprint().equals(fingerprint)) {
                return existing;
            }
            throw ApiException.conflict("COMMAND_REUSED", "이미 다른 요청에 쓴 번호예요.");
        }
        if (outbound.existsByConversationIdAndKindAndStatusIn(conv.getId(), kind,
                EnumSet.of(TalkTalkOutbound.Status.QUEUED, TalkTalkOutbound.Status.SENDING))) {
            throw ApiException.conflict("IN_FLIGHT", "같은 요청을 보내는 중이에요.");
        }
        if (!c.getPartnerId().equals(conv.getPartnerId())) {
            throw ApiException.conflict("PARTNER_CHANGED", "다른 톡톡 계정으로 다시 연결해서 이 대화로는 보낼 수 없어요.");
        }
        TalkTalkOutbound o = new TalkTalkOutbound();
        o.setOrgId(orgId);
        o.setConversationId(conv.getId());
        o.setKind(kind);
        o.setContentFingerprint(fingerprint);
        o.setCommandId(cmd);
        o.setActorUserId(userId);
        o.setTransportMode(c.getTransportMode());
        o.setConnectionGeneration(c.getGeneration());
        o.setStatus(TalkTalkOutbound.Status.QUEUED);
        o.setCreatedAt(clock.now());
        TalkTalkOutbound saved = outbound.save(o);
        if (kind == TalkTalkOutbound.Kind.HANDOVER_PASS) {
            outbound.cancelQueuedAuto(conv.getId(), "PASSED_TO_PARTNER", clock.now());
        }
        publisher.publishEvent(new TalkTalkWorkSignal());
        return saved;
    }

    static java.util.Set<String> holds(TalkTalkDraft d) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (d.getHoldReasons() != null) {
            for (String h : d.getHoldReasons().split(",")) {
                if (!h.isBlank()) {
                    out.add(h.strip());
                }
            }
        }
        return out;
    }

    private static String command(String commandId) {
        if (commandId == null || commandId.isBlank() || commandId.length() > 80 || commandId.startsWith("auto:")) {
            throw ApiException.badRequest("요청 번호가 올바르지 않아요.");
        }
        return commandId.strip();
    }
}
