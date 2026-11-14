package com.sellerops.operations.board;

import com.sellerops.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * <b>The one order of the seller's work, applied before any page is cut</b> (2026-11-09, demo handoff §7.12).
 *
 * <p>{@link Sort#IMPORTANCE} is the default (product-owner decisions 2026-10-22 · 2026-10-28 · 2026-11-09):
 * <ol>
 *   <li>a review reporting a safety or legal problem;</li>
 *   <li>the inquiries, and the cases about one — <b>newest first</b> (2026-11-09: the owner chose newest for inquiries
 *       of equal importance; 「longest waiting」 is no longer the default and stays as {@link Sort#LONGEST});</li>
 *   <li>the reviews, and the cases about one — risk first (to judge or ★1–2, then ★3, then the rest), newest first
 *       inside each.</li>
 * </ol>
 * A waiting time is never turned into an importance score: no channel tells us an inquiry's deadline, so inside a
 * group the only signals are the ones above. A row with no time goes last in its group. The final key is the row's own
 * stable key, so two rows never tie and a page boundary is exact.
 *
 * <p><b>Paging is by position, not offset.</b> A cursor names the last row's place in the order; the next page is
 * every row strictly after it. Rows added before that place do not shift what the next page holds.
 */
public final class HomeWorkOrder {

    private HomeWorkOrder() {
    }

    public enum Sort {
        IMPORTANCE, NEWEST, LONGEST;

        static Sort of(String raw) {
            if (raw == null || raw.isBlank()) {
                return IMPORTANCE;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw ApiException.badRequest("sort는 importance · newest · longest 중 하나예요.");
            }
        }
    }

    /** A row's place in the order. {@code since} is epoch millis, null when the row has no time. */
    record Place(int group, int risk, Long since, String key) {
    }

    static Place placeOf(HomeWorkRow r) {
        int group = r.safety() ? 0 : "INQUIRY".equals(r.subject()) ? 1 : 2;
        int risk = group != 2 ? 0 : reviewRisk(r);
        return new Place(group, risk, r.since() == null ? null : r.since().toEpochMilli(), r.key());
    }

    /** 0 — to judge, or ★1–2; 1 — ★3; 2 — everything else (2026-10-28). */
    static int reviewRisk(HomeWorkRow r) {
        if ("NEEDS_LOOK".equals(r.state()) || "LOOK_INTO".equals(r.state())) {
            return 0;
        }
        if (r.rating() != null && r.rating() <= 2) {
            return 0;
        }
        return r.rating() != null && r.rating() == 3 ? 1 : 2;
    }

    static Comparator<Place> comparator(Sort sort) {
        Comparator<Place> newest = Comparator.comparing(Place::since,
                Comparator.nullsLast(Comparator.<Long>reverseOrder()));
        Comparator<Place> oldest = Comparator.comparing(Place::since,
                Comparator.nullsLast(Comparator.<Long>naturalOrder()));
        Comparator<Place> byKey = Comparator.comparing(Place::key);
        return switch (sort) {
            case IMPORTANCE -> Comparator.comparingInt(Place::group).thenComparingInt(Place::risk)
                    .thenComparing(newest).thenComparing(byKey);
            case NEWEST -> newest.thenComparing(byKey);
            case LONGEST -> oldest.thenComparing(byKey);
        };
    }

    static List<HomeWorkRow> sorted(List<HomeWorkRow> rows, Sort sort) {
        Comparator<Place> order = comparator(sort);
        return rows.stream().sorted((a, b) -> order.compare(placeOf(a), placeOf(b))).toList();
    }

    /** The rows strictly after {@code cursor}'s place, at most {@code size} of them. */
    static List<HomeWorkRow> after(List<HomeWorkRow> sortedRows, Sort sort, Place cursor, int size) {
        Comparator<Place> order = comparator(sort);
        return sortedRows.stream().filter(r -> cursor == null || order.compare(placeOf(r), cursor) > 0)
                .limit(size).toList();
    }

    // ---- the cursor: an opaque token naming the sort, the bucket and the last row's place ----

    static String cursorOf(Sort sort, String bucket, HomeWorkRow last) {
        Place p = placeOf(last);
        String raw = String.join("|", "v1", sort.name(), bucket, String.valueOf(p.group()), String.valueOf(p.risk()),
                p.since() == null ? "-" : String.valueOf(p.since()), p.key());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Place parseCursor(String cursor, Sort sort, String bucket) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] f = raw.split("\\|", 7);
            if (f.length != 7 || !"v1".equals(f[0])) {
                throw new IllegalArgumentException("shape");
            }
            if (!sort.name().equals(f[1]) || !bucket.equals(f[2])) {
                throw ApiException.badRequest("cursor가 다른 정렬이나 묶음의 것이에요. 처음부터 다시 읽어 주세요.");
            }
            return new Place(Integer.parseInt(f[3]), Integer.parseInt(f[4]), "-".equals(f[5]) ? null : Long.valueOf(f[5]),
                    f[6]);
        } catch (ApiException mismatch) {
            throw mismatch;
        } catch (RuntimeException unreadable) {
            throw ApiException.badRequest("cursor를 읽지 못했어요. 처음부터 다시 읽어 주세요.");
        }
    }
}
