package com.sellerops.knowledge.document;

import com.sellerops.common.ApiException;
import com.sellerops.knowledge.document.dto.KnowledgeDocumentView;
import com.sellerops.knowledge.org.OrgKnowledgeChunkRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeAuthorship;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeIndexer;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The seller's own material, brought in as it is.</b> (Knowledge Sources &amp; Acquisition v1)
 *
 * <p>A manual, an FAQ, a shipping policy: the company already wrote these, and a product that makes
 * them retype one into a form is asking for work that was already done. This takes the file, extracts
 * its text ({@link KnowledgeDocumentText}), and writes it into <b>the corpus that already exists</b> —
 * a {@code ProductKnowledgeSource} or an {@code OrgKnowledgeSource}, indexed by the same indexer, found
 * by the same retriever, cited with the same {@code DraftEvidenceView}. There is no document corpus
 * beside the knowledge corpus, because a second corpus is a second thing to keep in sync and a second
 * place a citation can point.
 *
 * <p><b>The seller confirms the FILE, not the passages.</b> What they are asked is what this is, where
 * it applies, and whether it is current — three questions about a document a person can answer. Asking
 * them to approve forty chunks would be asking them to do the chunker's job, and they would say yes to
 * all of it without reading, which is worse than not asking.
 *
 * <p><b>Retiring, not deleting.</b> {@link #setActive} stops a superseded manual from grounding new
 * answers while leaving every citation that stood on it resolvable. A delete would erase the record of
 * what a draft was written from.
 *
 * <p>No model is called here, and nothing leaves this deployment.
 */
@Service
public class KnowledgeDocumentService {

    /** Which corpus a document lands in. Two, because there are two corpora — see the class note. */
    public enum Scope { PRODUCT, ORG }

    private final ProductKnowledgeSourceRepository productSources;
    private final ProductKnowledgeIndexer productIndexer;
    private final ProductRepository products;
    private final OrgKnowledgeSourceRepository orgSources;
    private final OrgKnowledgeChunkRepository orgChunks;
    private final SellerOperationsKnowledgeService orgKnowledge;

    public KnowledgeDocumentService(ProductKnowledgeSourceRepository productSources,
                                    ProductKnowledgeIndexer productIndexer, ProductRepository products,
                                    OrgKnowledgeSourceRepository orgSources,
                                    OrgKnowledgeChunkRepository orgChunks,
                                    SellerOperationsKnowledgeService orgKnowledge) {
        this.productSources = productSources;
        this.productIndexer = productIndexer;
        this.products = products;
        this.orgSources = orgSources;
        this.orgChunks = orgChunks;
        this.orgKnowledge = orgKnowledge;
    }

    /**
     * Import one document into one corpus.
     *
     * @param sourceType for a PRODUCT document, which kind it is (FAQ / USAGE / POLICY / DESCRIPTION)
     * @param orgType    for an ORG document, which operating topic it states
     * @throws ApiException 400 on a format this cannot read, a file too large, or a document with no
     *                      readable text; 404 when the product is not this org's; 409 {@link #DOCUMENT_EXISTS} when
     *                      the title is taken where titles are unique — company-wide per org (V72), a product's per
     *                      product (V64) — the existing one is left exactly as it was and the seller is sent to its
     *                      「파일 바꾸기」
     */
    @Transactional
    public KnowledgeDocumentView importDocument(UUID orgId, Scope scope, UUID productId,
                                                KnowledgeSourceType sourceType, OrgKnowledgeType orgType,
                                                String filename, byte[] bytes, UUID actorUserId,
                                                String actorName) {
        String text = KnowledgeDocumentText.extract(filename, bytes);
        String title = titleFor(filename);
        if (scope == Scope.PRODUCT) {
            Product product = products.findById(productId)
                    .filter(p -> p.getOrgId().equals(orgId))
                    .orElseThrow(() -> ApiException.notFound("상품을 찾을 수 없습니다."));
            // A product's title is unique within that product (V64); the same title on another product is fine.
            ProductKnowledgeSource sameTitle = productSources
                    .findByOrgIdAndProductIdAndTitle(orgId, product.getId(), title).orElse(null);
            if (sameTitle != null) {
                throw alreadyThere(title, sameTitle.getDocumentName() != null);
            }
            ProductKnowledgeSource source = new ProductKnowledgeSource();
            source.setOrgId(orgId);
            source.setProductId(product.getId());
            source.setSourceType(sourceType == null ? KnowledgeSourceType.FAQ : sourceType);
            // The seller uploaded it, so it is theirs — and the file is what they can retire.
            source.setAuthoredOrigin(KnowledgeAuthorship.SELLER_UPLOADED_DOCUMENT);
            source.setDocumentName(filename);
            source.setTitle(title);
            source.setBody(text);
            source.setAuthorUserId(actorUserId);
            source.setAuthorName(actorName);
            ProductKnowledgeSource saved;
            try {
                saved = productSources.saveAndFlush(source);
            } catch (DataIntegrityViolationException raced) {
                if (!violates(raced, PRODUCT_TITLE_UNIQUE)) {
                    throw raced;
                }
                throw alreadyThere(title, true);
            }
            int passages = productIndexer.index(saved);
            return view(saved, passages, product);
        }
        // A company-wide title is unique per org (V72). Re-uploading a file of the same name is the common way to
        // reach it while keeping manuals up to date: say so and point at 「파일 바꾸기」 instead of overwriting.
        OrgKnowledgeSource sameTitle = orgSources.findByOrgIdAndTitle(orgId, title).orElse(null);
        if (sameTitle != null) {
            throw alreadyThere(title, sameTitle.getDocumentName() != null);
        }
        OrgKnowledgeSource source = new OrgKnowledgeSource();
        source.setOrgId(orgId);
        source.setKnowledgeType(orgType == null ? OrgKnowledgeType.GENERAL_CS_FAQ : orgType);
        source.setAuthoredOrigin(KnowledgeAuthorship.SELLER_UPLOADED_DOCUMENT);
        source.setDocumentName(filename);
        source.setTitle(title);
        source.setBody(text);
        source.setAuthorUserId(actorUserId);
        source.setAuthorName(actorName);
        OrgKnowledgeSource saved;
        try {
            // Flushed here so that two uploads of the same name at once meet the index inside this method.
            saved = orgSources.saveAndFlush(source);
        } catch (DataIntegrityViolationException raced) {
            if (!violates(raced, ORG_TITLE_UNIQUE)) {
                throw raced;
            }
            throw alreadyThere(title, true);
        }
        int passages = orgKnowledge.index(saved);
        return view(saved, passages);
    }

    /** The closed name a screen can branch on when an upload meets an existing company-wide title. */
    public static final String DOCUMENT_EXISTS = "KNOWLEDGE_DOCUMENT_EXISTS";

    static final String ORG_TITLE_UNIQUE = "uq_org_knowledge_sources_title";

    static final String PRODUCT_TITLE_UNIQUE = "uq_pk_sources_title";

    static ApiException alreadyThere(String title, boolean isUploadedFile) {
        return ApiException.conflict(DOCUMENT_EXISTS, isUploadedFile
                ? "이미 있는 자료입니다(「" + title + "」). 기존 자료의 「파일 바꾸기」를 이용해 주세요."
                : "같은 제목의 운영 기준이 이미 있습니다(「" + title + "」). 파일 이름을 바꿔 올리거나 그 기준을 고쳐 주세요.");
    }

    /** Whether this failure is the named unique index — any other integrity failure stays what it was. */
    static boolean violates(Throwable failure, String constraint) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException c
                    && constraint.equalsIgnoreCase(c.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private com.sellerops.product.library.ProductKnowledgeRevisionRepository productRevisions;

    /** Where a product document's past body is kept when a new file replaces it (V190). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProductRevisions(com.sellerops.product.library.ProductKnowledgeRevisionRepository productRevisions) {
        this.productRevisions = productRevisions;
    }

    /**
     * <b>파일 바꾸기</b> (2026-11-16): the same document, from a new file.
     *
     * <p>The seller's material changes often — a new price, a new schedule — and uploading the new file beside the old
     * one leaves two documents to keep straight. This puts the new file's text into the SAME source: the same id (so a
     * citation, a 적용일 and a conflict decision keep pointing at it), the same kind, the same product or company scope,
     * and the same 사용 여부 — a document the seller turned off stays off. The version rises and the old text is kept as
     * a revision when the text changed (V164 · V190), and the passages are re-cut, so a draft that cited the old text is
     * read as changed by the existing recheck.
     *
     * <p><b>All or nothing.</b> The file is read before anything is looked up or written: a format this cannot read, a
     * scan, an empty file or a document from another company changes nothing at all.
     *
     * @throws ApiException 400 when the file cannot be read (the reader's own sentence), 404 when the id is not an
     *                      uploaded document of this org
     */
    @Transactional
    public KnowledgeDocumentView replaceFile(UUID orgId, UUID sourceId, String filename, byte[] bytes) {
        String text = KnowledgeDocumentText.extract(filename, bytes);
        ProductKnowledgeSource product = productSources.findByIdAndOrgId(sourceId, orgId)
                .filter(s -> s.getDocumentName() != null).orElse(null);
        if (product != null) {
            com.sellerops.product.library.ProductKnowledgeRevision before =
                    com.sellerops.product.library.ProductKnowledgeRevision.of(product, java.time.Instant.now());
            product.setBody(text);
            product.setDocumentName(filename);
            if (!text.equals(before.getBody())) {
                if (productRevisions != null) {
                    productRevisions.save(before);
                }
                product.setVersion(product.getVersion() + 1);
            }
            ProductKnowledgeSource saved = productSources.save(product);
            int passages = productIndexer.index(saved);
            return view(saved, passages, products.findById(saved.getProductId()).orElse(null));
        }
        OrgKnowledgeSource org = orgSources.findByIdAndOrgId(sourceId, orgId)
                .filter(s -> s.getDocumentName() != null)
                .orElseThrow(() -> ApiException.notFound("자료를 찾을 수 없습니다."));
        orgKnowledge.replaceBody(orgId, org.getId(), text, filename);
        OrgKnowledgeSource saved = orgSources.findByIdAndOrgId(sourceId, orgId).orElseThrow();
        return view(saved, orgChunks.countBySourceId(saved.getId()));
    }

    /** Every uploaded document this org has, product and org corpora together, newest first. */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> list(UUID orgId) {
        return dated(orgId, everything(orgId));
    }

    private com.sellerops.knowledge.currency.KnowledgeSourceCurrencyRepository currency;

    /** Where a document's 적용일 is kept (2026-11-15). Optional: without it no 적용일 is shown. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCurrency(com.sellerops.knowledge.currency.KnowledgeSourceCurrencyRepository currency) {
        this.currency = currency;
    }

    private List<KnowledgeDocumentView> dated(UUID orgId, List<KnowledgeDocumentView> views) {
        if (currency == null) {
            return views;
        }
        java.util.Map<UUID, java.time.LocalDate> from = new java.util.HashMap<>();
        currency.findAllByOrgId(orgId).forEach(c -> from.put(c.getSourceId(), c.getEffectiveFrom()));
        return views.stream().map(v -> v.withEffectiveFrom(from.get(v.sourceId()))).toList();
    }

    /**
     * The documents for ONE product — what the product screen lists beside its 상품 지식.
     *
     * <p>A separate query rather than a filter over the org's whole list, because a company with
     * three hundred products would read three hundred products' documents to show one product's two.
     * Org documents are excluded: they apply everywhere, and a shipping policy listed under a
     * cutting board reads as a fact about the cutting board.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> listForProduct(UUID orgId, UUID productId) {
        Product product = products.findById(productId)
                .filter(p -> p.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("상품을 찾을 수 없습니다."));
        List<KnowledgeDocumentView> out = new ArrayList<>();
        for (ProductKnowledgeSource source
                : productSources.findAllByOrgIdAndProductIdAndDocumentNameIsNotNull(orgId, productId)) {
            out.add(view(source, chunkCountOf(source), product));
        }
        out.sort((a, b) -> b.uploadedAt().compareTo(a.uploadedAt()));
        return dated(orgId, List.copyOf(out));
    }

    private List<KnowledgeDocumentView> everything(UUID orgId) {
        List<KnowledgeDocumentView> out = new ArrayList<>();
        for (ProductKnowledgeSource source : productSources.findAllByOrgIdAndDocumentNameIsNotNull(orgId)) {
            Product product = products.findById(source.getProductId()).orElse(null);
            out.add(view(source, chunkCountOf(source), product));
        }
        for (OrgKnowledgeSource source : orgSources.findAllByOrgIdAndDocumentNameIsNotNull(orgId)) {
            out.add(view(source, orgChunks.countBySourceId(source.getId())));
        }
        out.sort((a, b) -> b.uploadedAt().compareTo(a.uploadedAt()));
        return List.copyOf(out);
    }

    /**
     * Retire or restore one document.
     *
     * <p>Addressed by its source id, which is what the list hands back, and scoped to this org on both
     * corpora — an id from another company resolves to nothing rather than to their document.
     */
    @Transactional
    public KnowledgeDocumentView setActive(UUID orgId, UUID sourceId, boolean active) {
        ProductKnowledgeSource product = productSources.findByIdAndOrgId(sourceId, orgId).orElse(null);
        if (product != null) {
            product.setActive(active);
            ProductKnowledgeSource saved = productSources.save(product);
            return view(saved, chunkCountOf(saved), products.findById(saved.getProductId()).orElse(null));
        }
        OrgKnowledgeSource org = orgSources.findByIdAndOrgId(sourceId, orgId)
                .orElseThrow(() -> ApiException.notFound("자료를 찾을 수 없습니다."));
        org.setActive(active);
        OrgKnowledgeSource saved = orgSources.save(org);
        return view(saved, orgChunks.countBySourceId(saved.getId()));
    }

    private int chunkCountOf(ProductKnowledgeSource source) {
        return productIndexer.countFor(source.getId());
    }

    /**
     * The document's title.
     *
     * <p>The filename with its extension removed — the seller named the file, and a title we composed
     * would be a second name for the same thing. Bounded to the column, and never empty.
     */
    public static String titleFor(String filename) {
        String name = filename == null ? "" : filename.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        } else if (dot == 0) {
            // The whole name is an extension («.pdf»). There is no title in it to keep.
            name = "";
        }
        name = name.strip();
        if (name.isEmpty()) {
            name = "올린 자료";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static KnowledgeDocumentView view(ProductKnowledgeSource source, int passages, Product product) {
        return new KnowledgeDocumentView(source.getId(), "PRODUCT", source.getProductId(),
                product == null ? null : OperatorProductName.displayNameOrNull(product),
                source.getDocumentName(), source.getTitle(), source.getSourceType().name(),
                source.isActive(), passages, source.getAuthorName(), source.getCreatedAt(), source.getVersion(),
                source.getUpdatedAt());
    }

    private static KnowledgeDocumentView view(OrgKnowledgeSource source, int passages) {
        return new KnowledgeDocumentView(source.getId(), "ORG", null, null,
                source.getDocumentName(), source.getTitle(), source.getKnowledgeType().name(),
                source.isActive(), passages, source.getAuthorName(), source.getCreatedAt(), source.getVersion(),
                source.getUpdatedAt());
    }
}
