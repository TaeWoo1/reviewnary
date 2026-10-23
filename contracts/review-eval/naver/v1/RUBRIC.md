# `review-eval/naver/v1` — labeling rubric and go/no-go thresholds

This is the bar a review-analysis detector must clear before it may put anything in front of an
operator. It exists **before** any candidate detector does, deliberately: a threshold agreed after
seeing a result is not a threshold.

## Why this exists at all

`docs/slices/review-classification-queue-v1.md` established that the analyzer's `sentiment` and
`urgency` are pure functions of `rating` (`negative = rating <= 2`), so a 5★ review reading
"배송이 너무 늦었어요" is invisible to the queue. The obvious fix — a polarity-aware keyword pass —
is the approach `aiagent/docs/phase2e_detector_design.md` (2026-04-27) already measured at
**0/30 sample recall, 0/121 records, 12 of 12 attributes never captured**, with the diagnosis that
the failure is surface-form rigidity rather than vocabulary breadth, and a warning that naive
expansion converts 0% recall into unacceptable false positives.

⚠ **Bound that transfer honestly.** That corpus is cosmetics with 12 attributes × 6 polarities — a
harder target than "is this review complaining?" — and a different product. The *architectural*
finding transfers; the specific 0% does not. The conclusion is not "a detector is impossible", it is
that **we cannot currently tell whether one works.** This rubric is how that changes.

---

## 1. The labeling question

For each review, reading **only the review text and its star rating**:

> **Does the seller need to do something about this review?**

Not "is it negative", not "is the customer unhappy" — *is there an action for the seller*. The queue
is a worklist, so the label has to mean what the queue means.

| label | meaning |
|---|---|
| `NEEDS_LOOK` | A seller reading this should respond, investigate, or fix something. |
| `NO_ACTION` | Nothing for the seller to do. Praise, neutral description, or a complaint with no actionable content. |
| `UNCERTAIN` | Honestly unclear. **Use it.** |

`UNCERTAIN` exists so a labeler is never forced into a guess that later hardens into ground truth. It
is **excluded from every metric** and reported separately; a high `UNCERTAIN` rate is itself a finding
about the rubric, not about the detector.

## 2. Tie-breakers

These decide the cases that actually move the metric. Chosen in advance so they cannot be tuned
afterwards to flatter a result.

| case | label | why |
|---|---|---|
| Praise with a concession — "예쁜데 배송이 너무 늦었어요" | `NEEDS_LOOK` | This is the exact class the whole effort exists for. A high rating does not neutralise an actionable complaint. |
| Complaint about the **courier**, not the seller — "택배 기사님이 던지고 갔어요" | `NEEDS_LOOK` | The seller still owns the customer's experience and may want to respond. Reasonable people differ; pinning it here makes labeling consistent rather than correct-by-definition. |
| Complaint the channel **already answered** (`reply_state = ANSWERED`) | label on the TEXT alone | Reply state is the queue's job, not the detector's — `IngestedReviewVocItemSource` already excludes answered rows. Folding it into the label would measure two mechanisms at once. |
| Low rating, no text — "★" with an empty or emoji-only body | `NO_ACTION` | There is nothing to detect. Rating already handles it; crediting a text detector here would inflate recall for free. |
| Product criticism with no request — "생각보다 두꺼워요" | `NO_ACTION` | Useful catalog feedback, not an action. Include as `NEEDS_LOOK` only if it asks for or implies a remedy. |
| Mixed, multi-topic review where any one topic is actionable | `NEEDS_LOOK` | The queue surfaces whole reviews, so any actionable part makes the review actionable. |

## 3. Storage — what may and may not be committed

`labels.json` carries **only** the review-id fingerprint, the label, and the rubric version.

**Not** the body, the raw `리뷰글번호`, the rating, the date, the product, or any seller identity. The
rating in particular is deliberately absent even though the metrics need it: the harness reads it from
the local database at evaluation time, so committing it would add a re-identifying attribute for no
benefit.

⚠ **The fingerprint is leak-hygiene, not anonymity.** `ReviewIdFingerprint` says so itself: a NAVER
`리뷰글번호` is a 10-digit number — an enumerable space — so anyone already holding the id space can
map a fingerprint back to a review. That is acceptable here because a label is an *operator judgment*
("this needs a look"), not customer content, and it is exactly why nothing else may be added
alongside it.

```json
{
  "contract": "review-eval/naver/v1",
  "rubricVersion": "v1",
  "labels": [
    { "reviewIdFingerprint": "<64 hex chars>", "label": "NEEDS_LOOK" }
  ]
}
```

## 4. Seed adequacy

Below this, the numbers are **descriptive, not decisive**, and the harness refuses to print a verdict
and says why:

- **≥ 200** labeled reviews (excluding `UNCERTAIN`),
- **≥ 40** labeled `NEEDS_LOOK`, and
- **≥ 30** labeled `NO_ACTION` reviews rated **4–5★**.

Rationale for the first two: with 30 samples a single flip moves recall by 3+ points, which is how a
detector "passes" on noise.

⚠ The third is not symmetry — it closes a hole found in review. Without it the high-rating
false-positive gate is **vacuous in exactly the case it exists for**: a seed drawn mostly from
low-rated reviews contains few or no happy customers, `0/0` reads as a 0.00 rate, and a detector
clears "we do not flag happy customers" on a sample containing none. At the 0.05 bar one false
positive in 20 is already a failure, so fewer than ~30 such rows cannot separate 0.05 from zero.

## 5. Go / no-go

A candidate detector may be **built** regardless of these numbers. It may not be **surfaced to an
operator** unless it clears all four.

| gate | bar | why this number |
|---|---|---|
| **Precision** | **≥ 0.80** on the *Wilson 95% lower bound* | The headline reads "확인이 필요한 리뷰 N건". Past roughly one-in-five noise, the queue stops being a worklist and becomes something to skim. Gating on the lower bound rather than the point estimate stops a small lucky sample from passing. |
| **Recall** | **≥ 0.30** point estimate | Deliberately low. The comparison is against today's **zero**, and a high-precision partial detector is useful where a noisy complete one is not. |
| **False positives on 4–5★ `NO_ACTION`** | **≤ 0.05** | The specific harm: telling a seller that a happy customer needs handling. Most likely to be wrong, most damaging to trust. |
| **Regression** | `LOW_RATING_REVIEW` counts unchanged | A detector may only ADD. It must not silently redefine the queue the seller already relies on. |

**Precision is the gating metric, not recall** — the product's whole posture is fail-closed and
never-overclaim, and a queue that cries wolf is worse than one that is quietly incomplete.

## 6. Baseline

The first thing the harness produces is the honest `rules-v1` number. Expected from the code rather
than from sentiment: recall on high-rating complaints ≈ 0, since `urgency` is a pure function of
`rating`. The harness must **confirm** that rather than assume it — and that number is what every
future candidate has to beat.

## 7. Owner exception — the safety and legal signal (2026-10-22)

§5 still binds every detector of the §1 question. One narrower signal is surfaced **without** clearing
it, by an explicit product-owner decision, and it is recorded here so the gate is not read as broken
silently.

- **What it is.** `ReviewSafetySignal`: a fixed list of expressions that report someone hurt or made
  ill (화상 · 다쳤 · 배탈 · 두드러기 …), a dangerous product (이물질 · 감전 · 타는 냄새 …) or a legal step
  (소비자원 · 신고했 · 소송 …), with a short negation window (「다칠 걱정 없어요」 · 「이물질 없이」 do not
  count). No model. **A worry is not a negation (2026-10-23):** 「화상 입을까 걱정돼요」 rises; only a
  worry that is itself denied (「화상 걱정 없어요」) does not.
- **What it does.** A review carrying it joins the seller's undecided 확인 필요 list **whatever its
  rating**, is marked **「안전 관련 확인」** (2026-10-23; it was 「안전 문제」 for one day — a term list finds a
  candidate, it does not establish that harm happened), and is listed ahead of the inquiries — interview questions 19–20's
  「매우 심각한 리뷰는 문의 우선의 예외」. It never changes a tier and only ADDS rows (§5's regression gate
  holds).
- **Why without the gate.** The owner was shown, before deciding, that `labels.json` holds no labels
  for this question and that the §5 harm — a happy 4–5★ customer's 「다칠 걱정 없어요」 raised as a
  problem — is the likely error, and chose rating-independent surfacing over (a) applying it only to
  1–2★ reviews and (b) holding it until labelled.
- **Known misses, stated.** The signal does not tell an occurrence (「화상을 입었어요」) from a worry
  (「화상 입을까 걱정돼요」) or a background mention (「알레르기 있는 아이도 잘 써요」, which still rises);
  all three are candidates for the seller. Anything worded outside the list is not seen. Telling them
  apart is the next step — rules find the candidate, a context judgement is added on top, and that
  judgement keeps its uncertainty and is measured like this one.
- **What it reads, stated.** Outside the attention window it scans the 200 most recent undecided
  reviews whose text contains a term's opening (`OperationsHomeService.SAFETY_SCAN`). It therefore never
  supports 「there is no safety problem in this seller's reviews」, and no surface may say so. The
  intended shape is a check when each review arrives plus a separate sequential pass over the backlog.
- **What would end the exception.** A small labelled set of the seller's real reviews for this narrower
  question — prepared as [`contracts/review-eval/safety/v1`](../../safety/v1/RUBRIC.md), labels still empty — — reports, worries, reassurances and unrelated mentions all included — scored separately for
  **missed harm** and **needless alarms** against **bars the owner fixes before the labels are read**.
  Measuring is not enough by itself: meeting those bars ends the exception; missing them leads to a
  narrower operation or a fix, never to switching the signal off for 4–5★ reviews as a whole (the point of
  the signal is that content, not the rating, decides). This set is a separate task from the
  past-reply retrieval measurement, which says nothing about this signal. Until it is scored the
  false-alarm rate on 4–5★ is unknown and must not be claimed.
