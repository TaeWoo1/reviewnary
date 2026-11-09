package com.sellerops.connector.cafe24;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The second way a Cafe24 문의 can already be answered: a COMMENT written by the shop.
 *
 * <p><b>The defect this closes.</b> Cafe24 publishes more than one representation of an answer, and
 * SellerOps could see exactly one of them. A seller answering board-6 article 3674 from the admin UI
 * on 2026-08-26 produced a comment — {@code member_id == mall_id}, 14:56 KST — and produced NEITHER
 * a child reply article NOR a {@code reply_status} change: the article still read {@code N} when the
 * routine sweep re-read it at 17:43, so every signal SellerOps owned said 미답변 while the customer
 * had been answered nearly three hours earlier. Verdict {@code STANDARD_BOARD_COMMENT}, approved
 * bounded READ {@code apr-c24-a3674-obs}, 7 requests, WRITE 0.
 *
 * <p><b>A comment is never ingested as an inquiry.</b> Nothing here produces a canonical record. This
 * class answers one question about articles the connector is ALREADY collecting — "has the shop
 * answered this one in the other place?" — and returns a time. A customer's comment produces no
 * entry, so no customer question is ever silently marked handled, and no comment ever becomes a row
 * a seller has to answer.
 *
 * <p><b>Authorship is proven, not assumed.</b> The only accepted proof is
 * {@link Cafe24BoardCommentRow#authoredByMall()} — the platform's own 상점명 condition — plus a
 * parseable timestamp. An unknown author, a blank member id, or a timezone-less date yields nothing:
 * the article stays 미답변, which is the recoverable error.
 *
 * <p><b>Bounded, and it says when it stopped.</b> One discovery request narrows the window to the
 * articles that have any comment at all ({@code comment=T}); at most {@link #MAX_COMMENT_READS}
 * comment reads follow. Reaching the cap is logged as a count rather than absorbed, because a
 * truncated read that stayed quiet would be indistinguishable from a board where nobody comments.
 */
public class Cafe24InquiryAnswerObserver {

    public enum CheckState { SHOP_ANSWERED, NO_SHOP_COMMENT, NOT_CHECKED }

    /**
     * <b>Why a look is {@code NOT_CHECKED}</b> (2026-11-08). The stored record and the screen say only "could not look";
     * a caller that will later space out retries per inquiry needs to know whether the failure was about THIS article or
     * about the whole account and channel, and that cannot be recovered from the record afterwards — so it travels with
     * the result. Never stored, never shown.
     */
    public enum Reason {
        /** The channel answered 429 — to the discovery, or to a comment read that ended the run of reads. Account-wide. */
        RATE_LIMITED,
        /** The channel refused the account's token or permission (401/403) — to the discovery, or to a comment read,
         *  which then ends the run of reads. Account-wide. */
        AUTH_REJECTED,
        /** The comment discovery itself failed (other than 429 or 401/403). Not about any one article. */
        DISCOVERY_FAILED,
        /** The discovery list hit its cap or its coverage is unproven, so absence from it proves nothing. */
        DISCOVERY_INCOMPLETE,
        /** Beyond this read's comment-read cap. A ceiling of ours, not the article's doing. */
        CAPPED,
        /** This article's own comment read failed in a way that is about this article: 404 or 500 on its resource, or
         *  an unreadable body for it. The reads go on. */
        READ_FAILED,
        /** A comment read was answered 502/503/504: the channel is not answering. Ends the run of reads. */
        CHANNEL_UNAVAILABLE,
        /** A comment read failed in a way not known to be about the article — any other status, a transport error, an
         *  exception of unknown kind. Ends the run of reads: what is not known to be the article's is not counted as
         *  the article's. */
        UNEXPECTED,
        /** This article's comments were read, but no shop answer could be proven: an unknown author, an unreadable
         *  time, a row naming another article. */
        UNPROVEN,
        /** No request was sent for this article: the caller's request limit refused it. Not a look at all. */
        NOT_ATTEMPTED,
        /** A result built without a reason (older callers and fixtures). */
        UNSPECIFIED
    }

    /**
     * No comment body or author identity crosses the observation boundary. {@code reason} is set exactly when the
     * state is {@code NOT_CHECKED}.
     */
    public record CommentCheck(CheckState state, Instant answeredAt, Reason reason) {
        public CommentCheck {
            if (state == null || (state == CheckState.SHOP_ANSWERED) != (answeredAt != null)
                    || (state == CheckState.NOT_CHECKED) != (reason != null)) {
                throw new IllegalArgumentException("댓글 확인 결과와 답변 시각이 맞지 않습니다.");
            }
        }

        /** As before 2026-11-08: a {@code NOT_CHECKED} with no stated reason. */
        public CommentCheck(CheckState state, Instant answeredAt) {
            this(state, answeredAt, state == CheckState.NOT_CHECKED ? Reason.UNSPECIFIED : null);
        }

        static CommentCheck notChecked(Reason reason) {
            return new CommentCheck(CheckState.NOT_CHECKED, null, reason);
        }

        /** No request went out for this article — it must not be recorded as a failed look. */
        public boolean notAttempted() {
            return reason == Reason.NOT_ATTEMPTED;
        }
    }

    private static final CommentCheck NO_SHOP_COMMENT = new CommentCheck(CheckState.NO_SHOP_COMMENT, null);

    private static final Logger log = LoggerFactory.getLogger(Cafe24InquiryAnswerObserver.class);

    /** How many articles' comments one page may read. Exceeding it is reported, never hidden. */
    static final int MAX_COMMENT_READS = 20;

    /** How many rows the one discovery request asks for. The platform's own maximum is 100. */
    static final int DISCOVERY_LIMIT = 100;

    /**
     * The comment-read ceiling for a historical run over an EXACT named set.
     *
     * <p>Higher than {@link #MAX_COMMENT_READS} because the two are bounded by different things. The
     * routine cap protects a sweep page whose candidate count nobody chose; a historical run names
     * every article in its approval manifest, so its ceiling is the manifest's, and exceeding it is a
     * refusal rather than a truncation.
     */
    static final int MAX_EXACT_COMMENT_READS = 25;

    private final Cafe24BoardArticlesClient articles;
    private final Cafe24BoardCommentsClient comments;

    public Cafe24InquiryAnswerObserver(Cafe24BoardArticlesClient articles,
                                       Cafe24BoardCommentsClient comments) {
        this.articles = articles;
        this.comments = comments;
    }

    /**
     * For the given candidate articles, when the shop answered each one by comment.
     *
     * <p>{@code candidates} is what the caller would otherwise store as 미답변 — already-answered
     * articles and thread replies are excluded by the caller, so this never spends a request to
     * re-confirm something the article itself already states. An empty candidate set makes zero
     * requests, including the discovery one.
     *
     * @return article number → the earliest proven shop-comment instant; never null, possibly empty
     */
    public Map<Long, Instant> observe(String accessToken, String mallId, int boardNo,
                                      LocalDate windowStart, LocalDate windowEnd,
                                      Set<Long> candidates) {
        return answeredOnly(observeChecks(accessToken, mallId, boardNo, windowStart, windowEnd, candidates));
    }

    /** One outcome for every candidate, including those the bounded read could not check. */
    public Map<Long, CommentCheck> observeChecks(String accessToken, String mallId, int boardNo,
                                                LocalDate windowStart, LocalDate windowEnd,
                                                Set<Long> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<Long> commented;
        try {
            commented = articles.fetchCommentedArticleNumbers(
                    accessToken, mallId, boardNo, windowStart, windowEnd, DISCOVERY_LIMIT);
        } catch (Cafe24RequestBudget.Exhausted e) {
            return unchecked(candidates, Reason.NOT_ATTEMPTED);
        } catch (Cafe24RateLimitedException e) {
            // The ordinary sweep already succeeded; losing the comment lane must not lose the page.
            log.info("카페24 댓글 답변 관측 생략: board={} 사유=RATE_LIMITED", boardNo);
            return unchecked(candidates, Reason.RATE_LIMITED);
        } catch (RuntimeException e) {
            Reason reason = discoveryFailure(e);
            log.info("카페24 댓글 답변 관측 생략: board={} 사유={}", boardNo, reason);
            return unchecked(candidates, reason);
        }
        if (commented.size() >= DISCOVERY_LIMIT) {
            log.info("카페24 댓글 후보 조회가 상한에 도달: board={} 상한={} — 이 창의 일부만 확인됨",
                    boardNo, DISCOVERY_LIMIT);
        }
        return readChecks(accessToken, mallId, boardNo, candidates, commented, MAX_COMMENT_READS,
                commented.size() < DISCOVERY_LIMIT);
    }

    /**
     * The same observation over an EXACT named set of articles, with no date window.
     *
     * <p><b>Why a second entry point rather than a wider window.</b> The routine form asks "which
     * articles in the last fortnight have comments?"; this one asks "which of THESE 25 have comments?"
     * The distinction is the whole bound: a historical backlog spanning eleven years cannot be reached
     * by a windowed discovery in one request (the contract caps a call at one year), and widening the
     * routine path to do it would put an eleven-year question on the sweep's critical path forever.
     *
     * <p>Requests: <b>1 + (candidates that have comments)</b>, ceiling
     * {@code 1 + }{@link #MAX_EXACT_COMMENT_READS}. Over-cap is a refusal, not a silent truncation —
     * a bounded run that quietly read fewer rows than its manifest named would report a "true"
     * unanswered count computed from a partial read.
     *
     * @throws IllegalArgumentException when more articles are named than the ceiling allows
     */
    public Map<Long, Instant> observeExact(String accessToken, String mallId, int boardNo,
                                           List<Long> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        if (candidates.size() > MAX_EXACT_COMMENT_READS) {
            throw new IllegalArgumentException("승인된 상한을 초과하는 대상 수입니다 ("
                    + candidates.size() + " > " + MAX_EXACT_COMMENT_READS + ").");
        }
        List<Long> commented =
                articles.fetchCommentedArticleNumbers(accessToken, mallId, boardNo, candidates);
        return answeredOnly(readChecks(accessToken, mallId, boardNo, new HashSet<>(candidates), commented,
                MAX_EXACT_COMMENT_READS, commented.size() < candidates.size()));
    }

    /** Exact-set observation for future rechecks; an unavailable discovery is not a negative result. */
    public Map<Long, CommentCheck> observeExactChecks(String accessToken, String mallId, int boardNo,
                                                     List<Long> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        if (candidates.size() > MAX_EXACT_COMMENT_READS) {
            throw new IllegalArgumentException("승인된 댓글 조회 상한을 초과했습니다.");
        }
        Set<Long> targets = new HashSet<>(candidates);
        List<Long> commented;
        try {
            commented = articles.fetchCommentedArticleNumbers(accessToken, mallId, boardNo, candidates);
        } catch (Cafe24RequestBudget.Exhausted e) {
            return unchecked(targets, Reason.NOT_ATTEMPTED);
        } catch (Cafe24RateLimitedException e) {
            log.info("카페24 댓글 답변 관측 생략: board={} 사유=RATE_LIMITED", boardNo);
            return unchecked(targets, Reason.RATE_LIMITED);
        } catch (RuntimeException e) {
            Reason reason = discoveryFailure(e);
            log.info("카페24 댓글 답변 관측 생략: board={} 사유={}", boardNo, reason);
            return unchecked(targets, reason);
        }
        return readChecks(accessToken, mallId, boardNo, targets, commented,
                MAX_EXACT_COMMENT_READS, commented.size() < candidates.size());
    }

    /**
     * <b>Did this look stop on the account's side?</b> (2026-11-09) Of these outcomes, the gravest failure that was about
     * the account or the channel rather than about one article — a refused token or permission, a 429, an outage, a failed
     * discovery, a failure of unknown kind — or null when there was none. What is about one article (its own read failed,
     * its comments proved nothing), a ceiling of ours (the read cap, an incomplete discovery list) and a request never sent
     * are not the account's trouble and give null. A reason with no stated kind counts as the account's: what is not known
     * to be the article's is not counted as the article's.
     */
    public static Reason accountSideReason(java.util.Collection<CommentCheck> checks) {
        Reason gravest = null;
        if (checks == null) {
            return null;
        }
        for (CommentCheck check : checks) {
            if (check == null || check.state() != CheckState.NOT_CHECKED) {
                continue;
            }
            int rank = accountRank(check.reason());
            if (rank > 0 && (gravest == null || rank > accountRank(gravest))) {
                gravest = check.reason();
            }
        }
        return gravest;
    }

    private static int accountRank(Reason reason) {
        return switch (reason) {
            case AUTH_REJECTED -> 6;
            case RATE_LIMITED -> 5;
            case CHANNEL_UNAVAILABLE -> 4;
            case DISCOVERY_FAILED -> 3;
            case UNEXPECTED -> 2;
            case UNSPECIFIED -> 1;
            case DISCOVERY_INCOMPLETE, CAPPED, READ_FAILED, UNPROVEN, NOT_ATTEMPTED -> 0;
        };
    }

    private static Reason discoveryFailure(RuntimeException e) {
        return e instanceof Cafe24ReadFailedException f && f.kind() == Cafe24ReadFailedException.Kind.AUTH_REJECTED
                ? Reason.AUTH_REJECTED : Reason.DISCOVERY_FAILED;
    }

    /**
     * Whether a failed comment read is about the article it named. Only a failure whose kind says so is; a refused
     * token, an outage and anything of unknown kind are not, and end the reads instead of being skipped past.
     */
    static Reason commentReadFailure(RuntimeException e) {
        if (!(e instanceof Cafe24ReadFailedException f)) {
            return Reason.UNEXPECTED;
        }
        return switch (f.kind()) {
            case NOT_FOUND, SERVER_ERROR, UNREADABLE_BODY -> Reason.READ_FAILED;
            case AUTH_REJECTED -> Reason.AUTH_REJECTED;
            case UNAVAILABLE -> Reason.CHANNEL_UNAVAILABLE;
            case UNEXPECTED_STATUS -> Reason.UNEXPECTED;
        };
    }

    private static Map<Long, CommentCheck> unchecked(Set<Long> candidates, Reason reason) {
        Map<Long, CommentCheck> checks = new LinkedHashMap<>();
        CommentCheck notChecked = CommentCheck.notChecked(reason);
        candidates.forEach(no -> checks.put(no, notChecked));
        return checks;
    }

    private static Map<Long, Instant> answeredOnly(Map<Long, CommentCheck> checks) {
        Map<Long, Instant> answered = new HashMap<>();
        checks.forEach((no, check) -> {
            if (check.state() == CheckState.SHOP_ANSWERED) {
                answered.put(no, check.answeredAt());
            }
        });
        return answered;
    }

    /** The shared read: intersect, cap, fetch comments, and accept only proven shop authorship. */
    private Map<Long, CommentCheck> readChecks(String accessToken, String mallId, int boardNo,
                                    Set<Long> candidates, List<Long> commented, int maxReads,
                                    boolean discoveryComplete) {
        // Until something better is known, a candidate's absence from discovery proves nothing.
        Map<Long, CommentCheck> checks = unchecked(candidates, Reason.DISCOVERY_INCOMPLETE);
        if (discoveryComplete) {
            Set<Long> withComments = new HashSet<>(commented);
            candidates.stream().filter(no -> !withComments.contains(no))
                    .forEach(no -> checks.put(no, NO_SHOP_COMMENT));
        }
        // Order-stable intersection: only articles we were going to store as unanswered.
        List<Long> targets = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (Long articleNo : commented) {
            if (candidates.contains(articleNo) && taken.add(articleNo)) {
                targets.add(articleNo);
            }
        }
        int capped = 0;
        if (targets.size() > maxReads) {
            capped = targets.size() - maxReads;
            targets.subList(maxReads, targets.size()).forEach(no -> checks.put(no, CommentCheck.notChecked(Reason.CAPPED)));
            targets = targets.subList(0, maxReads);
        }

        Map<Long, Instant> answered = new LinkedHashMap<>();
        int unknownActor = 0;
        int unparseableDate = 0;
        for (int i = 0; i < targets.size(); i++) {
            Long articleNo = targets.get(i);
            List<Cafe24BoardCommentRow> rows;
            try {
                rows = comments.fetchComments(accessToken, mallId, boardNo, articleNo);
            } catch (Cafe24RequestBudget.Exhausted e) {
                // Nothing was sent for this one or any after it.
                targets.subList(i, targets.size()).forEach(no -> checks.put(no, CommentCheck.notChecked(Reason.NOT_ATTEMPTED)));
                break;
            } catch (Cafe24RateLimitedException e) {
                log.info("카페24 댓글 답변 관측 중단: board={} 사유=RATE_LIMITED 확인완료={}",
                        boardNo, answered.size());
                targets.subList(i, targets.size()).forEach(no -> checks.put(no, CommentCheck.notChecked(Reason.RATE_LIMITED)));
                break;
            } catch (RuntimeException e) {
                Reason reason = commentReadFailure(e);
                if (reason == Reason.READ_FAILED) {
                    // One unreadable article must not lose the others; it simply stays 미답변.
                    log.info("카페24 댓글 조회 실패 1건 건너뜀: board={}", boardNo);
                    checks.put(articleNo, CommentCheck.notChecked(Reason.READ_FAILED));
                    continue;
                }
                // Not this article's doing: no further comment request goes out. What was read stays.
                log.info("카페24 댓글 답변 관측 중단: board={} 사유={} 확인완료={}", boardNo, reason, answered.size());
                targets.subList(i, targets.size()).forEach(no -> checks.put(no, CommentCheck.notChecked(reason)));
                break;
            }
            boolean uncertain = false;
            for (Cafe24BoardCommentRow row : rows) {
                if (row.articleNo() == null || !articleNo.equals(row.articleNo())) {
                    uncertain = true;
                    continue;
                }
                if (!row.authoredByMall()) {
                    if (!row.authorKnown()) {
                        unknownActor++;
                        uncertain = true;
                    }
                    continue;
                }
                Instant at = Cafe24BoardArticleMapper.parseOffsetInstant(row.createdDate());
                if (at == null) {
                    // Proven author, unusable time. "Answered at an unknown moment" is not a fact
                    // this lane can state, so it states nothing.
                    unparseableDate++;
                    uncertain = true;
                    continue;
                }
                answered.merge(articleNo, at, (a, b) -> a.isBefore(b) ? a : b);
            }
            if (answered.containsKey(articleNo)) {
                checks.put(articleNo, new CommentCheck(CheckState.SHOP_ANSWERED, answered.get(articleNo)));
            } else if (!uncertain) {
                checks.put(articleNo, NO_SHOP_COMMENT);
            } else {
                checks.put(articleNo, CommentCheck.notChecked(Reason.UNPROVEN));
            }
        }
        if (!targets.isEmpty() || capped > 0) {
            // Counts only — never an article id, a comment, a member id, or a date.
            log.info("카페24 댓글 답변 관측: board={} 후보={} 댓글보유={} 조회={} 판매자답변확인={} "
                            + "작성자불명={} 시각해석불가={} 상한초과미조회={}",
                    boardNo, candidates.size(), commented.size(), targets.size(), answered.size(),
                    unknownActor, unparseableDate, capped);
        }
        return Map.copyOf(checks);
    }
}
