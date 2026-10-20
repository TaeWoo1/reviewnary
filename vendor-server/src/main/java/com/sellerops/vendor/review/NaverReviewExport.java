package com.sellerops.vendor.review;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * <b>Reads a NAVER Seller Center review export — and only the columns contract §3.1 allows.</b>
 *
 * <ol>
 *   <li>The bytes must be an OOXML workbook. The file is read from memory and never written anywhere.</li>
 *   <li>The header row must be exactly the export's 25 columns ({@code contracts/review-export/naver/v1}), in any
 *       order. A missing or an unknown column refuses the whole file and names the columns — a changed export is
 *       something the delegate must hear about, not something to skip past.</li>
 *   <li>Only {@link #ALLOWED} columns are read into a {@link Line}. The reviewer, the order number, the
 *       member-entered fields and the operating extras are never copied out of the sheet.</li>
 *   <li>Every line must be well-formed — a review number, a 1–5 rating, a registration time, a Y/N reply flag —
 *       and no review number may appear twice. Otherwise nothing is imported and the sheet rows are named.</li>
 * </ol>
 */
public final class NaverReviewExport {

    static {
        // Zip-bomb guards, as in the backend's FileParser: the 10MB upload cap bounds the compressed input; these
        // bound each entry's expansion.
        ZipSecureFile.setMaxEntrySize(64L * 1024 * 1024);
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    /** The real export's 25 headers, in the export's order. */
    public static final List<String> HEADERS = List.of("상품번호", "상품명", "리뷰구분", "구매자평점", "포토/영상",
            "리뷰상세내용", "리뷰도움수", "등록자", "리뷰등록일", "최종수정일", "리뷰글번호", "관련리뷰글번호", "관련리뷰상세내용",
            "전시상태", "답글여부", "답글등록일시", "베스트리뷰", "베스트리뷰선정일시", "이벤트번호", "혜택지급", "혜택지급일시",
            "유저정보 등록 항목", "상품주문번호", "풀필먼트사", "리뷰이동일");

    /** The columns that are read at all (contract §3.1 「저장(허용)」). */
    public static final Set<String> ALLOWED = Set.of("상품번호", "상품명", "리뷰구분", "구매자평점", "포토/영상",
            "리뷰상세내용", "리뷰등록일", "최종수정일", "리뷰글번호", "관련리뷰글번호", "관련리뷰상세내용", "전시상태", "답글여부",
            "답글등록일시");

    /** Times in the export are Seller Center's, i.e. Korean local time. */
    static final ZoneId SELLER_CENTER_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd.");
    private static final Pattern REVIEW_NO = Pattern.compile("\\d{1,20}");
    private static final Pattern RATING = Pattern.compile("[1-5]");
    /** At most this many bad sheet rows are named back; the count is always exact. */
    static final int MAX_NAMED_ROWS = 20;

    public enum Refusal { NOT_XLSX, HEADER_MISMATCH, INVALID_ROWS }

    /** A refusal and what the delegate needs to act on it. Column names are the export's own; rows are sheet rows. */
    public static final class Refused extends RuntimeException {
        private final Refusal reason;
        private final List<String> missing;
        private final List<String> unexpected;
        private final List<Integer> rows;
        private final int rowCount;

        Refused(Refusal reason, List<String> missing, List<String> unexpected, List<Integer> rows, int rowCount) {
            super(reason.name());
            this.reason = reason;
            this.missing = List.copyOf(missing);
            this.unexpected = List.copyOf(unexpected);
            this.rows = List.copyOf(rows);
            this.rowCount = rowCount;
        }

        public Refusal reason() {
            return reason;
        }

        public List<String> missing() {
            return missing;
        }

        public List<String> unexpected() {
            return unexpected;
        }

        public List<Integer> rows() {
            return rows;
        }

        public int rowCount() {
            return rowCount;
        }
    }

    /** One review as the allowed columns state it. Times are instants; {@code attachment} is the raw cell. */
    public record Line(String reviewNo, String productNo, String productName, String kind, int rating, String body,
                       String attachment, Instant writtenAt, Instant modifiedAt, String displayState, boolean replied,
                       Instant repliedAt, String relatedReviewNo, String relatedBody) {}

    private NaverReviewExport() {
    }

    public static List<Line> read(byte[] file) {
        if (file == null || file.length < 4 || file[0] != 'P' || file[1] != 'K' || file[2] != 3 || file[3] != 4) {
            throw refused(Refusal.NOT_XLSX);
        }
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw refused(Refusal.NOT_XLSX);
            }
            return read(workbook.getSheetAt(0));
        } catch (Refused e) {
            throw e;
        } catch (Exception | LinkageError e) {
            throw refused(Refusal.NOT_XLSX);
        }
    }

    private static List<Line> read(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        Map<String, Integer> column = new HashMap<>();
        Set<String> duplicated = new LinkedHashSet<>();
        if (headerRow != null) {
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String header = header(formatter.formatCellValue(headerRow.getCell(c)));
                if (header.isEmpty()) {
                    continue;
                }
                if (column.putIfAbsent(header, c) != null) {
                    duplicated.add(header);
                }
            }
        }
        List<String> missing = HEADERS.stream().filter(h -> !column.containsKey(h)).toList();
        List<String> unexpected = new ArrayList<>(column.keySet().stream().filter(h -> !HEADERS.contains(h)).sorted()
                .toList());
        unexpected.addAll(duplicated);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new Refused(Refusal.HEADER_MISMATCH, missing, unexpected, List.of(), 0);
        }

        List<Line> lines = new ArrayList<>();
        List<Integer> bad = new ArrayList<>();
        int badCount = 0;
        Set<String> seen = new HashSet<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> cells = new HashMap<>();
            boolean any = false;
            for (String name : ALLOWED) {
                String value = formatter.formatCellValue(row.getCell(column.get(name))).strip();
                cells.put(name, value);
                any = any || !value.isEmpty();
            }
            if (!any) {
                continue;
            }
            Optional<Line> line = line(cells);
            if (line.isEmpty() || !seen.add(line.get().reviewNo())) {
                badCount++;
                if (bad.size() < MAX_NAMED_ROWS) {
                    bad.add(r + 1);
                }
                continue;
            }
            lines.add(line.get());
        }
        if (badCount > 0) {
            throw new Refused(Refusal.INVALID_ROWS, List.of(), List.of(), bad, badCount);
        }
        return lines;
    }

    private static Optional<Line> line(Map<String, String> cells) {
        String reviewNo = cells.get("리뷰글번호");
        String rating = cells.get("구매자평점");
        String replied = cells.get("답글여부");
        String related = cells.get("관련리뷰글번호");
        Optional<Instant> written = time(cells.get("리뷰등록일"));
        Optional<Optional<Instant>> modified = optionalTime(cells.get("최종수정일"));
        Optional<Optional<Instant>> repliedAt = optionalTime(cells.get("답글등록일시"));
        if (!REVIEW_NO.matcher(reviewNo).matches() || !RATING.matcher(rating).matches() || written.isEmpty()
                || modified.isEmpty() || repliedAt.isEmpty() || !Set.of("Y", "N", "").contains(replied)
                || !(related.isEmpty() || REVIEW_NO.matcher(related).matches())
                || cells.get("상품번호").length() > 40 || cells.get("상품명").length() > 300
                || cells.get("리뷰구분").length() > 40 || cells.get("전시상태").length() > 40) {
            return Optional.empty();
        }
        return Optional.of(new Line(reviewNo, blankToNull(cells.get("상품번호")), blankToNull(cells.get("상품명")),
                blankToNull(cells.get("리뷰구분")), Integer.parseInt(rating), cells.get("리뷰상세내용"),
                blankToNull(cells.get("포토/영상")), written.get(), modified.get().orElse(null),
                blankToNull(cells.get("전시상태")), "Y".equals(replied), repliedAt.get().orElse(null),
                blankToNull(related), blankToNull(cells.get("관련리뷰상세내용"))));
    }

    /** {@code yyyy.MM.dd. HH:mm:ss}, or a bare {@code yyyy.MM.dd.} (start of that day), in Seller Center's zone. */
    static Optional<Instant> time(String value) {
        try {
            if (value.length() == 20) {
                return Optional.of(LocalDateTime.parse(value, DATE_TIME).atZone(SELLER_CENTER_ZONE).toInstant());
            }
            if (value.length() == 11) {
                return Optional.of(LocalDate.parse(value, DATE).atStartOfDay(SELLER_CENTER_ZONE).toInstant());
            }
            return Optional.empty();
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Empty cell → present-and-empty; a value → that time; a value that is not a time → absent (invalid). */
    private static Optional<Optional<Instant>> optionalTime(String value) {
        if (value.isEmpty()) {
            return Optional.of(Optional.empty());
        }
        return time(value).map(Optional::of);
    }

    private static String header(String raw) {
        String h = raw == null ? "" : raw;
        if (!h.isEmpty() && h.charAt(0) == '﻿') {
            h = h.substring(1);
        }
        return h.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Refused refused(Refusal reason) {
        return new Refused(reason, List.of(), List.of(), List.of(), 0);
    }
}
