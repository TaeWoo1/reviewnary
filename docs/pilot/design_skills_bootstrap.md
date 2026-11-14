# Design skills — bootstrap

> **What this is.** How to get the three third-party skills the UI work uses back on a fresh clone, and
> why they are not in the repository. **Design-time tooling only** — nothing this product builds, ships,
> or runs reads any of it. There is no runtime, build, test, or CI dependency on these files.

## Restore them

```
npx skills install
```

`skills-lock.json` (tracked) names each source and pins a content hash, so this fetches the same three
skills into `.agents/skills/` and writes the `.claude/skills/*` symlinks that point at them.

| skill | source | used for |
|---|---|---|
| `shadcn` | `shadcn/ui` | chat/messaging composition rules — read as reference, **0 components imported** |
| `ai-elements` | `vercel/ai-elements` | conversation visual composition — reference only |
| `migrate-radix-to-base` | `shadcn/ui` | arrived with the shadcn install; **not used** (this repo has no Radix) |

Anthropic's official `frontend-design` plugin is installed separately (`claude plugin install`) and is
not in the lockfile.

## Why nothing here is committed

`npx skills add` writes ~1.3MB of fetched markdown into `.agents/skills/` and leaves symlinks under
`.claude/skills/` pointing into it. The markdown is not this repository's source and does not belong in
its diffs, so it is ignored — and the symlinks were committed anyway, which meant a fresh clone got
three files that **exist and resolve to nothing**. A dangling link reads as breakage; an absent one plus
one documented command reads as a step. Both are ignored now and the lockfile is the only tracked trace.

## What these skills may and may not do to this codebase

Reference, not runtime. The registries these skills describe target **Tailwind v4** utilities and
`radix-ui`; this frontend is **Tailwind 3.4** with no Radix, no `components.json`, no `cva` and no
`cn()`, so a pasted registry component compiles to nothing visible rather than failing loudly. The
design contract in `docs/frontend/reviewnary_design.md` and the visual system in
`docs/frontend/reviewnary_visual_system_v1.md` remain the authority for what may enter `frontend/`; a colour,
typeface, or component library that is not in them is not authorised by a skill suggesting it.

## Second set — copied by hand (2026-10-27)

Product-owner decision 2026-10-27: UI design and layout go through design skills, not by hand, and the
skills may also re-set the design rules. So the closing paragraph above no longer freezes the contract:
the contract will be **revised** through that work, and a change still lands in
`docs/frontend/reviewnary_design.md` before it lands in `frontend/`.

These five are copied, not installed with `npx skills add`. One of them is edited (below), and a lockfile
hash would pin the unedited upstream. They live in `.claude/skills/<name>/` and are ignored. To restore
them, clone each source and copy the folder:

| skill | source (commit) | folder copied | edit |
|---|---|---|---|
| `hallmark` | `Nutlope/hallmark` (`7ec8682`) | `skills/hallmark` | none. `scripts/imagegen.mjs` calls Together AI and needs a key, so it is not used. `install-hook.mjs` is not run |
| `design-review` | `Superfuture/design-review` (`d4d2609`) | `design-review/skills/design-review` | **§0 usage ping and §5 Pro mode removed**: the first posts an install id to an outside metrics endpoint, the second uploads the reviewed code to an outside server |
| `accessibility` | `addyosmani/web-quality-skills` (`afa8da9`) | `skills/accessibility` | none |
| `apple-design` | `emilkowalski/skills` (`2d22863`) | `skills/apple-design/SKILL.md` | none |
| `beautiful-shadows` | `MengTo/Skills` (`a45892d`) | `agent-skills/web-design/beautiful-shadows/SKILL.md` | none (demo folder left out) |

Source of the list: Hallmark plus a Threads post of design skills that the owner pointed to. The post's
fifth entry, `frontend-design`, is the Anthropic plugin above.

## Demo FE preparation — reference selection (2026-11-09, design only)

Owner direction: improve FE before the measured demo; Codex prepares the design and handoff, Claude implements it. No skill was installed or run for this review. Existing theme, dependencies and skill copies were not changed.

Owner sequencing correction on the same date: do not begin with FE. Establish the demo's target work and efficiency measures, audit the current functional path, and resolve demo-blocking functional/server/quality gaps before screen refinement. The reference selection below is deferred guidance, not an instruction to install tools or start mockups. Overall order lives in [demo validation §7.7](demo_seller_validation_v1.md#77-다음-작업-순서와-멈출-지점).

Later owner direction on the same date: use existing interview requirements and proceed without waiting for the Nanuri employee interview. Essential functional repairs and dashboard UX mockup design now proceed together under [demo validation §7.10](demo_seller_validation_v1.md#710-인터뷰와-병행하는-기능-보완과-홈-대시보드-ux-2026-11-09-후속-결정). Product FE implementation still follows the concrete mockup checkpoint; current runtime semantics and truthful counts are preserved. The references below are available for this scoped design work.

Local source supplied by the owner: an owner-supplied local folder (outside the repository). Reviewed slides 01–14 and video contact sheets 04/05/08. These are a tool roundup, not a Reviewnary screen specification or verified performance/cost claims.

| Reference | Selection for this batch | Application boundary |
|---|---|---|
| slide 08 — Impeccable | Primary refinement approach: audit/critique → distill/clarify → adapt/harden → polish | Apply to named work surfaces, not an automatic whole-app redesign. Treat existing product/design documents as authority; do not generate competing product contracts |
| slide 04 — Hallmark | Reuse the established Coral direction and browser review | Coral is already in `docs/frontend/reviewnary_design.md` and tokens. Do not deal a new theme or restyle every page. Existing bootstrap records a copied revision, not proof that it is available in every Claude environment |
| slide 05 — Taste Skill | Secondary reference for consistent visual direction and checking the real rendered result | Do not adopt the promotional-page photographs, huge headings, animation or random style selection for work screens. Use only if it resolves a named gap in the existing approach |
| slide 03 — video creation | Later, if a recorded product introduction becomes necessary | Not needed to implement or validate the work screens |
| slides 06/07/09/10/11/12 | Outside this FE batch | No model router, reverse engineering, voice tool, remote coding application or broad security-tool installation is introduced by this reference review |

Primary sources checked on 2026-11-09: [Impeccable repository](https://github.com/pbakaus/impeccable), [Hallmark repository](https://github.com/Nutlope/hallmark), [Taste Skill repository](https://github.com/Leonxlnx/taste-skill). Their READMEs describe design-agent tooling; they do not establish a quality guarantee for this application. Versions and commands may differ from the saved slides and this repo's older copied skills.

Claude should inspect existing skills first. If additional tooling is needed, choose the smallest suitable set and record its source/revision. Do not bulk-install the roundup or replace the locally edited `design-review` with upstream telemetry/upload behaviour. Product runtime dependencies, Tailwind version and component framework remain unchanged in this batch. Do not run installation hooks, external image-generation scripts or code-upload features merely because a skill suggests them. The main deliverable is improved screens and their observed behaviour, not a new tool stack.

FE scope, mockup checkpoint and implementation handoff are in [demo validation §7.9](demo_seller_validation_v1.md#79-fe-정리-설계와-claude-개발-인계-2026-11-09). This section selects references; it does not change the design contract by itself.

## Tabler selection, Stageshow and Unslop (2026-11-11, design preparation)

The owner selected **A / Tabler** as the preferred visual reference and asked to use `stageshow` and `unslop`, with familiar seller-centre screens as additional references. The previous Coral-first refinement and Linear-list recommendation are not the fixed direction for the next mockup. The selected reference informs the proposed design; concrete token and component changes still belong in the reviewed mockup and design contract before product implementation.

Codex read the following primary README and SKILL documents in full. This was document review and workflow planning, **not installation, script execution, a completed redesign, or a completed skill audit**.

| Tool | Verified source and revision | Use in this work | Boundary |
|---|---|---|---|
| Stageshow 1.1.1 | [Benji Taylor, `benjitaylor/skills/stageshow`](https://github.com/benjitaylor/skills/tree/299140fafd08df55a9c234e610c3f64af3f45531/stageshow), `299140f` | Product composition and, when needed, an isolated visual/interaction preview; judge excess detail, empty wireframes, flat hierarchy and bland rendering | This is an authored product-illustration and cursor-story kit. Scripted clicks are scene changes, not functioning application controls. Do not replace actual app UI with an illustration or use its screenshots as evidence that approval/registration works. Its crop/filler/3–7-string recommendations are for illustrations, not rules to hide live work, warnings or counts |
| Unslop | [Damir Sokolovsky, `heydamir/unslop`](https://github.com/heydamir/unslop/tree/97daa3eb365670ea3f87ed85356cbfcee18105a5), `97daa3e` | Write a product-specific direction, then inspect spacing, colour, icons, copy, type, layout, detail, visuals and motion against actual rendered screens | Preserve features and truthful data. The owner's Tabler choice and dashboard goal take priority over generic advice to preserve the old section order or ban every sparkline. No automatic image generation, storytelling video or global restyling |

Stageshow's included code is MIT; third-party names/assets have a separate [asset notice](https://github.com/benjitaylor/skills/blob/299140fafd08df55a9c234e610c3f64af3f45531/stageshow/NOTICE.md). Unslop is CC BY 4.0: **Unslop by Damir Sokolovsky (@heydamir)**; preserve attribution in any copied/adapted material. The original documents, licences, revisions and checksums are saved at `/private/tmp/reviewnary-tabler-sellercenter-20261111/sources/` and the parent folder. No product dependency or installed-skill state changed. The referenced `references/` documents must be read from the same pinned source before using the relevant production steps; they were not all read in this capability review.

For the next isolated mockup, explicitly record which Stageshow example/reference was used and which Unslop findings changed the result. Installing or naming the tools alone is not an acceptance result. Claude reads the pinned sources for implementation preparation; Codex keeps the design/review role. Current owner decisions and the accepted screen remain authoritative when tool advice conflicts.

## D2 method applied to the Home candidate (2026-11-11)

The owner supplied [NAVER D2 — AI 스킬 만들기 10계명: Figma→Code 자동화 실전기](https://d2.naver.com/helloworld/8562180) and requested a concrete mockup. The article and linked [`naver/design-to-ui` README](https://github.com/naver/design-to-ui/tree/452f6d14) were checked. That plugin currently targets Android/iOS, not React; it was **not installed or executed**, and no Figma source/MCP comparison was performed.

For the isolated web mockup, apply the method's project-specific rules, token mapping and rendered comparisons: one editable candidate, root-scoped product tokens, a component/data mapping note, source fingerprints and viewport captures. A candidate is not an approved design contract. After owner selection, revise the existing design contract and use a short project-specific skill as a router to it; do not freeze unapproved colours or duplicate the contract into a long competing skill.

Source references additionally read for this mockup: Stageshow `references/craft.md` at `299140f`; Unslop `references/signs.md` and `references/tokens.md` at `97daa3e`. Applied craft checks: realistic product hierarchy, one accent, a single icon family, no filler imagery or fake logos. Applied Unslop checks: remove AI-process headings, greetings and unsupported trend claims; align counts/rows; use action-oriented copy and bounded previews. **Unslop by Damir Sokolovsky (@heydamir), CC BY 4.0.** Neither an installed Stageshow React/cursor-story run nor a complete automated Unslop audit is claimed. This is a still dashboard design with local preview interactions.

Candidate scope, API gaps and the implementation checkpoint are recorded in [demo validation §7.24](demo_seller_validation_v1.md#724-tabler-방향의-새-홈-후보-시안과-d2-방법-적용-2026-11-11). No runtime dependencies changed.

## Actual task-scoped execution (2026-11-11)

The owner rejected the reference-only candidate and requested direct execution. The pinned Stageshow and Unslop skill folders were installed with the official skill-installer helper into `/private/tmp/reviewnary-design-tool-run-20261111/skills/`. Dependencies were installed in the isolated Stageshow folder with lifecycle scripts disabled. No product dependency, global skill registration or product settings changed.

Stageshow's own Vite playground ran; Aside verified the untouched `table-edit` and `device-pair` examples and playback/Phone controls. Copied `table-edit` before adapting it and used the actual kit in the new React source. Executed official `typecheck`, `check:css`, `film.mjs --still --gate`, and an isolated Vite build. Executed Unslop `snapshot.mjs`, then its audit/direction/nine-pass/check/report workflow; **Unslop by Damir Sokolovsky (@heydamir), CC BY4.0**. No image generation or story video was requested or run.

The original gold still gate passes. The adapted full-app still gate remains **failed**, with3 desktop/5 mobile `uneven gutters` findings from illustration composition checks. Pixel-observed clipping/alignment issues were repaired and independently re-reviewed; this does not turn a failed gate into a passed one. Sources, licences, reports and limits are preserved with the candidate, and [demo validation §7.25](demo_seller_validation_v1.md#725-stageshow-실제-키트-실행과-unslop-절차-적용-2026-11-11) records the design checkpoint. Owner visual acceptance remains pending.
