package com.sellerops.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.common.SafePreviewResult.PreviewStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Channel markup leaves at one boundary, and that boundary is {@link VocPreviewSanitizer}.</b>
 *
 * <p>It used to be each caller's job and half of them forgot: seven of the fourteen places that
 * sanitized a VOC body wrapped it in {@link MarkupText} first and seven did not. The seven that did
 * not are where a seller saw markup — {@code /api/reviews/record} ended a Cafe24 row on
 * {@code <span style='color:}, because {@code sanitize} truncates to 60 characters LAST and sixty
 * characters of an HTML body is sixty characters of attributes. Once the closing {@code >} had been
 * thrown away nothing downstream could repair it: the frontend's defensive {@code lib/plainText.ts}
 * matches {@code <[^>]*>} and an unterminated tag is not that.
 *
 * <p>So these tests fix FOUR things, and the fourth is the one that makes the other three durable:
 * markup is gone from both entry points, the 60-character cut is measured in characters a person
 * wrote, the redaction contract is untouched by any of it, and no caller may re-wrap.
 *
 * <p>All inputs are SYNTHETIC — shaped like the measured rows, carrying no real customer text.
 */
class VocMarkupBoundaryTest {

    /** A Cafe24 board row: the sentence, the editor's break tags, and a trailing styled footer. */
    private static final String CAFE24 =
            "큰 컵이 안들어가 아쉬워요, 둘레가 넓은게 따로 있나요?<br/><br/>"
                    + "<span style='color:#999999;'>(상품 문의 게시판에서 옮겨 적음)</span>";

    /** A NAVER row: no tags, but the editor's entities where the customer typed punctuation. */
    private static final String NAVER_ENTITIES =
            "상세 페이지에 &ldquo;두 개씩 포장&rdquo; 이라고 적혀 있었는데 한 개만 왔어요 &amp;&nbsp;연락이 안 됩니다&hellip;";

    @Test
    @DisplayName("a Cafe24 body comes out of the backend as the sentence a person typed")
    void cafe24BodyArrivesAsPlainText() {
        SafePreviewResult preview = VocPreviewSanitizer.sanitize(CAFE24);
        RedactedBody full = VocPreviewSanitizer.redactFullBody(CAFE24);

        assertThat(preview.text()).startsWith("큰 컵이 안들어가 아쉬워요");
        assertThat(full.text()).startsWith("큰 컵이 안들어가 아쉬워요");
        for (String leak : List.of("<", ">", "span", "style", "color", "br/")) {
            assertThat(preview.text()).as("preview must not carry %s", leak).doesNotContain(leak);
            assertThat(full.text()).as("full body must not carry %s", leak).doesNotContain(leak);
        }
        // The footer is text the board wrote, so it survives — only its wrapper is gone.
        assertThat(full.text()).contains("상품 문의 게시판에서 옮겨 적음");
    }

    @Test
    @DisplayName("entities decode here, so the screen and the model read the same letters")
    void entitiesDecodeOnTheWayOut() {
        String text = VocPreviewSanitizer.redactFullBody(NAVER_ENTITIES).text();
        assertThat(text).contains("“두 개씩 포장”").contains("&").contains("…");
        assertThat(text).doesNotContain("&ldquo;").doesNotContain("&rdquo;")
                .doesNotContain("&amp;").doesNotContain("&nbsp;").doesNotContain("&hellip;");
    }

    /**
     * The exact shape of the defect: a tag that straddles the sixtieth RAW character. Before the
     * boundary moved, the preview was the first sixty characters of that string and ended inside the
     * attribute. Now the sixty characters are counted after the markup is gone, so a cut can no
     * longer land inside a tag — there are no tags left to land in.
     */
    @Test
    @DisplayName("the 60-character cut can no longer land inside a tag")
    void theCutNeverLandsInsideATag() {
        String raw = "접착력이 약해서 자꾸 떨어집니다 "
                + "<span style='color:#999999;font-size:11px'>보조 테이프를 따로 붙여서 쓰고 있어요</span> "
                + "다음에는 더 신경 써 주세요 정말 아쉽습니다 그래도 배송은 빨랐습니다";

        String preview = VocPreviewSanitizer.sanitize(raw).text();

        assertThat(preview).doesNotContain("<").doesNotContain("style").doesNotContain("font-size");
        assertThat(preview).startsWith("접착력이 약해서 자꾸 떨어집니다");
    }

    @Test
    @DisplayName("60 characters means 60 characters a person wrote, not 60 of markup")
    void truncationCountsPlainCharactersNotMarkup() {
        String shortSentence = "버튼 누르면 컵이 두 개씩 나옵니다";
        String buried = "<div class=\"review-body\" data-seq=\"8821\"><p style='margin:0;padding:0'>"
                + shortSentence + "</p></div>";

        SafePreviewResult r = VocPreviewSanitizer.sanitize(buried);

        // The whole sentence, and no ellipsis: its plain length is far under the limit even though
        // the raw string is three times as long.
        assertThat(buried.length()).isGreaterThan(shortSentence.length() * 3);
        assertThat(r.text()).isEqualTo(shortSentence);
        assertThat(r.text()).doesNotContain("…");

        // And the limit still bites on plain text that really is longer than it.
        String longSentence = "가".repeat(70);
        assertThat(VocPreviewSanitizer.sanitize("<p>" + longSentence + "</p>").text())
                .isEqualTo("가".repeat(60) + "…");
    }

    @Test
    @DisplayName("both entry points cross the same boundary")
    void bothEntryPointsStripMarkup() {
        for (String raw : List.of(CAFE24, NAVER_ENTITIES,
                "<p>포장이 찢어져서 왔습니다</p><p>교환 가능할까요?</p>",
                "<table border=\"1\"><tr><td>배송이 너무 느립니다</td></tr></table>")) {
            assertThat(VocPreviewSanitizer.sanitize(raw).text())
                    .as("preview of %s", raw).doesNotContain("<").doesNotContain("&#");
            assertThat(VocPreviewSanitizer.redactFullBody(raw).text())
                    .as("full body of %s", raw).doesNotContain("<").doesNotContain("&#");
        }
    }

    @Test
    @DisplayName("a body that was only markup has nothing to show, and says so the fail-closed way")
    void aBodyOfPureMarkupIsSuppressed() {
        String onlyMarkup = "<div class=\"wrap\"><span style='color:#999999;'></span><br/></div>";
        assertThat(VocPreviewSanitizer.sanitize(onlyMarkup).text()).isNull();
        assertThat(VocPreviewSanitizer.redactFullBody(onlyMarkup).text()).isNull();
    }

    // ── the redaction contract is untouched ────────────────────────────────────────────────

    @Test
    @DisplayName("PII inside markup is still redacted")
    void redactionStillFiresThroughMarkup() {
        String raw = "<p>연락처 <b>010-1234-5678</b> 로 주세요</p>";
        SafePreviewResult r = VocPreviewSanitizer.sanitize(raw);
        assertThat(r.status()).isEqualTo(PreviewStatus.REDACTED);
        assertThat(r.text()).contains("[전화번호]").doesNotContain("1234").doesNotContain("5678");
    }

    /**
     * <b>Markup removal is not redaction.</b> If stripping tags counted as redacting, every Cafe24
     * row would report REDACTED and the one signal that tells a seller their customer wrote
     * something sensitive would mean nothing.
     */
    @Test
    @DisplayName("a body that merely carried tags is still SAFE, not REDACTED")
    void markupAloneNeverReportsRedacted() {
        assertThat(VocPreviewSanitizer.sanitize(CAFE24).status()).isEqualTo(PreviewStatus.SAFE);
        assertThat(VocPreviewSanitizer.redactFullBody(CAFE24).redacted()).isFalse();
        assertThat(VocPreviewSanitizer.redactFullBody(NAVER_ENTITIES).redacted()).isFalse();
    }

    @Test
    @DisplayName("plain text behaves exactly as it did before the boundary moved")
    void plainTextIsUnaffected() {
        assertThat(VocPreviewSanitizer.sanitize("배송이 빨라서 좋았어요").text()).isEqualTo("배송이 빨라서 좋았어요");
        assertThat(VocPreviewSanitizer.sanitize("연락처 010-1234-5678 로 주세요").text()).contains("[전화번호]");
        assertThat(VocPreviewSanitizer.redactFullBody("첫 줄\n둘째 줄").text()).isEqualTo("첫 줄\n둘째 줄");
    }

    /**
     * The whole body is the surface the seller reads in order to answer, so it must not inherit the
     * list's throughput bound. {@link MarkupText#SCAN_LIMIT} stays on the preview path.
     */
    @Test
    @DisplayName("the full body is not cut at MarkupText's scan limit")
    void fullBodyIsNotBoundedByTheScanLimit() {
        String longBody = "<p>" + "가".repeat(MarkupText.SCAN_LIMIT + 500) + "</p>";
        assertThat(VocPreviewSanitizer.redactFullBody(longBody).text())
                .hasSize(MarkupText.SCAN_LIMIT + 500);
    }

    // ── and no caller may re-wrap ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("no production source wraps MarkupText inside a VocPreviewSanitizer call")
    void noCallerRewrapsTheBoundary() throws IOException {
        Path main = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String src = Files.readString(p);
                            return src.contains("redactFullBody(MarkupText") || src.contains("sanitize(MarkupText");
                        } catch (IOException e) {
                            throw new IllegalStateException(p.toString(), e);
                        }
                    })
                    .map(p -> main.relativize(p).toString())
                    .sorted()
                    .toList();
            assertThat(offenders)
                    .as("the sanitizer makes text plain itself; wrapping it again is the old split boundary")
                    .isEmpty();
        }
    }
}
