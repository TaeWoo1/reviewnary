package com.sellerops.product.detail.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Reading a fact off a picture and being allowed to state it are two different things.</b>
 *
 * <p>This is where the second becomes true, deterministically, after every picture in the product
 * has been read — never per image. The reason it cannot be per image is rule C: two pictures on the
 * same 상세페이지 can print different values for the same 규격 (an old table left below a new one is
 * the ordinary case), and an authority decided one image at a time would confidently publish
 * whichever it saw first.
 *
 * <p>The four rules, in order:
 * <ol>
 *   <li><b>A — no 규격 name: REFUSED.</b> A value with nothing governing it is the shape of the
 *       original defect — a strand count floating free, true of one option and stated of all.</li>
 *   <li><b>B — no exact stored variant: UNRESOLVED.</b> Kept, counted, never quoted. The picture may
 *       well be right; SellerOps just cannot say which 규격 it is right about. Since 2026-11-19 a label equal to
 *       one axis value ({@code 2호} of {@code 색상: 그레이 / 사이즈: 2호}) names every option with that value
 *       ({@link ImageFactAssociation#matchGroup}); the facts that still name none are product-wide statements the
 *       seller confirms once.</li>
 *   <li><b>C — same 규격 + same attribute, different values: ALL of them refused.</b> Not "pick the
 *       most common", not "pick the newest": we do not know which table is current, and a majority
 *       vote among printed tables is a guess wearing arithmetic.</li>
 *   <li><b>D — exact variant + no contradiction: accepted.</b> Only these become evidence.</li>
 * </ol>
 */
public final class ImageFactAuthority {

    private ImageFactAuthority() {
    }

    /** Why a triple did or did not become evidence. Closed, so a report needs no prose. */
    public enum Verdict {
        /** Rule D — exact variant, no contradiction. The only value that may be quoted. */
        ACCEPTED,
        /** Rule A — the picture stated a value with no 규격 governing it. */
        REFUSED_NO_SPEC_LABEL,
        /** Rule B — no stored variant is named by this label, exactly. */
        UNRESOLVED_NO_EXACT_VARIANT,
        /** Rule C — another picture states a different value for the same 규격 and attribute. */
        REFUSED_CONFLICTING_VALUE
    }

    /**
     * One judged triple, with the picture it came from kept attached.
     *
     * <p>Provenance travels per fact rather than per product because the alternative — merging 26
     * images' output into one blob — throws away the only thing that lets a person check a claim
     * against the page it was read from.
     */
    public record Judged(String imageSha256, ExtractedImageFacts.Fact fact, Verdict verdict,
                         String matchedOptionName) {

        public boolean accepted() {
            return verdict == Verdict.ACCEPTED;
        }
    }

    /** The whole product's judgement, in the order the pictures were read. */
    public record Finalized(List<Judged> judged) {

        public List<Judged> accepted() {
            return judged.stream().filter(Judged::accepted).toList();
        }

        public long countOf(Verdict verdict) {
            return judged.stream().filter(j -> j.verdict() == verdict).count();
        }
    }

    /** One picture's readings, as they go into finalization. */
    public record ImageFacts(String imageSha256, ExtractedImageFacts facts) {
    }

    /**
     * Judge every triple of every picture against the product's STORED variants.
     *
     * @param images      each picture's transcribed triples, in read order
     * @param optionNames the stored option names for this product — the catalogue's own words, never
     *                    anything derived from the pictures
     */
    public static Finalized decide(List<ImageFacts> images, List<String> optionNames) {
        List<Judged> staged = new ArrayList<>();
        // Rule C is decided across the whole product, so the conflict map is built before any triple
        // is accepted. Key: each stored option the triple applies to, plus the attribute, both normalized — per
        // OPTION, not per label, so a value printed for 「2호」 and another printed for 「그레이 / 2호」 still meet.
        Map<String, Set<String>> valuesPerSpecAttribute = new LinkedHashMap<>();
        Map<Judged, List<String>> appliesTo = new java.util.IdentityHashMap<>();

        for (ImageFacts image : images) {
            for (ExtractedImageFacts.Fact fact : image.facts().facts()) {
                if (fact.specLabel() == null || fact.specLabel().isBlank()) {
                    staged.add(new Judged(image.imageSha256(), fact, Verdict.REFUSED_NO_SPEC_LABEL, null));
                    continue;
                }
                Optional<ImageFactAssociation.Group> matched =
                        ImageFactAssociation.matchGroup(fact.specLabel(), optionNames);
                if (matched.isEmpty()) {
                    staged.add(new Judged(image.imageSha256(), fact,
                            Verdict.UNRESOLVED_NO_EXACT_VARIANT, null));
                    continue;
                }
                Judged accepted = new Judged(image.imageSha256(), fact, Verdict.ACCEPTED, matched.get().key());
                staged.add(accepted);
                appliesTo.put(accepted, matched.get().options());
                for (String option : matched.get().options()) {
                    valuesPerSpecAttribute
                            .computeIfAbsent(key(option, fact.attribute()), k -> new LinkedHashSet<>())
                            .add(ImageFactAssociation.normalize(fact.value()));
                }
            }
        }

        List<Judged> out = new ArrayList<>(staged.size());
        for (Judged judged : staged) {
            if (judged.verdict() != Verdict.ACCEPTED) {
                out.add(judged);
                continue;
            }
            boolean contradicted = appliesTo.getOrDefault(judged, List.of()).stream()
                    .map(option -> valuesPerSpecAttribute.get(key(option, judged.fact().attribute())))
                    .anyMatch(values -> values != null && values.size() > 1);
            out.add(contradicted
                    // Both sides of the contradiction are refused. Keeping one would mean choosing,
                    // and there is nothing here to choose with.
                    ? new Judged(judged.imageSha256(), judged.fact(), Verdict.REFUSED_CONFLICTING_VALUE,
                            judged.matchedOptionName())
                    : judged);
        }
        return new Finalized(List.copyOf(out));
    }

    private static String key(String optionName, String attribute) {
        return ImageFactAssociation.normalize(optionName) + " "
                + ImageFactAssociation.normalize(attribute);
    }
}
