package com.sellerops.review.draft;

import com.sellerops.inquiry.draft.InquiryEvidenceRetriever.ScopedPassage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>The small bundle a review reply is written from, and the passages the writer says it used</b> (2026-11-01 third
 * review: 「작은 자료 묶음을 기존 호출에 넣는 방향에 동의합니다」; {@code docs/standing/reply_agent_v1.md} §8 — the
 * Claude-only alternative to embeddings, after round 2 found 3 of 7 reviews whose product knowledge the word search
 * missed).
 *
 * <p><b>What goes in.</b> Only this org's material, only what is in use, only what may apply to this review: what the
 * word search found, then every in-use passage of the review's own product (none when the review names no product),
 * then every in-use operating policy — in that order, each passage once, none carrying a 【확인: …】 blank, at most
 * {@value #MAX_ITEMS} passages and {@value #MAX_CHARS} characters. A passage that does not fit is left out whole: a cut
 * passage could lose the condition that limits it. A past answer is never in it — it is wording, never a ground.
 *
 * <p><b>What comes out.</b> Each passage is named 「근거 n」. The writer answers with the ones its reply stands on and a
 * sentence copied from each ({@link #chosen}); a name not in the bundle, or a quote not in that passage, is dropped and
 * counted. What survives is the reply's whole ground: the checks after the writer read only it, the critique is shown
 * only it, the saved version cites only it, and the recheck reads only it again.
 */
public final class ReviewEvidenceBundle {

    static final int MAX_ITEMS = 12;
    static final int MAX_CHARS = 6000;
    /** A quote shorter than this proves nothing about which sentence was read. */
    static final int MIN_QUOTE = 6;

    /** One passage of the bundle, and the name the writer cites it by. */
    public record Item(String id, ScopedPassage passage) {
    }

    /** What the writer said it used: the passage's name and a sentence from it. */
    public record Used(String id, String quote) {
    }

    /** The passages a writer's citations name, verified; and how many it named that were not in the bundle as quoted. */
    public record Chosen(List<Item> items, int rejected) {
        public List<String> ids() {
            return items.stream().map(Item::id).toList();
        }
    }

    private ReviewEvidenceBundle() {
    }

    /**
     * What one bundle holds, and what it had to leave out: in-use passages that did not fit, by kind (2026-11-01 fourth
     * review: 「중요한 자료가 제외됐다는 상태를 남겨야 합니다」).
     */
    public record Built(List<Item> items, int policiesLeftOut, int productLeftOut) {
    }

    /** The bundle, in order: {@code found} (the word search's current passages), {@code product}, {@code policies}. */
    public static List<Item> build(List<ScopedPassage> found, List<ScopedPassage> product, List<ScopedPassage> policies) {
        return assemble(found, product, policies).items();
    }

    /** As {@link #assemble(List, List, List)}, with no question to rank by: registration order. */
    public static Built assemble(List<ScopedPassage> found, List<ScopedPassage> product, List<ScopedPassage> policies) {
        return assemble(found, product, policies, null);
    }

    /**
     * The bundle, and what did not fit. Each list is the caller's, already limited to this org, this review's product and
     * material in use. <b>Policies have room of their own</b> — up to {@value #POLICY_ROOM_ITEMS} passages and
     * {@value #POLICY_ROOM_CHARS} characters, taken first — so a product with many passages cannot push the store's
     * rules out; what is left after the product goes to the rest of the policies. Shown in the order found, product,
     * policies.
     */
    public static Built assemble(List<ScopedPassage> found, List<ScopedPassage> product, List<ScopedPassage> policies,
                                 String question) {
        List<ScopedPassage> foundOk = eligible(found);
        // What the search found keeps its place; the rest is ranked by how much of the question it shares (2026-11-01
        // sixth review: registration order put the answer outside a full bundle). A policy document travels whole, so its
        // conditions and exceptions are never cut from the rule they limit.
        List<List<ScopedPassage>> policyPool = new ArrayList<>();
        for (ScopedPassage p : foundOk) {
            if (isPolicy(p)) {
                policyPool.add(new ArrayList<>(List.of(p)));
            }
        }
        for (List<ScopedPassage> doc : ranked(documents(eligible(policies)), question)) {
            List<ScopedPassage> rest = doc.stream().filter(p -> policyPool.stream().noneMatch(g -> contains(g, p))).toList();
            List<ScopedPassage> withFound = policyPool.stream().filter(g -> sameDocument(g.get(0), doc.get(0))).findFirst()
                    .orElse(null);
            if (withFound != null) {
                // The search found one passage of this document: the rest of it goes with it.
                rest.forEach(withFound::add);
            } else if (!rest.isEmpty()) {
                policyPool.add(new ArrayList<>(rest));
            }
        }
        List<ScopedPassage> otherPool = new ArrayList<>(foundOk.stream().filter(p -> !isPolicy(p)).toList());
        List<ScopedPassage> productPool = eligible(product).stream()
                .filter(p -> !contains(otherPool, p) && policyPool.stream().noneMatch(g -> contains(g, p))).toList();
        rankedPassages(productPool, question).forEach(otherPool::add);

        List<ScopedPassage> taken = new ArrayList<>();
        int[] chars = {0};
        // 1. The policies' own room — whole documents.
        takeDocuments(taken, chars, policyPool, POLICY_ROOM_ITEMS, POLICY_ROOM_CHARS);
        // 2. What the search found, then the product, most related first, in what is left.
        takeInto(taken, chars, otherPool, MAX_ITEMS, MAX_CHARS);
        // 3. The rest of the policies, in what is left after that.
        takeDocuments(taken, chars, policyPool, MAX_ITEMS, MAX_CHARS);

        List<ScopedPassage> ordered = new ArrayList<>();
        foundOk.stream().filter(p -> contains(taken, p)).forEach(ordered::add);
        otherPool.stream().filter(p -> contains(taken, p) && !contains(ordered, p)).forEach(ordered::add);
        policyPool.stream().flatMap(List::stream).filter(p -> contains(taken, p) && !contains(ordered, p))
                .forEach(ordered::add);
        List<Item> out = new ArrayList<>();
        for (ScopedPassage p : ordered) {
            out.add(new Item("근거 " + (out.size() + 1), p));
        }
        int policiesLeft = (int) policyPool.stream().flatMap(List::stream).filter(p -> !contains(taken, p)).count();
        int productLeft = (int) otherPool.stream().filter(p -> !contains(taken, p)).count();
        return new Built(List.copyOf(out), policiesLeft, productLeft);
    }

    static final int POLICY_ROOM_ITEMS = 4;
    static final int POLICY_ROOM_CHARS = 2000;

    /**
     * Move from {@code pool} into {@code taken}: at most {@code most} passages and {@code room} characters this step, and
     * never past the bundle's own caps — whole passages only, skipping one that does not fit.
     */
    private static void takeInto(List<ScopedPassage> taken, int[] chars, List<ScopedPassage> pool, int most, int room) {
        int added = 0;
        int used = 0;
        for (ScopedPassage p : pool) {
            if (taken.size() >= MAX_ITEMS || added >= most) {
                break;
            }
            int length = p.text().length();
            if (contains(taken, p) || chars[0] + length > MAX_CHARS || used + length > room) {
                continue;
            }
            taken.add(p);
            added++;
            used += length;
            chars[0] += length;
        }
    }

    /** As {@link #takeInto}, a document at a time: all its passages, or none of them. */
    private static void takeDocuments(List<ScopedPassage> taken, int[] chars, List<List<ScopedPassage>> docs, int most,
                                      int room) {
        int added = 0;
        int used = 0;
        for (List<ScopedPassage> doc : docs) {
            List<ScopedPassage> fresh = doc.stream().filter(p -> !contains(taken, p)).toList();
            if (fresh.isEmpty()) {
                continue;
            }
            int length = fresh.stream().mapToInt(p -> p.text().length()).sum();
            if (taken.size() + fresh.size() > MAX_ITEMS || added + fresh.size() > most || chars[0] + length > MAX_CHARS
                    || used + length > room) {
                continue;
            }
            taken.addAll(fresh);
            added += fresh.size();
            used += length;
            chars[0] += length;
        }
    }

    /** Passages grouped by the document they are cut from, in the order the documents first appear. */
    static List<List<ScopedPassage>> documents(List<ScopedPassage> passages) {
        List<List<ScopedPassage>> out = new ArrayList<>();
        for (ScopedPassage p : passages) {
            List<ScopedPassage> doc = out.stream().filter(d -> sameDocument(d.get(0), p)).findFirst().orElse(null);
            if (doc == null) {
                out.add(new ArrayList<>(List.of(p)));
            } else {
                doc.add(p);
            }
        }
        return out;
    }

    private static boolean sameDocument(ScopedPassage a, ScopedPassage b) {
        return a.sourceId() != null && a.sourceId().equals(b.sourceId());
    }

    /** Documents most related to {@code question} first — by their best passage — ties in the order given. */
    static List<List<ScopedPassage>> ranked(List<List<ScopedPassage>> docs, String question) {
        if (question == null || question.isBlank()) {
            return docs;
        }
        Set<String> asked = bigrams(question);
        List<List<ScopedPassage>> out = new ArrayList<>(docs);
        out.sort(java.util.Comparator.comparingDouble(
                (List<ScopedPassage> d) -> -d.stream().mapToDouble(p -> related(asked, p)).max().orElse(0)));
        return out;
    }

    /** Passages most related to {@code question} first, ties in the order given. */
    static List<ScopedPassage> rankedPassages(List<ScopedPassage> passages, String question) {
        if (question == null || question.isBlank()) {
            return passages;
        }
        Set<String> asked = bigrams(question);
        List<ScopedPassage> out = new ArrayList<>(passages);
        out.sort(java.util.Comparator.comparingDouble((ScopedPassage p) -> -related(asked, p)));
        return out;
    }

    /**
     * How much of the question a passage shares: its title and text's two-character pieces that the question also has,
     * over the square root of how many it has — a long passage does not win by length. No model, no index; a word the
     * customer says differently is missed, which is why this only orders the bundle and never drops what fits.
     */
    static double related(Set<String> asked, ScopedPassage p) {
        Set<String> own = bigrams((p.heading() == null ? "" : p.heading()) + " " + p.text());
        if (own.isEmpty()) {
            return 0;
        }
        long shared = own.stream().filter(asked::contains).count();
        return shared / Math.sqrt(own.size());
    }

    static Set<String> bigrams(String text) {
        String t = text == null ? "" : text.replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]", "")
                .toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    /** Product and policy passages with text and no 【확인: …】 blank, each once — by passage id and by its words. */
    private static List<ScopedPassage> eligible(List<ScopedPassage> in) {
        List<ScopedPassage> out = new ArrayList<>();
        for (ScopedPassage p : in == null ? List.<ScopedPassage>of() : in) {
            if (p == null || p.text() == null || p.text().isBlank()
                    || p.scope() != com.sellerops.knowledge.KnowledgeScope.PRODUCT && !isPolicy(p)
                    || com.sellerops.knowledge.KnowledgeBlanks.carries(p.text()) || contains(out, p)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static boolean isPolicy(ScopedPassage p) {
        return p.scope() == com.sellerops.knowledge.KnowledgeScope.ORG_OPERATIONS;
    }

    private static boolean contains(List<ScopedPassage> list, ScopedPassage p) {
        String words = squash(p.text());
        return list.stream().anyMatch(q -> p.chunkId() != null && p.chunkId().equals(q.chunkId())
                || words.equals(squash(q.text())));
    }

    /**
     * The bundle's passages the writer cited, each once and in bundle order — kept only when the name is in the bundle
     * and the quote, whitespace, punctuation and case aside, is a piece of that passage at least {@value #MIN_QUOTE} characters
     * long.
     */
    public static Chosen chosen(List<Item> bundle, List<Used> used) {
        Set<String> kept = new LinkedHashSet<>();
        int rejected = 0;
        for (Used u : used == null ? List.<Used>of() : used) {
            Item item = u == null || u.id() == null ? null : bundle.stream()
                    .filter(i -> i.id().equals(u.id().strip())).findFirst().orElse(null);
            // Words, not punctuation: a quote that adds the reply's comma (「있어, 실리콘」) is still that sentence (2026-11-01
            // sixth round: both of a correct draft's citations were refused for it). The words themselves must all be there.
            String quote = u == null ? "" : words(u.quote());
            if (item == null || quote.length() < MIN_QUOTE || !words(item.passage().text()).contains(quote)) {
                rejected++;
                continue;
            }
            kept.add(item.id());
        }
        List<Item> items = bundle.stream().filter(i -> kept.contains(i.id())).toList();
        return new Chosen(items, rejected);
    }

    /** The items named by {@code ids} (as a run keeps them), in bundle order. Names no longer in the bundle are gone. */
    public static List<Item> named(List<Item> bundle, List<String> ids) {
        Set<String> wanted = Set.copyOf(ids == null ? List.of() : ids);
        return bundle.stream().filter(i -> wanted.contains(i.id())).toList();
    }

    /**
     * What a quote and its passage are compared by: letters and digits, lower case — and every mark that sits next to a
     * digit, because there it is part of the number (seventh review: 「1.5mm」 quoted as 「15mm」 passed when the point was
     * dropped; so would 「1~2일」 as 「12일」, 「10%」 as 「10」, 「-3」 as 「3」). A comma or a space between words is not.
     */
    static String words(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            } else if (!Character.isWhitespace(c) && (digitAt(s, i - 1) || digitAt(s, i + 1))) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean digitAt(String s, int i) {
        return i >= 0 && i < s.length() && Character.isDigit(s.charAt(i));
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
