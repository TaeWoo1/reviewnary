package com.sellerops.knowledge.currency;

import com.sellerops.knowledge.claim.ClaimAttribute;
import com.sellerops.knowledge.claim.ClaimCondition;
import com.sellerops.knowledge.claim.ClaimReader;
import com.sellerops.knowledge.claim.ClaimRelation;
import com.sellerops.knowledge.claim.KnowledgeClaim;
import com.sellerops.knowledge.schedule.KnowledgeSchedule;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>What the seller's material says now, value by value — which value is current, which was replaced, and which is not
 * settled</b> (2026-11-15, conflict steps 2–4; {@code DESIGN_conflict.md} §2–§4).
 *
 * <p>Computed, never stored: every stage that needs it — the search, the judge's candidates, the drafter's passages, the
 * check at approval and at registration — builds it from the documents in use at that moment, so a document turned off,
 * a replacement chosen or a 적용일 set changes every stage at once, and a state attached when a draft was written is never
 * trusted at approval.
 *
 * <p><b>Two values are compared only when</b> they are about the same attribute of the closed list, under the same
 * condition, in the same unit, in the same scope (two company documents, or two documents of the same product — a
 * product's own rule is that product's exception, not a disagreement with the company's), and neither says what used to
 * be. Range relations are {@link ClaimRelation}'s; overlap is not sameness.
 *
 * <p><b>Which one is current</b> — never by when it was uploaded or saved:
 * <ol>
 *   <li>a different authority settles it toward the seller's stronger word (the Knowledge Spine order, 2026-09-18);</li>
 *   <li>the seller's 「이 자료가 저 자료를 대신함」 for that attribute and condition — followed through a chain (A가 B를, B가
 *       C를 대신하면 A가 C를 대신함) and never round one: choices that come back to where they started decide nothing
 *       ({@link Reason#CYCLE});</li>
 *   <li>two different 적용일: the later one is current once it has begun — and only when the order date is known to be on or
 *       after it; an order placed before it was sold under the earlier rule. Without the order date the 적용일 alone does
 *       not settle it ({@code DESIGN_conflict.md} §3). A later 적용일 that has not begun yet leaves the earlier one current;</li>
 *   <li>otherwise it is <b>unresolved</b>: both stand, and neither value is a fact the reply may state.</li>
 * </ol>
 */
public final class KnowledgeState {

    public enum Status { UNRESOLVED, RESOLVED }

    /** Why a disagreement ended where it did. */
    public enum Reason {
        AUTHORITY, SUPERSEDED, EFFECTIVE_DATE, NOT_YET_EFFECTIVE,
        /** Two 적용일 and no order date: which rule this customer bought under is not known. */
        ORDER_DATE_UNKNOWN,
        /** The seller's replacements for this attribute come back round to each other: no document can be current. */
        CYCLE,
        /** Nothing the seller set decides it. */
        NONE
    }

    /**
     * One document as the state reads it.
     *
     * @param scopeKey  {@code ORG}, or {@code PRODUCT:<id>} — only documents with the same key are compared
     * @param rank      the authority rank ({@code KnowledgeAuthority#rank}); lower is stronger
     */
    public record Source(UUID id, String kind, UUID productId, String scopeKey, String title, int rank,
                         LocalDate effectiveFrom, List<KnowledgeClaim> claims) {
        public Source {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    /** A seller's replacement, as the state reads it. */
    public record Replacement(UUID id, UUID newer, UUID older, ClaimAttribute attribute, ClaimCondition condition) {
    }

    /** One side of a disagreement: the document and the value it states. */
    public record Side(UUID sourceId, String title, KnowledgeClaim claim, LocalDate effectiveFrom) {
        public String valueKo() {
            return claim.valueKo();
        }
    }

    /**
     * Two documents that state different values for the same attribute under the same condition. When resolved,
     * {@code a} is the current one; when not, {@code a} and {@code b} are in reading order.
     */
    public record Conflict(ClaimAttribute attribute, ClaimCondition condition, Side a, Side b, ClaimRelation relation,
                           Status status, Reason reason, UUID replacementId) {

        public boolean unresolved() {
            return status == Status.UNRESOLVED;
        }

        public Side current() {
            return unresolved() ? null : a;
        }

        public Side replaced() {
            return unresolved() ? null : b;
        }

        public boolean touches(UUID sourceId) {
            return a.sourceId().equals(sourceId) || b.sourceId().equals(sourceId);
        }

        public Side sideOf(UUID sourceId) {
            return a.sourceId().equals(sourceId) ? a : b.sourceId().equals(sourceId) ? b : null;
        }

        public Side otherThan(UUID sourceId) {
            return a.sourceId().equals(sourceId) ? b : a;
        }

        /** 「교환 신청 기한」, 「단순 변심 반품 배송비」. */
        public String subjectKo() {
            return a.claim().subjectKo();
        }

        /** The seller's sentence: 「교환 신청 기한이 자료마다 달라요 — 7일(「교환·반품 안내」) · 14일(「옛 공지」)」. */
        public String sentenceKo() {
            String pair = shown(a) + "(「" + a.title() + "」) · " + shown(b) + "(「" + b.title() + "」)";
            if (unresolved()) {
                String why = reason == Reason.ORDER_DATE_UNKNOWN
                        ? " 적용일만으로는 이 주문이 어느 기준으로 팔렸는지 알 수 없어요."
                        : reason == Reason.CYCLE ? " 정해 둔 대체 관계가 서로 돌고 있어 어느 쪽도 현행으로 볼 수 없어요." : "";
                return subjectKo() + "이(가) 자료마다 달라요 — " + pair + "." + why;
            }
            return subjectKo() + "은(는) 「" + a.title() + "」의 " + shown(a) + "이(가) 현행이에요(「" + b.title()
                    + "」의 " + shown(b) + " 대신, " + reasonKo() + ").";
        }

        /** A side's value as the seller reads it — with its payer when the two name different payers. */
        public String shown(Side side) {
            KnowledgeClaim.Payer pa = a.claim().payer();
            KnowledgeClaim.Payer pb = b.claim().payer();
            return pa != null && pb != null && pa != pb ? side.claim().valueWithPayerKo() : side.valueKo();
        }

        String reasonKo() {
            return switch (reason) {
                case AUTHORITY -> "더 우선하는 자료";
                case SUPERSEDED -> "판매자가 대신하도록 정함";
                case EFFECTIVE_DATE -> "적용일 기준";
                case NOT_YET_EFFECTIVE -> "다른 쪽 적용일이 아직 오지 않음";
                default -> "정해짐";
            };
        }
    }

    /**
     * What a candidate passage carries: lines the judge and the drafter read beside it, and the attributes it states that
     * are not settled (with the seller's sentence for each) — what code holds a need to.
     */
    public record Mark(List<String> notes, Map<ClaimAttribute, String> unresolved, Set<ClaimAttribute> replaced) {
        public static final Mark NONE = new Mark(List.of(), Map.of(), Set.of());

        public Mark {
            notes = notes == null ? List.of() : List.copyOf(notes);
            unresolved = unresolved == null ? Map.of() : Map.copyOf(unresolved);
            replaced = replaced == null ? Set.of() : Set.copyOf(replaced);
        }

        public boolean isEmpty() {
            return notes.isEmpty();
        }

        /** The notes as one line, for a stored record or a prompt field. */
        public String line() {
            return String.join(" ", notes);
        }
    }

    /**
     * A reply that states, as a fact, a value that is not settled or that was replaced — or a planned date as if it were
     * confirmed. {@code said} is the value as the reply states it.
     */
    public record Unsettled(String kind, ClaimAttribute attribute, String said, String sentenceKo) {
        public static final String UNRESOLVED = "UNRESOLVED";
        public static final String REPLACED = "REPLACED";
        public static final String PLANNED = "PLANNED";
    }

    private final List<Source> sources;
    private final List<Conflict> conflicts;
    private final List<KnowledgeSchedule> schedules;
    private final Map<UUID, String> productNames;

    private KnowledgeState(List<Source> sources, List<Conflict> conflicts, List<KnowledgeSchedule> schedules,
                           Map<UUID, String> productNames) {
        this.sources = List.copyOf(sources);
        this.conflicts = List.copyOf(conflicts);
        this.schedules = List.copyOf(schedules);
        this.productNames = Map.copyOf(productNames);
    }

    public static final KnowledgeState EMPTY = new KnowledgeState(List.of(), List.of(), List.of(), Map.of());

    /**
     * The state of {@code sources} under the seller's replacements, on {@code reference} (the day the question is asked),
     * for an order placed on {@code orderDate} (null when not known).
     */
    public static KnowledgeState of(List<Source> sources, List<Replacement> replacements, LocalDate reference,
                                    LocalDate orderDate) {
        return of(sources, replacements, reference, orderDate, List.of(), Map.of());
    }

    public static KnowledgeState of(List<Source> sources, List<Replacement> replacements, LocalDate reference,
                                    LocalDate orderDate, List<KnowledgeSchedule> schedules,
                                    Map<UUID, String> productNames) {
        List<Conflict> found = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            for (int j = i + 1; j < sources.size(); j++) {
                Source x = sources.get(i);
                Source y = sources.get(j);
                if (!Objects.equals(x.scopeKey(), y.scopeKey())) {
                    continue;
                }
                Set<String> slots = new LinkedHashSet<>();
                for (KnowledgeClaim c : x.claims()) {
                    if (!c.historical()) {
                        slots.add(c.slot());
                    }
                }
                for (String slot : slots) {
                    // The first pair that disagrees stands for the slot. Two documents that also agree on another value of
                    // the same slot still disagree: a customer can be told either.
                    KnowledgeClaim cx = null;
                    KnowledgeClaim cy = null;
                    ClaimRelation rel = null;
                    outer:
                    for (KnowledgeClaim a : x.claims()) {
                        if (!slot.equals(a.slot()) || a.historical()) {
                            continue;
                        }
                        for (KnowledgeClaim b : y.claims()) {
                            ClaimRelation r = ClaimRelation.of(a, b);
                            if (r.conflicts()) {
                                cx = a;
                                cy = b;
                                rel = r;
                                break outer;
                            }
                        }
                    }
                    if (rel != null) {
                        found.add(resolve(x, cx, y, cy, rel, replacements, reference, orderDate));
                    }
                }
            }
        }
        // Two values that both lost — each to a document that is current over it — do not leave the item unsettled
        // between them: neither is what the reply may say, and the current one is (2026-11-15 review: A가 B와 C를 모두
        // 대신하면 B와 C의 차이는 「확인 필요」가 아니다; the line beside them would call A current and the item unsettled).
        Set<String> losers = new LinkedHashSet<>();
        for (Conflict c : found) {
            if (!c.unresolved()) {
                losers.add(c.replaced().sourceId() + "|" + c.replaced().claim().slot());
            }
        }
        found.removeIf(c -> c.unresolved() && c.reason() != Reason.CYCLE
                && losers.contains(c.a().sourceId() + "|" + c.a().claim().slot())
                && losers.contains(c.b().sourceId() + "|" + c.b().claim().slot()));
        // …and a win by a value that itself lost to the slot's current one is no win: in A > B > C, B is not 「current」
        // over C — A is, on its own pair (or C says what A says). Only where such a current one exists: a set of wins that
        // goes round with no top is left as it is, every side shown.
        Set<String> tops = new LinkedHashSet<>();
        for (Conflict c : found) {
            if (!c.unresolved() && !losers.contains(c.current().sourceId() + "|" + c.current().claim().slot())) {
                tops.add(c.current().sourceId() + "|" + c.current().claim().slot());
            }
        }
        List<Conflict> resolvedNow = List.copyOf(found);
        found.removeIf(c -> !c.unresolved()
                && losers.contains(c.current().sourceId() + "|" + c.current().claim().slot())
                && resolvedNow.stream().anyMatch(o -> !o.unresolved() && o != c
                        && o.replaced().sourceId().equals(c.current().sourceId())
                        && o.replaced().claim().slot().equals(c.current().claim().slot())
                        && tops.contains(o.current().sourceId() + "|" + o.current().claim().slot())));
        // An item the documents themselves leave unsettled has no current value — so a weaker source (a past answer)
        // that disagrees with one of them is not 「replaced by the current one」: there is none yet. Its disagreement is
        // the same unsettled item, and saying 「X is current」 would contradict the line beside it.
        Set<String> unsettledSlots = new LinkedHashSet<>();
        Map<UUID, String> scopeOf = new LinkedHashMap<>();
        sources.forEach(src -> scopeOf.put(src.id(), src.scopeKey()));
        for (Conflict c : found) {
            if (c.unresolved()) {
                unsettledSlots.add(c.attribute() + "|" + c.condition() + "|" + scopeOf.get(c.a().sourceId()));
            }
        }
        // …and a value the seller replaced is not 「current」 over a weaker source either: the slot's current value is the
        // replacement's, and a weaker source is compared with that one on its own pair.
        Set<String> replacedSides = new LinkedHashSet<>();
        for (Conflict c : found) {
            if (!c.unresolved() && c.reason() != Reason.AUTHORITY) {
                replacedSides.add(c.attribute() + "|" + c.condition() + "|" + c.replaced().sourceId());
            }
        }
        found.removeIf(c -> c.reason() == Reason.AUTHORITY
                && (unsettledSlots.contains(c.attribute() + "|" + c.condition() + "|" + scopeOf.get(c.a().sourceId()))
                || replacedSides.contains(c.attribute() + "|" + c.condition() + "|" + c.a().sourceId())));
        return new KnowledgeState(sources, found, schedules == null ? List.of() : schedules,
                productNames == null ? Map.of() : productNames);
    }

    private static Conflict resolve(Source x, KnowledgeClaim cx, Source y, KnowledgeClaim cy, ClaimRelation rel,
                                    List<Replacement> replacements, LocalDate reference, LocalDate orderDate) {
        Side sx = new Side(x.id(), x.title(), cx, x.effectiveFrom());
        Side sy = new Side(y.id(), y.title(), cy, y.effectiveFrom());
        ClaimAttribute attribute = cx.attribute();
        ClaimCondition condition = cx.condition();
        if (x.rank() != y.rank()) {
            boolean xWins = x.rank() < y.rank();
            return new Conflict(attribute, condition, xWins ? sx : sy, xWins ? sy : sx, rel, Status.RESOLVED,
                    Reason.AUTHORITY, null);
        }
        List<Replacement> scoped = (replacements == null ? List.<Replacement>of() : replacements).stream()
                .filter(r -> r.attribute() == attribute && r.condition() == condition).toList();
        Replacement xOverY = path(scoped, x.id(), y.id());
        Replacement yOverX = path(scoped, y.id(), x.id());
        if (xOverY != null && yOverX != null) {
            return new Conflict(attribute, condition, sx, sy, rel, Status.UNRESOLVED, Reason.CYCLE, null);
        }
        if (xOverY != null) {
            return new Conflict(attribute, condition, sx, sy, rel, Status.RESOLVED, Reason.SUPERSEDED, xOverY.id());
        }
        if (yOverX != null) {
            return new Conflict(attribute, condition, sy, sx, rel, Status.RESOLVED, Reason.SUPERSEDED, yOverX.id());
        }
        LocalDate ex = x.effectiveFrom();
        LocalDate ey = y.effectiveFrom();
        if (ex != null && ey != null && !ex.equals(ey)) {
            Side later = ex.isAfter(ey) ? sx : sy;
            Side earlier = later == sx ? sy : sx;
            LocalDate laterFrom = later.effectiveFrom();
            if (reference != null && laterFrom.isAfter(reference)) {
                return new Conflict(attribute, condition, earlier, later, rel, Status.RESOLVED,
                        Reason.NOT_YET_EFFECTIVE, null);
            }
            if (orderDate != null) {
                boolean underLater = !orderDate.isBefore(laterFrom);
                return new Conflict(attribute, condition, underLater ? later : earlier, underLater ? earlier : later,
                        rel, Status.RESOLVED, Reason.EFFECTIVE_DATE, null);
            }
            return new Conflict(attribute, condition, later, earlier, rel, Status.UNRESOLVED,
                    Reason.ORDER_DATE_UNKNOWN, null);
        }
        return new Conflict(attribute, condition, sx, sy, rel, Status.UNRESOLVED, Reason.NONE, null);
    }

    /**
     * The seller's replacement that starts a chain from {@code from} to {@code to} — the direct one when there is one —
     * or null when no chain of {@code scoped} leads there.
     */
    static Replacement path(List<Replacement> scoped, UUID from, UUID to) {
        for (Replacement r : scoped) {
            if (r.newer().equals(from) && r.older().equals(to)) {
                return r;
            }
        }
        for (Replacement first : scoped) {
            if (!first.newer().equals(from)) {
                continue;
            }
            Set<UUID> seen = new LinkedHashSet<>(List.of(from));
            java.util.ArrayDeque<UUID> next = new java.util.ArrayDeque<>(List.of(first.older()));
            while (!next.isEmpty()) {
                UUID at = next.poll();
                if (at.equals(to)) {
                    return first;
                }
                if (!seen.add(at)) {
                    continue;
                }
                for (Replacement r : scoped) {
                    if (r.newer().equals(at)) {
                        next.add(r.older());
                    }
                }
            }
        }
        return null;
    }

    /** Whether a chain of the seller's replacements leads from {@code from} to {@code to}. */
    public static boolean reaches(List<Replacement> scoped, UUID from, UUID to) {
        return path(scoped, from, to) != null;
    }

    public List<Source> sources() {
        return sources;
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }

    public List<KnowledgeSchedule> schedules() {
        return schedules;
    }

    public Source source(UUID id) {
        return sources.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** The disagreements touching any of {@code sourceIds}. */
    public List<Conflict> touching(Collection<UUID> sourceIds) {
        return conflicts.stream().filter(c -> sourceIds.stream().anyMatch(c::touches)).toList();
    }

    /** The documents that state a current value for any of {@code attributes} — where a question's answer would be read. */
    public List<Source> stating(Set<ClaimAttribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return List.of();
        }
        return sources.stream().filter(s -> s.claims().stream()
                .anyMatch(c -> !c.historical() && attributes.contains(c.attribute()))).toList();
    }

    /** The attributes among {@code attributes} that are not settled, each with the seller's sentence. */
    public Map<ClaimAttribute, String> unresolvedAmong(Set<ClaimAttribute> attributes) {
        Map<ClaimAttribute, String> out = new LinkedHashMap<>();
        for (Conflict c : conflicts) {
            if (c.unresolved() && attributes.contains(c.attribute())) {
                out.putIfAbsent(c.attribute(), c.sentenceKo());
            }
        }
        return out;
    }

    /**
     * What a passage of {@code sourceId} carries: for each value it states that is in a disagreement — not settled,
     * replaced, or the current one — one line the judge and the drafter read beside it.
     */
    public Mark markFor(UUID sourceId, String passageText) {
        if (sourceId == null) {
            return Mark.NONE;
        }
        Source owner = source(sourceId);
        List<KnowledgeClaim> said = ClaimReader.read(passageText, owner == null ? null : owner.title());
        List<String> notes = new ArrayList<>();
        Map<ClaimAttribute, String> unresolved = new LinkedHashMap<>();
        Set<ClaimAttribute> replaced = EnumSet.noneOf(ClaimAttribute.class);
        // A value that lost, or that is not settled, is never also called current — not even over a third document.
        Set<String> notCurrent = new LinkedHashSet<>();
        for (Conflict c : conflicts) {
            Side mine = c.sideOf(sourceId);
            if (mine != null && (c.unresolved() || c.replaced().sourceId().equals(sourceId))) {
                notCurrent.add(mine.claim().slot());
            }
        }
        for (Conflict c : conflicts) {
            Side mine = c.sideOf(sourceId);
            if (mine == null || said.stream().noneMatch(k -> k.slot().equals(mine.claim().slot()) && !k.historical())) {
                continue;
            }
            Side other = c.otherThan(sourceId);
            if (!c.unresolved() && c.current().sourceId().equals(sourceId) && notCurrent.contains(mine.claim().slot())) {
                continue;
            }
            String line;
            // The line names its item, not just 「이 값」 (2026-11-15 real eval q06): 「수령 후 7일 이내에 교환·반품」 states one
            // number for two items, and 「이 값을 확정된 사실로 쓰지 않습니다」 under an exchange-window conflict made the judge
            // drop the 7일 return window too — a disagreement nobody had.
            if (c.unresolved()) {
                line = "[확인 필요] " + c.subjectKo() + "이(가) 자료마다 달라요: 이 자료 " + c.shown(mine) + ", 「"
                        + other.title() + "」 " + c.shown(other) + ". 어느 쪽이 현행인지 정해지지 않았으니 " + c.subjectKo()
                        + "(으)로는 이 값을 확정된 사실로 쓰지 않습니다.";
                unresolved.putIfAbsent(c.attribute(), c.sentenceKo());
            } else if (c.replaced().sourceId().equals(sourceId)) {
                line = "[대체됨] 이 자료의 " + c.subjectKo() + "(" + c.shown(mine) + ")는 「" + other.title() + "」의 "
                        + c.shown(other) + "(으)로 바뀌었습니다. " + c.subjectKo() + "(으)로는 이 값을 쓰지 않습니다.";
                replaced.add(c.attribute());
            } else {
                line = "[현행] " + c.subjectKo() + "는 이 자료의 " + c.shown(mine) + "이(가) 현행입니다(「" + other.title()
                        + "」의 " + c.shown(other) + " 대신).";
            }
            if (!notes.contains(line)) {
                notes.add(line);
            }
        }
        // Beside a 「확인 필요」 or 「대체됨」 line, what else this passage states that no detected disagreement touches — so a
        // reader does not carry the held item's doubt to the next item the same sentence states. Said only of the closed
        // list's items, and only that no disagreement was found: never that a value is confirmed.
        if (!unresolved.isEmpty() || !replaced.isEmpty()) {
            Set<String> touched = new LinkedHashSet<>();
            for (Conflict c : conflicts) {
                Side mine = c.sideOf(sourceId);
                if (mine != null) {
                    touched.add(mine.claim().slot());
                }
            }
            for (KnowledgeClaim k : said) {
                if (k.historical() || touched.contains(k.slot()) || unresolved.containsKey(k.attribute())
                        || replaced.contains(k.attribute())) {
                    continue;
                }
                String line = "[다툼 없음] 이 자료의 " + k.subjectKo() + " " + k.valueWithPayerKo()
                        + "은(는) 다른 자료와 값이 다르다고 확인된 것이 없습니다. 위 표시는 그 표시가 말하는 항목에만 해당합니다.";
                if (!notes.contains(line)) {
                    notes.add(line);
                }
            }
        }
        return new Mark(notes, unresolved, replaced);
    }

    // ── a reply checked against it ──────────────────────────────────────────────────────────────────────────────────

    /**
     * What says a date is not settled. Read in the date's own clause only (2026-11-15 review): 「11월 25일에 입고됩니다, 배송
     * 일정은 확인 후 안내하겠습니다」 states the 입고일 as confirmed, whatever the next clause hedges. 「확인 후」 is not one — it
     * says what will be done next, never that the date may change.
     */
    private static final Pattern HEDGE = Pattern.compile("예정|바뀔|변동|변경될|달라질|미정|확정되면|확정 ?전|아직|정해지면|확인 ?중");
    /** Where a clause ends inside a sentence: a comma, or a connective ending — never the 고 of 입고 · 출고 · 재고. */
    private static final Pattern DATE_CLAUSE = Pattern.compile(
            "[,，;]\\s*|(?<=며|지만|는데|으나|이나)\\s+|(?<=[^입출재]고)\\s+");
    private static final Map<KnowledgeSchedule.Kind, Pattern> KIND_WORDS = Map.of(
            KnowledgeSchedule.Kind.INBOUND, Pattern.compile("입고|들어오|들어와|입하"),
            KnowledgeSchedule.Kind.OUTBOUND, Pattern.compile("출고|발송|보내"),
            KnowledgeSchedule.Kind.PRODUCTION, Pattern.compile("제작|생산"));
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Every value {@code reply} states as a fact that the material does not settle: a value of an attribute two documents
     * disagree on, a value the seller replaced (by another document, or within the same one — 「그 전에는 5만원」), and a
     * planned schedule's date said without saying it is planned. Read as it is written, sentence by sentence; a reply
     * that says what used to be (「그 전에는 …였습니다」) states no current fact.
     */
    public List<Unsettled> unsettledIn(String reply) {
        List<Unsettled> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return out;
        }
        for (KnowledgeClaim said : ClaimReader.readReply(reply)) {
            if (said.historical()) {
                continue;
            }
            for (Conflict c : conflicts) {
                if (c.attribute() != said.attribute() || !conditionMatches(said.condition(), c.condition())) {
                    continue;
                }
                if (c.unresolved()) {
                    add(out, new Unsettled(Unsettled.UNRESOLVED, c.attribute(), said.valueKo(), c.sentenceKo()
                            + " 답변은 「" + said.valueKo() + "」로 적고 있어요. 어느 쪽이 맞는지 정한 뒤 고치거나, 이 부분을 빼 주세요."));
                } else if (sameValue(said, c.replaced().claim()) && !sameValue(said, c.current().claim())) {
                    add(out, new Unsettled(Unsettled.REPLACED, c.attribute(), said.valueKo(), "「" + c.replaced().title()
                            + "」의 " + c.subjectKo() + "(" + c.replaced().valueKo() + ")는 「" + c.current().title() + "」의 "
                            + c.current().valueKo() + "(으)로 바뀌었는데, 답변은 「" + said.valueKo() + "」로 적고 있어요."));
                }
            }
            // A value the same document says it used to be (「그 전에는 5만원 이상 무료였습니다」).
            for (Source s : sources) {
                for (KnowledgeClaim past : s.claims()) {
                    if (!past.historical() || past.attribute() != said.attribute()
                            || !conditionMatches(said.condition(), past.condition()) || !sameValue(said, past)) {
                        continue;
                    }
                    KnowledgeClaim now = s.claims().stream().filter(k -> !k.historical()
                            && k.attribute() == past.attribute() && k.condition() == past.condition()).findFirst()
                            .orElse(null);
                    if (now != null && !sameValue(said, now)) {
                        add(out, new Unsettled(Unsettled.REPLACED, said.attribute(), said.valueKo(), "「" + s.title()
                                + "」에서 " + past.subjectKo() + "은(는) " + now.valueKo() + "(으)로 바뀌었고 "
                                + past.valueKo() + "은(는) 이전 기준인데, 답변은 「" + said.valueKo() + "」로 적고 있어요."));
                    }
                }
            }
        }
        out.addAll(plannedAsConfirmed(reply));
        return List.copyOf(out);
    }

    private List<Unsettled> plannedAsConfirmed(String reply) {
        List<Unsettled> out = new ArrayList<>();
        for (KnowledgeSchedule s : schedules) {
            if (!s.isActive() || s.getStatus() != KnowledgeSchedule.Status.PLANNED || s.getScheduleDate() == null) {
                continue;
            }
            LocalDate d = s.getScheduleDate();
            for (String sentence : reply.split("(?<=[.!?。])\\s+|\\n+")) {
                for (String clause : DATE_CLAUSE.split(sentence)) {
                    if (!namesDate(clause, d) || HEDGE.matcher(clause).find() || aboutAnother(clause, s)) {
                        continue;
                    }
                    String what = scheduleLabel(s);
                    add(out, new Unsettled(Unsettled.PLANNED, null, d.getMonthValue() + "월 " + d.getDayOfMonth() + "일",
                            what + " " + s.getKind().labelKo() + "일 " + d.getMonthValue() + "월 " + d.getDayOfMonth()
                                    + "일은 아직 예정인데, 답변이 확정된 날짜처럼 적고 있어요. 그 날짜를 적은 문장에 예정이라 "
                                    + "바뀔 수 있다고 함께 적어 주세요."));
                }
            }
        }
        return out;
    }

    private static boolean namesDate(String text, LocalDate d) {
        List<String> forms = List.of(d.format(ISO), d.getMonthValue() + "월 " + d.getDayOfMonth() + "일",
                d.getMonthValue() + "월" + d.getDayOfMonth() + "일", d.getMonthValue() + "/" + d.getDayOfMonth(),
                String.format("%d.%d", d.getMonthValue(), d.getDayOfMonth()),
                String.format("%02d/%02d", d.getMonthValue(), d.getDayOfMonth()));
        return forms.stream().anyMatch(text::contains);
    }

    /**
     * Whether a clause naming {@code planned}'s date is plainly about another line on the same day (2026-11-15 review: 같은
     * 날짜의 다른 옵션 · 일정 종류): it names a kind of schedule and not this one's (「11월 25일에 출고」 against a planned 입고),
     * or names another line's option and not this one's. A clause that names neither is read as about the planned line.
     */
    private boolean aboutAnother(String clause, KnowledgeSchedule planned) {
        boolean namesMine = KIND_WORDS.get(planned.getKind()).matcher(clause).find();
        boolean namesOther = KIND_WORDS.entrySet().stream()
                .anyMatch(e -> e.getKey() != planned.getKind() && e.getValue().matcher(clause).find());
        if (namesOther && !namesMine) {
            return true;
        }
        String mine = option(planned);
        if (mine != null && clause.contains(mine)) {
            return false;
        }
        for (KnowledgeSchedule other : schedules) {
            String theirs = option(other);
            if (other != planned && theirs != null && !theirs.equals(mine) && clause.contains(theirs)) {
                return true;
            }
        }
        return false;
    }

    private static String option(KnowledgeSchedule s) {
        return s.getOptionName() == null || s.getOptionName().isBlank() ? null : s.getOptionName().strip();
    }

    // ── which old findings the state answers ────────────────────────────────────────────────────────────────────────

    /**
     * Whether the material, as settled, stands behind {@code said} (2026-11-15 review: 숫자를 읽었다는 것과 현행 근거로
     * 검증했다는 것은 다르다). It does only when a value in use — not replaced, not left unsettled — says the same for the same
     * attribute, unit and condition (a rule that names no condition covers every one; a reply that names none is read
     * against every one), and every other value in use that says otherwise lost to one that says the same. Nothing
     * stated, a different unit, a value another scope says otherwise of (a product document is not the company's
     * exception), or one only a ×2 makes equal while a payer is named the other way — none of them stands behind it.
     */
    public boolean supports(KnowledgeClaim said) {
        if (said == null || said.historical()) {
            return false;
        }
        boolean backed = false;
        for (Source src : sources) {
            for (KnowledgeClaim k : src.claims()) {
                if (k.historical() || k.attribute() != said.attribute() || k.unit() != said.unit()
                        || !compatible(said.condition(), k.condition())) {
                    continue;
                }
                boolean agrees = agrees(said, k);
                if (agrees && !lost(src.id(), k) && !unsettled(src.id(), k)) {
                    backed = true;
                } else if (!agrees && !lostTo(src.id(), k, said)) {
                    return false;
                }
            }
        }
        return backed;
    }

    /**
     * The figures of {@code reply} the material stands behind ({@link #supports}) — the only ones a word-level check's
     * 「another document says another number」 may be set aside for. A figure written twice is settled only when each
     * time it is.
     */
    public Set<String> settledFigures(String reply) {
        Map<String, Boolean> figures = new LinkedHashMap<>();
        for (KnowledgeClaim c : ClaimReader.readReply(reply)) {
            boolean ok = supports(c);
            for (String f : figuresOf(c)) {
                figures.merge(f, ok, Boolean::logicalAnd);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        figures.forEach((f, ok) -> {
            if (ok) {
                out.add(f);
            }
        });
        return out;
    }

    private static boolean compatible(ClaimCondition said, ClaimCondition stated) {
        return said == stated || said == ClaimCondition.GENERAL || stated == ClaimCondition.GENERAL;
    }

    /**
     * The same amount: the same value or range; one leg against the other only by ×2; a leg named on one side only when
     * the number is the same. Never when the two name different payers.
     */
    private static boolean agrees(KnowledgeClaim said, KnowledgeClaim stated) {
        if (said.payer() != null && stated.payer() != null && said.payer() != stated.payer()) {
            return false;
        }
        if (said.leg() != null && stated.leg() != null && said.leg() != stated.leg()) {
            if (said.low() != said.high() || stated.low() != stated.high()) {
                return false;
            }
            long a = said.leg() == KnowledgeClaim.Leg.ONE_WAY ? said.low() * 2 : said.low();
            long b = stated.leg() == KnowledgeClaim.Leg.ONE_WAY ? stated.low() * 2 : stated.low();
            return a == b;
        }
        return said.low() == stated.low() && said.high() == stated.high();
    }

    /** Whether {@code k} of {@code sourceId} is the side that lost a disagreement. */
    private boolean lost(UUID sourceId, KnowledgeClaim k) {
        return conflicts.stream().anyMatch(c -> !c.unresolved() && c.replaced().sourceId().equals(sourceId)
                && c.replaced().claim().slot().equals(k.slot()));
    }

    /** Whether {@code k} of {@code sourceId} lost to a value that says what {@code said} says. */
    private boolean lostTo(UUID sourceId, KnowledgeClaim k, KnowledgeClaim said) {
        return conflicts.stream().anyMatch(c -> !c.unresolved() && c.replaced().sourceId().equals(sourceId)
                && c.replaced().claim().slot().equals(k.slot()) && agrees(said, c.current().claim()));
    }

    private boolean unsettled(UUID sourceId, KnowledgeClaim k) {
        return conflicts.stream().anyMatch(c -> c.unresolved() && c.touches(sourceId)
                && c.sideOf(sourceId).claim().slot().equals(k.slot()));
    }

    private static List<String> figuresOf(KnowledgeClaim c) {
        String unit = switch (c.unit()) {
            case DAY, BUSINESS_DAY -> "일";
            case WON -> "원";
            case HOUR -> "시";
        };
        return c.low() == c.high() ? List.of(c.low() + unit) : List.of(c.low() + unit, c.high() + unit);
    }

    /**
     * The figures of {@code reply} that state a value of the closed list (「7일」, 「6000원」), as the word-level checks write
     * a term. Reading one is not checking it: only those {@link #settledFigures} stands behind may set a word-level
     * finding aside. Commas and spaces removed.
     */
    public static Set<String> governedFigures(String reply) {
        Set<String> out = new LinkedHashSet<>();
        for (KnowledgeClaim c : ClaimReader.readReply(reply)) {
            out.addAll(figuresOf(c));
        }
        return out;
    }

    /** Whether a word-level term is one of {@code figures} ({@link #settledFigures}, {@link #governedFigures}). */
    public static boolean governs(Set<String> figures, String term) {
        return term != null && figures.contains(term.replace(",", "").replaceAll("\\s+", ""));
    }

    /** 「면 침구 세트(퀸) · 그레이」 — the product and option a schedule line is about. */
    public String scheduleLabel(KnowledgeSchedule s) {
        String product = s.getProductId() == null ? "전체 상품" : productNames.getOrDefault(s.getProductId(), "상품");
        return "「" + product + (s.getOptionName() == null || s.getOptionName().isBlank() ? "" : " · "
                + s.getOptionName().strip()) + "」";
    }

    private static void add(List<Unsettled> out, Unsettled u) {
        if (out.stream().noneMatch(o -> o.kind().equals(u.kind()) && o.sentenceKo().equals(u.sentenceKo()))) {
            out.add(u);
        }
    }

    /** A reply that names no condition may be about any; one that names a condition is about that one. */
    private static boolean conditionMatches(ClaimCondition said, ClaimCondition stated) {
        return said == ClaimCondition.GENERAL || said == stated;
    }

    private static boolean sameValue(KnowledgeClaim said, KnowledgeClaim stated) {
        if (said.leg() != stated.leg() && said.leg() != null && stated.leg() != null) {
            long a = said.leg() == KnowledgeClaim.Leg.ONE_WAY ? said.low() * 2 : said.low();
            long b = stated.leg() == KnowledgeClaim.Leg.ONE_WAY ? stated.low() * 2 : stated.low();
            return a == b;
        }
        return said.low() == stated.low() && said.high() == stated.high();
    }

    /** What the state is made of that the seller set — for a basis that must move when it does. */
    public static String describe(List<Replacement> replacements, Map<UUID, LocalDate> effective,
                                  List<KnowledgeSchedule> schedules) {
        StringBuilder sb = new StringBuilder();
        replacements.stream().map(r -> r.newer() + ">" + r.older() + ":" + r.attribute() + ":" + r.condition())
                .sorted().forEach(s -> sb.append(s).append(';'));
        effective.entrySet().stream().filter(e -> e.getValue() != null).map(e -> e.getKey() + "=" + e.getValue())
                .sorted().forEach(s -> sb.append(s).append(';'));
        schedules.stream().map(s -> s.getId() + ":" + s.getProductId() + ":" + s.getOptionName() + ":" + s.getKind()
                + ":" + s.getStatus() + ":" + s.getScheduleDate() + ":" + s.getScopeNote() + ":" + s.isActive())
                .sorted().forEach(s -> sb.append(s).append(';'));
        return sb.toString();
    }
}
