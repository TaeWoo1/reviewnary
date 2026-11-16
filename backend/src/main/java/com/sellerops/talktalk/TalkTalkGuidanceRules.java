package com.sellerops.talktalk;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>What a confirmed guidance says at a given moment, and the one sentence set it may be said with</b> — pure.
 *
 * <p>The customer text is written by code from the guidance fields only (lane contract §6-7): no model, no
 * seller-typed note, no order. The renderer refuses — returns a reason instead of a text — when the guidance does not
 * settle the answer: no cut-off, or a next dispatch day past the date the seller confirmed the closed-day list through.
 */
public final class TalkTalkGuidanceRules {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** Bump when a sentence changes: a delegation is bound to it through the preview fingerprint. */
    public static final String RENDERER_VERSION = "talktalk-guidance-renderer:v1";
    private static final int LOOKAHEAD_DAYS = 14;

    private TalkTalkGuidanceRules() {
    }

    public record ClosedDate(LocalDate date, String note) {
    }

    public record Rendered(String text, String refusal) {
        public boolean ok() {
            return text != null;
        }
    }

    public static Set<DayOfWeek> days(String csv) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (csv == null) {
            return out;
        }
        for (String p : csv.split(",")) {
            String k = p.strip().toUpperCase(Locale.ROOT);
            for (DayOfWeek d : DayOfWeek.values()) {
                if (d.name().startsWith(k) && k.length() >= 3) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    public static String daysCsv(Set<DayOfWeek> days) {
        List<String> out = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (days.contains(d)) {
                out.add(d.name().substring(0, 3));
            }
        }
        return String.join(",", out);
    }

    public static List<ClosedDate> closed(String lines) {
        Map<LocalDate, ClosedDate> out = new LinkedHashMap<>();
        if (lines == null) {
            return List.of();
        }
        for (String line : lines.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split("\\|", 2);
            LocalDate d = LocalDate.parse(p[0].strip());
            out.putIfAbsent(d, new ClosedDate(d, p.length > 1 ? p[1].strip() : ""));
        }
        return out.values().stream().sorted((a, b) -> a.date().compareTo(b.date())).toList();
    }

    public static String closedLines(List<ClosedDate> dates) {
        StringBuilder sb = new StringBuilder();
        dates.stream().map(ClosedDate::date).distinct().sorted().forEach(d -> {
            String note = dates.stream().filter(c -> c.date().equals(d)).map(ClosedDate::note).findFirst().orElse("");
            sb.append(d).append('|').append(note == null ? "" : note.strip()).append('\n');
        });
        return sb.toString();
    }

    /** The canonical digest of everything the customer text is computed from. */
    public static String fingerprint(Integer cutoffHour, int cutoffMinute, String daysCsv, String closedLines,
                                     LocalDate confirmedThrough) {
        StringBuilder sb = new StringBuilder("talktalk-guidance:v1\n");
        sb.append("cutoff=").append(cutoffHour == null ? "-" : cutoffHour + ":" + cutoffMinute).append('\n');
        sb.append("days=").append(daysCsv(days(daysCsv))).append('\n');
        for (ClosedDate c : closed(closedLines)) {
            sb.append("closed=").append(c.date()).append('\n');
        }
        sb.append("through=").append(confirmedThrough == null ? "-" : confirmedThrough).append('\n');
        return TalkTalkHashes.sha256(sb.toString());
    }

    public static String fingerprint(TalkTalkGuidance g) {
        return fingerprint(g.getCutoffHour(), g.getCutoffMinute(), g.getDispatchDays(), g.getClosedDates(),
                g.getConfirmedThrough());
    }

    /** What a delegation is bound to besides the values: the sentence set that renders them. */
    public static String previewFingerprint(TalkTalkGuidance g) {
        return TalkTalkHashes.sha256(RENDERER_VERSION + "\n" + fingerprint(g));
    }

    public static boolean isDispatchDay(TalkTalkGuidance g, LocalDate d) {
        return days(g.getDispatchDays()).contains(d.getDayOfWeek())
                && closed(g.getClosedDates()).stream().noneMatch(c -> c.date().equals(d));
    }

    /** The guidance's customer text at {@code now}, or the reason it does not settle the answer. */
    public static Rendered render(TalkTalkGuidance g, Instant now) {
        if (g == null) {
            return new Rendered(null, "NO_CONFIRMED_GUIDANCE");
        }
        if (g.getCutoffHour() == null) {
            return new Rendered(null, "CUTOFF_MISSING");
        }
        Set<DayOfWeek> days = days(g.getDispatchDays());
        if (days.isEmpty()) {
            return new Rendered(null, "DISPATCH_DAYS_MISSING");
        }
        if (g.getConfirmedThrough() == null) {
            return new Rendered(null, "CALENDAR_UNCONFIRMED");
        }
        ZonedDateTime at = now.atZone(KST);
        LocalDate today = at.toLocalDate();
        LocalTime cutoff = LocalTime.of(g.getCutoffHour(), g.getCutoffMinute());
        boolean todayDispatch = isDispatchDay(g, today);
        LocalDate next;
        if (todayDispatch && at.toLocalTime().isBefore(cutoff)) {
            next = today;
        } else {
            next = today.plusDays(1);
            int guard = 0;
            while (!isDispatchDay(g, next) && guard++ < 60) {
                next = next.plusDays(1);
            }
            if (guard >= 60) {
                return new Rendered(null, "NO_DISPATCH_DAY");
            }
        }
        if (next.isAfter(g.getConfirmedThrough()) || today.isAfter(g.getConfirmedThrough())) {
            return new Rendered(null, "CALENDAR_UNCONFIRMED");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("안녕하세요, 배송 기준 안내드려요.\n");
        sb.append(daysLabel(days)).append(' ').append(clock(cutoff)).append(" 이전 결제 건은 당일 출고, ")
                .append("이후 결제 건은 다음 출고일 출고가 기준이에요.\n");
        boolean closedToday = closed(g.getClosedDates()).stream().anyMatch(c -> c.date().equals(today));
        if (closedToday) {
            sb.append("오늘 ").append(dayLabel(today)).append("은 휴무라 출고가 없어요.\n");
        }
        if (next.equals(today)) {
            sb.append("오늘 ").append(dayLabel(today)).append("은 출고일이고, ").append(clock(cutoff))
                    .append(" 이전 결제 건까지 오늘 출고 기준이에요.\n");
        } else {
            sb.append("지금 기준으로 다음 출고일은 ").append(dayLabel(next)).append("이에요.\n");
        }
        List<LocalDate> upcoming = closed(g.getClosedDates()).stream().map(ClosedDate::date)
                .filter(d -> d.isAfter(today) && !d.isAfter(today.plusDays(LOOKAHEAD_DAYS)))
                .filter(d -> !d.isAfter(g.getConfirmedThrough()))
                .toList();
        if (!upcoming.isEmpty()) {
            sb.append("앞으로 2주 안 휴무일: ")
                    .append(String.join(", ", upcoming.stream().map(TalkTalkGuidanceRules::dayLabel).toList()))
                    .append(".\n");
        }
        sb.append("재고 확인이 필요한 상품이나 주문 제작·예약 상품은 출고일이 달라질 수 있어요.\n");
        sb.append("이미 주문하신 건의 실제 출고·도착 여부는 말씀해 주시면 담당자가 확인해 안내드려요.");
        return new Rendered(sb.toString(), null);
    }

    /** The rule as one sentence for a judge, a drafter and the knowledge conflict check. */
    public static String ruleSentence(TalkTalkGuidance g) {
        if (g.getCutoffHour() == null) {
            return "출고 마감 시각은 정해지지 않았습니다.";
        }
        return daysLabel(days(g.getDispatchDays())) + " " + clock(LocalTime.of(g.getCutoffHour(), g.getCutoffMinute()))
                + " 이전 결제 주문은 당일 출고합니다.";
    }

    static String clock(LocalTime t) {
        int h = t.getHour();
        String part = h < 12 ? "오전 " + (h == 0 ? 12 : h) : (h == 12 ? "낮 12" : "오후 " + (h - 12));
        return part + "시" + (t.getMinute() == 0 ? "" : " " + t.getMinute() + "분");
    }

    static String dayLabel(LocalDate d) {
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일(" + weekdayKo(d.getDayOfWeek()) + ")";
    }

    static String daysLabel(Set<DayOfWeek> days) {
        EnumSet<DayOfWeek> weekdays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        if (days.equals(weekdays)) {
            return "평일(월~금)";
        }
        List<String> out = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (days.contains(d)) {
                out.add(weekdayKo(d));
            }
        }
        return String.join("·", out) + "요일";
    }

    static String weekdayKo(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }
}
