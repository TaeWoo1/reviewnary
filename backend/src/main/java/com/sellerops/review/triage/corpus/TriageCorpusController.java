package com.sellerops.review.triage.corpus;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.review.triage.feedback.CorrectionDispositionKind;
import com.sellerops.review.triage.feedback.TriageFeedbackService;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The offline corpus, operated by hand. <b>Off by default, and not a seller surface.</b>
 *
 * <p><b>Why a flag and not a screen.</b> {@code CorrectionDispositionKind} already settled who may disposition:
 * «Assigned by a person holding the rubric. That is a cost, and it is the cost of the guarantee.» A seller
 * reading their own correction as {@code CLASSIFIER_ERROR} is precisely the drift the disposition exists to
 * prevent — one org's taste becoming the global classifier's definition of accuracy. There is no role column in
 * this product ({@code User} makes every account {@code OWNER}), so the honest way to keep this off every
 * seller's product is a property that is false everywhere except where the rubric holder operates:
 * {@code sellerops.triage-corpus.enabled}. Same instrument the AI triage pilot, the ESM import and the inquiry
 * execution lane use for the same reason.
 *
 * <p><b>Nothing here is automatic.</b> Every route is a POST or GET somebody typed. There is no scheduler, no
 * startup runner and no hook on ingestion: a corpus that grew by itself would be the «silently growing» set §3
 * forbids, and an export that happened without a press would be the automatic-export the safety fences forbid.
 *
 * <p><b>Nothing here can change what the product shows.</b> No tier is written, no review is touched, no
 * classifier is called. The strongest thing any of these routes does is stamp a version string onto rows that
 * already existed.
 */
@RestController
@RequestMapping("/api/triage-corpus")
@ConditionalOnProperty(name = "sellerops.triage-corpus.enabled", havingValue = "true")
public class TriageCorpusController {

    private final TriageCorpusService corpus;
    private final TriageFeedbackService feedback;

    public TriageCorpusController(TriageCorpusService corpus, TriageFeedbackService feedback) {
        this.corpus = corpus;
        this.feedback = feedback;
    }

    /** What is loose, what a cut would take, and what it would leave. */
    @GetMapping("/pending")
    public TriageCorpusService.Pending pending(@AuthenticationPrincipal AuthPrincipal principal) {
        return corpus.pending(principal.orgId());
    }

    /** The cuts this org has taken, newest first. */
    @GetMapping("/snapshots")
    public List<SnapshotView> snapshots(@AuthenticationPrincipal AuthPrincipal principal) {
        return corpus.snapshots(principal.orgId()).stream().map(SnapshotView::of).toList();
    }

    /**
     * A human's reading of one correction.
     *
     * <p>{@code kind} is required and there is no default. {@code TriageFeedbackService.disposition} has «no
     * inferring overload and there must not be one»; a default here would be that overload, reached through a
     * request body instead of a method signature.
     */
    @PostMapping("/corrections/{correctionId}/disposition")
    public DispositionView disposition(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID correctionId,
                                       @RequestBody DispositionRequest request) {
        var row = feedback.disposition(principal.orgId(), correctionId, request.kind(), principal.userId());
        return new DispositionView(row.getCorrectionId(), row.getDisposition().name(), row.getDecidedAt());
    }

    /** Cut and freeze. The kind is in the path because the two cuts are different acts, not a parameter. */
    @PostMapping("/snapshots/{kind}")
    public TriageCorpusService.Cut cut(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable SnapshotKind kind,
                                       @RequestBody CutRequest request) {
        return corpus.cut(principal.orgId(), kind, request.version(), request.note(), principal.userId());
    }

    /**
     * One frozen correction set as a document.
     *
     * <p>A GET returning JSON, not a file download: the caller is a harness or a person running one, and an
     * attachment header would make this look like the product offering an export. The rows carry a one-way
     * fingerprint and closed vocabulary and nothing else — {@link TriageCorpusRow} is the boundary.
     */
    @GetMapping("/snapshots/{version}/corpus")
    public TriageCorpusDocument corpus(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable String version) {
        return corpus.corpus(principal.orgId(), version);
    }

    public record DispositionRequest(CorrectionDispositionKind kind) {
    }

    public record DispositionView(UUID correctionId, String disposition, Instant decidedAt) {
    }

    public record CutRequest(@Size(max = 40) String version, @Size(max = 2000) String note) {
    }

    /** A cut, without the ids of whoever took it. */
    public record SnapshotView(String kind, String kindLabelKo, String version, int rowCount,
                               String classifierVersion, String note, Instant cutAt) {

        static SnapshotView of(TriageFeedbackSnapshot row) {
            return new SnapshotView(row.getKind().name(), row.getKind().labelKo(), row.getVersion(),
                    row.getRowCount(), row.getClassifierVersion(), row.getNote(), row.getCutAt());
        }
    }
}
