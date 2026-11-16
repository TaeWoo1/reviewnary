package com.sellerops.agent.llm.reviewaspect;

import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.llm.operator.AgentLlmWireFormat;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The ONE door to the review-aspect model (owner decision 2026-11-17).
 *
 * <p>The org check sits here, as at every capability's door, and {@code AgentDraftBoundaryTest} asserts nothing else
 * constructs a {@link ReviewAspectGenerator}. Every call is charged to the org's daily AI ceiling (and the evaluation
 * budget, when one is set) before it goes out, and its tokens are written when it returns. Off is not an error: the
 * analysis keeps its word-list reading for any review this has not read.
 *
 * <p>The log line carries an org id, counts and a reason. Review words are never logged.
 */
@Service
public class ReviewAspectReader {

    private static final Logger log = LoggerFactory.getLogger(ReviewAspectReader.class);

    static final String READ_STEP = "ASPECT_READ";
    static final String OUTLOOK_STEP = "OUTLOOK_WRITE";

    private final ReviewAspectProperties properties;
    private final AgentLlmTransport transport;
    private final AgentQuotaService quota;

    public ReviewAspectReader(ReviewAspectProperties properties, AgentLlmTransport transport, AgentQuotaService quota) {
        this.properties = properties;
        this.transport = transport;
        this.quota = quota;
    }

    public boolean isEnabledFor(UUID orgId) {
        return properties.isEnabledFor(orgId);
    }

    public int batchSize() {
        return properties.batchSize();
    }

    public int maxCallsPerRequest() {
        return properties.maxCallsPerRequest();
    }

    /** The version stored beside every reading; it changes with the prompt, the model or the truncation. */
    public String readVersion() {
        return generator().readVersion();
    }

    public String outlookVersion() {
        return generator().outlookVersion();
    }

    /** Read one batch, or empty when the capability is off for this org or the ceiling is reached. */
    public Optional<ReviewAspectGenerator.ReadResult> read(UUID orgId, List<ReviewAspectGenerator.Item> items) {
        if (!properties.isEnabledFor(orgId) || items.isEmpty()) {
            return Optional.empty();
        }
        AgentQuotaService.Charge charge = quota.chargeCheck(orgId, AgentUsageKind.ANALYZE, READ_STEP, AgentUsageActor.USER);
        if (!charge.decision().allowed()) {
            log.info("review_aspect orgId={} step=read refused=quota", orgId);
            return Optional.empty();
        }
        ReviewAspectGenerator.ReadResult result = generator().read(items);
        quota.recordOutcome(charge.usageId(), result.metrics().elapsedMs(), result.metrics().promptTokens(),
                result.metrics().completionTokens());
        long read = result.readings().stream().filter(Optional::isPresent).count();
        log.info("review_aspect orgId={} step=read batch={} read={} reason={} dropped={}", orgId, items.size(), read,
                result.reason(), result.dropped());
        return Optional.of(result);
    }

    /** Write a product's opportunities and threats from its observed evidence, under the same gates. */
    public Optional<ReviewAspectGenerator.OutlookResult> writeOutlook(UUID orgId,
                                                                    List<ReviewAspectGenerator.EvidenceLine> evidence) {
        if (!properties.isEnabledFor(orgId) || evidence.isEmpty()) {
            return Optional.empty();
        }
        AgentQuotaService.Charge charge =
                quota.chargeCheck(orgId, AgentUsageKind.ANALYZE, OUTLOOK_STEP, AgentUsageActor.USER);
        if (!charge.decision().allowed()) {
            log.info("review_aspect orgId={} step=outlook refused=quota", orgId);
            return Optional.empty();
        }
        ReviewAspectGenerator.OutlookResult result = generator().writeOutlook(evidence);
        quota.recordOutcome(charge.usageId(), result.metrics().elapsedMs(), result.metrics().promptTokens(),
                result.metrics().completionTokens());
        log.info("review_aspect orgId={} step=outlook lines={} o={} t={} reason={}", orgId, evidence.size(),
                result.opportunities().size(), result.threats().size(), result.reason());
        return Optional.of(result);
    }

    /** Built per call, as at every door: a rotated key or a changed model takes effect without a restart. */
    private ReviewAspectGenerator generator() {
        return new ReviewAspectGenerator(transport, AgentLlmWireFormat.Vendor.of(properties.vendor()),
                properties.model(), properties.apiKey(), properties.maxOutputTokens(), properties.reasoningEffort(),
                properties.maxTextChars());
    }
}
