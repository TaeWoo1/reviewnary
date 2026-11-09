package com.sellerops.connector.cafe24;

import static com.sellerops.connector.cafe24.Cafe24InquiryAnswerObserver.CheckState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Offline HTTP fixtures: absence, uncertainty and a proven shop answer are different outcomes. */
class Cafe24CommentCheckTest {
    private final FakeCafe24HttpClient http = new FakeCafe24HttpClient();
    private final Cafe24InquiryAnswerObserver observer = new Cafe24InquiryAnswerObserver(
            new Cafe24BoardArticlesClient(http), new Cafe24BoardCommentsClient(http));

    private Map<Long, Cafe24InquiryAnswerObserver.CommentCheck> check(Set<Long> candidates) {
        return observer.observeChecks("synthetic-token", "test-shop", 6,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 8), candidates);
    }

    private void ok(String body) {
        http.enqueue(new Cafe24HttpClient.Response(200, body, Map.of()));
    }

    private void discovery() {
        ok("{\"articles\":[{\"article_no\":1}]}");
    }

    private String comment(String author, String date) {
        return "{\"article_no\":1,\"member_id\":" + author
                + ",\"created_date\":\"" + date + "\"}";
    }

    @Test
    void noCandidatesMakesNoRequests() {
        assertThat(check(Set.of())).isEmpty();
        assertThat(http.sent).isEmpty();
    }

    @Test
    void aProvenShopAnswerWinsEvenAlongsideUnknownComments() {
        discovery();
        ok("{\"comments\":[" + comment("null", "bad-date") + ","
                + comment("\"test-shop\"", "2026-11-08T10:00:00+09:00") + ","
                + comment("\"test-shop\"", "2026-11-08T09:00:00+09:00") + "]}");
        var checks = check(Set.of(1L, 2L));
        assertThat(checks).containsOnlyKeys(1L, 2L);
        assertThat(checks.get(1L).state()).isEqualTo(SHOP_ANSWERED);
        assertThat(checks.get(1L).answeredAt()).isEqualTo(Instant.parse("2026-11-08T00:00:00Z"));
        assertThat(checks.get(2L).state()).isEqualTo(NO_SHOP_COMMENT);
        assertThat(http.sent).hasSize(2);
    }

    @Test
    void knownCustomerCommentsAreDistinctFromAnUnknownAuthor() {
        discovery();
        ok("{\"comments\":[" + comment("\"synthetic-buyer\"", "bad-date") + "]}");
        var result = check(Set.of(1L)).get(1L);
        assertThat(result.state()).isEqualTo(NO_SHOP_COMMENT);
        assertThat(result.answeredAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    void anUnknownAuthorIsNotProofOfNoShopAnswer(String author) {
        discovery();
        ok("{\"comments\":[" + comment(author, "2026-11-08T09:00:00+09:00") + "]}");
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
    }

    @Test
    void aShopAnswerWithNoParseableTimeIsNotChecked() {
        discovery();
        ok("{\"comments\":[" + comment("\"test-shop\"", "2026-11-08 09:00:00") + "]}");
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
    }

    @Test
    void anotherArticlesCommentCannotAnswerThisOne() {
        discovery();
        ok("{\"comments\":[" + comment("\"test-shop\"", "2026-11-08T09:00:00+09:00")
                .replace("\"article_no\":1", "\"article_no\":9") + "]}");
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"articles\":null}", "{\"articles\":[{}]}",
            "{\"articles\":[{\"article_no\":0}]}", "broken-json"})
    void malformedDiscoveryCannotProveAbsence(String body) {
        ok(body);
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
        assertThat(http.sent).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void discoveryFailureLeavesEveryCandidateUnchecked(int status) {
        http.enqueue(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        var checks = check(Set.of(1L, 2L));
        assertThat(checks).hasSize(2);
        assertThat(checks.values()).allSatisfy(c -> assertThat(c.state()).isEqualTo(NOT_CHECKED));
        assertThat(http.sent).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"comments\":null}", "{\"comments\":[null]}", "broken-json"})
    void malformedCommentsCannotProveAbsence(String body) {
        discovery();
        ok(body);
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void unavailableCommentsDoNotMeanNoComments(int status) {
        discovery();
        http.enqueue(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
    }

    @Test
    void rateLimitingStopsTheRemainingReadsAndKeepsEarlierEvidence() {
        ok("{\"articles\":[{\"article_no\":1},{\"article_no\":2},{\"article_no\":3}]}");
        ok("{\"comments\":[" + comment("\"test-shop\"", "2026-11-08T09:00:00+09:00") + "]}");
        http.enqueue(FakeCafe24HttpClient.rateLimited429("1"));
        var checks = check(Set.of(1L, 2L, 3L, 4L));
        assertThat(checks.get(1L).state()).isEqualTo(SHOP_ANSWERED);
        assertThat(checks.get(2L).state()).isEqualTo(NOT_CHECKED);
        assertThat(checks.get(3L).state()).isEqualTo(NOT_CHECKED);
        assertThat(checks.get(4L).state()).isEqualTo(NO_SHOP_COMMENT);
        assertThat(http.sent).hasSize(3);
    }

    private Map<Long, Cafe24InquiryAnswerObserver.CommentCheck> secondReadAnswers(Cafe24HttpClient.Response second) {
        ok("{\"articles\":[{\"article_no\":1},{\"article_no\":2},{\"article_no\":3}]}");
        ok("{\"comments\":[]}");
        http.enqueue(second);
        ok("{\"comments\":[]}");
        return check(Set.of(1L, 2L, 3L));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedTokenOnACommentReadIsTheAccountsAndNoFurtherReadGoesOut(int status) {
        var checks = secondReadAnswers(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        assertThat(checks.get(1L).state()).as("already read, kept").isEqualTo(NO_SHOP_COMMENT);
        assertThat(checks.get(2L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.AUTH_REJECTED);
        assertThat(checks.get(3L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.AUTH_REJECTED);
        assertThat(http.sent).as("discovery and two reads; the third is never sent").hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void anOutageOnACommentReadEndsTheReads(int status) {
        var checks = secondReadAnswers(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        assertThat(checks.get(3L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.CHANNEL_UNAVAILABLE);
        assertThat(http.sent).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 418})
    void aCommentReadStatusOfUnknownMeaningEndsTheReads(int status) {
        var checks = secondReadAnswers(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        assertThat(checks.get(2L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.UNEXPECTED);
        assertThat(checks.get(3L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.UNEXPECTED);
        assertThat(http.sent).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void oneArticlesOwnReadFailureIsSkippedAndTheReadsGoOn(int status) {
        var checks = secondReadAnswers(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        assertThat(checks.get(2L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.READ_FAILED);
        assertThat(checks.get(3L).state()).isEqualTo(NO_SHOP_COMMENT);
        assertThat(http.sent).hasSize(4);
    }

    @Test
    void anUnreadableBodyIsThatArticlesAndTheReadsGoOn() {
        var checks = secondReadAnswers(new Cafe24HttpClient.Response(200, "broken-json", Map.of()));
        assertThat(checks.get(2L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.READ_FAILED);
        assertThat(checks.get(3L).state()).isEqualTo(NO_SHOP_COMMENT);
    }

    @Test
    void aFailureThatCarriesNoKindIsNeverTheArticles() {
        assertThat(Cafe24InquiryAnswerObserver.commentReadFailure(new IllegalStateException("합성")))
                .isEqualTo(Cafe24InquiryAnswerObserver.Reason.UNEXPECTED);
        assertThat(Cafe24InquiryAnswerObserver.commentReadFailure(
                new java.io.UncheckedIOException(new java.io.IOException("합성"))))
                .isEqualTo(Cafe24InquiryAnswerObserver.Reason.UNEXPECTED);
        assertThat(Cafe24AnswerRecheckRunner.causeOf(Cafe24InquiryAnswerObserver.Reason.UNEXPECTED))
                .isEqualTo(Cafe24AnswerRecheckRunner.Cause.ACCOUNT);
        for (Cafe24InquiryAnswerObserver.Reason r : Cafe24InquiryAnswerObserver.Reason.values()) {
            boolean article = r == Cafe24InquiryAnswerObserver.Reason.READ_FAILED
                    || r == Cafe24InquiryAnswerObserver.Reason.UNPROVEN;
            assertThat(Cafe24AnswerRecheckRunner.causeOf(r)).as(r.name())
                    .isEqualTo(article ? Cafe24AnswerRecheckRunner.Cause.ARTICLE : Cafe24AnswerRecheckRunner.Cause.ACCOUNT);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedTokenOnTheDiscoveryIsNamedAsSuch(int status) {
        http.enqueue(new Cafe24HttpClient.Response(status, "{}", Map.of()));
        assertThat(check(Set.of(1L)).get(1L).reason()).isEqualTo(Cafe24InquiryAnswerObserver.Reason.AUTH_REJECTED);
    }

    @Test
    void discoveryAtItsLimitDoesNotProveAnAbsentCandidateHasNoComments() {
        String rows = LongStream.rangeClosed(2, 101).mapToObj(n -> "{\"article_no\":" + n + "}")
                .collect(Collectors.joining(","));
        ok("{\"articles\":[" + rows + "]}");
        assertThat(check(Set.of(1L)).get(1L).state()).isEqualTo(NOT_CHECKED);
        assertThat(http.sent).hasSize(1);
    }

    @Test
    void hittingTheReadLimitLeavesUnvisitedArticlesUnchecked() {
        String rows = LongStream.rangeClosed(1, 21).mapToObj(n -> "{\"article_no\":" + n + "}")
                .collect(Collectors.joining(","));
        ok("{\"articles\":[" + rows + "]}");
        for (int i = 0; i < 20; i++) {
            ok("{\"comments\":[]}");
        }
        var checks = check(LongStream.rangeClosed(1, 21).boxed().collect(Collectors.toSet()));
        assertThat(checks.get(20L).state()).isEqualTo(NO_SHOP_COMMENT);
        assertThat(checks.get(21L).state()).isEqualTo(NOT_CHECKED);
        assertThat(http.sent).hasSize(21);
    }

    @Test
    void exactChecksReportFailureButLegacyHistoricalRunsStillRefuseIt() {
        http.enqueue(FakeCafe24HttpClient.rateLimited429("1"));
        assertThat(observer.observeExactChecks("token", "test-shop", 6, List.of(1L))
                .get(1L).state()).isEqualTo(NOT_CHECKED);
        http.enqueue(FakeCafe24HttpClient.rateLimited429("1"));
        assertThatThrownBy(() -> observer.observeExact("token", "test-shop", 6, List.of(1L)))
                .isInstanceOf(Cafe24RateLimitedException.class);
    }

    @Test
    void exactChecksNameEveryCandidateAndPreserveTheApprovedCeiling() {
        ok("{\"articles\":[]}");
        assertThat(observer.observeExactChecks("token", "test-shop", 6, List.of(1L, 2L)).values())
                .allSatisfy(c -> assertThat(c.state()).isEqualTo(NO_SHOP_COMMENT));
        assertThatThrownBy(() -> observer.observeExactChecks("token", "test-shop", 6,
                LongStream.rangeClosed(1, 26).boxed().toList())).isInstanceOf(IllegalArgumentException.class);
        assertThat(http.sent).hasSize(1);
    }
}
