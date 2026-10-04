package com.sellerops.common;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turn a stored body that happens to carry markup into the text a person wrote.
 *
 * <p><b>Why this exists.</b> The first browser render of 문의 showed the Cafe24 backlog as
 * {@code <br /> [ Original Message ] <p>...}, {@code &nbsp;}, and one row whose entire preview was
 * {@code <table border="1" style='width: 1240px; border-width: 0px 1p…}. The board API returns the
 * post exactly as the customer's mail client or the shop's editor wrote it, and the product was
 * cutting the first 60 characters of that — which on an HTML body is 60 characters of attributes.
 * The seller could not read their own queue.
 *
 * <p><b>Display boundary only.</b> The stored body is never rewritten: it is the record of what the
 * customer sent, and a lossy normalisation is not a thing to persist. This runs on the way OUT — the
 * feed snippet, the inquiry detail the seller reads, and the draft payload the model reads. The model
 * matters as much as the screen: a question wrapped in {@code <table>} attributes is a question the
 * model answers badly.
 *
 * <p><b>Every VOC body reaches this through {@link VocPreviewSanitizer} (2026-10-05).</b> Both of
 * that class's entry points call {@link #toPlainText} as their first step, so no caller has to
 * remember to. They used to: of the fourteen places that sanitized a VOC body, seven wrapped it here
 * first and seven did not, and the seven that did not are exactly where markup reached the screen —
 * {@code /api/reviews/record} ended a Cafe24 row on {@code <span style='color:}, because
 * {@code sanitize} truncates to 60 characters LAST, and sixty characters of an HTML body is sixty
 * characters of attributes. Call it directly only for text that is NOT going through the sanitizer:
 * a retrieval query, a draft payload, a knowledge import.
 *
 * <p><b>Not a sanitizer, and not a renderer.</b> It produces plain text that is then escaped by
 * whatever renders it (React escapes by construction; nothing here is ever set as HTML). Entity
 * decoding runs AFTER tag removal, so a body containing {@code &lt;script&gt;} becomes the literal
 * text {@code <script>} — visible characters, never an element.
 *
 * <p><b>That order also hid a defect, and {@link #DOCUMENT_METADATA} is the exception it needs.</b> A
 * preamble that arrives ESCAPED survives the tag pass ({@code &lt;} is not {@code <}) and the decode
 * pass then materializes it. Measured 2026-09-27 on the demo organisation: five Cafe24 inquiries —
 * the two newest among them — carry {@code &lt;meta charset=&quot;utf-8&quot;&gt;} as their first
 * characters, so the seller's screen, the retrieval query and the draft payload all began with
 * {@code <meta charset="utf-8">}. It is removed once more after decoding. <b>Only the named element
 * is</b>: a second generic pass over decoded text would delete a customer's own 「&lt;급함&gt;」 and
 * would break the {@code &lt;script&gt;} sentence above.
 */
public final class MarkupText {

    /**
     * How much of a body is examined. A board post can run to tens of thousands of characters and
     * every caller here wants either a 60-character preview or a bounded read; scanning the whole
     * thing with three regex passes is what made reading 500 rows cost seconds before
     * ({@code InboxService.MASK_WINDOW} carries the same lesson). 4000 is far past any preview and
     * past the readable part of a real inquiry.
     */
    public static final int SCAN_LIMIT = 4000;

    /** Block-ish tags whose end means a line ended — everything else is dropped without a trace. */
    private static final Pattern LINE_BREAKING =
            Pattern.compile("(?i)<\\s*br\\s*/?\\s*>|<\\s*/\\s*(p|div|tr|li|h[1-6]|blockquote|table)\\s*>");

    /** Any remaining tag, including an unterminated one at the scan boundary. */
    private static final Pattern TAG = Pattern.compile("<[^>]*>|<[^>]*$");

    /**
     * Document-preamble elements, re-checked after entity decoding.
     *
     * <p><b>A closed list of names that grows only on observation.</b> Measured across every stored
     * inquiry and review body in this deployment, the only escaped tag name present at all is
     * {@code meta} (five inquiries), and no body uses {@code &lt;} as a literal less-than. Adding
     * {@code html}/{@code head}/{@code style} because the same editor paste could produce them would
     * be inventing data; when one appears it is one word here and a fixture beside it.
     *
     * <p>{@code \b} is load-bearing: {@code <metallic>} is a word a customer could write, and this
     * pattern must not touch it. The frontend's {@code lib/plainText.ts} states the same list for the
     * same reason — the two ladders are different endpoints, so they share the rule, not the code.
     */
    private static final Pattern DOCUMENT_METADATA = Pattern.compile("(?i)<\\s*/?\\s*meta\\b[^>]*>");

    /**
     * The named entities a Korean commerce board actually emits. Unknown names are left alone.
     *
     * <p><b>Measured, not guessed.</b> Counted across every stored review and inquiry body/title in
     * this deployment on 2026-10-05: {@code gt} 766, {@code nbsp} 165, {@code quot} 10, {@code lt} 6,
     * {@code hellip} 5, {@code amp} 2, {@code ldquo} 1, {@code rdquo} 1, and no numeric entity at
     * all. The last three were missing while this list was only reached by half the callers and the
     * frontend's {@code lib/plainText.ts} decoded them on the way to the screen — so a seller saw
     * {@code “패키지:} and the model, the retrieval query and the draft all read {@code &ldquo;패키지:}.
     * {@code apos}/{@code #39}/{@code #34} predate the measurement and stay. Names the frontend lists
     * but this deployment has never carried ({@code lsquo}, {@code rsquo}, {@code middot},
     * {@code ndash}, {@code mdash}) are NOT added here: this list grows on observation.
     */
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("nbsp", " "), Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("#39", "'"), Map.entry("#34", "\""),
            Map.entry("hellip", "…"), Map.entry("ldquo", "\u201C"), Map.entry("rdquo", "\u201D"));

    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");

    /** Runs of whitespace — including the newlines the block tags just produced. */
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private MarkupText() {
    }

    /**
     * The plain text of {@code body}, or {@code ""} for null. Line structure survives (block tags
     * become newlines); every other tag disappears; entities become their characters.
     *
     * <p>Bounded by {@link #SCAN_LIMIT}, which is what a preview and a retrieval query want. The
     * surface that must show the WHOLE body uses {@link #toPlainText(String, int)}.
     */
    public static String toPlainText(String body) {
        return toPlainText(body, SCAN_LIMIT);
    }

    /**
     * The same, examining at most {@code scanLimit} characters.
     *
     * <p><b>Why the bound is a parameter and not a constant.</b> {@link #SCAN_LIMIT} exists for
     * throughput — three regex passes over a 30,000-character board post, 500 rows at a time, is what
     * made a list take seconds. {@link VocPreviewSanitizer#redactFullBody} has the opposite shape:
     * one body, which the seller is about to read in full in order to answer it. Cutting that at 4000
     * characters to buy throughput nobody needs would silently truncate the complaint, and
     * {@code redactFullBody}'s whole contract is that it does not truncate. So it passes
     * {@link Integer#MAX_VALUE} and the preview path keeps the bound.
     */
    public static String toPlainText(String body, int scanLimit) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String scanned = body.length() > scanLimit ? body.substring(0, scanLimit) : body;
        String broken = LINE_BREAKING.matcher(scanned).replaceAll("\n");
        String stripped = TAG.matcher(broken).replaceAll(" ");
        String decoded = decodeEntities(stripped);
        // The one thing the decode can create: a preamble that reached us escaped is a tag only now.
        String unwrapped = DOCUMENT_METADATA.matcher(decoded).replaceAll(" ");
        String spaced = SPACES.matcher(unwrapped).replaceAll(" ");
        // Trim each line so " \n " does not leave a line of one space.
        StringBuilder out = new StringBuilder(spaced.length());
        for (String line : spaced.split("\n", -1)) {
            out.append(line.strip()).append('\n');
        }
        return BLANK_LINES.matcher(out.toString()).replaceAll("\n\n").strip();
    }

    /**
     * The single line version — every newline becomes a space. What a list row wants, since a
     * preview that keeps line structure just wastes its 60 characters on emptiness.
     */
    public static String toSingleLine(String body) {
        return toPlainText(body).replace('\n', ' ').replaceAll(" {2,}", " ").strip();
    }

    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher m = ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacementFor(m.group(1), m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replacementFor(String name, String whole) {
        String named = NAMED.get(name.toLowerCase(java.util.Locale.ROOT));
        if (named != null) {
            return named;
        }
        if (name.charAt(0) != '#') {
            return whole; // an entity we did not audit — leave it exactly as written
        }
        try {
            boolean hex = name.charAt(1) == 'x' || name.charAt(1) == 'X';
            int code = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
            // Refuse controls (except tab/newline) and anything outside the BMP-safe range.
            if (code < 0x20 && code != 0x09 && code != 0x0A) {
                return " ";
            }
            return code > 0x10FFFF ? whole : new String(Character.toChars(code));
        } catch (RuntimeException e) {
            return whole;
        }
    }
}
