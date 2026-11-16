package com.sellerops.knowledge.foldersync;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One file of the folder, by its path inside the folder: which document it became and what was last sent.
 *
 * <p>SYNCED — the document holds this content. MISSING — the file left the folder; the document was switched off, not
 * deleted. HELD — this file is stopped for a reason the seller has to look at ({@link Reason}); everything else in the
 * folder keeps syncing.
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_folder_files")
public class KnowledgeFolderFile extends BaseEntity {

    public enum State { SYNCED, MISSING, HELD }

    public enum Scope { ORG, PRODUCT }

    /** Why a file is stopped — each is a thing only the seller can settle. */
    public enum Reason {
        /** The subfolder's name is not exactly one product's name. Never filed as company-wide instead. */
        PRODUCT_UNMATCHED,
        /** A document of the same name exists that the folder did not make. */
        CONFLICT_TITLE,
        /** The document was changed on the web after the folder last wrote it. */
        CONFLICT_EDITED,
        /** A PDF with a password. */
        ENCRYPTED,
        /** The file could not be read (no text, too large, damaged). */
        READ_FAILED
    }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 500)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Scope scope;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** The document's version right after the folder last wrote it; a different version now means someone else did. */
    @Column(name = "synced_version")
    private Integer syncedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(name = "held_reason", length = 30)
    private Reason heldReason;

    @Column(name = "held_message", length = 300)
    private String heldMessage;

    /** The seller chose the folder's copy over a conflicting document; the next upload overwrites it once. */
    @Column(name = "use_folder", nullable = false)
    private boolean useFolder;
}
