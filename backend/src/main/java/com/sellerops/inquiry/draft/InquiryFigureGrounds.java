package com.sellerops.inquiry.draft;

import com.sellerops.knowledge.guidance.SellerGuidanceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.product.ProductFact;
import com.sellerops.product.ProductFactRepository;
import com.sellerops.product.ProductVariant;
import com.sellerops.product.ProductVariantRepository;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * <b>What each number in an inquiry draft was written from, and whether it still is</b> (2026-11-01 review: the inquiry
 * recheck dropped every figure finding, so 「수령 후 7일 이내 교환 가능」 over a policy now saying 14일 would have been
 * sent once approved).
 *
 * <p>At save, {@link #record} keeps one {@link InquiryDraftFigure} row per figure the reply states, with the ground the
 * <b>claim check</b> named for it at generation (2026-11-01 third review: 「생성 때 모델이 검증한 '주장 → 근거 문단' 연결을
 * 그대로 저장해야 합니다」) — the evidence line it read the number from, the order, or the customer's question said back —
 * and what the number is about and under which condition, in the evidence's words. Within the named passage the ground is
 * every clause that states the number, unless what it is about singles one out: 「배송 7일 · 교환 7일」 in one passage
 * keeps both, never the first one found. A figure the check named no ground for is grounded by the text only where one
 * clause in all the material states it; otherwise it has none, and a recheck cannot prove it. For the seller's material
 * the clauses are read through the same reader the recheck uses ({@link #currentText}), so the two compare like with
 * like.
 *
 * <p>At recheck, {@link #verdict} gives one of three answers, model-free:
 * <ul>
 *   <li>{@link Verdict#STANDS} — the clause is still in the material word for word, or the figure came from the order
 *       or the customer's question (neither is the seller's knowledge, and neither changed with it);</li>
 *   <li>{@link Verdict#CHANGED} — the material no longer states the number at all: the answer is now wrong;</li>
 *   <li>{@link Verdict#UNVERIFIED} — the clause was rewritten but the number is still somewhere, or the material cannot
 *       be read: whether the answer still holds is a reading of meaning, and nothing here reads meaning.</li>
 * </ul>
 */
@Component
public class InquiryFigureGrounds {

    public enum Verdict { STANDS, CHANGED, UNVERIFIED }

    /** One piece of material the drafter was shown, and where to read it again. */
    public record Shown(InquiryDraftFigure.Ground kind, UUID sourceId, UUID productId, String text) {
    }

    /**
     * What the claim check read one figure from: the figure as asked ({@code value}, {@code unit}, the reply's
     * {@code sentence}), its verdict, and the evidence line it named ({@code passage}, 1-based into the list the check was
     * shown) or {@code source} 주문 · 문의; {@code about} and {@code when} in the evidence's words.
     */
    public record Link(String value, String unit, String sentence, String verdict, Integer passage, String source,
                       String about, String when) {
    }

    static final int MAX_CLAUSE = 500;

    private final InquiryDraftFigureRepository figures;
    private final OrgKnowledgeSourceRepository orgSources;
    private final ProductKnowledgeSourceRepository productSources;
    private final ProductFactRepository facts;
    private final ProductVariantRepository variants;
    private final SellerGuidanceRepository guidance;

    public InquiryFigureGrounds(InquiryDraftFigureRepository figures, OrgKnowledgeSourceRepository orgSources,
                                ProductKnowledgeSourceRepository productSources, ProductFactRepository facts,
                                ProductVariantRepository variants, SellerGuidanceRepository guidance) {
        this.figures = figures;
        this.orgSources = orgSources;
        this.productSources = productSources;
        this.facts = facts;
        this.variants = variants;
        this.guidance = guidance;
    }

    /**
     * Keep the ground of every figure {@code body} states. Nothing when it states none. {@code shown} is the list the
     * claim check was shown, line for line — null where a line is not the seller's material (a past answer, the order).
     */
    public void record(UUID orgId, UUID workItemId, int version, String body, List<Shown> shown, String orderSentence,
                       String asked, List<Link> links) {
        List<InquiryDraftFigure> rows = ground(body, shown, orderSentence, asked,
                s -> currentText(orgId, s.kind(), s.sourceId(), s.productId()), links);
        for (InquiryDraftFigure row : rows) {
            row.setOrgId(orgId);
            row.setWorkItemId(workItemId);
            row.setDraftVersion(version);
            if (InquiryDraftFigure.Ground.ORG_POLICY.name().equals(row.getGroundKind()) && row.getSourceId() != null) {
                row.setSourceVersion(orgSources.findByIdAndOrgId(row.getSourceId(), orgId)
                        .map(OrgKnowledgeSource::getVersion).orElse(null));
            }
            figures.save(row);
        }
    }

    /** The grounds kept for one version, in the order the reply states them. */
    public List<InquiryDraftFigure> of(UUID workItemId, int version) {
        return figures.findAllByWorkItemIdAndDraftVersionOrderByOrdinalAsc(workItemId, version);
    }

    /**
     * What the material says now, through one reader per kind; null when it is gone, retired or unreadable. Product facts
     * read as one line per fact, option and 추가상품 — the same lines at save and at recheck.
     */
    public String currentText(UUID orgId, InquiryDraftFigure.Ground kind, UUID sourceId, UUID productId) {
        return switch (kind) {
            case ORG_POLICY -> sourceId == null ? null : orgSources.findByIdAndOrgId(sourceId, orgId)
                    .filter(OrgKnowledgeSource::isActive).map(s -> s.getTitle() + "\n" + s.getBody()).orElse(null);
            case PRODUCT_KNOWLEDGE -> sourceId == null ? null : productSources.findByIdAndOrgId(sourceId, orgId)
                    .filter(s -> s.isActive()).map(s -> s.getTitle() + "\n" + s.getBody()).orElse(null);
            case SELLER_GUIDANCE -> sourceId == null ? null : guidance.findById(sourceId)
                    .filter(g -> orgId.equals(g.getOrgId())).map(g -> g.getGuidance()).orElse(null);
            case PRODUCT_FACTS -> productId == null ? null : productLines(orgId, productId);
            // The record while it is current; a corrected one says nothing any more, so the figure is checked again.
            case SELLER_CHECK -> sourceId == null || sellerChecks == null ? null : sellerChecks.findById(sourceId)
                    .filter(c -> orgId.equals(c.getOrgId()) && c.getSupersededAt() == null)
                    .map(com.sellerops.inquiry.sellercheck.InquirySellerCheckService::evidenceText).orElse(null);
            case ORDER, QUESTION, NONE -> null;
        };
    }

    private com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerChecks;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSellerChecks(com.sellerops.inquiry.sellercheck.InquirySellerCheckRepository sellerChecks) {
        this.sellerChecks = sellerChecks;
    }

    private String productLines(UUID orgId, UUID productId) {
        List<String> lines = new ArrayList<>();
        for (ProductFact f : facts.findByOrgIdAndProductId(orgId, productId)) {
            if (f.getFactKey() != null && f.getFactValue() != null && !f.getFactValue().isBlank()) {
                lines.add(f.getFactKey() + ": " + f.getFactValue().strip() + (f.getUnit() == null ? "" : " " + f.getUnit()));
            }
        }
        for (ProductVariant v : variants.findByOrgIdAndProductId(orgId, productId)) {
            if (v.getOptionName() != null && !v.getOptionName().isBlank()) {
                lines.add(v.getOptionName().strip());
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines.stream().sorted().toList());
    }

    /**
     * The ground of each figure, pure. With the claim check's link, the line it named — when that line is the seller's
     * material and states the number — or the order, or the question said back; a link that does not resolve leaves the
     * figure with no ground. Without one, the only clause in all the material that states the number, else the order,
     * else the question, else none. Never the first of several.
     */
    static List<InquiryDraftFigure> ground(String body, List<Shown> shown, String orderSentence, String asked,
                                           Function<Shown, String> reread, List<Link> links) {
        List<InquiryDraftFigure> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (InquiryClaimGuard.Figure f : InquiryClaimGuard.statedFigures(body)) {
            if (!seen.add(f.value() + "|" + f.unit() + "|" + f.clause())) {
                continue;
            }
            InquiryDraftFigure row = new InquiryDraftFigure();
            row.setOrdinal(out.size());
            row.setValue(cut(f.value(), 40));
            row.setUnit(f.unit());
            row.setReplyClause(cut(f.clause().strip(), MAX_CLAUSE));
            row.setGroundKind(InquiryDraftFigure.Ground.NONE.name());
            Link link = linkFor(f, links == null ? List.of() : links);
            if (link != null) {
                row.setAbout(link.about() == null ? null : cut(link.about(), 80));
                row.setConditionText(link.when() == null ? null : cut(link.when(), 120));
                row.setLinkedBy("MODEL");
                if ("SAID_BACK".equals(link.verdict()) || "문의".equals(link.source())) {
                    if (clauseWith(asked, f) != null) {
                        row.setGroundKind(InquiryDraftFigure.Ground.QUESTION.name());
                        row.setVoice("CUSTOMER");
                    }
                } else if ("주문".equals(link.source())) {
                    if (clauseWith(orderSentence, f) != null) {
                        row.setGroundKind(InquiryDraftFigure.Ground.ORDER.name());
                        row.setVoice("ORDER");
                    }
                } else if (link.passage() != null && link.passage() >= 1 && link.passage() <= shown.size()
                        && shown.get(link.passage() - 1) != null) {
                    Shown s = shown.get(link.passage() - 1);
                    List<String> clauses = clausesOf(s, f, reread);
                    if (!clauses.isEmpty()) {
                        sellerGround(row, s, narrowed(clauses, link.about()));
                    }
                }
                out.add(row);
                continue;
            }
            Shown only = null;
            List<String> clauses = List.of();
            int stating = 0;
            for (Shown s : shown) {
                if (s == null) {
                    continue;
                }
                List<String> here = clausesOf(s, f, reread);
                if (!here.isEmpty()) {
                    stating++;
                    only = s;
                    clauses = here;
                }
            }
            if (stating == 1 && clauses.size() == 1) {
                sellerGround(row, only, clauses);
                row.setLinkedBy("TEXT");
            } else if (stating == 0 && clauseWith(orderSentence, f) != null) {
                row.setGroundKind(InquiryDraftFigure.Ground.ORDER.name());
                row.setVoice("ORDER");
                row.setLinkedBy("TEXT");
            } else if (stating == 0 && clauseWith(asked, f) != null) {
                row.setGroundKind(InquiryDraftFigure.Ground.QUESTION.name());
                row.setVoice("CUSTOMER");
                row.setLinkedBy("TEXT");
            }
            out.add(row);
        }
        return out;
    }

    private static void sellerGround(InquiryDraftFigure row, Shown s, List<String> clauses) {
        row.setGroundKind(s.kind().name());
        row.setSourceId(s.sourceId());
        row.setProductId(s.productId());
        row.setGroundClause(cut(String.join("\n", clauses), MAX_CLAUSE));
        row.setVoice("SELLER");
    }

    /**
     * The check's link for {@code f}: the one asked about the same quantity in the same sentence; else the only one asked
     * about that quantity; else none.
     */
    static Link linkFor(InquiryClaimGuard.Figure f, List<Link> links) {
        List<Link> same = links.stream().filter(l -> l.value() != null && InquiryClaimGuard.sameQuantity(
                new InquiryClaimGuard.Figure(l.value(), l.unit(), l.sentence() == null ? "" : l.sentence()), f)).toList();
        String clause = squash(f.clause());
        for (Link l : same) {
            String sentence = squash(l.sentence());
            if (!sentence.isEmpty() && (sentence.contains(clause) || clause.contains(sentence))) {
                return l;
            }
        }
        return same.size() == 1 ? same.get(0) : null;
    }

    /** Every clause of {@code s} that states {@code f}'s quantity, read through the recheck's reader where it can be. */
    private static List<String> clausesOf(Shown s, InquiryClaimGuard.Figure f, Function<Shown, String> reread) {
        if (clausesWith(s.text(), f).isEmpty()) {
            return List.of();
        }
        List<String> again = clausesWith(reread.apply(s), f);
        return again.isEmpty() ? clausesWith(s.text(), f) : again;
    }

    /**
     * The one clause whose words name every word of {@code about}, when exactly one does; else all of them. A guess
     * between clauses is how 「교환 7일」 came to rest on 「배송 7일」.
     */
    static List<String> narrowed(List<String> clauses, String about) {
        if (clauses.size() < 2 || about == null || about.isBlank()) {
            return clauses;
        }
        List<String> words = java.util.Arrays.stream(about.strip().split("\\s+")).map(InquiryFigureGrounds::squash)
                .filter(w -> !w.isEmpty()).toList();
        List<String> naming = clauses.stream().filter(c -> words.stream().allMatch(squash(c)::contains)).toList();
        return naming.size() == 1 ? naming : clauses;
    }

    /** Every distinct clause of {@code text} that states the same quantity as {@code f}. */
    static List<String> clausesWith(String text, InquiryClaimGuard.Figure f) {
        List<String> out = new ArrayList<>();
        for (InquiryClaimGuard.Figure g : InquiryClaimGuard.writtenFigures(text)) {
            if (InquiryClaimGuard.sameQuantity(g, f) && !out.contains(g.clause().strip())) {
                out.add(g.clause().strip());
            }
        }
        return out;
    }

    /** Whether the figure {@code row} kept still holds, given what its material says now ({@code current}, or null). */
    public static Verdict verdict(InquiryDraftFigure row, String current) {
        InquiryDraftFigure.Ground kind = InquiryDraftFigure.Ground.valueOf(row.getGroundKind());
        if (kind == InquiryDraftFigure.Ground.ORDER || kind == InquiryDraftFigure.Ground.QUESTION) {
            return Verdict.STANDS;
        }
        if (kind == InquiryDraftFigure.Ground.NONE || current == null || row.getGroundClause() == null) {
            return Verdict.UNVERIFIED;
        }
        // Every clause the figure may rest on must still be there: a ground of two clauses stands only as both.
        String now = squash(current);
        if (java.util.Arrays.stream(row.getGroundClause().split("\n")).map(InquiryFigureGrounds::squash)
                .filter(c -> !c.isEmpty()).allMatch(c -> holdsClause(now, c))) {
            return Verdict.STANDS;
        }
        InquiryClaimGuard.Figure stated = new InquiryClaimGuard.Figure(row.getValue(), row.getUnit(), row.getReplyClause());
        return clauseWith(current, stated) == null ? Verdict.CHANGED : Verdict.UNVERIFIED;
    }

    /** The first clause of {@code text} that states the same quantity as {@code f}; null when none does. */
    static String clauseWith(String text, InquiryClaimGuard.Figure f) {
        for (InquiryClaimGuard.Figure g : InquiryClaimGuard.writtenFigures(text)) {
            if (InquiryClaimGuard.sameQuantity(g, f)) {
                return g.clause().strip();
            }
        }
        return null;
    }

    /**
     * Whether {@code clause} is in {@code text} as a whole number: 「7일 이내 교환」 is not in 「17일 이내 교환」, which a plain
     * substring search would say it is.
     */
    static boolean holdsClause(String text, String clause) {
        if (clause.isEmpty()) {
            return false;
        }
        for (int at = text.indexOf(clause); at >= 0; at = text.indexOf(clause, at + 1)) {
            int end = at + clause.length();
            boolean before = at == 0 || !Character.isDigit(clause.charAt(0)) || !Character.isDigit(text.charAt(at - 1));
            boolean after = end == text.length() || !Character.isDigit(clause.charAt(clause.length() - 1))
                    || !Character.isDigit(text.charAt(end));
            if (before && after) {
                return true;
            }
        }
        return false;
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
