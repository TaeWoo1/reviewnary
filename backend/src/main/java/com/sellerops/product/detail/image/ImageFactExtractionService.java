package com.sellerops.product.detail.image;

import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.AgentUsageKind;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The image-knowledge capability's one door.
 *
 * <p>Same shape as every other LLM capability here, for the same reason: the org allow-list is
 * checked HERE, so a caller that held {@link ImageFactExtractionGenerator} directly would be an
 * allow-list nobody runs. {@code AgentDraftBoundaryTest} enforces that this is the only class in
 * {@code main} that may construct one.
 *
 * <p>The generator is null when the capability is off — there is nothing to construct without a key,
 * and a live object behind a disabled flag is one refactor away from being called.
 *
 * <p><b>Every picture is charged before it is read</b> (2026-11-19, FINDINGS B215). The first pilot run called the vendor
 * 25 times and wrote no {@code agent_llm_usage} row, so the day's ceiling and the money budget neither counted nor
 * stopped it. Each read now asks {@link AgentQuotaService} first — the same door the review-aspect reader uses — and is
 * refused as {@link #QUOTA_REFUSED} with no vendor call when there is no room; its tokens are recorded after.
 */
@Service
public class ImageFactExtractionService {

    /** The reason a read carries when the day's ceiling or the money budget had no room. No vendor was reached. */
    public static final String QUOTA_REFUSED = "quota_refused";

    /** The meter's step name for one picture. */
    public static final String READ_STEP = "IMAGE_READ";

    private final ImageKnowledgeProperties properties;
    private final ImageFactExtractionGenerator generator;
    private final AgentQuotaService quota;

    @Autowired
    public ImageFactExtractionService(ImageKnowledgeProperties properties,
                                      com.sellerops.agent.llm.AgentLlmTransport transport,
                                      AgentQuotaService quota) {
        this.properties = properties;
        this.quota = quota;
        this.generator = properties.apiKey() == null || properties.apiKey().isBlank() ? null
                : new ImageFactExtractionGenerator(transport,
                        ImageFactExtractionGenerator.Vendor.of(properties.vendor()),
                        properties.model(), properties.apiKey(), properties.maxOutputTokens(),
                        properties.reasoningEffort());
    }

    public boolean isEnabledFor(UUID orgId) {
        return generator != null && properties.isEnabledFor(orgId);
    }

    /** What produced a stored receipt. Part of its identity, so an upgrade re-reads. */
    public String extractorVersion() {
        return generator == null ? ImageFactExtractionGenerator.EXTRACTOR_VERSION : generator.version();
    }

    /** The model identity, stored separately so a model change alone invalidates a reading. */
    public String modelVersion() {
        return generator == null ? "none" : generator.modelVersion();
    }

    /**
     * Read one picture. Every failure is a {@link ImageFactExtractionGenerator.Result} with no facts
     * and a sanitized reason; nothing throws at the caller.
     */
    public ImageFactExtractionGenerator.Result read(UUID orgId, byte[] imageBytes, String contentType) {
        return read(orgId, imageBytes, contentType, AgentUsageActor.RUN);
    }

    /**
     * Read one picture on behalf of {@code actor}: {@link AgentUsageActor#RUN} for work no seller pressed (it stops where
     * the seller's reserve begins), {@link AgentUsageActor#USER} for a seller's own press.
     */
    public ImageFactExtractionGenerator.Result read(UUID orgId, byte[] imageBytes, String contentType,
                                                    AgentUsageActor actor) {
        if (!isEnabledFor(orgId)) {
            return ImageFactExtractionGenerator.Result.failed("disabled");
        }
        AgentQuotaService.Charge charge = quota.chargeCheck(orgId, AgentUsageKind.IMAGE, READ_STEP, actor);
        if (!charge.decision().allowed()) {
            return ImageFactExtractionGenerator.Result.failed(QUOTA_REFUSED);
        }
        long started = System.nanoTime();
        ImageFactExtractionGenerator.Result result;
        try {
            result = generator.generate(imageBytes, contentType);
        } catch (RuntimeException e) {
            // The class only. A vendor exception message can quote the request, and the request is
            // the seller's own picture.
            result = ImageFactExtractionGenerator.Result.failed("threw:" + e.getClass().getSimpleName());
        }
        // A call that never reported its tokens stays unknown (null), which the budget prices at its worst case.
        boolean reported = result.usage().promptTokens() > 0 || result.usage().completionTokens() > 0;
        quota.recordOutcome(charge.usageId(), (System.nanoTime() - started) / 1_000_000L,
                reported ? result.usage().promptTokens() : null, reported ? result.usage().completionTokens() : null);
        return result;
    }
}
