package com.sellerops.product.analysis;

import com.sellerops.reviewissue.IssueSignatureExtractor;
import com.sellerops.reviewissue.IssueSignatureExtractor.ExtractedUnit;
import com.sellerops.reviewissue.NegationScope;
import com.sellerops.reviewissue.OpinionUnitSplitter;
import com.sellerops.reviewissue.UnknownReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads one review body into aspect-level <b>complaints</b> and <b>praise</b>, unit by unit.
 *
 * <p>Complaints are exactly what the issue memory's extractor says — the same bean, the same units — so
 * a 「포장 · 파손」 count here and on the 반복 문제 screen are the same reading of the same sentence. Praise
 * is {@link PraiseVocabulary}, and only on a unit the extractor found no affirmed problem in; a unit is
 * never both. The rating is not read.
 */
@Component
public class ReviewOpinionReader {

    private final IssueSignatureExtractor extractor;

    public ReviewOpinionReader(IssueSignatureExtractor extractor) {
        this.extractor = extractor;
    }

    public String extractorProvenance() {
        return extractor.kind() + ":" + extractor.version();
    }

    /** One unit's verdict. {@code problem} is null for praise; {@code aspect} is null for unattributed praise. */
    public record Opinion(int ordinal, String aspect, String problem, boolean praise, String text) {
    }

    public List<Opinion> read(String body) {
        List<String> units = OpinionUnitSplitter.split(body);
        List<ExtractedUnit> extracted = extractor.extract(body);
        List<Opinion> out = new ArrayList<>();
        for (int i = 0; i < units.size() && i < extracted.size(); i++) {
            String unit = units.get(i);
            ExtractedUnit verdict = extracted.get(i);
            if (verdict.isMatched()) {
                out.add(new Opinion(i, verdict.signature().aspect(), verdict.signature().problem(), false, unit));
                continue;
            }
            UnknownReason reason = verdict.unknownReason();
            // An affirmed problem without an aspect, or someone else's product: neither is praise.
            if (reason == UnknownReason.NO_ASPECT || reason == UnknownReason.OTHER_PRODUCT
                    || NegationScope.aboutAnotherProduct(unit)) {
                continue;
            }
            if (PraiseVocabulary.praiseOf(unit).isEmpty()) {
                continue;
            }
            Optional<String> aspect = PraiseVocabulary.praisedAspect(unit);
            out.add(new Opinion(i, aspect.orElse(null), null, true, unit));
        }
        return List.copyOf(out);
    }
}
