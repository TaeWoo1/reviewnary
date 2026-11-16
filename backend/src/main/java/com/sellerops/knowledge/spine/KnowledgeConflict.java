package com.sellerops.knowledge.spine;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.currency.KnowledgeState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Two pieces of company knowledge that state different values for the same thing.</b>
 *
 * <p>Since 2026-11-15 (conflict step 2) read value by value: a value counts only when it is about one attribute of the
 * closed list ({@link ClaimAttribute}), and two values are compared only under the same condition, in the same unit and
 * the same scope ({@link KnowledgeState}). 「배송 1~2일」 is not compared with 「반품 7일」, 「편도 3,000원」 is the same as
 * 「왕복 6,000원」, and two documents that agree on delivery and disagree on the return window disagree. The earlier reading
 * — any two figures of a shared unit under a shared topic — produced all three of those wrong.
 *
 * <p><b>Resolution moves only toward what the seller decided.</b> A different authority settles it toward the seller's
 * stronger word; within one authority, only the seller's replacement or two 적용일 do. <b>When the document was uploaded
 * or saved never decides</b> (manual eval F1: an old notice uploaded later beat the current policy). An unresolved
 * conflict has no winner: {@link #resolved()} is false, both documents stay, and the conflict is what is shown.
 *
 * @param winnerEntryId the current side when resolved; the first side otherwise
 * @param unit          the unit the two values are stated in (일 · 원 · 시)
 * @param attribute     the attribute disagreed on ({@link ClaimAttribute#name()})
 * @param resolved      whether something the seller decided settles which value is current
 * @param sentence      the seller's sentence for it
 */
public record KnowledgeConflict(String winnerEntryId, String winnerTitle, KnowledgeAuthority winnerAuthority,
                                String loserEntryId, String loserTitle, KnowledgeAuthority loserAuthority,
                                String unit, String attribute, boolean resolved, String sentence) {

    /** A conflict as recorded before attributes existed: resolved toward the winner. */
    public KnowledgeConflict(String winnerEntryId, String winnerTitle, KnowledgeAuthority winnerAuthority,
                             String loserEntryId, String loserTitle, KnowledgeAuthority loserAuthority, String unit) {
        this(winnerEntryId, winnerTitle, winnerAuthority, loserEntryId, loserTitle, loserAuthority, unit, null, true,
                null);
    }

    /**
     * The conflicts among {@code entries} alone — nothing the seller set is read, so only a difference in authority
     * resolves one. What a caller without the material's state (a hand-wired test, a bare spine) sees.
     */
    public static List<KnowledgeConflict> detect(List<KnowledgeEntry> entries) {
        return of(entries, stateOf(entries), null);
    }

    /** The entries read as documents, with nothing the seller set: only a difference in authority resolves. */
    public static KnowledgeState stateOf(List<KnowledgeEntry> entries) {
        Map<UUID, KnowledgeEntry> bySource = new LinkedHashMap<>();
        List<KnowledgeState.Source> sources = new ArrayList<>();
        for (KnowledgeEntry e : entries) {
            UUID id = sourceOf(e);
            if (bySource.containsKey(id)) {
                continue;
            }
            bySource.put(id, e);
            sources.add(new KnowledgeState.Source(id, e.scope().name(), e.productId(), scopeKey(e), e.title(),
                    e.authority().rank(), null, ClaimReader.read(textOf(entries, id), e.title())));
        }
        return KnowledgeState.of(sources, List.of(), LocalDate.now(), null);
    }

    /**
     * The conflicts {@code state} holds that matter to a question over {@code entries}: with {@code asked} attributes,
     * every conflict on one of them that touches a document the question found — its partner need not have been found,
     * so a disagreement is not hidden because one side used other words (q14); without, only conflicts both of whose
     * documents were found.
     */
    public static List<KnowledgeConflict> of(List<KnowledgeEntry> entries, KnowledgeState state,
                                             Set<ClaimAttribute> asked) {
        Map<UUID, KnowledgeEntry> bySource = new LinkedHashMap<>();
        for (KnowledgeEntry e : entries) {
            bySource.putIfAbsent(sourceOf(e), e);
        }
        List<KnowledgeConflict> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KnowledgeState.Conflict c : state.conflicts()) {
            boolean a = bySource.containsKey(c.a().sourceId());
            boolean b = bySource.containsKey(c.b().sourceId());
            boolean relevant = asked != null && !asked.isEmpty()
                    ? asked.contains(c.attribute()) && (a || b)
                    : a && b;
            if (!relevant || !seen.add(c.attribute() + "|" + c.condition() + "|" + c.a().sourceId() + "|"
                    + c.b().sourceId())) {
                continue;
            }
            KnowledgeEntry ea = bySource.get(c.a().sourceId());
            KnowledgeEntry eb = bySource.get(c.b().sourceId());
            out.add(new KnowledgeConflict(ea == null ? "source:" + c.a().sourceId() : ea.entryId(), c.a().title(),
                    authorityOf(ea, state.source(c.a().sourceId())),
                    eb == null ? "source:" + c.b().sourceId() : eb.entryId(), c.b().title(),
                    authorityOf(eb, state.source(c.b().sourceId())), unitOf(c.a().claim().unit()), c.attribute().name(),
                    !c.unresolved(), c.sentenceKo()));
        }
        return List.copyOf(out);
    }

    /**
     * The entries whose every compared value lost a resolved conflict — what is left out of what a draft is written
     * with. An entry that also states something nobody replaced stays: a replacement is for one attribute, not for the
     * document.
     */
    public static Set<String> replacedEntries(List<KnowledgeEntry> entries, KnowledgeState state) {
        Set<String> out = new HashSet<>();
        for (KnowledgeEntry e : entries) {
            UUID id = sourceOf(e);
            List<com.sellerops.knowledge.claim.KnowledgeClaim> said = ClaimReader.read(e.text(), e.title()).stream()
                    .filter(k -> !k.historical()).toList();
            if (said.isEmpty()) {
                continue;
            }
            boolean allLost = said.stream().allMatch(k -> state.conflicts().stream().anyMatch(c -> !c.unresolved()
                    && c.replaced().sourceId().equals(id) && c.replaced().claim().slot().equals(k.slot())));
            if (allLost) {
                out.add(e.entryId());
            }
        }
        return out;
    }

    /**
     * The entries that are not a document of the seller's library — a past answer, a guidance, a channel attribute — each
     * as a source of its own, so the material's state compares them with the documents as before (a past answer of
     * 「30일」 against the policy's 「7일」 is resolved toward the policy by authority).
     */
    public static List<KnowledgeState.Source> undocumented(List<KnowledgeEntry> entries) {
        List<KnowledgeState.Source> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (KnowledgeEntry e : entries) {
            boolean documented = e.sourceRefs().stream().anyMatch(r -> r.kind() == SourceRef.Kind.ORG_KNOWLEDGE_SOURCE
                    || r.kind() == SourceRef.Kind.PRODUCT_KNOWLEDGE_SOURCE);
            UUID id = sourceOf(e);
            if (documented || !seen.add(id)) {
                continue;
            }
            out.add(new KnowledgeState.Source(id, e.scope().name(), e.productId(), scopeKey(e), e.title(),
                    e.authority().rank(), null, ClaimReader.read(e.text(), e.title())));
        }
        return out;
    }

    private static String unitOf(ClaimAttribute.Unit unit) {
        return switch (unit) {
            case DAY -> "일";
            case BUSINESS_DAY -> "영업일";
            case WON -> "원";
            case HOUR -> "시";
        };
    }

    /** The document an entry was read from: its source ref, or the entry itself when it names none. */
    public static UUID sourceOf(KnowledgeEntry e) {
        for (SourceRef ref : e.sourceRefs()) {
            if ((ref.kind() == SourceRef.Kind.ORG_KNOWLEDGE_SOURCE || ref.kind() == SourceRef.Kind.PRODUCT_KNOWLEDGE_SOURCE)
                    && ref.id() != null) {
                return ref.id();
            }
        }
        return UUID.nameUUIDFromBytes(String.valueOf(e.entryId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String scopeKey(KnowledgeEntry e) {
        return e.scope() == KnowledgeSpineScope.PRODUCT ? "PRODUCT:" + e.productId() : "ORG";
    }

    /** Every passage of one document among the entries, read together — a document's values are the document's. */
    private static String textOf(List<KnowledgeEntry> entries, UUID source) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry e : entries) {
            if (sourceOf(e).equals(source) && e.text() != null) {
                sb.append(e.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private static KnowledgeAuthority authorityOf(KnowledgeEntry e, KnowledgeState.Source s) {
        if (e != null) {
            return e.authority();
        }
        int rank = s == null ? KnowledgeAuthority.SELLER_POLICY.rank() : s.rank();
        for (KnowledgeAuthority a : KnowledgeAuthority.values()) {
            if (a.rank() == rank) {
                return a;
            }
        }
        return KnowledgeAuthority.SELLER_POLICY;
    }
}
