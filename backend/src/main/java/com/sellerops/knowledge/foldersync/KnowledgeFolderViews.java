package com.sellerops.knowledge.foldersync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the folder sync screen and the browser's scanner exchange. Paths are relative to the folder, never the PC. */
public final class KnowledgeFolderViews {

    private KnowledgeFolderViews() {
    }

    /** One file as the browser sees it now. */
    public record Entry(String path, long size, String sha256) {
    }

    /**
     * One look at the folder. {@code complete} is true only when the browser listed the whole folder without an error —
     * a partial listing never switches a document off.
     */
    public record ScanRequest(String folderName, KnowledgeFolderLink.Mode mode, boolean complete, boolean replaceFolder,
                              List<Entry> files) {
    }

    /**
     * What to do with one file: UPLOAD (send its bytes), UNCHANGED, REACTIVATED (it came back with the same content, the
     * document is on again), HELD (stopped — see reason), IGNORED (not a material file: a format we do not read, a
     * hidden or temporary file).
     */
    public record Plan(String path, String action, String reason, String message) {
    }

    public record ScanResult(List<Plan> plans, int deactivated, int reactivated, String warning, Status status) {
    }

    public record UploadResult(String path, String state, String reason, String message, UUID sourceId, Integer version) {
    }

    /** A file the seller should know about: stopped, or gone from the folder. */
    public record FileView(UUID id, String path, String scope, String productName, String state, String reason,
                           String message, UUID sourceId, boolean canUseFolder, Instant updatedAt) {
    }

    public record Status(boolean linked, String folderName, String mode, Instant lastScanAt, int synced,
                         List<FileView> held, List<FileView> missing) {
    }
}
