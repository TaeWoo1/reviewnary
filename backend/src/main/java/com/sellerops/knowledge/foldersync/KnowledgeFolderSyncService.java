package com.sellerops.knowledge.foldersync;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.document.KnowledgeDocumentService;
import com.sellerops.knowledge.document.KnowledgeDocumentText;
import com.sellerops.knowledge.document.dto.KnowledgeDocumentView;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자료 폴더 동기화 (owner decision 2026-11-17). The folder is the seller's; this keeps the knowledge library in step with
 * it through the same upload, 파일 바꾸기 and 사용 중지 a seller's own clicks go through.
 *
 * <p><b>The rules, as the owner set them.</b> A file at the top of the folder is company-wide. A file under a subfolder
 * whose name is exactly one product's name is that product's; any other subfolder is 「상품 확인 필요」 and is never filed
 * as company-wide instead. A changed file becomes a new version right away. A file that left the folder switches its
 * document off — never deletes it. Only three things stop a file, and each stops that file alone: a conflict, a file that
 * cannot be read, a PDF with a password.
 *
 * <p><b>What counts as a conflict.</b> A document of the same name that the folder did not make, unless it is an upload
 * of the very same file name (that is the seller's earlier hand upload of this file, and the folder takes it over as a
 * new version, history kept). And a document the folder made that was then changed on the web: the folder does not
 * silently overwrite someone's edit. In both, the seller can choose the folder's copy ({@link #useFolder}).
 *
 * <p><b>No refusal reaches a nested transaction.</b> Every check that can fail runs here first — reading the text, the
 * name collision — so the document service is only called when it will succeed, and a stopped file is recorded rather
 * than rolled back with the request.
 */
@Service
public class KnowledgeFolderSyncService {

    private static final int MAX_PATH = 500;

    private final KnowledgeFolderLinkRepository links;
    private final KnowledgeFolderFileRepository files;
    private final KnowledgeDocumentService documents;
    private final OrgKnowledgeSourceRepository orgSources;
    private final ProductKnowledgeSourceRepository productSources;
    private final ProductRepository products;

    public KnowledgeFolderSyncService(KnowledgeFolderLinkRepository links, KnowledgeFolderFileRepository files,
                                      KnowledgeDocumentService documents, OrgKnowledgeSourceRepository orgSources,
                                      ProductKnowledgeSourceRepository productSources, ProductRepository products) {
        this.links = links;
        this.files = files;
        this.documents = documents;
        this.orgSources = orgSources;
        this.productSources = productSources;
        this.products = products;
    }

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true)
    public KnowledgeFolderViews.Status status(UUID orgId) {
        KnowledgeFolderLink link = links.findByOrgId(orgId).orElse(null);
        Map<UUID, String> productNames = new HashMap<>();
        products.findAllByOrgId(orgId).forEach(p -> productNames.put(p.getId(), p.getName()));
        List<KnowledgeFolderViews.FileView> held = new ArrayList<>();
        List<KnowledgeFolderViews.FileView> missing = new ArrayList<>();
        int synced = 0;
        for (KnowledgeFolderFile f : files.findAllByOrgId(orgId)) {
            switch (f.getState()) {
                case SYNCED -> synced++;
                case HELD -> held.add(view(orgId, f, productNames));
                case MISSING -> missing.add(view(orgId, f, productNames));
            }
        }
        held.sort((a, b) -> a.path().compareTo(b.path()));
        missing.sort((a, b) -> a.path().compareTo(b.path()));
        return new KnowledgeFolderViews.Status(link != null, link == null ? null : link.getFolderName(),
                link == null ? null : link.getMode().name(), link == null ? null : link.getLastScanAt(), synced,
                held, missing);
    }

    // ------------------------------------------------------------------ scan

    /**
     * Compare the folder as the browser sees it with what was last sent, say which files to send, and switch off the
     * documents whose file left the folder. Bytes never travel here; only paths, sizes and fingerprints.
     */
    @Transactional
    public KnowledgeFolderViews.ScanResult scan(UUID orgId, UUID userId, KnowledgeFolderViews.ScanRequest request) {
        String folderName = clean(request.folderName());
        if (folderName == null || folderName.length() > 200) {
            throw ApiException.badRequest("폴더 이름이 없어요.");
        }
        KnowledgeFolderLink link = links.findByOrgId(orgId).orElse(null);
        if (link != null && !link.getFolderName().equals(folderName) && !request.replaceFolder()) {
            throw ApiException.conflict("FOLDER_MISMATCH",
                    "이미 「" + link.getFolderName() + "」 폴더가 연결돼 있어요. 이 폴더로 바꿀지 먼저 정해 주세요.");
        }
        if (link == null) {
            link = new KnowledgeFolderLink();
            link.setOrgId(orgId);
            link.setLinkedBy(userId);
        }
        link.setFolderName(folderName);
        link.setMode(request.mode() == null ? KnowledgeFolderLink.Mode.UPLOAD : request.mode());
        link.setLastScanAt(Instant.now());
        link.setLastScanBy(userId);
        links.save(link);

        List<KnowledgeFolderViews.Entry> entries = request.files() == null ? List.of() : request.files();
        Map<String, KnowledgeFolderFile> rows = new HashMap<>();
        files.findAllByOrgId(orgId).forEach(f -> rows.put(f.getPath(), f));
        List<Product> catalogue = products.findAllByOrgId(orgId);

        List<KnowledgeFolderViews.Plan> plans = new ArrayList<>();
        Set<String> present = new HashSet<>();
        int reactivated = 0;
        for (KnowledgeFolderViews.Entry e : entries) {
            String path;
            try {
                path = normalize(e.path());
            } catch (ApiException bad) {
                plans.add(new KnowledgeFolderViews.Plan(e.path(), "IGNORED", "BAD_PATH", bad.getMessage()));
                continue;
            }
            String ignored = ignoredReason(path);
            if (ignored != null) {
                plans.add(new KnowledgeFolderViews.Plan(path, "IGNORED", "FORMAT", ignored));
                continue;
            }
            present.add(path);
            KnowledgeFolderFile row = rows.get(path);
            Target target = target(path, catalogue);
            if (target.unmatched() != null) {
                hold(orgId, row, path, target, e.sha256(), e.size(), KnowledgeFolderFile.Reason.PRODUCT_UNMATCHED,
                        target.unmatched());
                plans.add(new KnowledgeFolderViews.Plan(path, "HELD", "PRODUCT_UNMATCHED", target.unmatched()));
                continue;
            }
            if (e.size() > KnowledgeDocumentText.MAX_BYTES) {
                String why = "파일이 너무 커요 (최대 10MB). 나눠서 저장해 주세요.";
                hold(orgId, row, path, target, e.sha256(), e.size(), KnowledgeFolderFile.Reason.READ_FAILED, why);
                plans.add(new KnowledgeFolderViews.Plan(path, "HELD", "READ_FAILED", why));
                continue;
            }
            boolean same = row != null && e.sha256() != null && e.sha256().equals(row.getContentSha256());
            if (row == null || !same) {
                plans.add(new KnowledgeFolderViews.Plan(path, "UPLOAD", null, null));
                continue;
            }
            switch (row.getState()) {
                case SYNCED -> {
                    SourceRef ref = source(orgId, row.getSourceId());
                    plans.add(new KnowledgeFolderViews.Plan(path, ref == null ? "UPLOAD" : "UNCHANGED", null, null));
                }
                case MISSING -> {
                    SourceRef ref = source(orgId, row.getSourceId());
                    if (ref == null) {
                        plans.add(new KnowledgeFolderViews.Plan(path, "UPLOAD", null, null));
                    } else {
                        if (!ref.active()) {
                            documents.setActive(orgId, ref.id(), true);
                        }
                        row.setState(KnowledgeFolderFile.State.SYNCED);
                        files.save(row);
                        reactivated++;
                        plans.add(new KnowledgeFolderViews.Plan(path, "REACTIVATED", null, null));
                    }
                }
                case HELD -> {
                    if (row.isUseFolder() || row.getHeldReason() == KnowledgeFolderFile.Reason.PRODUCT_UNMATCHED) {
                        // The seller chose the folder's copy, or the product folder now matches: send it.
                        plans.add(new KnowledgeFolderViews.Plan(path, "UPLOAD", null, null));
                    } else {
                        plans.add(new KnowledgeFolderViews.Plan(path, "HELD", row.getHeldReason().name(),
                                row.getHeldMessage()));
                    }
                }
            }
        }

        int deactivated = 0;
        String warning = null;
        if (request.complete()) {
            boolean anyMapped = rows.values().stream().anyMatch(f -> f.getSourceId() != null
                    && f.getState() != KnowledgeFolderFile.State.MISSING);
            if (present.isEmpty() && anyMapped) {
                // An empty listing of a folder that had material is more often a moved or unplugged folder than a
                // seller who deleted everything; switching the whole library off on it would be the worse mistake.
                warning = "EMPTY_FOLDER";
            } else {
                for (KnowledgeFolderFile row : rows.values()) {
                    if (present.contains(row.getPath()) || row.getState() == KnowledgeFolderFile.State.MISSING) {
                        continue;
                    }
                    SourceRef ref = source(orgId, row.getSourceId());
                    if (ref == null) {
                        files.delete(row);
                        continue;
                    }
                    if (ref.active()) {
                        documents.setActive(orgId, ref.id(), false);
                        deactivated++;
                    }
                    row.setState(KnowledgeFolderFile.State.MISSING);
                    row.setHeldReason(null);
                    row.setHeldMessage(null);
                    row.setUseFolder(false);
                    files.save(row);
                }
            }
        }
        return new KnowledgeFolderViews.ScanResult(plans, deactivated, reactivated, warning, status(orgId));
    }

    // ------------------------------------------------------------------ upload

    /** Send one file: a new document, or a new version of the one this path already became. */
    @Transactional
    public KnowledgeFolderViews.UploadResult upload(UUID orgId, UUID userId, String actorName, String rawPath,
                                                    String claimedSha, byte[] bytes) {
        if (links.findByOrgId(orgId).isEmpty()) {
            throw ApiException.conflict("FOLDER_NOT_LINKED", "폴더를 먼저 연결해 주세요.");
        }
        String path = normalize(rawPath);
        if (ignoredReason(path) != null) {
            throw ApiException.badRequest(ignoredReason(path));
        }
        String sha = sha256(bytes);
        if (claimedSha != null && !claimedSha.isBlank() && !claimedSha.equals(sha)) {
            throw ApiException.conflict("FILE_CHANGED_WHILE_SENDING", "보내는 동안 파일이 바뀌었어요. 다음 확인 때 다시 보내요.");
        }
        KnowledgeFolderFile row = files.findByOrgIdAndPath(orgId, path).orElse(null);
        Target target = target(path, products.findAllByOrgId(orgId));
        if (target.unmatched() != null) {
            return held(hold(orgId, row, path, target, sha, bytes.length,
                    KnowledgeFolderFile.Reason.PRODUCT_UNMATCHED, target.unmatched()));
        }
        String filename = fileName(path);
        try {
            KnowledgeDocumentText.extract(filename, bytes);
        } catch (ApiException unreadable) {
            boolean locked = KnowledgeDocumentText.PASSWORD_REFUSAL.equals(unreadable.getMessage());
            return held(hold(orgId, row, path, target, sha, bytes.length,
                    locked ? KnowledgeFolderFile.Reason.ENCRYPTED : KnowledgeFolderFile.Reason.READ_FAILED,
                    unreadable.getMessage()));
        }
        boolean wasMissing = row != null && row.getState() == KnowledgeFolderFile.State.MISSING;
        boolean useFolder = row != null && row.isUseFolder();

        SourceRef mapped = row == null ? null : source(orgId, row.getSourceId());
        KnowledgeDocumentView written;
        if (mapped != null) {
            if (!useFolder && row.getSyncedVersion() != null && mapped.version() != row.getSyncedVersion()) {
                return held(hold(orgId, row, path, target, sha, bytes.length,
                        KnowledgeFolderFile.Reason.CONFLICT_EDITED,
                        "웹에서 이 자료가 따로 바뀌었어요(파일 바꾸기나 직접 고침). 폴더 파일로 덮어쓸지 정해 주세요."));
            }
            written = documents.replaceFile(orgId, mapped.id(), filename, bytes);
        } else {
            String title = KnowledgeDocumentService.titleFor(filename);
            SourceRef same = sameTitle(orgId, target, title);
            if (same != null) {
                KnowledgeFolderFile owner = files.findFirstByOrgIdAndSourceId(orgId, same.id()).orElse(null);
                if (owner != null && !owner.getPath().equals(path)) {
                    return held(hold(orgId, row, path, target, sha, bytes.length,
                            KnowledgeFolderFile.Reason.CONFLICT_TITLE,
                            "폴더 안의 다른 파일 「" + owner.getPath() + "」이 이미 같은 이름의 자료예요. 둘 중 하나의 이름을 바꿔 주세요."));
                }
                if (same.documentName() == null) {
                    return held(hold(orgId, row, path, target, sha, bytes.length,
                            KnowledgeFolderFile.Reason.CONFLICT_TITLE,
                            "직접 쓴 기준 「" + title + "」과 이름이 같아요. 파일 이름을 바꿔 주세요."));
                }
                if (!useFolder && !nfc(same.documentName()).equals(nfc(filename))) {
                    return held(hold(orgId, row, path, target, sha, bytes.length,
                            KnowledgeFolderFile.Reason.CONFLICT_TITLE,
                            "이름이 같은 자료 「" + same.documentName() + "」가 이미 있어요. 폴더 파일로 바꿀지 정해 주세요."));
                }
                // The seller's earlier hand upload of this same file: the folder takes it over as a new version.
                written = documents.replaceFile(orgId, same.id(), filename, bytes);
            } else {
                written = documents.importDocument(orgId,
                        target.scope() == KnowledgeFolderFile.Scope.PRODUCT
                                ? KnowledgeDocumentService.Scope.PRODUCT : KnowledgeDocumentService.Scope.ORG,
                        target.productId(), null, null, filename, bytes, userId, actorName);
            }
        }
        if (wasMissing && !written.active()) {
            written = documents.setActive(orgId, written.sourceId(), true);
        }
        KnowledgeFolderFile r = row != null ? row : fresh(orgId, path);
        r.setScope(target.scope());
        r.setProductId(target.productId());
        r.setSourceId(written.sourceId());
        r.setContentSha256(sha);
        r.setSizeBytes((long) bytes.length);
        r.setSyncedVersion(written.version());
        r.setState(KnowledgeFolderFile.State.SYNCED);
        r.setHeldReason(null);
        r.setHeldMessage(null);
        r.setUseFolder(false);
        files.save(r);
        return new KnowledgeFolderViews.UploadResult(path, "SYNCED", null, null, written.sourceId(), written.version());
    }

    // ------------------------------------------------------------------ the seller's choices

    /** The seller chose the folder's copy over a conflicting document; the next look at the folder sends it. */
    @Transactional
    public KnowledgeFolderViews.Status useFolder(UUID orgId, UUID fileId) {
        KnowledgeFolderFile f = files.findByIdAndOrgId(fileId, orgId)
                .orElseThrow(() -> ApiException.notFound("폴더 파일을 찾을 수 없어요."));
        if (f.getState() != KnowledgeFolderFile.State.HELD || !canUseFolder(orgId, f)) {
            throw ApiException.conflict("CANNOT_USE_FOLDER", "이 파일은 폴더 쪽으로 덮어쓸 수 없어요. 안내대로 고친 뒤 다시 확인해 주세요.");
        }
        f.setUseFolder(true);
        f.setContentSha256(null); // the next scan sends it whatever its fingerprint
        files.save(f);
        return status(orgId);
    }

    /** Disconnect the folder. What it already wrote stays as it is, and reconnecting the same folder picks it up again. */
    @Transactional
    public KnowledgeFolderViews.Status unlink(UUID orgId) {
        links.findByOrgId(orgId).ifPresent(links::delete);
        return status(orgId);
    }

    // ------------------------------------------------------------------ helpers

    /** Where a path goes: the company, one product, or nowhere until the seller settles the folder's name. */
    record Target(KnowledgeFolderFile.Scope scope, UUID productId, String unmatched) {
    }

    static Target target(String path, List<Product> catalogue) {
        int slash = path.indexOf('/');
        if (slash < 0) {
            return new Target(KnowledgeFolderFile.Scope.ORG, null, null);
        }
        String folder = path.substring(0, slash);
        List<Product> named = catalogue.stream().filter(p -> nfc(p.getName()).equals(folder)).toList();
        if (named.size() == 1) {
            return new Target(KnowledgeFolderFile.Scope.PRODUCT, named.get(0).getId(), null);
        }
        String why = named.isEmpty()
                ? "상품 확인 필요: 「" + folder + "」와 이름이 똑같은 상품이 없어요. 폴더 이름을 상품 이름과 똑같이 바꿔 주세요."
                : "상품 확인 필요: 「" + folder + "」라는 상품이 " + named.size() + "개예요. 어느 상품인지 이름을 구분해 주세요.";
        return new Target(KnowledgeFolderFile.Scope.PRODUCT, null, why);
    }

    /** A path inside the folder: forward slashes, no empty, current or parent parts, every part NFC (macOS writes NFD). */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("파일 경로가 없어요.");
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.replace('\\', '/').split("/")) {
            String p = nfc(part);
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals(".") || p.equals("..")) {
                throw ApiException.badRequest("폴더 밖을 가리키는 경로는 받지 않아요.");
            }
            parts.add(p);
        }
        if (parts.isEmpty()) {
            throw ApiException.badRequest("파일 경로가 없어요.");
        }
        String path = String.join("/", parts);
        if (path.length() > MAX_PATH) {
            throw ApiException.badRequest("경로가 너무 길어요.");
        }
        return path;
    }

    /** Why a file is not material at all, or null. Hidden and temporary files, and formats the reader does not take. */
    static String ignoredReason(String path) {
        for (String part : path.split("/")) {
            if (part.startsWith(".") || part.startsWith("~$") || part.equalsIgnoreCase("Thumbs.db")
                    || part.equalsIgnoreCase("desktop.ini")) {
                return "숨김 · 임시 파일이라 읽지 않아요.";
            }
        }
        String ext = KnowledgeDocumentText.extensionOf(fileName(path));
        if (ext.equals("xlsx") || ext.equals("xls")) {
            return KnowledgeDocumentText.EXCEL_REFUSAL;
        }
        if (!KnowledgeDocumentText.ACCEPTED.contains(ext)) {
            return "이 형식은 읽지 않아요. " + KnowledgeDocumentText.acceptedListKo();
        }
        return null;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String nfc(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFC).strip();
    }

    private static String clean(String s) {
        String c = nfc(s);
        return c.isEmpty() ? null : c;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The document a row points at, from either library, or null when it no longer exists. */
    record SourceRef(UUID id, boolean active, int version, String documentName, String title) {
    }

    private SourceRef source(UUID orgId, UUID sourceId) {
        if (sourceId == null) {
            return null;
        }
        ProductKnowledgeSource p = productSources.findByIdAndOrgId(sourceId, orgId).orElse(null);
        if (p != null) {
            return new SourceRef(p.getId(), p.isActive(), p.getVersion(), p.getDocumentName(), p.getTitle());
        }
        OrgKnowledgeSource o = orgSources.findByIdAndOrgId(sourceId, orgId).orElse(null);
        return o == null ? null
                : new SourceRef(o.getId(), o.isActive(), o.getVersion(), o.getDocumentName(), o.getTitle());
    }

    private SourceRef sameTitle(UUID orgId, Target target, String title) {
        if (target.scope() == KnowledgeFolderFile.Scope.PRODUCT) {
            return productSources.findByOrgIdAndProductIdAndTitle(orgId, target.productId(), title)
                    .map(p -> new SourceRef(p.getId(), p.isActive(), p.getVersion(), p.getDocumentName(), p.getTitle()))
                    .orElse(null);
        }
        return orgSources.findByOrgIdAndTitle(orgId, title)
                .map(o -> new SourceRef(o.getId(), o.isActive(), o.getVersion(), o.getDocumentName(), o.getTitle()))
                .orElse(null);
    }

    private KnowledgeFolderFile fresh(UUID orgId, String path) {
        KnowledgeFolderFile f = new KnowledgeFolderFile();
        f.setOrgId(orgId);
        f.setPath(path);
        return f;
    }

    private KnowledgeFolderFile hold(UUID orgId, KnowledgeFolderFile row, String path, Target target, String sha,
                                     long size, KnowledgeFolderFile.Reason reason, String message) {
        KnowledgeFolderFile r = row != null ? row : fresh(orgId, path);
        r.setScope(target.scope());
        if (target.productId() != null) {
            r.setProductId(target.productId());
        }
        r.setContentSha256(sha);
        r.setSizeBytes(size);
        r.setState(KnowledgeFolderFile.State.HELD);
        r.setHeldReason(reason);
        r.setHeldMessage(message != null && message.length() > 300 ? message.substring(0, 300) : message);
        r.setUseFolder(false);
        return files.save(r);
    }

    private static KnowledgeFolderViews.UploadResult held(KnowledgeFolderFile r) {
        return new KnowledgeFolderViews.UploadResult(r.getPath(), "HELD", r.getHeldReason().name(), r.getHeldMessage(),
                r.getSourceId(), null);
    }

    private boolean canUseFolder(UUID orgId, KnowledgeFolderFile f) {
        if (f.getHeldReason() == KnowledgeFolderFile.Reason.CONFLICT_EDITED) {
            return true;
        }
        if (f.getHeldReason() != KnowledgeFolderFile.Reason.CONFLICT_TITLE) {
            return false;
        }
        // Only an uploaded document can take a file's text; a rule the seller wrote by hand is never overwritten.
        Target t = new Target(f.getScope(), f.getProductId(), null);
        SourceRef same = sameTitle(orgId, t, KnowledgeDocumentService.titleFor(fileName(f.getPath())));
        return same != null && same.documentName() != null
                && files.findFirstByOrgIdAndSourceId(orgId, same.id()).map(o -> o.getId().equals(f.getId())).orElse(true);
    }

    private KnowledgeFolderViews.FileView view(UUID orgId, KnowledgeFolderFile f, Map<UUID, String> productNames) {
        return new KnowledgeFolderViews.FileView(f.getId(), f.getPath(), f.getScope().name(),
                f.getProductId() == null ? null : productNames.get(f.getProductId()), f.getState().name(),
                f.getHeldReason() == null ? null : f.getHeldReason().name(), f.getHeldMessage(), f.getSourceId(),
                f.getState() == KnowledgeFolderFile.State.HELD && !f.isUseFolder() && canUseFolder(orgId, f),
                f.getUpdatedAt());
    }
}
