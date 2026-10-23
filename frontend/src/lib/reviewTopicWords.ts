/**
 * The remedy nouns a review reply's promise check reads — the backend's `ReviewClaimGuard.COMMITMENT_TERMS`, word for
 * word (`ReviewTopicWordsParityTest` holds the two equal). A line in 「절대 쓰지 않을 표현」 that is exactly one of
 * them is a topic, not a phrasing: the review check leaves it out, and the settings screen says so as it is typed.
 */
export const REVIEW_TOPIC_WORDS = [
  "환불", "반품", "교환", "재발송", "재배송", "보상", "배상", "할인", "쿠폰", "적립금", "회수",
] as const;

/** Exactly a topic word, spacing and case aside — `ReviewStyleGuard.topicWord`. */
export function isReviewTopicWord(phrase: string): boolean {
  const folded = phrase.replace(/\s+/g, "").toLowerCase();
  return (REVIEW_TOPIC_WORDS as readonly string[]).includes(folded);
}
