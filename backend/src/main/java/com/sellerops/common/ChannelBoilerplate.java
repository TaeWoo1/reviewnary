package com.sellerops.common;

import java.util.regex.Pattern;

/**
 * Remove the line a CHANNEL appended to a customer's text, so what is left is what the customer
 * wrote.
 *
 * <p><b>Why this is not {@link MarkupText}'s job.</b> Markup is a transport artefact: every channel
 * that sends HTML sends it for the same reason, and one generic pass handles all of them. This is the
 * opposite — a specific sentence a specific shop's importer stamped onto a specific set of rows, in
 * Korean, with that shop's wording. Folding it into {@code MarkupText} would turn a channel-generic
 * function into a list of per-channel strings, and the next shop's footer would be added to the same
 * list until nobody could say what the function does. It runs beside {@code MarkupText}, at the same
 * boundary, under its own name.
 *
 * <p><b>Measured before it was written (2026-10-05, this deployment).</b> Across all 4,839 stored
 * review bodies, exactly one trailing boilerplate shape exists:
 * {@code (YYYY-MM-DD HH:MM:SS 에 등록된 네이버 페이 구매평)}. It appears on 124 bodies, all Cafe24;
 * all 124 carry it at the very END of the plain text, none carries it mid-sentence, none carries it
 * twice, and on none of them is it the whole body. The no-space spelling 「네이버페이」 does not occur.
 * Nothing here is generalised past that: a rule that stripped any trailing parenthetical would delete
 * 「(서울경희직업전문학교)」, which four customers in this deployment typed themselves as the last thing
 * in an inquiry.
 *
 * <p><b>Terminal only, and that is the point.</b> The pattern is anchored with {@code \z}. A customer
 * who writes the same words inside their own sentence keeps them — the string is not what makes this
 * boilerplate; its position at the end of a row the importer wrote is.
 *
 * <p><b>A second shape, for text that was cut and redacted before this class existed (2026-10-06).</b>
 * Rows written before this boundary went through the redactor and an excerpt first, so what survives
 * is the footer with its date masked and its tail cut — on this deployment,
 * {@code ([번호] 12:36:41 에 등록된 네…} inside a 40-character quote of a customer's review. It is not
 * a generalisation of the pattern above but a second observed shape, and it is held to the same
 * evidence: measured 2026-10-06 across every stored review body, inquiry body and knowledge
 * candidate, an unclosed {@code (} that carries a {@code HH:MM:SS} time AND the words 「에 등록된」
 * occurs exactly once — the one stale candidate — and nothing a customer typed matches it. It ends at
 * the text's end or at the closing quote the generated question put after the excerpt, because that
 * is where an excerpt was cut off.
 *
 * <p>Both shapes are removed by the one {@link #strip} call, so no caller has to know which of them a
 * given row carries.
 *
 * <p><b>Fail-closed on emptiness.</b> If stripping would leave nothing, the original comes back.
 * Showing a seller a machine's footer is a blemish; showing them an empty review where a review
 * exists is a lie about their own data.
 *
 * <p>Pure: no DB, no clock, no channel lookup. It is called from {@link VocPreviewSanitizer}, which
 * is the one boundary every VOC body leaves through, so the screen, the retrieval query, the model
 * payload and the draft all read the same sentence.
 */
public final class ChannelBoilerplate {

    /**
     * Cafe24's NaverPay review import footer, exactly as this deployment carries it.
     *
     * <p>The leading {@code \s*} eats the break the editor put in front of it (119 of the 124 rows
     * have no space there, 5 have one, and after {@code MarkupText} the {@code <br/><br/>} has become
     * a newline). {@code \z} — not {@code $} — because Java's {@code $} also matches before a final
     * line terminator, and a footer that is not the last thing is not this footer.
     */
    private static final Pattern NAVER_PAY_IMPORT_FOOTER = Pattern.compile(
            "\\s*\\(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} 에 등록된 네이버 페이 구매평\\)\\s*\\z");

    /**
     * The same footer after the redactor masked its date and an excerpt cut its tail.
     *
     * <p>Three things have to be true at once, and that is what keeps it off a customer's own
     * parenthesis: an opening {@code (} that is never closed before the end, a {@code HH:MM:SS} time
     * inside it, and the words 「에 등록된」 after that time. The end is either the text's end or the
     * quote mark the generated question closes the excerpt with — the two places an excerpt stops.
     */
    private static final Pattern TRUNCATED_IMPORT_FOOTER = Pattern.compile(
            "\\s*\\([^()'\u2018\u2019]*\\d{2}:\\d{2}:\\d{2} 에 등록된[^()'\u2018\u2019]*?(?=['\u2018\u2019]|\\z)");

    private ChannelBoilerplate() {
    }

    /**
     * {@code plain} without the channel's own trailing line. Expects text that markup has already
     * been removed from; returns it unchanged when there is nothing to remove, which is the case for
     * every row of every other channel.
     */
    public static String strip(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String stripped = NAVER_PAY_IMPORT_FOOTER.matcher(plain).replaceFirst("");
        stripped = TRUNCATED_IMPORT_FOOTER.matcher(stripped).replaceFirst("");
        return stripped.isBlank() ? plain : stripped;
    }
}
