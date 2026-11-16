package com.sellerops.talktalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.credential.LocalSecretSealer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Claim, recheck, send outside any transaction, record</b> — the intake-alert pattern (lane contract §5, §6-6).
 * Only a QUEUED row is claimed, and only once; a SENDING row whose answer never came back becomes UNKNOWN and is
 * never sent again on its own. Nothing here logs a key, a user value or a text.
 */
@Service
public class TalkTalkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TalkTalkDispatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkConnectionRepository connections;
    private final TalkTalkConversationRepository conversations;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkAutomationGate gate;
    private final TalkTalkTransport transport;
    private final LocalSecretSealer sealer;
    private final TalkTalkSettings settings;
    private final TalkTalkClock clock;
    private final TalkTalkConnectionService readiness;
    private final TalkTalkDraftRepository drafts;
    private final TalkTalkDraftService draftService;
    private final TransactionTemplate tx;

    public TalkTalkDispatcher(TalkTalkOutboundRepository outbound, TalkTalkConnectionRepository connections,
                              TalkTalkConversationRepository conversations, TalkTalkMessageRepository messages,
                              TalkTalkAutomationGate gate, TalkTalkTransport transport, LocalSecretSealer sealer,
                              TalkTalkSettings settings, TalkTalkClock clock, TalkTalkConnectionService readiness,
                              TalkTalkDraftRepository drafts, TalkTalkDraftService draftService,
                              PlatformTransactionManager txManager) {
        this.readiness = readiness;
        this.drafts = drafts;
        this.draftService = draftService;
        this.outbound = outbound;
        this.connections = connections;
        this.conversations = conversations;
        this.messages = messages;
        this.gate = gate;
        this.transport = transport;
        this.sealer = sealer;
        this.settings = settings;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Prepared(String sendKey, String body) {
        @Override
        public String toString() {
            return "Prepared(<masked>)";
        }
    }

    public int dispatchPending(int limit) {
        tx.executeWithoutResult(s -> outbound.markStaleUnknown(
                clock.now().minusSeconds(settings.sendingStaleSeconds()), clock.now()));
        List<UUID> ids = tx.execute(s -> outbound.queuedIds(PageRequest.of(0, limit)));
        int n = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            if (dispatch(id)) {
                n++;
            }
        }
        return n;
    }

    /** @return true when this call claimed the row (whatever the outcome) */
    public boolean dispatch(UUID outboundId) {
        Optional<Prepared> prepared = tx.execute(s -> claimAndPrepare(outboundId));
        if (prepared == null) {
            return false;
        }
        if (prepared.isEmpty()) {
            return true;
        }
        TalkTalkTransport.Outcome outcome;
        try {
            outcome = transport.send(prepared.get().sendKey(), prepared.get().body());
        } catch (RuntimeException e) {
            outcome = new TalkTalkTransport.Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "TRANSPORT_ERROR");
        }
        TalkTalkTransport.Outcome result = outcome == null
                ? new TalkTalkTransport.Outcome(TalkTalkOutbound.Status.UNKNOWN, null, "NO_OUTCOME") : outcome;
        tx.executeWithoutResult(s -> record(outboundId, result));
        return true;
    }

    /**
     * Inside one transaction: claim, then everything that may stop the send. Returns null when another caller has
     * the row, empty when the row was closed here (cancelled or blocked), or the request to send.
     */
    private Optional<Prepared> claimAndPrepare(UUID id) {
        Instant now = clock.now();
        if (outbound.claim(id, now) != 1) {
            return null;
        }
        TalkTalkOutbound o = outbound.findById(id).orElseThrow();
        // Lock first: a delegation, guidance or connection change either committed before this point and is seen
        // below, or waits until this transaction (and with it the decision to send) has committed.
        TalkTalkConnection c = connections.lockByOrgId(o.getOrgId()).orElse(null);
        TalkTalkConversation conv = c == null ? null
                : conversations.lockByIdAndOrgId(o.getConversationId(), o.getOrgId()).orElse(null);
        if (conv == null || c == null || !c.getId().equals(conv.getConnectionId())) {
            close(o, TalkTalkOutbound.Status.BLOCKED, "NO_CONVERSATION");
            return Optional.empty();
        }
        if (c.getStatus() != TalkTalkConnection.Status.CONNECTED) {
            close(o, o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE ? TalkTalkOutbound.Status.CANCELLED
                    : TalkTalkOutbound.Status.BLOCKED, "CONNECTION_OFF");
            return Optional.empty();
        }
        if (o.getConnectionGeneration() != c.getGeneration()) {
            // Made under an earlier connection (reconnect, other partner or key, rotation): never sent with these.
            close(o, TalkTalkOutbound.Status.BLOCKED, "CONNECTION_CHANGED");
            return Optional.empty();
        }
        if (!c.getPartnerId().equals(conv.getPartnerId())) {
            close(o, TalkTalkOutbound.Status.BLOCKED, "PARTNER_CHANGED");
            return Optional.empty();
        }
        if (o.getTransportMode() != c.getTransportMode() || o.getTransportMode() != transport.mode()) {
            close(o, TalkTalkOutbound.Status.BLOCKED, "MODE_CHANGED");
            return Optional.empty();
        }
        if (o.getTransportMode() == TalkTalkConnection.Mode.LIVE && !readiness.readiness(c).ready()) {
            // Flags alone never make a live send: the whole checklist, ingress observation included.
            close(o, TalkTalkOutbound.Status.BLOCKED, "LIVE_NOT_READY");
            return Optional.empty();
        }
        if (o.getKind() == TalkTalkOutbound.Kind.MANUAL_REPLY) {
            // Time of check = time of send: the draft's sources and text are rechecked again here, because the
            // material may have changed while the reply waited (review item 12).
            TalkTalkDraft d = o.getDraftId() == null ? null : drafts.findById(o.getDraftId()).orElse(null);
            java.util.Set<String> confirmed = new java.util.HashSet<>();
            if (o.getHoldConfirmed() != null) {
                confirmed.addAll(java.util.Arrays.asList(o.getHoldConfirmed().split(",")));
            }
            List<String> codes = d == null || !d.getContentFingerprint().equals(o.getContentFingerprint())
                    ? List.of("DRAFT_MISSING")
                    : draftService.recheck(o.getOrgId(), d).stream().filter(x -> !confirmed.contains(x)).toList();
            if (!codes.isEmpty()) {
                close(o, TalkTalkOutbound.Status.CANCELLED, "RECHECK_REQUIRED:" + codes.get(0));
                return Optional.empty();
            }
        }
        if (o.getKind() == TalkTalkOutbound.Kind.MANUAL_REPLY && o.getTriggerMessageId() != null) {
            TalkTalkMessage latest = messages
                    .findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
                            conv.getId(), TalkTalkMessage.Author.CUSTOMER).orElse(null);
            if (latest != null && !latest.getId().equals(o.getTriggerMessageId())) {
                // The customer wrote again after the seller reviewed this reply: they read it first.
                close(o, TalkTalkOutbound.Status.CANCELLED, "NEWER_MESSAGE");
                return Optional.empty();
            }
        }
        if (o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE) {
            Optional<String> stop = gate.recheckBeforeSend(c, conv, o, now);
            if (stop.isPresent()) {
                close(o, TalkTalkOutbound.Status.CANCELLED, stop.get());
                conv.setAutomationNote(stop.get());
                conv.setNeedsHuman(true);
                conv.setHumanReason(stop.get());
                conversations.save(conv);
                return Optional.empty();
            }
        }
        Optional<String> key = sealer.open(c.getSendKeyKeyId(), c.getSendKeySealed())
                .map(b -> new String(b, StandardCharsets.UTF_8));
        Optional<String> user = sealer.open(conv.getUserKeyId(), conv.getUserSealed())
                .map(b -> new String(b, StandardCharsets.UTF_8));
        if (key.isEmpty() || user.isEmpty()) {
            close(o, TalkTalkOutbound.Status.BLOCKED, "KEY_UNAVAILABLE");
            return Optional.empty();
        }
        return Optional.of(new Prepared(key.get(), body(o, user.get(), c.getPartnerId())));
    }

    /** The documented request bodies (README 「send 이벤트」, handover_v1.md). Package-visible for contract tests. */
    static String body(TalkTalkOutbound o, String user, String partner) {
        ObjectNode root = JSON.createObjectNode();
        switch (o.getKind()) {
            case MANUAL_REPLY, AUTO_GUIDANCE -> {
                root.put("event", "send");
                root.put("user", user);
                root.putObject("textContent").put("text", o.getTextContent());
            }
            case HANDOVER_PASS -> {
                root.put("event", "handover");
                root.put("user", user);
                root.put("partner", partner);
                root.putObject("options").put("control", "passThread").put("targetId", 1);
            }
            case HANDOVER_TAKE -> {
                root.put("event", "handover");
                root.put("user", user);
                root.put("partner", partner);
                root.putObject("options").put("control", "takeThread").put("metadata", "");
            }
        }
        return root.toString();
    }

    private void record(UUID id, TalkTalkTransport.Outcome outcome) {
        UUID orgId = outbound.orgIdOf(id).orElseThrow();
        // Same lock order as everywhere: connection, then conversation; the row itself is read after the lock.
        TalkTalkConnection c = connections.lockByOrgId(orgId).orElse(null);
        TalkTalkOutbound o = outbound.findById(id).orElseThrow();
        if (o.getStatus() != TalkTalkOutbound.Status.SENDING) {
            // Marked UNKNOWN by the stale sweep while the request was out: the late answer is recorded beside it.
            log.info("talktalk_send_late outboundId={} status={} late={}", id, o.getStatus(), outcome.status());
            return;
        }
        TalkTalkOutbound.Status status = outcome.status();
        if (status != TalkTalkOutbound.Status.FAKE_RECORDED && status != TalkTalkOutbound.Status.API_ACCEPTED
                && status != TalkTalkOutbound.Status.FAILED && status != TalkTalkOutbound.Status.UNKNOWN
                && status != TalkTalkOutbound.Status.BLOCKED) {
            status = TalkTalkOutbound.Status.UNKNOWN;
        }
        o.setStatus(status);
        o.setResultCode(outcome.resultCode());
        o.setFailureCode(outcome.failureCode());
        o.setFinishedAt(clock.now());
        outbound.save(o);
        log.info("talktalk_send outboundId={} kind={} mode={} status={}", id, o.getKind(), o.getTransportMode(),
                status);
        boolean accepted = status == TalkTalkOutbound.Status.API_ACCEPTED || status == TalkTalkOutbound.Status.FAKE_RECORDED;
        if (!accepted) {
            if (o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE) {
                String why = "AUTO_" + status.name();
                conversations.findByIdAndOrgId(o.getConversationId(), o.getOrgId()).ifPresent(conv -> {
                    conv.setNeedsHuman(true);
                    conv.setHumanReason(why);
                    conversations.save(conv);
                });
            }
            return;
        }
        TalkTalkConversation conv = conversations.lockByIdAndOrgId(o.getConversationId(), o.getOrgId()).orElseThrow();
        Instant now = clock.now();
        // The request went out under this generation; if the connection changed meanwhile, the message is still
        // logged (it was sent) but nothing about the conversation's ownership or open work is decided by it.
        boolean sameGeneration = c != null && c.getGeneration() == o.getConnectionGeneration();
        TalkTalkMessage latest = messages
                .findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
                        conv.getId(), TalkTalkMessage.Author.CUSTOMER).orElse(null);
        boolean answeredLatest = o.getTriggerMessageId() == null || latest == null
                || latest.getId().equals(o.getTriggerMessageId());
        if (!sameGeneration) {
            if (o.getKind() == TalkTalkOutbound.Kind.MANUAL_REPLY || o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE) {
                logService(o, conv, now);
                conversations.save(conv);
            }
            return;
        }
        switch (o.getKind()) {
            case MANUAL_REPLY, AUTO_GUIDANCE -> {
                logService(o, conv, now);
                if (o.getKind() == TalkTalkOutbound.Kind.AUTO_GUIDANCE) {
                    conv.setLastAutoAt(now);
                } else if (answeredLatest) {
                    // Only when nothing newer came in while the request was out: a new customer line stays open.
                    conv.setNeedsHuman(false);
                    conv.setHumanReason(null);
                }
            }
            case HANDOVER_PASS -> {
                conv.setThreadOwner(TalkTalkConversation.Owner.PARTNER);
                conv.setThreadOwnerAt(now);
                if (answeredLatest) {
                    conv.setNeedsHuman(false);
                    conv.setHumanReason(null);
                }
                outbound.cancelQueuedAuto(conv.getId(), "PASSED_TO_PARTNER", now);
            }
            case HANDOVER_TAKE -> {
                conv.setThreadOwner(TalkTalkConversation.Owner.BOT);
                conv.setThreadOwnerAt(now);
            }
        }
        conversations.save(conv);
    }

    private void logService(TalkTalkOutbound o, TalkTalkConversation conv, Instant now) {
        TalkTalkMessage m = new TalkTalkMessage();
        m.setOrgId(o.getOrgId());
        m.setConversationId(conv.getId());
        m.setOutboundId(o.getId());
        m.setAuthor(TalkTalkMessage.Author.SERVICE);
        m.setTextContent(o.getTextContent());
        m.setInputType("typing");
        m.setOccurredAt(now);
        messages.save(m);
        conv.setLastOutboundAt(now);
    }

    private void close(TalkTalkOutbound o, TalkTalkOutbound.Status status, String code) {
        o.setStatus(status);
        o.setFailureCode(code);
        o.setFinishedAt(clock.now());
        outbound.save(o);
        log.info("talktalk_send_closed outboundId={} status={} code={}", o.getId(), status, code);
    }
}
