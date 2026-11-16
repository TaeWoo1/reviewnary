package com.sellerops.talktalk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns accepted webhook events into conversation state, one event per transaction (lane contract §4). An event is
 * claimed by a conditional update, so two workers never process it twice; an echo never produces a reply, and a
 * partner's message or a {@code standby} flag moves the persisted thread owner to PARTNER.
 */
@Service
public class TalkTalkEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TalkTalkEventProcessor.class);
    /** TalkTalk's id for the partner (파트너센터) in handover and echo options (handover_v1.md). */
    static final long PARTNER_ID = 1L;

    private final TalkTalkEventRepository events;
    private final TalkTalkConnectionRepository connections;
    private final TalkTalkConversationRepository conversations;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkAutomationGate gate;
    private final TalkTalkSettings settings;
    private final TalkTalkClock clock;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public TalkTalkEventProcessor(TalkTalkEventRepository events, TalkTalkConnectionRepository connections,
                                  TalkTalkConversationRepository conversations, TalkTalkMessageRepository messages,
                                  TalkTalkOutboundRepository outbound, TalkTalkAutomationGate gate,
                                  TalkTalkSettings settings, TalkTalkClock clock, ApplicationEventPublisher publisher,
                                  PlatformTransactionManager txManager) {
        this.events = events;
        this.connections = connections;
        this.conversations = conversations;
        this.messages = messages;
        this.outbound = outbound;
        this.gate = gate;
        this.settings = settings;
        this.clock = clock;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Process up to {@code limit} accepted events in receipt order. Returns how many were processed. */
    public int processPending(int limit) {
        tx.executeWithoutResult(s -> events.releaseStale(clock.now().minusSeconds(120), clock.now()));
        List<UUID> ids = tx.execute(s -> events.acceptedIds(PageRequest.of(0, limit)));
        int done = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            if (process(id)) {
                done++;
            }
        }
        return done;
    }

    public boolean process(UUID eventId) {
        try {
            Boolean ok = tx.execute(s -> {
                if (events.claim(eventId, clock.now()) != 1) {
                    return false;
                }
                TalkTalkEvent e = events.findById(eventId).orElseThrow();
                String note = handle(e);
                e.setState(note == null ? TalkTalkEvent.State.PROCESSED : TalkTalkEvent.State.IGNORED);
                e.setNote(note);
                e.setProcessedAt(clock.now());
                events.save(e);
                return true;
            });
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException ex) {
            log.warn("talktalk_event_failed eventId={} error={}", eventId, ex.getClass().getSimpleName());
            tx.executeWithoutResult(s -> events.findById(eventId).ifPresent(e -> {
                e.setState(TalkTalkEvent.State.FAILED);
                e.setNote("PROCESSING_ERROR");
                e.setProcessedAt(clock.now());
                events.save(e);
            }));
            return false;
        }
    }

    /** @return null when processed, or why the event was ignored */
    private String handle(TalkTalkEvent e) {
        // Lock order: connection, then conversation. Processing is serialized per org with intake, rotation,
        // reconnect, sends and dispatch, so two events never race on one conversation's owner or open work.
        TalkTalkConnection c = connections.lockByOrgId(e.getOrgId()).orElse(null);
        if (c == null || !c.getId().equals(e.getConnectionId())) {
            return "NO_CONNECTION";
        }
        if (e.getConnectionGeneration() != c.getGeneration() || c.getStatus() != TalkTalkConnection.Status.CONNECTED) {
            // Received under a connection that has since been reconnected, rotated or disconnected.
            return "STALE_GENERATION";
        }
        if (e.getUserHash() == null || e.getUserSealed() == null) {
            return "NO_USER";
        }
        return switch (e.getEventType()) {
            case "open" -> {
                conversation(c, e);
                yield null;
            }
            case "leave" -> {
                TalkTalkConversation conv = conversation(c, e);
                conv.setLeftAt(e.getReceivedAt());
                conversations.save(conv);
                outbound.cancelQueuedAuto(conv.getId(), "CUSTOMER_LEFT", clock.now());
                yield null;
            }
            case "send" -> {
                customerMessage(c, e);
                yield null;
            }
            case "echo" -> {
                echo(c, e);
                yield null;
            }
            case "handover" -> handover(c, e);
            default -> "NOT_HANDLED";
        };
    }

    private TalkTalkConversation conversation(TalkTalkConnection c, TalkTalkEvent e) {
        return conversations.findByOrgIdAndConnectionIdAndPartnerIdAndUserHash(c.getOrgId(), c.getId(),
                        c.getPartnerId(), e.getUserHash())
                .flatMap(found -> conversations.lockByIdAndOrgId(found.getId(), c.getOrgId()))
                .orElseGet(() -> {
                    TalkTalkConversation conv = new TalkTalkConversation();
                    conv.setOrgId(c.getOrgId());
                    conv.setConnectionId(c.getId());
                    conv.setPartnerId(c.getPartnerId());
                    conv.setUserHash(e.getUserHash());
                    conv.setUserSealed(e.getUserSealed());
                    conv.setUserKeyId(e.getUserKeyId());
                    conv.setAlias(TalkTalkHashes.alias(e.getUserHash()));
                    conv.setThreadOwner(TalkTalkConversation.Owner.BOT);
                    return conversations.save(conv);
                });
    }

    private void customerMessage(TalkTalkConnection c, TalkTalkEvent e) {
        TalkTalkConversation conv = conversation(c, e);
        Instant at = e.getReceivedAt();
        TalkTalkMessage m = new TalkTalkMessage();
        m.setOrgId(c.getOrgId());
        m.setConversationId(conv.getId());
        m.setEventId(e.getId());
        m.setAuthor(TalkTalkMessage.Author.CUSTOMER);
        m.setTextContent(e.getTextContent());
        m.setInputType(e.getInputType());
        m.setStandby(e.isStandby());
        m.setBodyHash(e.getBodyHash());
        m.setOccurredAt(at);
        m = messages.save(m);
        // The protocol has no event id: the same body again within the window is kept, marked, and never answered
        // automatically a second time. A customer may really repeat themselves, so nothing is discarded.
        boolean duplicate = messages.existsByConversationIdAndAuthorAndBodyHashAndOccurredAtAfterAndIdNot(conv.getId(),
                TalkTalkMessage.Author.CUSTOMER, e.getBodyHash(), at.minusSeconds(settings.duplicateWindowSeconds()),
                m.getId());
        if (duplicate) {
            m.setDuplicateSuspect(true);
            m = messages.save(m);
        }
        if (e.isStandby()) {
            setOwner(conv, TalkTalkConversation.Owner.PARTNER, at);
        }
        if (e.getProductName() != null) {
            conv.setLastProductName(e.getProductName());
        }
        conv.setLastInboundAt(at);
        // A newer message supersedes any automatic guidance still waiting for an older one. A suspected duplicate
        // is not newer information: it neither cancels the original's guidance nor gets one of its own.
        if (!duplicate) {
            outbound.cancelQueuedAuto(conv.getId(), "NEWER_MESSAGE", clock.now());
        }
        TalkTalkMessage evaluated = m;
        TalkTalkAutomationGate.Decision d = TalkTalkAutomationGate.ingressBlock(c, e.isTrustedSource())
                .map(TalkTalkAutomationGate.Decision::human)
                .orElseGet(() -> gate.evaluate(c, conv, evaluated, clock.now()));
        if (!duplicate) {
            conv.setAutomationNote(d.reason());
        }
        if (d.auto()) {
            TalkTalkOutbound o = new TalkTalkOutbound();
            o.setOrgId(c.getOrgId());
            o.setConversationId(conv.getId());
            o.setKind(TalkTalkOutbound.Kind.AUTO_GUIDANCE);
            o.setTextContent(d.text());
            o.setContentFingerprint(TalkTalkHashes.replyFingerprint(d.text()));
            o.setCommandId("auto:" + m.getId());
            o.setTriggerMessageId(m.getId());
            o.setGuidanceId(d.guidance().getId());
            o.setGuidanceFingerprint(d.guidance().getFingerprint());
            o.setDelegationId(d.delegation().getId());
            o.setTransportMode(c.getTransportMode());
            o.setConnectionGeneration(c.getGeneration());
            o.setStatus(TalkTalkOutbound.Status.QUEUED);
            o.setCreatedAt(clock.now());
            outbound.save(o);
            conv.setNeedsHuman(false);
            conv.setHumanReason(null);
            publisher.publishEvent(new TalkTalkWorkSignal());
        } else if (!duplicate) {
            conv.setNeedsHuman(true);
            conv.setHumanReason(d.reason());
        }
        conversations.save(conv);
    }

    private void echo(TalkTalkConnection c, TalkTalkEvent e) {
        TalkTalkConversation conv = conversation(c, e);
        Instant at = e.getReceivedAt();
        boolean partner = e.getSourceId() != null && e.getSourceId() == PARTNER_ID;
        if (!partner && e.getTextContent() != null) {
            List<TalkTalkOutbound> ours = outbound.echoCandidates(conv.getId(),
                    TalkTalkHashes.replyFingerprint(e.getTextContent()));
            if (!ours.isEmpty()) {
                TalkTalkOutbound o = ours.get(0);
                o.setEchoSeenAt(at);
                outbound.save(o);
                return;
            }
        }
        TalkTalkMessage m = new TalkTalkMessage();
        m.setOrgId(c.getOrgId());
        m.setConversationId(conv.getId());
        m.setEventId(e.getId());
        m.setAuthor(partner ? TalkTalkMessage.Author.PARTNER_CENTER : TalkTalkMessage.Author.BOT_ECHO);
        m.setTextContent(e.getTextContent());
        m.setInputType(e.getInputType());
        m.setBodyHash(e.getBodyHash());
        m.setOccurredAt(at);
        messages.save(m);
        // Someone other than this service is speaking in the conversation: stop what automation still has queued.
        outbound.cancelQueuedAuto(conv.getId(), partner ? "PARTNER_REPLIED" : "UNMATCHED_ECHO", clock.now());
        if (partner || (e.getThreadOwnerId() != null && e.getThreadOwnerId() == PARTNER_ID)) {
            setOwner(conv, TalkTalkConversation.Owner.PARTNER, at);
        }
        conversations.save(conv);
    }

    private String handover(TalkTalkConnection c, TalkTalkEvent e) {
        TalkTalkConversation conv = conversation(c, e);
        String control = e.getHandoverControl();
        if ("passThread".equals(control)) {
            if (e.getHandoverTarget() != null && e.getHandoverTarget() == PARTNER_ID) {
                setOwner(conv, TalkTalkConversation.Owner.PARTNER, e.getReceivedAt());
                outbound.cancelQueuedAuto(conv.getId(), "PASSED_TO_PARTNER", clock.now());
            } else {
                // 파트너 「상담 완료하기」 — the thread comes back to the bot.
                setOwner(conv, TalkTalkConversation.Owner.BOT, e.getReceivedAt());
            }
            conversations.save(conv);
            return null;
        }
        if ("takeThread".equals(control)) {
            setOwner(conv, TalkTalkConversation.Owner.BOT, e.getReceivedAt());
            conversations.save(conv);
            return null;
        }
        return "HANDOVER_UNKNOWN_CONTROL";
    }

    private static void setOwner(TalkTalkConversation conv, TalkTalkConversation.Owner owner, Instant at) {
        if (conv.getThreadOwner() != owner) {
            conv.setThreadOwner(owner);
            conv.setThreadOwnerAt(at);
        }
    }

    Optional<TalkTalkEvent> event(UUID id) {
        return events.findById(id);
    }
}
