package com.sellerops.review.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One thing a generated review reply was grounded in, bound to the exact draft version.
 *
 * <p>The review lane's mirror of {@code InquiryDraftEvidence}, field for field. It is a separate
 * table rather than a shared one because the two are keyed by different objects — a work item and a
 * review — and a nullable-either-way key would let a row exist that belongs to neither.
 *
 * <p>Append-only beside the append-only draft, and it copies no passage text: {@code chunkId} points
 * at the seller's own knowledge row, so the excerpt under 「왜 이렇게 썼어요?」 is read back from the
 * document rather than from a second copy that can drift from it.
 *
 * <p>Carries no customer content and no buyer identity.
 */
@Getter
@Setter
@Entity
@Table(name = "review_draft_evidence")
public class ReviewDraftEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    /** Position in the order the passages were given to the drafter. Stable, so a citation list is. */
    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    /** The {@code KnowledgeScope} the passage came from, as a string — see the inquiry lane's note. */
    @Column(name = "kind", nullable = false, length = 40)
    private String kind;

    /** The seller's knowledge document. */
    @Column(name = "source_id")
    private UUID sourceId;

    /** The chunk within it that actually scored. */
    @Column(name = "chunk_id")
    private UUID chunkId;

    @Column(name = "title", length = 300)
    private String title;

    @Column(name = "locator", length = 200)
    private String locator;

    /**
     * The SHA-256 of the cited passage as the drafter saw it, whitespace folded ({@link #fingerprintOf}) — not the
     * passage itself, which stays in the seller's document. Null on citations recorded before 2026-10-23 and on past
     * answers. What lets a recheck tell an edit to the cited passage from an edit elsewhere in the same document.
     */
    @Column(name = "passage_fingerprint", length = 64)
    private String passageFingerprint;

    /** One passage's fingerprint: whitespace runs folded to one space, trimmed, SHA-256 hex. */
    public static String fingerprintOf(String passage) {
        if (passage == null) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(passage.replaceAll("\\s+", " ").strip().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
