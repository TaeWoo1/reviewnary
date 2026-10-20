package com.sellerops.vendor.review;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Review exports for tests: the committed contract fixtures, and workbooks built cell by cell with the same library
 * the fixtures were made with. Everything here is synthetic.
 */
public final class ExportWorkbooks {

    public static final Path CONTRACT = Path.of("..", "contracts", "review-export", "naver", "v1");

    private ExportWorkbooks() {
    }

    public static byte[] fixture() throws IOException {
        return Files.readAllBytes(CONTRACT.resolve("naver-review-export-v1.xlsx"));
    }

    public static byte[] emptyFixture() throws IOException {
        return Files.readAllBytes(CONTRACT.resolve("naver-review-export-empty-v1.xlsx"));
    }

    /** One review line with every export column; {@code overrides} replaces cells by header. */
    public static Map<String, String> line(String reviewNo, Map<String, String> overrides) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (String h : NaverReviewExport.HEADERS) {
            cells.put(h, "");
        }
        cells.put("상품번호", "SKU-합성-1");
        cells.put("상품명", "합성 상품");
        cells.put("리뷰구분", "일반");
        cells.put("구매자평점", "5");
        cells.put("리뷰상세내용", "합성 리뷰 " + reviewNo);
        cells.put("등록자", "REVIEWER-MUST-NOT-PERSIST");
        cells.put("리뷰등록일", "2026.10.01. 10:00:00");
        cells.put("리뷰글번호", reviewNo);
        cells.put("전시상태", "노출");
        cells.put("답글여부", "N");
        cells.put("유저정보 등록 항목", "USERINFO-MUST-NOT-PERSIST");
        cells.put("상품주문번호", "ORDER-MUST-NOT-PERSIST");
        cells.putAll(overrides);
        return cells;
    }

    public static byte[] workbook(List<String> headers, List<Map<String, String>> lines) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet0");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < lines.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < headers.size(); c++) {
                    row.createCell(c).setCellValue(lines.get(r).getOrDefault(headers.get(c), ""));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] workbook(List<Map<String, String>> lines) throws IOException {
        return workbook(NaverReviewExport.HEADERS, lines);
    }
}
