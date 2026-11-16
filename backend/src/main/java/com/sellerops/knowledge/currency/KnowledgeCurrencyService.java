package com.sellerops.knowledge.currency;

import com.sellerops.common.ApiException;
import com.sellerops.common.DataOrigin;
import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import com.sellerops.knowledge.schedule.KnowledgeScheduleRepository;
import com.sellerops.knowledge.spine.KnowledgeAuthority;
import com.sellerops.knowledge.spine.adapter.SellerKnowledgeAdapter;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Builds {@link KnowledgeState} from the documents in use, and keeps what the seller decides about them</b> — a
 * document's 적용일 and 「이 자료가 저 자료를 대신함」 (owner decision 1, 2026-11-14).
 *
 * <p>Reads only documents that are on (사용) and the seller's own ({@link DataOrigin#REAL}) — what a draft may stand on.
 * Everything is read at the moment it is asked, so the search, the judge, the drafter and the checks at approval and at
 * registration never disagree about which value is current because one of them kept an old answer.
 */
@Service
public class KnowledgeCurrencyService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OrgKnowledgeSourceRepository orgSources;
    private final ProductKnowledgeSourceRepository productSources;
    private final KnowledgeSourceCurrencyRepository currency;
    private final KnowledgeSupersessionRepository supersessions;
    private final KnowledgeScheduleRepository schedules;
    private final ProductRepository products;

    public KnowledgeCurrencyService(OrgKnowledgeSourceRepository orgSources,
                                    ProductKnowledgeSourceRepository productSources,
                                    KnowledgeSourceCurrencyRepository currency,
                                    KnowledgeSupersessionRepository supersessions,
                                    KnowledgeScheduleRepository schedules, ProductRepository products) {
        this.orgSources = orgSources;
        this.productSources = productSources;
        this.currency = currency;
        this.supersessions = supersessions;
        this.schedules = schedules;
        this.products = products;
    }

    /** Today in Korea — the day a question asked now is asked on. */
    public static LocalDate today() {
        return LocalDate.now(KST);
    }

    public static LocalDate dayOf(Instant at) {
        return at == null ? today() : at.atZone(KST).toLocalDate();
    }

    /**
     * The state for one question: the company's documents and, when {@code productId} is given, that product's —
     * never another product's.
     *
     * @param reference the day the question is asked on (an inquiry's receipt day)
     * @param orderDate the day the customer's order was placed, when known — what a 적용일 is measured against
     */
    @Transactional(readOnly = true)
    public KnowledgeState stateFor(UUID orgId, UUID productId, LocalDate reference, LocalDate orderDate) {
        return stateFor(orgId, productId, reference, orderDate, List.of());
    }

    /**
     * The same, with {@code extra} sources that are not documents of the library (a past answer, a guidance) compared
     * alongside them.
     */
    @Transactional(readOnly = true)
    public KnowledgeState stateFor(UUID orgId, UUID productId, LocalDate reference, LocalDate orderDate,
                                   List<KnowledgeState.Source> extra) {
        Map<UUID, LocalDate> effective = effectiveDates(orgId);
        List<KnowledgeState.Source> sources = new ArrayList<>();
        for (OrgKnowledgeSource s : orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (usable(s.isActive(), s.getDataOrigin())) {
                sources.add(source(s, effective));
            }
        }
        if (productId != null) {
            for (ProductKnowledgeSource s : productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(orgId, productId)) {
                if (usable(s.isActive(), s.getDataOrigin())) {
                    sources.add(source(s, effective));
                }
            }
        }
        List<KnowledgeSchedule> lines = schedules.findAllByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(KnowledgeSchedule::isActive)
                .filter(s -> s.getProductId() == null || s.getProductId().equals(productId))
                .toList();
        if (extra != null) {
            sources.addAll(extra);
        }
        return KnowledgeState.of(sources, replacements(orgId), reference == null ? today() : reference, orderDate,
                lines, names(orgId, lines));
    }

    /** Every disagreement in the company's material, every product's included — the knowledge screen's read. */
    @Transactional(readOnly = true)
    public KnowledgeState overview(UUID orgId) {
        Map<UUID, LocalDate> effective = effectiveDates(orgId);
        List<KnowledgeState.Source> sources = new ArrayList<>();
        for (OrgKnowledgeSource s : orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (usable(s.isActive(), s.getDataOrigin())) {
                sources.add(source(s, effective));
            }
        }
        for (ProductKnowledgeSource s : productSources.findAllByOrgId(orgId)) {
            if (usable(s.isActive(), s.getDataOrigin())) {
                sources.add(source(s, effective));
            }
        }
        List<KnowledgeSchedule> lines = schedules.findAllByOrgIdOrderByCreatedAtAsc(orgId);
        return KnowledgeState.of(sources, replacements(orgId), today(), null, lines, names(orgId, lines));
    }

    /**
     * What the seller set that the state is made of, as a digest — empty when nothing is set, so a company that never
     * used any of it keeps the basis it had (a recorded decision is bound to the basis).
     */
    @Transactional(readOnly = true)
    public String fingerprint(UUID orgId) {
        List<KnowledgeSchedule> lines = schedules.findAllByOrgIdOrderByCreatedAtAsc(orgId);
        Map<UUID, LocalDate> effective = effectiveDates(orgId);
        List<KnowledgeState.Replacement> replaced = replacements(orgId);
        if (lines.isEmpty() && replaced.isEmpty() && effective.values().stream().allMatch(Objects::isNull)) {
            return "";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(
                    KnowledgeState.describe(replaced, effective, lines).getBytes(StandardCharsets.UTF_8));
            return "k1:" + HexFormat.of().formatHex(d).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── what the seller sets ────────────────────────────────────────────────────────────────────────────────────────

    /** A document's 적용일, or none. The document itself is not saved, so nothing that cited it reads as edited. */
    @Transactional
    public LocalDate setEffectiveFrom(UUID orgId, UUID sourceId, LocalDate from, String actorName) {
        requireSource(orgId, sourceId);
        KnowledgeSourceCurrency row = currency.findByOrgIdAndSourceId(orgId, sourceId).orElseGet(() -> {
            KnowledgeSourceCurrency fresh = new KnowledgeSourceCurrency();
            fresh.setOrgId(orgId);
            fresh.setSourceId(sourceId);
            return fresh;
        });
        row.setEffectiveFrom(from);
        row.setUpdatedByName(actorName);
        currency.save(row);
        return from;
    }

    /**
     * 「{@code newer}가 {@code older}를 대신함」 for one attribute under one condition. Both must be this company's documents
     * in the same scope — a product's own rule is not replaced by the company's, nor the other way round.
     */
    @Transactional
    public KnowledgeSupersession supersede(UUID orgId, UUID newer, UUID older, ClaimAttribute attribute,
                                           ClaimCondition condition, String actorName) {
        if (newer == null || older == null || attribute == null) {
            throw ApiException.badRequest("어느 자료가 어느 자료를, 어떤 항목에서 대신하는지 골라 주세요.");
        }
        if (newer.equals(older)) {
            throw ApiException.badRequest("같은 자료끼리는 대신할 수 없어요.");
        }
        String a = requireSource(orgId, newer);
        String b = requireSource(orgId, older);
        if (!a.equals(b)) {
            throw ApiException.badRequest("회사 전체 자료와 상품 자료는 서로 대신하지 않아요. 상품 자료는 그 상품의 예외예요.");
        }
        ClaimCondition cond = condition == null ? ClaimCondition.GENERAL : condition;
        for (KnowledgeSupersession s : supersessions.findAllByOrgId(orgId)) {
            if (s.getAttribute() == attribute && s.getCondition() == cond && s.getNewerSourceId().equals(newer)
                    && s.getOlderSourceId().equals(older)) {
                return s;
            }
        }
        // The latest choice wins, never a contradiction (2026-11-15 review): choosing the other side replaces the earlier
        // choice — and so does any earlier choice that would lead back round, through other documents, from the one now
        // replaced to the one now chosen (A가 B를, B가 C를 대신하는데 C가 A를 대신한다고 고르면 B→C는 지워짐). Only for this
        // attribute and condition; other attributes keep their choices.
        while (true) {
            List<KnowledgeState.Replacement> scoped = supersessions.findAllByOrgId(orgId).stream()
                    .filter(s -> s.getAttribute() == attribute && s.getCondition() == cond)
                    .map(s -> new KnowledgeState.Replacement(s.getId(), s.getNewerSourceId(), s.getOlderSourceId(),
                            s.getAttribute(), s.getCondition()))
                    .toList();
            if (!KnowledgeState.reaches(scoped, older, newer)) {
                break;
            }
            List<UUID> back = scoped.stream()
                    .filter(r -> r.older().equals(newer)
                            && (r.newer().equals(older) || KnowledgeState.reaches(scoped, older, r.newer())))
                    .map(KnowledgeState.Replacement::id).toList();
            if (back.isEmpty()) {
                break;
            }
            back.forEach(id -> supersessions.findByIdAndOrgId(id, orgId).ifPresent(supersessions::delete));
            supersessions.flush();
        }
        KnowledgeSupersession row = new KnowledgeSupersession();
        row.setOrgId(orgId);
        row.setNewerSourceId(newer);
        row.setOlderSourceId(older);
        row.setAttribute(attribute);
        row.setCondition(cond);
        row.setCreatedByName(actorName);
        return supersessions.save(row);
    }

    @Transactional
    public void removeSupersession(UUID orgId, UUID id) {
        KnowledgeSupersession row = supersessions.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("정해 둔 대체 관계를 찾을 수 없어요."));
        supersessions.delete(row);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSupersession> supersessions(UUID orgId) {
        return supersessions.findAllByOrgId(orgId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, LocalDate> effectiveDates(UUID orgId) {
        Map<UUID, LocalDate> out = new HashMap<>();
        for (KnowledgeSourceCurrency c : currency.findAllByOrgId(orgId)) {
            out.put(c.getSourceId(), c.getEffectiveFrom());
        }
        return out;
    }

    /** The title of a document of this company, or null. */
    @Transactional(readOnly = true)
    public String titleOf(UUID orgId, UUID sourceId) {
        return productSources.findByIdAndOrgId(sourceId, orgId).map(ProductKnowledgeSource::getTitle)
                .orElseGet(() -> orgSources.findByIdAndOrgId(sourceId, orgId).map(OrgKnowledgeSource::getTitle)
                        .orElse(null));
    }

    /** The scope key of a document of this company; 404 when it is not one. */
    private String requireSource(UUID orgId, UUID sourceId) {
        var product = productSources.findByIdAndOrgId(sourceId, orgId);
        if (product.isPresent()) {
            return "PRODUCT:" + product.get().getProductId();
        }
        orgSources.findByIdAndOrgId(sourceId, orgId)
                .orElseThrow(() -> ApiException.notFound("자료를 찾을 수 없어요."));
        return "ORG";
    }

    private List<KnowledgeState.Replacement> replacements(UUID orgId) {
        return supersessions.findAllByOrgId(orgId).stream()
                .map(s -> new KnowledgeState.Replacement(s.getId(), s.getNewerSourceId(), s.getOlderSourceId(),
                        s.getAttribute(), s.getCondition()))
                .toList();
    }

    private Map<UUID, String> names(UUID orgId, List<KnowledgeSchedule> lines) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (KnowledgeSchedule s : lines) {
            if (s.getProductId() != null && !out.containsKey(s.getProductId())) {
                products.findById(s.getProductId()).filter(p -> orgId.equals(p.getOrgId()))
                        .ifPresent(p -> out.put(p.getId(), OperatorProductName.displayNameOrNull(p)));
            }
        }
        out.values().removeIf(Objects::isNull);
        return out;
    }

    private static boolean usable(boolean active, DataOrigin origin) {
        return active && (origin == null || origin == DataOrigin.REAL);
    }

    private static KnowledgeState.Source source(OrgKnowledgeSource s, Map<UUID, LocalDate> effective) {
        return new KnowledgeState.Source(s.getId(), "ORG", null, "ORG", s.getTitle(),
                KnowledgeAuthority.SELLER_POLICY.rank(), effective.get(s.getId()),
                ClaimReader.read(s.getBody(), s.getTitle()));
    }

    private static KnowledgeState.Source source(ProductKnowledgeSource s, Map<UUID, LocalDate> effective) {
        return new KnowledgeState.Source(s.getId(), "PRODUCT", s.getProductId(), "PRODUCT:" + s.getProductId(),
                s.getTitle(), SellerKnowledgeAdapter.authorityOf(s.getAuthoredOrigin()).rank(),
                effective.get(s.getId()), ClaimReader.read(s.getBody(), s.getTitle()));
    }
}
