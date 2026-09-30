# Reviewnary UI System — Audit v1

**Status:** 2026-09-30 · `frontend/` only · measured on `1e23487c` (worktree `decision-workspace`) ·
**this document records what was measured and what was decided, not a new design system**

The system already exists: `docs/reviewnary_design.md` v3 fixes typography, spacing, colour, radius,
the primitives and the per-surface hierarchy, with measurements. `components/ui/` holds 15 primitives
built to it. So this audit does not propose a second system — writing one would be the fourth
vocabulary in this frontend. It measures the distance between the contract and the code, and records
the two product-owner decisions taken on 2026-09-30 to close it.

**Why now.** The live pilot screenshots showed a Home whose page title was smaller than its own
section headings. That is not a styling slip; it is what happens when the screens a seller opens
every morning are built beside the system rather than out of it.

---

## 0. Method

Counted across 258 non-test `.tsx` files under `frontend/src`, comments stripped where prose would
otherwise be counted. Every number below is a count, not an impression. No screenshots were
available for this pass, so the information-density findings are measured from source geometry
(paddings, widths, alignment, type sizes) rather than read off an image.

---

## 1. The headline finding

`PageHead` — the primitive that owns the `h1` — is used by **19 of 25** app pages. The six that do
not use it are:

| Page | What it is |
|---|---|
| `pages/app/AgentHome.tsx` | 오늘 — the first screen |
| `components/customerOperations/CustomerOpsHome.tsx` | 오늘, the live variant |
| `pages/app/KnowledgeHome.tsx` | 지식 |
| `pages/app/OperationsCase.tsx` | 확인할 일 detail |
| `pages/app/ReviewRecord.tsx` | 리뷰 record |
| `pages/app/ReviewCollectionFlow.tsx` | 리뷰 수집 |

The contract is not missing and it is not disputed. **It was written, and then the next screens were
built without it.** Every finding below is a consequence of that one fact, which is why the remedy is
enforcement plus three additions rather than a redesign.

---

## 2. One structural level, many sizes

| Level | Contract | Measured |
|---|---|---|
| `h1` page title | `xl` 22/700 | **6 sizes** — 22 (×6) · 26 (×4) · 32 (×1) · 25 (×1) · 18 (×1) · 17 (×1) |
| `h2` section title | `base` 16/600 | **6 sizes** — 18 (×17) · 16 (×13) · 15 (×4) · 17 (×4) · 22 (×1) · 13 (×1) |
| `h3` | `base` 16/600 | 16 (×21) · 15 (×6) · 18 (×6) · 13 (×2) |

Inside `CustomerOpsHome` — one screen:

```
h1  확인할 일 오늘          17px     the page title
h2  확인할 일               15px
h2  자동 확인               13px
h2  실행 대기 / 반복 문제   17px     the same size as the page title
```

`KnowledgeHome` sets its `h1` to 25px `font-extrabold`, a weight the contract does not define.

**Why it matters.** The pass mark in `reviewnary_design.md` §0 is that a seller answers the screen's
one question without reading a paragraph. That is only possible if size carries rank. Here the page
title is *smaller* than two of its own section headings, so size carries nothing and the seller has to
read the words to find the structure.

---

## 3. A queue row has three implementations

| | `WorkItem` (contract §6) | `DecisionRow` `dense` | `DecisionRow` default |
|---|---|---|---|
| Used by | 문의 · 리뷰 · 리포트 · artifacts | **확인할 일 · 오늘** | 지식 받은함 |
| Leading element | **state word** | category tag badge | 40px coloured icon tile |
| Title | 16/600 | 15/600 | 16/700 |
| `selected` | `bg-brand-50/70` | `bg-[#EFF3F9]` | `brand-50` + `inset 3px` bar |
| hover | `bg-canvas` | `bg-[#F7F8FA]` | `bg-[#FAFBFC]` |
| row rule | `border-line` | `#EEF0F3` | `#EEF0F3` |
| padding | 16 × 12 | 10 × 10 | 20 × 16 |
| container | `rounded-2xl` + `border-line` | none (`-mx-1.5`) | `rounded-[14px]` + 1px shadow |

§6 puts the state word first because the question a queue answers is 「내가 뭘 해야 하나」.

**The `dense` row carries neither a state word nor a verb.** Its first element is a category tag. So
확인할 일 — the screen a seller opens every morning, and the one the sidebar's 「일」 group leads with —
tells them what each item *is* and not what to *do* with it. Every other queue in the product tells
them both.

---

## 4. Colour: three tokens, seventeen greys

**73 raw hex occurrences** in class strings across 12 app-surface files. Greys alone:

```
#E4E7EC  #F1F3F5  #EEF0F3  #D5DAE1  #F7F8FA  #C9CFD8  #C4CAD3  #B0B8C1  #8B95A1
#FAFBFC  #F8F9FA  #F4F5F7  #E3E8EF  #DCE0E6  #C9D2DD  #EFF3F9  #F4F7FB
```

The contract has `surface` · `canvas` · `line`. Tints exist too (`warn/10`, `good/10`), yet
`#FFF3E4` · `#FFF8EF` · `#E9F4EC` · `#F5FAF6` · `#CFE3D6` are written beside them.

**11 borders are drawn as `shadow-[0_0_0_1px_#E4E7EC]`** rather than `border border-line`.

This is the physical cause of the density complaint. What a reader notices is uneven whitespace; what
is actually uneven is the **edges** — the same card boundary is drawn at `#E5E8EB`, `#E4E7EC`,
`#EEF0F3` and `#DCE0E6` on different screens, sometimes as a border and sometimes as a shadow, so
identical gaps read as different gaps.

Worst first: `OperationsCase.tsx` 25 · `DecisionRow.tsx` 9 · `CustomerOpsHome.tsx` 7 ·
`WorkFlowCard.tsx` 6 · `KnowledgeHome.tsx` 4.

Tailwind's default palette does **not** leak in anywhere (0 occurrences of `gray-*`/`slate-*`/…) —
that discipline held. The legitimate exception is `SocialSignInButtons`, which must reproduce
Google's and NAVER's own brand marks.

---

## 5. Whitespace: two page rhythms

| | Content width | Alignment | Rhythm |
|---|---|---|---|
| default layout (`AppShellV2`) | `max-w-content` 1120 | **left** | `space-y-6` (24) |
| master-detail (`MasterDetail`) | `max-w-[760px]`, 1160 when closed | **centred** (`mx-auto`) | `space-y-5` (**20 — off-scale**) |

Contract §2 defines one: 1120, left-aligned, because 「a centred column on a wide monitor floats the
page away from the navigation that names it」. Today, moving from 문의 to 상품 changes the body width,
the alignment and the vertical rhythm at once.

Also off the six-step scale (4 · 8 · 12 · 16 · 24 · 32):

- **~100 spacing occurrences**: `gap-1.5` 38 · `space-y-1.5` 16 · `gap-0.5` 13 · `space-y-0.5` 10 ·
  `space-y-5` 9 · `gap-2.5` 5 · `gap-5` 4 · `*-3.5` 4 · `space-y-7` 1
- **17 radii**: `[14px]` ×6 · `[10px]` ×5 · `[16px]` ×4 · `[9px]` · `[11px]`

`Empty` is `px-6 py-10`, dashed, `text-center` — the only centred block in the product, and the
component that most needs a compact reading, since an empty section currently costs ~120px of page.
It is used in 14 files; empty-state copy is hand-written in many more.

---

## 6. Two button systems

`Btn` in 63 files; the legacy `.btn-primary` in 24 and `.btn-ghost` in 21. `.btn-primary` is
`min-h-44 px-5`, `Btn md` is `min-h-40 px-4`, so the CTA on the Agent, tutorial and
guided-connection screens is 4px taller and wider than the same CTA everywhere else.

---

## 7. The contract contradicts itself on the two-pane

- §2 and §7 (문의): `[340px list | flexible detail]`
- §8-A v3.1: `[flexible list (cap 1160) | 440px fixed preview]`
- The code: the second.

A contract that says two things cannot be enforced. Resolved in §10 below.

---

## 8. The mobile IA never followed the Phase 4 IA

`NAV_GROUPS` leads with 「일」 = 확인할 일 · 반복 문제 — what a seller opens daily.
`MOBILE_TAB_ROUTES` is `[/ , /reviews, /inquiries, /orders]`.

**The whole 「일」 group is absent from the mobile tab bar**, while three 「기록」 destinations occupy it.
Recorded, not fixed in this package: the pilot is a desktop product and the enforcement scope below is
deliberately narrow.

---

## 9. 오늘 is two products behind one route

```
coHomeApplies(co)   → TodayWorkspace   list + pane · quiet dock · no numbers · h1 17px
co null / ineligible → legacyLead      greeting → one numbers line → four areas → thread
co undefined         → sr-only h1      nothing
```

The three branches disagree on information order, on what an empty state means, on how the composer
is treated, and on the size of the page title. This is why 「Home을 command center로」 is not a styling
task: the first thing it needs is **one Home**.

---

## 10. Decisions (product owner, 2026-09-30)

### 10.1 Home's top strip carries obligations only

Home's summary is **확인할 일 · 실행 대기 · 미답변** and a channel-freshness line. 매출 · 주문량 · 추이 ·
채널별 KPI stay owned by `/overview`. 반복 문제 is **not** a KPI: it keeps its own section below the
work list, because it is a pattern and not a customer waiting.

§8-A's no-duplication principle stands — 「같은 숫자가 두 정의로 두 곳에 있는 것이 이 저장소가 여러 번
고쳐 온 결함」. This decision satisfies the command-center requirement without reopening that defect.

**The three numbers are a compact summary strip, not three dashboard cards.** A counter card over a
work list is a second pointer to the list; the list is the count.

### 10.2 The two-pane is `[minmax(0,1fr) | 440px]`

One reading, everywhere. §2 and §7 of `reviewnary_design.md` are corrected to match; §8-A already
said this.

A narrow viewport does **not** squeeze the detail — it falls back to single-pane at the existing
responsive breakpoint. `minmax(0,1fr)` rather than `1fr` because a `1fr` track floors at its
content's min-content width, and a long unbroken Korean line would then push the 440px pane off
screen.

### 10.3 `ListRow` is one semantic structure

The operating queue's **leading slot is the state word** — 답변 필요 / 확인 필요 / 준비됨 / 답변 완료.
A category tag is secondary. This is §6 restated, and it is what `DecisionRow` `dense` lost.

### 10.4 Enforcement scope

This package enforces the system on **App shell · 오늘 · 확인할 일 · 문의 · 리뷰 · 연결**.

The raw-hex, spacing and button debt on every *other* screen is **not** cleaned here. Improvements
that follow from changing a shared primitive are in scope and expected; a sweep of
`OperationsCase.tsx`'s 25 hex values is not. Debt left standing on purpose is recorded in §4 above so
the next package does not have to re-measure it.

### 10.5 What enforcement deliberately leaves alone

- **Sub-row optical nudges** (`gap-0.5`, `mt-0.5`, `gap-1.5`). §3's six steps are *layout* steps; 2px
  between a title and the line under it is a baseline adjustment. Rewriting 16 call sites of these
  would be churn with no measured benefit and no screenshot to verify it against.
- **A scroll fade.** `bg-gradient-to-t … to-transparent` over the bottom of a scroller is the
  affordance that says the content continues. The gradient ban is on decoration, so the fence requires
  a gradient to end in `to-transparent` rather than banning the word.
- **`pb-28`**, the mobile tab-bar clearance: a measured gap to a fixed bar, not a spacing step.
- **Third-party brand marks** in `SocialSignInButtons` — Google's and NAVER's buttons must reproduce
  Google's and NAVER's own colours.

The enforcement is carried by `frontend/src/lib/visualSystemFence.test.ts`, whose file list is
append-only: a screen joins it when it is brought onto the system. That list, not this document, is
what stops the debt returning — the contract existed for a month and was not enough.

---

## 11. What was deliberately not concluded

- **No screenshots were available for this pass.** Everything here is source-measured. The visual
  review at 1600×1000 / 1440×900 / 1366×768 over 오늘 / 문의(확인할 일) / 리뷰 / 연결 is a required
  gate on the implementation, not on this audit, and the work is not complete before it.
- **No new colour, font, radius or component library.** `reviewnary_design.md` §11 forbids it and
  nothing measured here needs it.
- **No behaviour or API change.** This is a rendering package.
