# review-eval/safety v1 — does the safety signal raise the reviews it should?

The labelled set that decides whether the review safety signal (`ReviewSafetySignal`) keeps its owner exception
(`contracts/review-eval/naver/v1/RUBRIC.md` §7). It is a separate task from the past-reply retrieval measurement,
which says nothing about this signal.

## 1. The labelling question

For each review, reading **only its text and star rating**:

> **Does this customer tell the seller the product hurt someone, may hurt someone, or that they are taking it
> further?**

| label | meaning | example |
|---|---|---|
| `REPORT` | It happened: an injury, a bodily reaction, a dangerous product, or a legal step taken or announced. | 「사용하다 화상을 입었어요」 |
| `WORRY` | A warning about a danger that has not (yet) happened. | 「너무 뜨거워서 화상 입을까 걱정돼요」 |
| `REASSURANCE` | The danger word is denied or reassured away. | 「뜨겁지 않고 화상 걱정 없어요」 |
| `BACKGROUND` | The word describes the customer, not the product's effect. | 「알레르기 있는 아이도 잘 써요」 |
| `UNRELATED` | Nothing about harm or a legal step. | 「배송이 빨라요」 |
| `UNCERTAIN` | Honestly unclear. **Use it.** Excluded from every metric and reported separately. | |

`REPORT` and `WORRY` are the reviews the signal **should raise**; the other three should not be raised.

Tie-breakers: a worry that is denied in the same breath is `REASSURANCE`. A report about a different product or
a past purchase is still `REPORT`. A legal word used figuratively (「고소한 맛」) is `UNRELATED`.

## 2. The frame and the draw

All NAVER reviews in the local database that carry an id, split by what the signal does with them today:

| stratum | what it holds | drawn |
|---|---|---|
| `RAISED` | the signal raises it | first 150 by sample order |
| `FILTERED` | a term matched, but a negation or reassurance cancelled it | first 80 |
| `OTHER` | no term matched | first 200 |

Sample order is `CalibrationSample.sampleOrderKey` of the review-id fingerprint, so the draw is reproducible and
the labelled set can be re-derived. Strata are sampled at different rates, and every population estimate below
is weighted by stratum size.

## 3. Storage

`labels.json` carries **only** the review-id fingerprint and the label, exactly as
`contracts/review-eval/naver/v1/RUBRIC.md` §3 — never the body, the raw `리뷰글번호`, the rating or the product.
The labelling sheet (`SafetyLabelSheetIT`) holds the review text so a person can read it; it is written **outside
the repository** to a path the labeller names, is never committed, and is not read by anyone but the labeller.
`SafetyLabelImportIT` turns the filled sheet into `labels.json`, keeping the fingerprint and the label only.

## 4. Metrics

Computed by `SafetySignalEvalIT` (counts and rates only on stdout):

- **Missed harm** — weighted recall of `REPORT` + `WORRY`: the share of the reviews that should rise that do.
- **Hidden reports** — `REPORT` labels in the `FILTERED` stratum: real events the negation filter cancelled.
- **Needless alarms** — precision of `RAISED` (share that is `REPORT` or `WORRY`), with its Wilson 95% lower
  bound; and the weighted share of 4–5★ non-harm reviews that the signal raises.

## 5. Bars — **proposed, for the owner to fix before any label is read**

These are a proposal. The owner fixes the numbers **before** the labels are read; changing them afterwards
voids the run. Meeting all of them ends the RUBRIC §7 exception. Missing one leads to a narrower operation or a
fix — never to switching the signal off for 4–5★ reviews as a whole.

| gate | proposed bar | why |
|---|---|---|
| Missed harm | weighted recall **≥ 0.80** | A safety report that does not rise is the costly error. |
| Hidden reports | **0** `REPORT` in `FILTERED` | The negation filter may cancel a reassurance, never an event. |
| Needless alarms (raised) | precision Wilson lower bound **≥ 0.50** | Cheaper than a miss, but a list where most marks are wrong teaches the seller to ignore them. |
| Needless alarms (4–5★) | weighted share **≤ 0.05** | The same ceiling as RUBRIC §5 for a happy customer raised as a problem. |

**Adequacy floor** — no verdict below it: ≥ 30 labelled `REPORT` + `WORRY`, ≥ 100 labelled in `OTHER`, ≥ 60
labelled 4–5★ non-harm. Below the floor the harness reports counts and says it cannot judge.

## 6. Running it

1. `RUN_SAFETY_SHEET=true SAFETY_SHEET_PATH=<outside the repo>.csv` plus the `REVIEW_EVAL_*` database variables:
   writes the sheet. The labeller fills the `label` column.
2. `RUN_SAFETY_IMPORT=true SAFETY_SHEET_PATH=…`: writes `labels.json` (fingerprint + label).
3. `RUN_SAFETY_EVAL=true` plus the database variables: prints the metrics and, above the floor, the verdict
   against the bars as fixed in §5.
