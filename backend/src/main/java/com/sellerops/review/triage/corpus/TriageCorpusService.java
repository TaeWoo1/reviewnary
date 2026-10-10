package com.sellerops.review.triage.corpus;

import com.sellerops.common.ApiException;
import com.sellerops.common.ReviewIdFingerprint;
import com.sellerops.review.triage.feedback.CorrectionDisposition;
import com.sellerops.review.triage.feedback.TriageCorrection;
import com.sellerops.review.triage.feedback.TriageCorrectionRepository;
import com.sellerops.review.triage.feedback.TriageFeedbackService;
import com.sellerops.review.triage.feedback.TriagePrediction;
import com.sellerops.review.triage.feedback.TriagePredictionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The door that was missing.</b> Disposition a correction, cut a numbered set, read the set back.
 *
 * <p>{@code TriageFeedbackService.disposition}, {@code freezeSnapshot} and {@code freezeSilverSnapshot} were
 * written, documented and unit-tested when the feedback spine shipped, and <b>nothing in {@code main/} ever
 * called one of them</b>. The design in {@code docs/slices/production-triage-feedback-draft-v1.md} §3 and §7.4
 * was complete; the corpus did not exist because no operator could reach it. This class is the reach, and
 * nothing more — every write still goes through {@code TriageFeedbackService}, which stays the only stamper.
 *
 * <p><b>What it adds on top of delegation, and why each is not optional:</b>
 * <ul>
 *   <li><b>A version is used once.</b> The old signature took the version as a free string, so cutting
 *       {@code 2026-10/1} twice merged two sets under one name. §3: an evaluation set that changes under a
 *       metric makes the metric meaningless.</li>
 *   <li><b>The two kinds never share a name</b> (§7.4). Uniqueness is over (org, version), not (org, kind,
 *       version).</li>
 *   <li><b>An empty cut is refused, not recorded.</b> A manifest row naming zero rows is a named evaluation set
 *       with nothing in it, and the next person to quote the version would be quoting nothing.</li>
 *   <li><b>A withheld member is named.</b> A row whose review has no well-formed channel-side id cannot be
 *       fingerprinted, so an offline harness cannot join it. It stays IN the frozen set and OUT of the exported
 *       document, and the document says how many — a set that quietly exported fewer rows than it froze would
 *       report a metric over a population nobody chose.</li>
 * </ul>
 *
 * <p><b>Online learning is impossible from here, and that is structural.</b> This class holds no classifier, no
 * prompt, no tier writer and no repository that could update one; it returns a document to a caller.
 * {@code SellerFeedbackCorpusFenceTest} asserts those absences by name, for the same reason
 * {@code SellerOperationsPolicyFenceTest} does: four of the five properties are absences, and an absence has no
 * runtime to test.
 */
@Service
public class TriageCorpusService {

    private final TriageFeedbackService feedback;
    private final TriageFeedbackSnapshotRepository snapshots;
    private final TriageCorpusRepository rows;
    private final TriageCorrectionRepository corrections;
    private final TriagePredictionRepository predictions;
    private final Clock clock;

    @Autowired
    public TriageCorpusService(TriageFeedbackService feedback, TriageFeedbackSnapshotRepository snapshots,
                               TriageCorpusRepository rows, TriageCorrectionRepository corrections,
                               TriagePredictionRepository predictions) {
        this(feedback, snapshots, rows, corrections, predictions, Clock.systemUTC());
    }

    /** Test seam: an explicit {@link Clock}, the same shape {@code TriageFeedbackService} uses. */
    TriageCorpusService(TriageFeedbackService feedback, TriageFeedbackSnapshotRepository snapshots,
                        TriageCorpusRepository rows, TriageCorrectionRepository corrections,
                        TriagePredictionRepository predictions, Clock clock) {
        this.feedback = feedback;
        this.snapshots = snapshots;
        this.rows = rows;
        this.corrections = corrections;
        this.predictions = predictions;
        this.clock = clock;
    }

    /**
     * What a cut would take right now, per kind, plus the two counts that explain what it would NOT take.
     *
     * @param correctionRows      loose {@code CLASSIFIER_ERROR} dispositions whose correction still stands
     * @param withdrawnRows       loose {@code CLASSIFIER_ERROR} dispositions whose correction the seller took
     *                            back. Never cut, and said out loud rather than subtracted silently
     * @param sellerPreferenceRows loose {@code SELLER_PREFERENCE} rows. <b>Never stamped, ever</b> (§3) — they
     *                            are a fact about one seller's catalog, and folding them into a set that
     *                            measures the global classifier would make its accuracy a function of whichever
     *                            sellers corrected most
     */
    public record Pending(int correctionRows, int withdrawnRows, int sellerPreferenceRows,
                          int actionRows, int behaviorRows) {

        public int silverRows() {
            return actionRows + behaviorRows;
        }
    }

    /** One cut, as the operator who took it needs to see it. */
    public record Cut(UUID snapshotId, String kind, String version, int rowCount, Instant cutAt) {
    }

    @Transactional(readOnly = true)
    public Pending pending(UUID orgId) {
        List<CorrectionDisposition> cuttable = feedback.cuttable(orgId);
        int looseErrors = feedback.looseErrorCount(orgId);
        int[] silver = feedback.looseSilverCounts(orgId);
        return new Pending(cuttable.size(), looseErrors - cuttable.size(),
                feedback.loosePreferenceCount(orgId), silver[0], silver[1]);
    }

    @Transactional(readOnly = true)
    public List<TriageFeedbackSnapshot> snapshots(UUID orgId) {
        return snapshots.findByOrgIdOrderByCutAtDesc(orgId);
    }

    /**
     * Cut and freeze one set.
     *
     * <p>The version is checked against both kinds before anything is stamped, so a refused cut leaves the loose
     * rows exactly as loose as they were. The manifest is written in the same transaction as the stamping: a
     * manifest without its rows would name a set that does not exist, and rows without a manifest are the state
     * this package was built to end.
     */
    @Transactional
    public Cut cut(UUID orgId, SnapshotKind kind, String version, String note, UUID cutBy) {
        String label = version == null ? "" : version.strip();
        if (label.isEmpty()) {
            throw ApiException.badRequest("스냅샷 버전을 적어 주세요.");
        }
        if (label.length() > 40) {
            throw ApiException.badRequest("스냅샷 버전은 40자까지 쓸 수 있습니다.");
        }
        snapshots.findByOrgIdAndVersion(orgId, label).ifPresent(existing -> {
            // Both kinds, deliberately: §7.4. The message names which kind already holds it, because the next
            // thing the cutter needs to decide is whether they meant to add to it (they cannot) or misnamed this.
            throw ApiException.conflict(
                    "「" + label + "」은 이미 고정된 " + existing.getKind().labelKo() + "의 버전입니다.");
        });

        String classifierVersion = null;
        String promptHash = null;
        int taken;
        if (kind == SnapshotKind.CORRECTION) {
            // One classifier, or none named. A set spanning two versions has no single one to measure a
            // successor against, and naming either would describe half the rows; a set gathered on tiers the
            // RULE produced has no classifier at all, which is the pilot's normal case rather than a gap.
            List<TriagePrediction> behind = predictionsBehind(feedback.cuttable(orgId));
            List<String> versions = behind.stream().map(TriagePrediction::getClassifierVersion)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            if (versions.size() == 1) {
                classifierVersion = versions.get(0);
                List<String> hashes = behind.stream().map(TriagePrediction::getPromptHash)
                        .filter(java.util.Objects::nonNull).distinct().toList();
                promptHash = hashes.size() == 1 ? hashes.get(0) : null;
            }
            taken = feedback.freezeSnapshot(orgId, label);
        } else {
            taken = feedback.freezeSilverSnapshot(orgId, label);
        }
        if (taken == 0) {
            // Not an error state of the system — an honest "nothing to cut". Refused rather than recorded so no
            // version string is burned on an empty set.
            throw ApiException.conflict("고정할 항목이 없습니다.");
        }

        TriageFeedbackSnapshot row = new TriageFeedbackSnapshot();
        row.setOrgId(orgId);
        row.setKind(kind);
        row.setVersion(label);
        row.setRowCount(taken);
        row.setClassifierVersion(classifierVersion);
        row.setPromptHash(promptHash);
        row.setCutBy(cutBy);
        row.setCutAt(Instant.now(clock));
        row.setNote(note == null || note.isBlank() ? null : note.strip());
        TriageFeedbackSnapshot saved = snapshots.save(row);
        return new Cut(saved.getId(), saved.getKind().name(), saved.getVersion(), saved.getRowCount(),
                saved.getCutAt());
    }

    /**
     * One frozen correction set, as the document an offline harness reads.
     *
     * <p><b>A SILVER version is refused here and that is the §7.4 rule in code.</b> Behaviour may rank a human-QA
     * queue and trigger a drift audit; it is never a label and never a measurement, so there is no document to
     * hand anybody. Giving it one would make the weaker evidence look exactly like the stronger — the single
     * combined file the draft forbids, arrived at one endpoint at a time.
     */
    @Transactional(readOnly = true)
    public TriageCorpusDocument corpus(UUID orgId, String version) {
        TriageFeedbackSnapshot snapshot = snapshots.findByOrgIdAndVersion(orgId, version)
                .orElseThrow(() -> ApiException.notFound("그 버전의 스냅샷이 없습니다."));
        if (snapshot.getKind() != SnapshotKind.CORRECTION) {
            throw ApiException.badRequest(
                    "행동 스냅샷은 평가 집합이 아닙니다. 수치를 재는 데 쓸 수 없고 내보내지 않습니다.");
        }
        List<Object[]> raw = rows.correctionSnapshotRows(orgId, version);
        Map<UUID, TriagePrediction> said = new HashMap<>();
        List<UUID> predictionIds = raw.stream().map(r -> (UUID) r[6]).filter(java.util.Objects::nonNull).toList();
        if (!predictionIds.isEmpty()) {
            for (TriagePrediction p : predictions.findAllById(predictionIds)) {
                said.put(p.getId(), p);
            }
        }
        List<TriageCorpusRow> out = new ArrayList<>();
        int withheld = 0;
        for (Object[] r : raw) {
            String fingerprint = ReviewIdFingerprint.of(r[0] == null ? null : String.valueOf(r[0]));
            if (fingerprint == null) {
                withheld++;
                continue;
            }
            TriagePrediction p = r[6] == null ? null : said.get((UUID) r[6]);
            out.add(new TriageCorpusRow(fingerprint, name(r[1]), name(r[2]), name(r[3]),
                    r[4] == null ? null : String.valueOf(r[4]), tags(r[5]),
                    p == null ? null : name(p.getTier()), p == null ? null : name(p.getModelTier()),
                    p == null ? null : name(p.getStatus()),
                    p == null ? null : p.getClassifierVersion(), p == null ? null : p.getModelId(),
                    p == null ? null : p.getPromptHash()));
        }
        return new TriageCorpusDocument(TriageCorpusDocument.CONTRACT, snapshot.getKind().name(),
                snapshot.getKind().labelKo(), snapshot.getVersion(), snapshot.getCutAt(),
                snapshot.getRowCount(), withheld, snapshot.getClassifierVersion(), snapshot.getPromptHash(),
                snapshot.getNote(), TriageCorpusDocument.USAGE, List.copyOf(out));
    }

    /** The predictions behind a set of dispositioned corrections, for the ones that have any. */
    private List<TriagePrediction> predictionsBehind(List<CorrectionDisposition> cuttable) {
        if (cuttable.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = corrections.findAllById(
                        cuttable.stream().map(CorrectionDisposition::getCorrectionId).toList()).stream()
                .map(TriageCorrection::getPredictionId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return ids.isEmpty() ? List.of() : predictions.findAllById(ids);
    }

    private static String name(Object value) {
        return value == null ? null : value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }

    private static List<String> tags(Object joined) {
        if (joined == null || String.valueOf(joined).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(joined).split(","));
    }
}
