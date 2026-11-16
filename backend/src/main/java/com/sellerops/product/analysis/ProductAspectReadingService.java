package com.sellerops.product.analysis;

import com.sellerops.agent.llm.reviewaspect.ReviewAspectGenerator;
import com.sellerops.agent.llm.reviewaspect.ReviewAspectReader;
import com.sellerops.common.ApiException;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 「AI로 읽기」 — the seller's press that reads one product's reviews of the period with the review-aspect model and,
 * once all of them are read, has its opportunities and threats written from the evidence.
 *
 * <p><b>Bounded per press.</b> At most {@code max-calls-per-request} model calls: reviews already read under the current
 * reader version are skipped (the same words are read once per shop), and what is left over is said, so a large
 * product is read over a few presses rather than one long, unbounded one. Every call passes the door's quota and
 * evaluation-budget gate; a refusal stops the press and is reported, never retried here.
 *
 * <p>Not one transaction: model calls take seconds, and each stored reading stands on its own.
 */
@Service
public class ProductAspectReadingService {

    /** The most reviews one evaluation press may name. */
    static final int MAX_NAMED_REVIEWS = 400;

    private final ReviewAspectReader reader;
    private final ReviewAspectReadingRepository readings;
    private final ProductOutlookWritingRepository outlooks;
    private final ReviewAspectOverlay overlay;
    private final ProductAnalysisService analysis;
    private final ReviewRepository reviews;

    public ProductAspectReadingService(ReviewAspectReader reader, ReviewAspectReadingRepository readings,
                                       ProductOutlookWritingRepository outlooks, ReviewAspectOverlay overlay,
                                       ProductAnalysisService analysis, ReviewRepository reviews) {
        this.reader = reader;
        this.readings = readings;
        this.outlooks = outlooks;
        this.overlay = overlay;
        this.analysis = analysis;
        this.reviews = reviews;
    }

    /**
     * What one press did. {@code remaining} distinct review texts are still unread; {@code stopped} names why the press
     * ended early (QUOTA — the daily ceiling or evaluation budget; CALLS — this press's call bound), or is null.
     */
    public record ReadSummary(int calls, int textsRead, int remaining, int failedBatches, String stopped,
                              boolean outlookWritten, ProductAnalysisView analysis) {
    }

    public boolean isEnabledFor(UUID orgId) {
        return reader.isEnabledFor(orgId);
    }

    public ReadSummary readProduct(UUID orgId, UUID productId, int days, LocalDate referenceDate) {
        requireEnabled(orgId);
        analysis.analyse(orgId, productId, days, referenceDate); // the product is this org's, or this throws
        List<Review> window = analysis.reviewsInWindow(orgId, productId, days, referenceDate);
        Pass pass = readTexts(orgId, window);
        boolean written = false;
        String stopped = pass.stopped;
        ProductAnalysisView view = analysis.analyse(orgId, productId, days, referenceDate);
        if (pass.remaining == 0 && stopped == null && view.scope().enoughReviews()) {
            List<ReviewAspectGenerator.EvidenceLine> lines = ReviewAspectOverlay.evidence(view.swot(), view.aspects(),
                    view.inquiryTopics());
            if (!lines.isEmpty()) {
                String fingerprint = overlay.fingerprint(lines);
                String version = overlay.outlookVersion();
                if (outlooks.findFirstByOrgIdAndProductIdAndInputFingerprintAndWriterVersion(orgId, productId,
                        fingerprint, version).isEmpty()) {
                    Optional<ReviewAspectGenerator.OutlookResult> result = reader.writeOutlook(orgId, lines);
                    pass.calls += result.isPresent() ? 1 : 0;
                    if (result.isEmpty()) {
                        stopped = "QUOTA";
                    } else if (result.get().ok()) {
                        ProductOutlookWriting w = new ProductOutlookWriting();
                        w.setOrgId(orgId);
                        w.setProductId(productId);
                        w.setInputFingerprint(fingerprint);
                        w.setWriterVersion(version);
                        w.setCells(ReviewAspectOverlay.outlookJson(result.get()));
                        try {
                            outlooks.save(w);
                            written = true;
                        } catch (DataIntegrityViolationException raced) {
                            written = true; // another press stored the same writing
                        }
                        view = analysis.analyse(orgId, productId, days, referenceDate);
                    } else {
                        pass.failed++;
                    }
                }
            }
        }
        return new ReadSummary(pass.calls, pass.read, pass.remaining, pass.failed, stopped, written, view);
    }

    /** One review's reading, as the evaluation compares it with the answer key. */
    public record ReviewReading(UUID reviewId, boolean read, List<ReviewOpinionReader.Opinion> opinions) {
    }

    public record NamedReadSummary(int calls, int textsRead, int remaining, int failedBatches, String stopped,
                                   String readerVersion, List<ReviewReading> reviews) {
    }

    /** Read named reviews of this org (the accuracy evaluation's press) and return each one's reading. */
    public NamedReadSummary readReviews(UUID orgId, Collection<UUID> reviewIds) {
        requireEnabled(orgId);
        if (reviewIds == null || reviewIds.isEmpty() || reviewIds.size() > MAX_NAMED_REVIEWS) {
            throw ApiException.badRequest("리뷰는 1개에서 " + MAX_NAMED_REVIEWS + "개까지 고를 수 있어요.");
        }
        List<Review> named = reviews.findAllById(reviewIds).stream()
                .filter(r -> orgId.equals(r.getOrgId())).toList();
        Pass pass = readTexts(orgId, named);
        Map<UUID, List<ReviewOpinionReader.Opinion>> got = overlay.readingsFor(orgId, named);
        List<ReviewReading> out = named.stream()
                .map(r -> new ReviewReading(r.getId(), got.containsKey(r.getId()), got.getOrDefault(r.getId(), List.of())))
                .toList();
        return new NamedReadSummary(pass.calls, pass.read, pass.remaining, pass.failed, pass.stopped,
                overlay.readerVersion(), out);
    }

    private static final class Pass {
        int calls;
        int read;
        int remaining;
        int failed;
        String stopped;
    }

    /** Read the distinct unread texts of these reviews, batch by batch, within this press's call bound. */
    private Pass readTexts(UUID orgId, List<Review> window) {
        String version = overlay.readerVersion();
        Map<String, Review> unread = new LinkedHashMap<>();
        for (Review r : window) {
            if (r.getBody() != null && !r.getBody().isBlank()) {
                unread.putIfAbsent(ReviewAspectOverlay.sha(r.getBody()), r);
            }
        }
        Set<String> already = new HashSet<>();
        List<String> shas = new ArrayList<>(unread.keySet());
        for (int i = 0; i < shas.size(); i += 500) {
            readings.findAllByOrgIdAndReaderVersionAndBodySha256In(orgId, version,
                    shas.subList(i, Math.min(shas.size(), i + 500))).forEach(x -> already.add(x.getBodySha256()));
        }
        already.forEach(unread::remove);

        Pass pass = new Pass();
        List<Map.Entry<String, Review>> todo = new ArrayList<>(unread.entrySet());
        int size = reader.batchSize();
        int at = 0;
        while (at < todo.size()) {
            if (pass.calls >= reader.maxCallsPerRequest()) {
                pass.stopped = "CALLS";
                break;
            }
            List<Map.Entry<String, Review>> batch = todo.subList(at, Math.min(todo.size(), at + size));
            List<ReviewAspectGenerator.Item> items = batch.stream()
                    .map(e -> new ReviewAspectGenerator.Item(e.getValue().getRating(), e.getValue().getBody())).toList();
            Optional<ReviewAspectGenerator.ReadResult> result = reader.read(orgId, items);
            if (result.isEmpty()) {
                pass.stopped = "QUOTA";
                break;
            }
            pass.calls++;
            at += batch.size();
            if (!result.get().ok()) {
                pass.failed++;
                continue;
            }
            List<Optional<List<ReviewAspectGenerator.Opinion>>> got = result.get().readings();
            for (int i = 0; i < batch.size(); i++) {
                if (got.get(i).isEmpty()) {
                    continue;
                }
                ReviewAspectReading row = new ReviewAspectReading();
                row.setOrgId(orgId);
                row.setBodySha256(batch.get(i).getKey());
                row.setReaderVersion(version);
                row.setOpinions(ReviewAspectOverlay.toJson(got.get(i).get()));
                try {
                    readings.save(row);
                    pass.read++;
                } catch (DataIntegrityViolationException raced) {
                    // another press read the same words first
                }
            }
        }
        Set<String> stored = new HashSet<>();
        for (int i = 0; i < shas.size(); i += 500) {
            readings.findAllByOrgIdAndReaderVersionAndBodySha256In(orgId, version,
                    shas.subList(i, Math.min(shas.size(), i + 500))).forEach(x -> stored.add(x.getBodySha256()));
        }
        pass.remaining = (int) shas.stream().filter(s -> !stored.contains(s)).count();
        return pass;
    }

    private void requireEnabled(UUID orgId) {
        if (!reader.isEnabledFor(orgId)) {
            throw ApiException.conflict("REVIEW_ASPECT_OFF", "이 가게에는 AI 리뷰 읽기가 켜져 있지 않아요.");
        }
    }
}
