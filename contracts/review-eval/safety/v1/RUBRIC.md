# review-eval/safety v1 — does the safety signal raise the reviews it should?

The labelled set that decides whether the review safety signal (`ReviewSafetySignal`) keeps its owner exception
(`contracts/review-eval/naver/v1/RUBRIC.md` §7). It is a separate task from the past-reply retrieval measurement,
which says nothing about this signal. Criteria, sets and pilot bars fixed by the owner on 2026-10-23, **before any
label was read**.

## 1. The answer — four labels

For each review, reading **only its text and star rating**:

| label | meaning | example |
|---|---|---|
| `REPORT` | **실제 위험 발생** — it happened: an injury, a bodily reaction, a dangerous product, or a legal step taken or announced. | 「사용하다 화상을 입었어요」 |
| `WORRY` | **구체적인 위험 우려** — a danger the customer foresees from real use, not yet happened. | 「너무 뜨거워서 화상 입을까 걱정돼요」 |
| `REASSURANCE` | **위험 부정 · 안심** — an experience of no danger. | 「뜨겁지 않고 화상 걱정 없어요」 |
| `UNRELATED` | **관련 없음** — background or nothing about harm. | 「알레르기 있는 아이도 잘 써요」 · 「배송이 빨라요」 |
| `UNKNOWN` | **판단 불가** — not enough to tell. Excluded from every rate and reported on its own. | |

`REPORT` and `WORRY` are what the seller should be asked to look at, and the screen tells the two apart. A harm
word alone (「알레르기」) does not make a review `REPORT`. A worry denied in the same breath is `REASSURANCE`. So is a worry the customer says **at once** the product put to rest (「곰팡이 걱정되는데 아주 만족」 —
product-owner decision, 2026-10-27); a worry followed by praise of something else (「화상 걱정되는데 디자인은 만족」) is still
`WORRY`. A legal
word used figuratively (「고소한 맛」) is `UNRELATED`.

## 2. Three sets, three jobs — never mixed

| set | what it holds | measures | never claims |
|---|---|---|---|
| **random** | the first 400 of all NAVER reviews by sample order — the real mix | how often an alarm is needless (precision), the 4–5★ false-alarm rate, alarms per 100 reviews | what is missed (positives are too rare) |
| **challenge** | the first 300 reviews that mention any harm word at all — raised, cancelled by a negation, or a bare noun | what is missed **among reviews that use a word the signal knows** (detection rate), real reports the negation filter cancelled | operating precision · what is missed outside the word list |
| **low-star** (2026-10-23 external review) | the first 200 of all 1–2★ reviews by sample order, **chosen without the signal's word list** | real reports and worries written in words the signal does not know | a rate for 3–5★ reviews · operating precision |

The challenge set is drawn by the signal's own words, so its detection rate can only say what is missed among
reviews that use them: a report written in other words («손잡이에 데었어요») never enters it. The low-star set is the
check from outside: a harm report is usually also a complaint, and the star rating does not depend on the word list.
Together with the random set it gives the **detection rate outside the word list** (§4). It does not reach a 5★
review that reports harm in unknown words — the random set is the only, thin, look at those.

Sample order is `CalibrationSample.sampleOrderKey` of the review-id fingerprint, so every draw is reproducible. A
review drawn into more than one set is labelled once and counts in each by its membership.

## 3. Who labels, and what is kept

1. **The owner labels a first batch** (about 30, mixed from all sets) to set the criteria in practice.
2. **The delegate labels the rest.** The owner then checks every `UNKNOWN`, every case the delegate marks unsure, and
   a sample of the rest (at least one in ten).
3. **No suggested labels.** The sheet has no model-proposed answer; a model's guess is never the truth here.

The labelling sheet (`SafetySignalEvalIT`, sheet run) holds the review text so a person can read it; it is written
**outside the repository**, never committed, and read by no one but the labellers. `labels.json` keeps only the
review-id fingerprint, the label, the labeller's role (`OWNER` · `DELEGATE`) and whether the owner checked it —
never the body, the raw `리뷰글번호`, the rating or the product (`contracts/review-eval/naver/v1/RUBRIC.md` §3).

## 4. Measures — each on its own denominator

| measure | set | numerator / denominator |
|---|---|---|
| **위험 리뷰 탐지율** | challenge | raised `REPORT`+`WORRY` / all `REPORT`+`WORRY` |
| **부정 필터가 지운 실제 신고** | challenge | `REPORT` whose words were cancelled by a negation |
| **경보 정밀도** | random | raised and `REPORT`+`WORRY` / all raised |
| **4–5★ 오탐률** | random | raised / 4–5★ reviews labelled `REASSURANCE` or `UNRELATED` |
| **낱말 목록 밖 탐지율** (reference) | random ∪ low-star | raised `REPORT`+`WORRY` / all `REPORT`+`WORRY` — and how many of the missed ones carry no known word at all |

The 4–5★ rate is a false-positive rate on reviews that are **not** harm — not the share of all reviews that raise an
alarm. If many reviews really report harm, alarms above 5% are correct; few alarms do not mean harm is found. The
report also prints alarms per 100 random reviews and the Wilson 95% interval of precision **for reference**.

## 5. Pilot bars — owner decision 2026-10-23

These are this product's early operating targets, not an industry standard.

| measure | pilot bar | at least, else 「판단 보류」 |
|---|---|---|
| 위험 리뷰 탐지율 | **≥ 0.90** | 30 `REPORT`+`WORRY` in the challenge set |
| 부정 필터가 지운 실제 신고 | **0** in the labelled set — not a promise that none will ever be missed | 30 `REPORT`+`WORRY` in the challenge set |
| 경보 정밀도 | **≥ 0.50** measured value (improvement target 0.70) — one alarm in two may be a needless check | 20 raised reviews in the random set |
| 4–5★ 오탐률 | **≤ 0.05** | 100 labelled non-harm 4–5★ reviews in the random set |

**The detection bar of 0.90 is a bar for reviews that use a word the signal knows** (§2); passing it does not
say that 90% of all harm reviews are found. The detection rate outside the word list has **no bar yet** — it is
printed with its count and the number of missed reports that carry no known word, below 30 `REPORT`+`WORRY` it says
판단 보류, and it is not part of the overall verdict until the owner sets a bar for it.

**"≥ 0.50" is the measured value, reported with its count**, not a confidence bound. Below a measure's minimum count
the run says **판단 보류**, never 통과. Meeting every bar ends the RUBRIC §7 exception for the pilot. Missing one leads
to a narrower operation or a fix to the signal — never to switching it off for 4–5★ reviews as a whole.

## 6. Running it

Database variables as `ReviewTriageEvalIT`: `REVIEW_EVAL_JDBC_URL` · `REVIEW_EVAL_DB_USER` · `REVIEW_EVAL_DB_PASSWORD`.

1. `RUN_SAFETY_SHEET=true SAFETY_SHEET_PATH=<outside the repo>.csv` — writes the sheet: fingerprint, rating, set
   (`RANDOM` · `CHALLENGE` · `LOWSTAR`, joined with `+` for a review in more than one), then empty `label` ·
   `labeller` · `ownerChecked`, then the review.
2. `RUN_SAFETY_IMPORT=true SAFETY_SHEET_PATH=…` — writes `labels.json` from the filled rows.
3. `RUN_SAFETY_EVAL=true` — prints the measures, their counts and verdicts (통과 · 미달 · 판단 보류). Counts and rates only.
