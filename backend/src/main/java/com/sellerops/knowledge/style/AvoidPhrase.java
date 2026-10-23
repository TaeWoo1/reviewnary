package com.sellerops.knowledge.style;

/**
 * <b>A phrase this company would rather not use, and what to say instead</b> (owner decision 2026-10-23, the
 * 「되도록 피할 표현」 half of the split). A preference, not a ban: a draft that carries one is kept and may be approved
 * as it is; the seller is shown the phrase and, when there is one, the replacement.
 *
 * @param replacement what to write instead, or null to only mark the phrase
 */
public record AvoidPhrase(String phrase, String replacement) {
}
