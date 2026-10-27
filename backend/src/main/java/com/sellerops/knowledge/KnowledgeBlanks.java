package com.sellerops.knowledge;

/**
 * Text that is not yet the company's word: a 【확인: …】 blank only the owner can fill (owner decision 2026-10-27,
 * 「확인 전 안내는 답글에 쓰지 않게」).
 *
 * <p>A proposal is written with blanks where only the seller knows the fact, and it cannot be accepted while one is
 * left. That gate is the first door; this is the check every later one shares — a passage that still carries a blank
 * is not evidence, a generated reply that carries one is not saved, and a spreadsheet row that carries one is filed as
 * a proposal rather than as knowledge. The opening bracket alone is the mark, so 「【확인】」 typed without the colon is
 * caught as well.
 */
public final class KnowledgeBlanks {

    /** The mark. {@code KnowledgeCandidateService.BLANK_OPEN} is this with its colon. */
    public static final String MARK = "【확인";

    private KnowledgeBlanks() {
    }

    /** Whether {@code text} still holds a blank. */
    public static boolean carries(String text) {
        return text != null && text.contains(MARK);
    }
}
