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
