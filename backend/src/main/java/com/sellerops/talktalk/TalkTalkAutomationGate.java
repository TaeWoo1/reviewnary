package com.sellerops.talktalk;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * <b>The one narrow exception to per-message approval</b> (lane contract §6). Decides whether one customer message may
 * be answered with the delegated guidance, and re-decides right before the send. Every condition is a reason to hand
 * the message to a person; there is no condition under which a person's answer is replaced.
 */
@Component
public class TalkTalkAutomationGate {

    private final TalkTalkGuidanceService guidance;
    private final TalkTalkFactCheck facts;
    private final TalkTalkMessageRepository messages;
    private final TalkTalkOutboundRepository outbound;
    private final TalkTalkSettings settings;

    public TalkTalkAutomationGate(TalkTalkGuidanceService guidance, TalkTalkFactCheck facts,
                                  TalkTalkMessageRepository messages, TalkTalkOutboundRepository outbound,
                                  TalkTalkSettings settings) {
        this.guidance = guidance;
        this.facts = facts;
        this.messages = messages;
        this.outbound = outbound;
        this.settings = settings;
    }

    public record Decision(boolean auto, String reason, String text, TalkTalkGuidance guidance,
                           TalkTalkDelegation delegation) {
        static Decision human(String reason) {
            return new Decision(false, reason, null, null, null);
        }
    }

    /** At intake of one customer message. */
    public Decision evaluate(TalkTalkConnection connection, TalkTalkConversation conversation, TalkTalkMessage message,
                             Instant now) {
        if (connection == null || connection.getStatus() != TalkTalkConnection.Status.CONNECTED) {
            return Decision.human("CONNECTION_OFF");
        }
        TalkTalkGuidanceService.DelegationState d = guidance.delegation(conversation.getOrgId());
        if (!d.active()) {
            return Decision.human(d.reason());
        }
        Optional<String> thread = threadBlock(conversation, message);
        if (thread.isPresent()) {
            return Decision.human(thread.get());
        }
        if (handoverUnresolved(conversation)) {
            return Decision.human("HANDOVER_PENDING");
        }
        if (message.isDuplicateSuspect()) {
            return Decision.human("DUPLICATE_SUSPECT");
        }
        if (conversation.getLastAutoAt() != null
                && conversation.getLastAutoAt().isAfter(now.minus(Duration.ofHours(settings.autoCooldownHours())))) {
            return Decision.human("RECENT_AUTO");
        }
        if (outbound.existsByConversationIdAndKindAndStatusIn(conversation.getId(),
                TalkTalkOutbound.Kind.AUTO_GUIDANCE,
                EnumSet.of(TalkTalkOutbound.Status.QUEUED, TalkTalkOutbound.Status.SENDING,
                        TalkTalkOutbound.Status.UNKNOWN))) {
            return Decision.human("AUTO_IN_FLIGHT");
        }
        TalkTalkShippingClassifier.Result topic =
                TalkTalkShippingClassifier.classify(message.getTextContent(), message.getInputType());
        if (!topic.answerable()) {
            return Decision.human(topic.reason());
        }
        TalkTalkGuidance g = d.guidance();
        TalkTalkGuidanceRules.Rendered rendered = TalkTalkGuidanceRules.render(g, now);
        if (!rendered.ok()) {
            return Decision.human(rendered.refusal());
        }
        LocalDate today = now.atZone(TalkTalkGuidanceRules.KST).toLocalDate();
        TalkTalkFactCheck.Result fact = facts.check(conversation.getOrgId(), g, today);
        if (!fact.clear()) {
            return Decision.human(fact.reason());
        }
        if (fact.productExceptions() && conversation.getLastProductName() != null) {
            return Decision.human("PRODUCT_EXCEPTION_POSSIBLE");
        }
        return new Decision(true, "AUTO_QUEUED", rendered.text(), g, d.row());
    }

    /** A LIVE connection answers automatically only what came through the documented trust boundary. */
    public static Optional<String> ingressBlock(TalkTalkConnection connection, boolean trustedSource) {
        if (connection.getTransportMode() == TalkTalkConnection.Mode.LIVE && !trustedSource) {
            return Optional.of("UNTRUSTED_SOURCE");
        }
        return Optional.empty();
    }

    /**
     * Right before the send, after the claim: everything again, plus what may have changed while it waited. A
     * reason means the outbound is cancelled and nothing is sent.
     */
    public Optional<String> recheckBeforeSend(TalkTalkConnection connection, TalkTalkConversation conversation,
                                              TalkTalkOutbound o, Instant now) {
        if (connection == null || connection.getStatus() != TalkTalkConnection.Status.CONNECTED) {
            return Optional.of("CONNECTION_OFF");
        }
        if (o.getCreatedAt() != null
                && o.getCreatedAt().isBefore(now.minusSeconds(settings.autoMaxAgeSeconds()))) {
            return Optional.of("STALE");
        }
        TalkTalkGuidanceService.DelegationState d = guidance.delegation(o.getOrgId());
        if (!d.active()) {
            return Optional.of(d.reason());
        }
        if (!d.row().getId().equals(o.getDelegationId())) {
            return Optional.of("DELEGATION_CHANGED");
        }
        if (!d.guidance().getId().equals(o.getGuidanceId())
                || !d.guidance().getFingerprint().equals(o.getGuidanceFingerprint())) {
            return Optional.of("GUIDANCE_CHANGED");
        }
        TalkTalkMessage trigger = o.getTriggerMessageId() == null ? null
                : messages.findById(o.getTriggerMessageId()).orElse(null);
        if (trigger == null || trigger.getTextContent() == null) {
            return Optional.of("TRIGGER_MISSING");
        }
        TalkTalkMessage latest = messages
                .findFirstByConversationIdAndAuthorAndDuplicateSuspectFalseOrderByOccurredAtDescCreatedAtDesc(
                        conversation.getId(), TalkTalkMessage.Author.CUSTOMER).orElse(null);
        if (latest == null || !latest.getId().equals(trigger.getId())) {
            return Optional.of("NEWER_MESSAGE");
        }
        Optional<String> thread = threadBlock(conversation, trigger);
        if (thread.isPresent()) {
            return thread;
        }
        if (handoverUnresolved(conversation)) {
            return Optional.of("HANDOVER_PENDING");
        }
        if (messages.existsByConversationIdAndAuthorAndOccurredAtAfter(conversation.getId(),
                TalkTalkMessage.Author.PARTNER_CENTER, trigger.getOccurredAt())) {
            return Optional.of("PARTNER_REPLIED");
        }
        if (messages.existsByConversationIdAndAuthorAndOccurredAtAfter(conversation.getId(),
                TalkTalkMessage.Author.SERVICE, trigger.getOccurredAt())) {
            return Optional.of("ALREADY_ANSWERED");
        }
        TalkTalkShippingClassifier.Result topic =
                TalkTalkShippingClassifier.classify(trigger.getTextContent(), trigger.getInputType());
        if (!topic.answerable()) {
            return Optional.of(topic.reason());
        }
        TalkTalkGuidanceRules.Rendered rendered = TalkTalkGuidanceRules.render(d.guidance(), now);
        if (!rendered.ok()) {
            return Optional.of(rendered.refusal());
        }
        if (!rendered.text().equals(o.getTextContent())) {
            return Optional.of("TEXT_CHANGED");
        }
        TalkTalkFactCheck.Result fact = facts.check(o.getOrgId(), d.guidance(),
                now.atZone(TalkTalkGuidanceRules.KST).toLocalDate());
        if (!fact.clear()) {
            return Optional.of(fact.reason());
        }
        if (fact.productExceptions() && conversation.getLastProductName() != null) {
            return Optional.of("PRODUCT_EXCEPTION_POSSIBLE");
        }
        return Optional.empty();
    }

    /**
     * A handover asked for but not settled — queued, being sent, its result unknown, or refused — leaves who owns the
     * thread unknown. Automation waits until a handover is accepted either way (review item 9).
     */
    boolean handoverUnresolved(TalkTalkConversation conversation) {
        return outbound.findFirstByConversationIdAndKindInOrderByCreatedAtDesc(conversation.getId(),
                        EnumSet.of(TalkTalkOutbound.Kind.HANDOVER_PASS, TalkTalkOutbound.Kind.HANDOVER_TAKE))
                .map(h -> h.getStatus() != TalkTalkOutbound.Status.API_ACCEPTED
                        && h.getStatus() != TalkTalkOutbound.Status.FAKE_RECORDED
                        && h.getStatus() != TalkTalkOutbound.Status.CANCELLED)
                .orElse(false);
    }

    private static Optional<String> threadBlock(TalkTalkConversation conversation, TalkTalkMessage message) {
        if (message.isStandby()) {
            return Optional.of("STANDBY");
        }
        if (conversation.getThreadOwner() != TalkTalkConversation.Owner.BOT) {
            return Optional.of("PARTNER_OWNS_THREAD");
        }
        if (conversation.getLeftAt() != null && message.getOccurredAt() != null
                && !conversation.getLeftAt().isBefore(message.getOccurredAt())) {
            return Optional.of("CUSTOMER_LEFT");
        }
        return Optional.empty();
    }
}
