package com.sellerops.knowledge.foldersync;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * The one folder an org keeps its material in. WATCH: a Chrome/Edge window holds the folder and looks at it every few
 * minutes while open. UPLOAD: another browser, which sends the whole folder when the seller picks it.
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_folder_links")
public class KnowledgeFolderLink extends BaseEntity {

    public enum Mode { WATCH, UPLOAD }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** The folder's own name — never its location on the PC, which the browser does not reveal and we do not keep. */
    @Column(name = "folder_name", nullable = false, length = 200)
    private String folderName;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Mode mode;

    @Column(name = "linked_by")
    private UUID linkedBy;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    @Column(name = "last_scan_by")
    private UUID lastScanBy;
}
