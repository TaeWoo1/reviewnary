package com.sellerops.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.common.ApiException;
import com.sellerops.report.dto.AgentReportListItem;
import com.sellerops.report.dto.AgentReportView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshot semantics for the operating report (Agentic Report v1).
 *
 * <p><b>Open = read; generate only when nothing exists.</b> Opening a cadence's current report reads
 * the newest stored version for the latest completed period and generates one only if that period has
 * never been generated. Reopening never regenerates, never re-asks the model, and never recomputes a
 * fact — the seller reads the same page they read an hour ago.
 *
 * <p><b>Regenerate = a new version.</b> A seller who wants a newer reading (a re-import landed, a
 * decision was made) asks for one explicitly and gets version N+1; version N stays. There is no
 * in-place update anywhere in this class.
 *
 * <p><b>Generation reaches no model</b> (2026-10-06, product-owner decision). The report screen stopped
 * printing a narrative when it became a workspace of values with their read range, and this class is
 * where the call used to be made: a synchronous vendor request inside a GET, measured at 25.5s on the
 * first open of a period, for an artifact nothing rendered. {@code NarrativeStatus}, the stored columns
 * and {@code NarrativeClaimGuard} stay so rows written by older builds still read; nothing here asks
 * for a new one, and {@code ReportSafetyFenceTest} refuses the door by name.
 */
@Service
public class AgentReportService {

    static final int LIST_LIMIT = 12;

    private final AgentReportRepository reports;
    private final ReportFactsBuilder facts;
    private final ObjectMapper mapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentReportService(AgentReportRepository reports, ReportFactsBuilder facts, ObjectMapper mapper) {
        this(reports, facts, mapper, Clock.system(ReportPeriod.CALENDAR));
    }

    AgentReportService(AgentReportRepository reports, ReportFactsBuilder facts, ObjectMapper mapper, Clock clock) {
        this.reports = reports;
        this.facts = facts;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The current report of one cadence: the stored one, or the first generation of that period. */
    @Transactional
    public AgentReportView current(UUID orgId, ReportKind kind) {
        ReportPeriod period = ReportPeriod.latestCompleted(kind, LocalDate.now(clock));
        Optional<AgentReport> existing =
                reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(orgId, kind, period.start());
        return view(existing.orElseGet(() -> generate(orgId, period, 1)));
    }

    /** A newer reading of one period, as a new version. The period defaults to the latest completed. */
    @Transactional
    public AgentReportView regenerate(UUID orgId, ReportKind kind, LocalDate periodStart) {
        LocalDate today = LocalDate.now(clock);
        ReportPeriod period = periodStart == null
                ? ReportPeriod.latestCompleted(kind, today)
                : ReportPeriod.startingAt(kind, periodStart);
        if (!period.completedBy(today)) {
            throw ApiException.badRequest("아직 끝나지 않은 기간의 리포트는 만들 수 없습니다.");
        }
        int nextVersion = reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(orgId, kind, period.start())
                .map(r -> r.getVersion() + 1).orElse(1);
        return view(generate(orgId, period, nextVersion));
    }

    @Transactional(readOnly = true)
    public AgentReportView get(UUID orgId, UUID id) {
        return view(reports.findByOrgIdAndId(orgId, id)
                .orElseThrow(() -> ApiException.notFound("리포트를 찾을 수 없습니다.")));
    }

    @Transactional(readOnly = true)
    public List<AgentReportListItem> list(UUID orgId, ReportKind kind) {
        return reports.findByOrgIdAndKindOrderByPeriodStartDescVersionDesc(orgId, kind, PageRequest.of(0, LIST_LIMIT))
                .stream().map(r -> new AgentReportListItem(r.getId(), r.getKind().name(), r.getPeriodStart(),
                        r.getPeriodEnd(), ReportPeriod.startingAt(r.getKind(), r.getPeriodStart()).labelKo(),
                        r.getVersion(), r.getGeneratedAt(), r.getNarrativeStatus().name()))
                .toList();
    }

    private AgentReport generate(UUID orgId, ReportPeriod period, int version) {
        Instant now = Instant.now(clock);
        ReportFacts built = facts.build(orgId, period, now);
        ReportSummary summary = ReportSummaryComposer.compose(built);
        String factsJson = write(built);

        AgentReport row = new AgentReport();
        row.setOrgId(orgId);
        row.setKind(period.kind());
        row.setPeriodStart(period.start());
        row.setPeriodEnd(period.end());
        row.setVersion(version);
        row.setFactsJson(factsJson);
        row.setSummaryJson(write(summary));
        row.setGeneratedAt(now);

        row.setNarrativeStatus(NarrativeStatus.NOT_GENERATED);

        return reports.save(row);
    }

    private AgentReportView view(AgentReport row) {
        ReportPeriod period = ReportPeriod.startingAt(row.getKind(), row.getPeriodStart());
        return new AgentReportView(row.getId(), row.getKind().name(), row.getKind().labelKo(),
                row.getPeriodStart(), row.getPeriodEnd(), period.labelKo(), row.getVersion(), row.getGeneratedAt(),
                read(row.getFactsJson(), ReportFacts.class), read(row.getSummaryJson(), ReportSummary.class),
                row.getNarrativeJson() == null ? null : read(row.getNarrativeJson(), ReportNarrative.class),
                row.getNarrativeStatus().name(), row.getNarrativeStatus().noteKo());
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("리포트를 저장할 수 없습니다.", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 리포트를 읽을 수 없습니다.", e);
        }
    }
}
