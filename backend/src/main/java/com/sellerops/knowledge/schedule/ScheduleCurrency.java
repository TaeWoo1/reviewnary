package com.sellerops.knowledge.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A schedule whose every date has passed is history, not the plan</b> — the one rule for a schedule the seller typed as
 * a document table and for a structured schedule line (2026-11-16 review §7.57: q04 S2 asked when the grey set comes back,
 * only the October table was left, and the judge kept it as partial ground and the draft wrote its 10/20 「확정」).
 *
 * <p>The structured lines already follow it ({@link KnowledgeScheduleService#current}). A table in a document follows it
 * here, and only for a question about the current plan: a question about what happened before (「지난번엔 언제
 * 들어왔었나요?」) still reads the old table. Nothing here looks at when a document was written — an old document with a
 * rule that still holds is not a schedule, and a schedule with one row still ahead is not history.
 */
public final class ScheduleCurrency {

    /** Words of a question a product's schedule can answer: stock coming in, going out, being made, and when. */
    public static final Pattern SCHEDULE_WORDS = Pattern.compile("입고|재입고|들어오|들어와|출고|생산|제작|품절|언제|일정|시작|재고");

    /**
     * A question about the past, not the plan: an explicit 「before」 word, or a past-perfect ending (「들어왔었나요」 ·
     * 「입고됐었던」). A plain past (「재입고 되었나요?」) asks whether it is back now — the plan — and is not this.
     */
    static final Pattern HISTORY = Pattern.compile(
            "지난번|저번|예전에|예전엔|이전에|이전엔|지난달|지난 ?주|작년|지난 ?일정|었었|았었|였었|됐었|왔었|었던 날|했던 날");

    /** A column header naming the date of a schedule row. */
    private static final Pattern DATE_HEADER = Pattern.compile("날짜|일정|입고일|출고일|예정일|일자");
    private static final Pattern FULL_DATE = Pattern.compile("(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})");

    private ScheduleCurrency() {
    }

    /** The question asks about the past (「지난번」 · 「들어왔었나요」). */
    public static boolean asksHistory(String question) {
        return question != null && HISTORY.matcher(question).find();
    }

    /** The question asks about the current plan of stock, shipping or making — schedule words, and not about the past. */
    public static boolean asksCurrentSchedule(String question) {
        return question != null && SCHEDULE_WORDS.matcher(question).find() && !asksHistory(question);
    }

    /**
     * The passage is a schedule that is all history at {@code reference}: it has at least one table with a date column,
     * every row of every such table carries a full date there, and every one of those dates is before the reference. A
     * table with a row still ahead, a row with no date (「미정」), or no date column at all is not history — it is read
     * as before. No reference, no verdict.
     */
    public static boolean pastOnly(String passage, LocalDate reference) {
        if (passage == null || reference == null) {
            return false;
        }
        boolean sawSchedule = false;
        int dateCol = -1;
        boolean header = false;
        for (String raw : passage.split("\\R")) {
            String line = raw.strip();
            if (!line.startsWith("|")) {
                dateCol = -1;
                header = false;
                continue;
            }
            List<String> cells = cells(line);
            if (dateCol < 0 && !header) {
                header = true;
                for (int i = 0; i < cells.size(); i++) {
                    if (DATE_HEADER.matcher(cells.get(i).replace(" ", "")).find()) {
                        dateCol = i;
                        break;
                    }
                }
                continue;
            }
            if (cells.stream().allMatch(c -> c.matches("[-: ]*"))) {
                continue; // the separator row
            }
            if (dateCol < 0) {
                continue; // a table without a date column says nothing about time
            }
            if (cells.size() <= dateCol) {
                return false;
            }
            List<LocalDate> dates = dates(cells.get(dateCol));
            if (dates.isEmpty()) {
                return false; // a row with no date (「미정」) is not history
            }
            for (LocalDate d : dates) {
                if (!d.isBefore(reference)) {
                    return false; // a row still ahead: the table is the plan
                }
            }
            sawSchedule = true;
        }
        return sawSchedule;
    }

    private static List<String> cells(String line) {
        String inner = line.replaceAll("^\\||\\|$", "");
        return Arrays.stream(inner.split("\\|")).map(String::strip).toList();
    }

    private static List<LocalDate> dates(String cell) {
        List<LocalDate> out = new ArrayList<>();
        Matcher m = FULL_DATE.matcher(cell);
        while (m.find()) {
            try {
                out.add(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))));
            } catch (java.time.DateTimeException e) {
                // not a date: the row is read as undated
            }
        }
        return out;
    }
}
