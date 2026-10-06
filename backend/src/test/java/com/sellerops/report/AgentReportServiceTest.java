package com.sellerops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sellerops.report.dto.AgentReportView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Snapshot semantics: open reads, first open generates once, regenerate appends a version — and
 * generation reaches no model.
 */
class AgentReportServiceTest {

    private final AgentReportRepository reports = mock(AgentReportRepository.class);
    private final ReportFactsBuilder builder = mock(ReportFactsBuilder.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // Friday 2026-09-04, Seoul: the latest completed week is 08-24 … 08-30.
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-04T03:00:00Z"), ReportPeriod.CALENDAR);
    private final AgentReportService service = new AgentReportService(reports, builder, mapper, clock);
    private final UUID org = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        when(builder.build(eq(org), any(), any())).thenReturn(ReportFixtures.busy());
        when(reports.save(any())).thenAnswer(inv -> {
            AgentReport r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    @DisplayName("the first open generates the period once; reopening reads the stored row and asks nothing")
    void openGeneratesOnceThenReads() {
        when(reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(org, ReportKind.WEEKLY,
                LocalDate.of(2026, 8, 24))).thenReturn(Optional.empty());

        AgentReportView first = service.current(org, ReportKind.WEEKLY);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.periodLabelKo()).isEqualTo("2026년 8월 24일 ~ 30일");
        assertThat(first.summary().lines()).isNotEmpty();
        assertThat(first.facts().issues()).hasSize(1);

        ArgumentCaptor<AgentReport> saved = ArgumentCaptor.forClass(AgentReport.class);
        verify(reports).save(saved.capture());
        when(reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(org, ReportKind.WEEKLY,
                LocalDate.of(2026, 8, 24))).thenReturn(Optional.of(saved.getValue()));

        AgentReportView again = service.current(org, ReportKind.WEEKLY);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.facts()).isEqualTo(first.facts());
        // One build, one save: the second open read the row.
        verify(builder).build(eq(org), any(), any());
    }

    /**
     * <b>Generation reaches no vendor</b> (2026-10-06, product-owner decision). Measured before the
     * change: a first open of the monthly period took 25.5s end to end, of which the facts build was
     * under a second — the rest was a synchronous model call inside a GET, for a narrative the screen
     * had stopped printing. The structural half of this proof is {@code ReportSafetyFenceTest}, which
     * refuses the door by name; this is the behavioural half.
     */
    @Test
    @DisplayName("a generated snapshot carries no narrative, and nothing in the path can ask for one")
    void generationAsksNoModel() {
        when(reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        AgentReportView view = service.current(org, ReportKind.WEEKLY);

        assertThat(view.narrativeStatus()).isEqualTo("NOT_GENERATED");
        assertThat(view.narrative()).isNull();
        assertThat(view.narrativeNoteKo()).isNull();
        assertThat(view.summary().lines()).isNotEmpty();

        ArgumentCaptor<AgentReport> saved = ArgumentCaptor.forClass(AgentReport.class);
        verify(reports).save(saved.capture());
        assertThat(saved.getValue().getNarrativeJson()).isNull();
        assertThat(saved.getValue().getNarrativeVersion()).isNull();
    }

    /**
     * Rows written by older builds still read: they carry a narrative, a {@code READY} status and
     * counters that are plain numbers with no unit, no dataType and no exclusion list.
     */
    @Test
    @DisplayName("a report stored by an older build is still readable, narrative and all")
    void legacyRowsStillRead() throws Exception {
        String legacyFacts = """
                {"period":{"kind":"WEEKLY","kindLabelKo":"주간","start":"2026-08-24","end":"2026-08-30",
                 "labelKo":"2026년 8월 24일 ~ 30일","previousStart":"2026-08-17","previousEnd":"2026-08-23"},
                 "counters":[{"id":"c-reviews","labelKo":"받은 리뷰","periodic":true,"current":81,
                              "previous":65,"delta":16,"to":null}],
                 "issues":[],"opportunities":[],"nextSteps":[],"generatedAt":"2026-09-04T03:00:00Z"}
                """;
        AgentReport row = new AgentReport();
        row.setId(UUID.randomUUID());
        row.setOrgId(org);
        row.setKind(ReportKind.WEEKLY);
        row.setPeriodStart(LocalDate.of(2026, 8, 24));
        row.setPeriodEnd(LocalDate.of(2026, 8, 30));
        row.setVersion(1);
        row.setFactsJson(legacyFacts);
        row.setSummaryJson("{\"lines\":[{\"text\":\"리뷰 81건\",\"kind\":\"FACT\",\"factIds\":[\"c-reviews\"]}]}");
        row.setNarrativeJson("{\"headline\":\"이번 주 요약\",\"lines\":[{\"text\":\"리뷰가 늘었습니다.\",\"factIds\":[\"c-reviews\"]}]}");
        row.setNarrativeStatus(NarrativeStatus.READY);
        row.setGeneratedAt(Instant.parse("2026-09-04T03:00:00Z"));
        when(reports.findByOrgIdAndId(org, row.getId())).thenReturn(Optional.of(row));

        AgentReportView view = service.get(org, row.getId());

        assertThat(view.narrativeStatus()).isEqualTo("READY");
        assertThat(view.narrative().headline()).isEqualTo("이번 주 요약");
        assertThat(view.facts().counters()).singleElement().satisfies(c -> {
            assertThat(c.current()).isEqualTo(81L);
            // The fields that did not exist then read back as their absent values, not as claims.
            assertThat(c.unit()).isNull();
            assertThat(c.unitOrDefault()).isEqualTo("건");
            assertThat(c.dataType()).isNull();
            assertThat(c.excludedChannels()).isZero();
        });
        // Lists added later are empty, never null — no reader has to know how old a row is. An empty
        // `reads` is exactly what makes a legacy edition recognisable: it recorded no read range, and
        // the screen says so rather than borrowing today's.
        assertThat(view.facts().reads()).isEmpty();
        assertThat(view.facts().salesByChannel()).isEmpty();
        assertThat(view.facts().nextSteps()).isEmpty();
    }

    @Test
    void regenerateAppendsAVersionAndLeavesTheOldOne() {
        AgentReport v1 = new AgentReport();
        v1.setVersion(1);
        when(reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(org, ReportKind.WEEKLY,
                LocalDate.of(2026, 8, 24))).thenReturn(Optional.of(v1));

        AgentReportView v2 = service.regenerate(org, ReportKind.WEEKLY, null);
        assertThat(v2.version()).isEqualTo(2);
        verify(reports, never()).delete(any());
    }

    /**
     * <b>A stored edition is a frozen edition</b> — and that includes the evidence, not only the
     * figures. Before 2026-10-06 the read range came from a live coverage call, so reopening a report
     * cut on 9월 27일 would show whatever had been collected since; the numbers stood still while their
     * justification moved.
     */
    @Test
    @DisplayName("reopening a stored report returns the same numbers, including 매출")
    void storedFiguresDoNotMove() {
        when(reports.findTopByOrgIdAndKindAndPeriodStartOrderByVersionDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        AgentReportView first = service.current(org, ReportKind.WEEKLY);

        ArgumentCaptor<AgentReport> saved = ArgumentCaptor.forClass(AgentReport.class);
        verify(reports).save(saved.capture());
        when(reports.findByOrgIdAndId(eq(org), any())).thenReturn(Optional.of(saved.getValue()));
        // The builder would now answer differently; the stored row must not.
        when(builder.build(eq(org), any(), any())).thenReturn(ReportFixtures.quiet());

        AgentReportView reopened = service.get(org, first.id());
        assertThat(reopened.facts()).isEqualTo(first.facts());
        assertThat(reopened.facts().reads()).isEqualTo(first.facts().reads());
        assertThat(reopened.facts().reads()).extracting(ReportFacts.Read::lastReadAt).doesNotContainNull();
        assertThat(reopened.facts().counters()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo("c-revenue");
            assertThat(c.current()).isEqualTo(3_884_590L);
            assertThat(c.unit()).isEqualTo("원");
        });
    }
}
