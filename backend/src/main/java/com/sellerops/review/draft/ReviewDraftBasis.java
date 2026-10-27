package com.sellerops.review.draft;

import com.sellerops.attention.reply.ReviewReplyTemplateKey;
import com.sellerops.attention.reply.ReviewReplyTemplateService;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>What a review reply draft is written from, as three fingerprints</b> (2026-10-22 external review — 「새 정책이
 * 기존 초안에 적용되는 흐름」).
 *
 * <p>A draft the seller has not approved yet was written under the knowledge, policy and wording the seller had at
 * the time. When any of those changes, the draft is sent back and checked again before it can be approved (vendor
 * server contract §4.2; product-owner answer to interview question 34, 2026-10-12). These three values are how the
 * change is noticed — each is a digest of the seller's own rows that reach a review draft, and nothing else:
 *
 * <ul>
 *   <li><b>knowledge</b> — the product knowledge library: every source's product, variant, type, title, body and
 *       whether it is retired.</li>
 *   <li><b>policy</b> — the company's operating rules (org knowledge) the same way, and the commitment terms,
 *       condition terms and refusal words {@link ReviewClaimGuard} holds a reply to.</li>
 *   <li><b>voice</b> — the review reply wording per template key, the seller's own or the shipped default.</li>
 * </ul>
 *
 * <p><b>Answer memory is in none of them, on purpose.</b> Every approval adds a past reply; counting it would send
 * every waiting draft back each time the seller approves another. A past reply is a way of saying and never
 * authorizes a fact or a promise ({@link ReviewDraftComposer}), so a new one cannot make a waiting draft wrong.
 * Nor is the inquiry lane's answer style: the review lane does not read it.
 *
 * <p>Content, not timestamps: a save that changes nothing changes no fingerprint. The form is {@code v1:} and 32
 * hex characters — what the vendor server accepts as a fingerprint.
 */
@Service
public class ReviewDraftBasis {

    public record Fingerprints(String knowledge, String policy, String voice) {

        /** One string for all three — what a PC remembers it last reported. */
        public String combined() {
            return knowledge + "|" + policy + "|" + voice;
        }
    }

    static final String PREFIX = "v1:";

    private final ProductKnowledgeSourceRepository productSources;
    private final OrgKnowledgeSourceRepository orgSources;
    private final ReviewReplyTemplateService templates;
    /** The company's forbidden phrases, as review replies obey them; null in a bare test. */
    private ReviewStyleGuard styleGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStyleGuard(ReviewStyleGuard styleGuard) {
        this.styleGuard = styleGuard;
    }

    public ReviewDraftBasis(ProductKnowledgeSourceRepository productSources, OrgKnowledgeSourceRepository orgSources,
                            ReviewReplyTemplateService templates) {
        this.productSources = productSources;
        this.orgSources = orgSources;
        this.templates = templates;
    }

    @Transactional(readOnly = true)
    public Fingerprints of(UUID orgId) {
        Digest knowledge = new Digest();
        productSources.findAllByOrgId(orgId).stream()
                .sorted(Comparator.comparing(ProductKnowledgeSource::getId))
                .forEach(s -> knowledge.add(s.getId(), s.getProductId(), s.getVariantId(), s.getSourceType(),
                        s.isActive(), s.getTitle(), s.getBody()));
        Digest policy = new Digest();
        orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .sorted(Comparator.comparing(OrgKnowledgeSource::getId))
                .forEach(s -> policy.add(s.getId(), s.getKnowledgeType(), s.isActive(), s.getTitle(), s.getBody()));
        policy.add("guard", String.join(",", ReviewClaimGuard.COMMITMENT_TERMS));
        policy.add("conditions", ReviewClaimGuard.FIGURE.pattern(), String.join(",", ReviewClaimGuard.CONDITION_TERMS));
        policy.add("refusals", String.join(",", ReviewClaimGuard.REFUSALS), String.valueOf(ReviewClaimGuard.ENDING),
                ReviewClaimGuard.READING);
        Digest voice = new Digest();
        for (ReviewReplyTemplateKey key : ReviewReplyTemplateKey.values()) {
            voice.add(key.category(), templates.bodyFor(orgId, key));
        }
        // A new ban sends waiting drafts back and has approved ones checked (2026-10-23, finding B24).
        // Only when there is a ban, so the fingerprint of a company without one stays what it was.
        java.util.List<String> banned = styleGuard == null ? java.util.List.of() : styleGuard.enforced(orgId);
        if (!banned.isEmpty()) {
            voice.add("forbidden", String.join("\n", banned));
        }
        return new Fingerprints(knowledge.value(), policy.value(), voice.value());
    }

    /** SHA-256 over length-prefixed fields, so no two different rows can run together into the same text. */
    private static final class Digest {

        private final MessageDigest sha;

        Digest() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        void add(Object... fields) {
            for (Object field : fields) {
                byte[] bytes = Objects.toString(field, "\u0000").getBytes(StandardCharsets.UTF_8);
                sha.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                sha.update(bytes);
            }
            sha.update((byte) '\n');
        }

        String value() {
            return PREFIX + HexFormat.of().formatHex(sha.digest()).substring(0, 32);
        }
    }
}
