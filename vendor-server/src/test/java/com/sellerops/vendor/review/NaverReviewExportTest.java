package com.sellerops.vendor.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.vendor.review.NaverReviewExport.Line;
import com.sellerops.vendor.review.NaverReviewExport.Refusal;
import com.sellerops.vendor.review.NaverReviewExport.Refused;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** <b>The export is read whole or not at all, and only through the allowed columns</b> — contract §3.1. */
class NaverReviewExportTest {

    @Test
    void theContractFixtureReadsToItsRecordedRows() throws Exception {
        JsonNode expected = new ObjectMapper().readTree(Files.readString(
                ExportWorkbooks.CONTRACT.resolve("expected-rows.json")));
        byte[] file = ExportWorkbooks.fixture();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file)))
                .as("the pinned fixture bytes").isEqualTo(expected.path("fileSha256").asText());
        List<String> headers = new ArrayList<>();
        expected.path("headers").forEach(h -> headers.add(h.asText()));
        assertThat(NaverReviewExport.HEADERS).as("the contract's 25 columns, in order").containsExactlyElementsOf(headers);

        List<Line> lines = NaverReviewExport.read(file);
        assertThat(lines).hasSize(expected.path("rows").size());
        for (int i = 0; i < lines.size(); i++) {
            JsonNode row = expected.path("rows").get(i);
            Line line = lines.get(i);
            assertThat(line.reviewNo()).isEqualTo(row.path("channelReviewId").asText());
            assertThat(line.productNo()).isEqualTo(row.path("sku").asText());
            assertThat(line.productName()).isEqualTo(row.path("product").asText());
            assertThat(line.rating()).isEqualTo(row.path("rating").asInt());
            assertThat(line.body()).isEqualTo(row.path("body").asText());
            assertThat(line.kind()).isEqualTo(row.path("reviewType").asText());
            assertThat(line.replied()).isEqualTo("Y".equals(row.path("replyFlag").asText()));
            assertThat(line.relatedReviewNo()).isEqualTo(row.path("relatedReviewId").asText().isEmpty() ? null
                    : row.path("relatedReviewId").asText());
        }
        // 2026.05.05. 09:12:33 in Seller Center's (Korean) time.
        assertThat(lines.get(0).writtenAt()).isEqualTo(Instant.parse("2026-05-05T00:12:33Z"));
        assertThat(lines.get(1).repliedAt()).isEqualTo(Instant.parse("2026-05-07T01:22:41Z"));
        assertThat(lines.get(3).modifiedAt()).isEqualTo(Instant.parse("2026-05-08T22:10:04Z"));
        assertThat(lines.get(4).attachment()).isEqualTo("https://example.invalid/synthetic-media-1");
    }

    @Test
    void noForbiddenColumnReachesALine() throws Exception {
        String everything = String.valueOf(NaverReviewExport.read(ExportWorkbooks.fixture()));
        assertThat(everything).as("the lines were read").contains("1000000001");
        for (String sentinel : List.of("REVIEWER-MUST-NOT-PERSIST", "ORDER-MUST-NOT-PERSIST",
                "USERINFO-MUST-NOT-PERSIST")) {
            assertThat(everything).doesNotContain(sentinel);
        }
        assertThat(NaverReviewExport.ALLOWED).doesNotContain("등록자", "상품주문번호", "유저정보 등록 항목")
                .allMatch(NaverReviewExport.HEADERS::contains);
    }

    @Test
    void anEmptyExportIsZeroLinesNotAnError() throws Exception {
        assertThat(NaverReviewExport.read(ExportWorkbooks.emptyFixture())).isEmpty();
    }

    @Test
    void somethingThatIsNotAWorkbookIsRefusedAsSuch() throws Exception {
        for (byte[] bad : List.of(new byte[0], "상품번호,상품명\n".getBytes(StandardCharsets.UTF_8),
                new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0})) {
            assertThatThrownBy(() -> NaverReviewExport.read(bad))
                    .isInstanceOfSatisfying(Refused.class, e -> assertThat(e.reason()).isEqualTo(Refusal.NOT_XLSX));
        }
    }

    @Test
    void aChangedExportIsRefusedAndTheColumnsAreNamed() throws Exception {
        List<String> headers = new ArrayList<>(NaverReviewExport.HEADERS);
        headers.remove("답글여부");
        headers.add("새로운열");
        byte[] file = ExportWorkbooks.workbook(headers, List.of(ExportWorkbooks.line("1", Map.of())));
        assertThatThrownBy(() -> NaverReviewExport.read(file)).isInstanceOfSatisfying(Refused.class, e -> {
            assertThat(e.reason()).isEqualTo(Refusal.HEADER_MISMATCH);
            assertThat(e.missing()).containsExactly("답글여부");
            assertThat(e.unexpected()).containsExactly("새로운열");
        });
        List<String> doubled = new ArrayList<>(NaverReviewExport.HEADERS);
        doubled.add("상품명");
        assertThatThrownBy(() -> NaverReviewExport.read(ExportWorkbooks.workbook(doubled, List.of())))
                .isInstanceOfSatisfying(Refused.class, e -> assertThat(e.unexpected()).containsExactly("상품명"));
    }

    @Test
    void theColumnsMayComeInAnyOrderAndABomIsNotPartOfAName() throws Exception {
        List<String> shuffled = new ArrayList<>(NaverReviewExport.HEADERS);
        java.util.Collections.reverse(shuffled);
        Map<String, String> line = ExportWorkbooks.line("77", Map.of());
        assertThat(NaverReviewExport.read(ExportWorkbooks.workbook(shuffled, List.of(line))))
                .singleElement().satisfies(l -> assertThat(l.reviewNo()).isEqualTo("77"));
        List<String> bom = new ArrayList<>(NaverReviewExport.HEADERS);
        bom.set(0, "﻿" + bom.get(0));
        assertThat(NaverReviewExport.read(ExportWorkbooks.workbook(bom, List.of()))).isEmpty();
    }

    @Test
    void oneMalformedLineRefusesTheWholeFileAndNamesSheetRows() throws Exception {
        List<Map<String, String>> lines = List.of(
                ExportWorkbooks.line("1", Map.of()),
                ExportWorkbooks.line("2", Map.of("구매자평점", "6")),
                ExportWorkbooks.line("", Map.of()),
                ExportWorkbooks.line("4", Map.of("리뷰등록일", "2026-10-01 10:00")),
                ExportWorkbooks.line("5", Map.of("답글여부", "예")),
                ExportWorkbooks.line("1", Map.of()),
                ExportWorkbooks.line("7", Map.of("답글등록일시", "어제")),
                ExportWorkbooks.line("8", Map.of("리뷰등록일", "2026.10.01.")));
        assertThatThrownBy(() -> NaverReviewExport.read(ExportWorkbooks.workbook(lines)))
                .isInstanceOfSatisfying(Refused.class, e -> {
                    assertThat(e.reason()).isEqualTo(Refusal.INVALID_ROWS);
                    // Sheet rows: the header is row 1, so the n-th line is row n + 1. The repeated number is row 7.
                    assertThat(e.rows()).containsExactly(3, 4, 5, 6, 7, 8);
                    assertThat(e.rowCount()).isEqualTo(6);
                });
    }

    @Test
    void anEntirelyEmptyLineIsSkipped() throws Exception {
        Map<String, String> blank = new java.util.HashMap<>();
        NaverReviewExport.HEADERS.forEach(h -> blank.put(h, ""));
        blank.put("등록자", "REVIEWER-MUST-NOT-PERSIST");
        assertThat(NaverReviewExport.read(ExportWorkbooks.workbook(List.of(blank, ExportWorkbooks.line("9", Map.of())))))
                .singleElement().satisfies(l -> assertThat(l.reviewNo()).isEqualTo("9"));
    }
}
