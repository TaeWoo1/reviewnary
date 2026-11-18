package com.sellerops.product.detail.image;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which stored 규격 an image's {@code specLabel} refers to — <b>by exact match or not at all</b>.
 *
 * <p><b>This is the class the whole lane exists to keep small.</b> The 2026-08-26 defect was a
 * confident answer built on an association nobody made: a FAQ written per product answered a
 * question whose answer varies per 규격. Letting a model — or a similarity score — decide that
 * "2" on a picture means the variant named "2호" would rebuild that defect with more machinery.
 *
 * <p>So the only permitted match is <b>exact, whole-token, after normalization</b>. Forbidden, and
 * absent by construction rather than by policy: substring containment, edit distance, embeddings,
 * numeric reasoning, and asking the model. There is no scoring function here to tune.
 *
 * <p><b>Normalization is narrow on purpose.</b> Unicode NFKC, case folding, and whitespace collapse
 * — transformations that change how the same characters are ENCODED, never which characters they
 * are. It does not strip 호/mm/개, because dropping a unit is precisely how "2" starts matching
 * "2호".
 *
 * <p><b>One axis value names every option that carries it</b> (product-owner, 2026-11-19 — the decision
 * {@code image_product_knowledge_v1.md} §11-4 raised). NAVER writes an option as its axes, {@code 색상: 그레이 / 사이즈: 2호}.
 * A 규격 table printed per 사이즈 says 「2호」, and that is a statement about every colour of 2호 — the table does not
 * vary by colour, it never mentions one. Reading the axes is parsing the channel's own documented format, not a
 * similarity: the label must equal one axis value exactly, after the same normalization, and every option it names
 * carries that exact value. A label that equals values on two DIFFERENT axes still names nothing. Still forbidden: a
 * unit dropped ({@code 2호(10개)} is not {@code 2호}), a translation ({@code WOOD} is not {@code 우드}), a fragment.
 */
public final class ImageFactAssociation {

    private ImageFactAssociation() {
    }

    /**
     * The stored variant whose option name this label names exactly, or empty.
     *
     * <p>An option name is split on the axis separator the catalogue uses, so a two-axis variant
     * ("16x10mm / 화이트") is matchable by either axis's token — that is still an exact whole-token
     * match, not a substring one: "16x10" would NOT match "16x10mm".
     *
     * <p>Ambiguity refuses. A label matching two different stored variants names neither of them.
     */
    public static Optional<String> match(String specLabel, List<String> optionNames) {
        return matchGroup(specLabel, optionNames).map(Group::key);
    }

    /**
     * What a label names: one stored option, or the stored options that share one exact axis value.
     *
     * @param key     the name a statement about them is written under — the option name for one option, the axis as
     *                the catalogue writes it ({@code 사이즈: 2호}) for several
     * @param options every stored option name the statement applies to, in catalogue order
     */
    public record Group(String key, List<String> options) {
    }

    public static Optional<Group> matchGroup(String specLabel, List<String> optionNames) {
        String normalized = normalize(specLabel);
        if (normalized.isEmpty() || optionNames == null || optionNames.isEmpty()) {
            return Optional.empty();
        }
        Set<String> hits = new LinkedHashSet<>();
        for (String option : optionNames) {
            if (option == null || option.isBlank()) {
                continue;
            }
            if (tokens(option).contains(normalized)) {
                hits.add(option);
            }
        }
        if (hits.size() == 1) {
            String only = hits.iterator().next();
            return Optional.of(new Group(only, List.of(only)));
        }
        // By axis: which axis this label is a value of, and every option carrying that exact value.
        Map<String, Set<String>> optionsByAxis = new LinkedHashMap<>();
        Map<String, String> keyByAxis = new LinkedHashMap<>();
        for (String option : optionNames) {
            if (option == null || option.isBlank()) {
                continue;
            }
            String[] parts = option.split("[/|]");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].strip();
                int colon = part.indexOf(':');
                String axisName = colon > 0 ? normalize(part.substring(0, colon)) : "";
                String value = colon > 0 ? part.substring(colon + 1) : part;
                if (!normalize(value).equals(normalized) && !normalize(part).equals(normalized)) {
                    continue;
                }
                // A named axis is the same axis on every option; an unnamed one is identified by its position.
                String axis = axisName.isEmpty() ? "#" + i : "n:" + axisName;
                optionsByAxis.computeIfAbsent(axis, k -> new LinkedHashSet<>()).add(option);
                keyByAxis.putIfAbsent(axis, part);
            }
        }
        if (optionsByAxis.size() != 1) {
            return Optional.empty();
        }
        String axis = optionsByAxis.keySet().iterator().next();
        List<String> named = List.copyOf(optionsByAxis.get(axis));
        return Optional.of(named.size() == 1 ? new Group(named.get(0), named)
                : new Group(keyByAxis.get(axis), named));
    }

    /** The whole tokens of one stored option name: the full name and each axis of it. */
    static Set<String> tokens(String optionName) {
        Set<String> out = new LinkedHashSet<>();
        String whole = normalize(optionName);
        if (!whole.isEmpty()) {
            out.add(whole);
        }
        for (String part : optionName.split("[/|]")) {
            String token = normalize(part);
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * NFKC, lower case, collapsed whitespace. Nothing is removed.
     *
     * <p>NFKC exists here for one concrete reason: Korean shops write 규격 with full-width digits and
     * compatibility characters, and the same 규격 typed on two keyboards must be one 규격. That is an
     * encoding difference. A unit is not.
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }
}
