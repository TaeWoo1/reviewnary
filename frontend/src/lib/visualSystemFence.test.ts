import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * <b>The visual system, asserted on the source — because the last three times it was only written down,
 * the next screens were built beside it.</b>
 *
 * <p>`docs/reviewnary_design.md` v3 has fixed the palette, the spacing scale, the radii and the type
 * scale since 2026-09-01, with measurements. It was never enforced, and by 2026-09-30 the app surface
 * carried 73 raw hex values, 17 greys where the contract has three, 11 card borders drawn as
 * `shadow-[0_0_0_1px_…]`, six sizes of `h1` and six of `h2`
 * (`docs/ui/reviewnary_ui_system_audit_v1.md` §2, §4, §5). None of that is a styling slip: it is what a
 * contract with no test becomes.
 *
 * <p><b>Scope is the enforcement scope, and that is deliberate</b> (audit §10.4). The debt on the other
 * screens is real and recorded; sweeping it in the same package would make one commit that touches
 * every screen and proves nothing about any of them. A file joins this list when its screen is brought
 * onto the system — the list only grows.
 *
 * <p><b>What is NOT banned.</b> Sub-row optical nudges (`gap-0.5`, `mt-0.5`, `gap-1.5`) stay: §3's six
 * steps are LAYOUT steps, and 2px between a title and the line under it is a baseline adjustment, not a
 * layout decision. Banning them would be a rewrite of 16 call sites with no measured benefit and no
 * screenshot to verify it against. The third-party brand marks in `SocialSignInButtons` are out of scope
 * for the same honest reason: Google's and NAVER's buttons must reproduce Google's and NAVER's colours.
 */

/**
 * The screens and primitives brought onto the system by UI System v2. Append-only.
 *
 * <p>`customerOperations/CustomerOpsHome.tsx` joined in the commit that rebuilt the Home — its remaining
 * literals were its heading scale, and normalising them in the enforcement commit would have been half of
 * the Home rework done in the wrong commit.
 */
const ON_THE_SYSTEM = [
  "components/app/AppShellV2.tsx",
  "components/app/SideNav.tsx",
  "components/app/AppTopBar.tsx",
  "components/workspace/MasterDetail.tsx",
  "components/workspace/CaseLayout.tsx",
  "components/ui/Empty.tsx",
  "components/ui/WorkItem.tsx",
  "components/ui/DecisionRow.tsx",
  "components/ui/WorkFlowCard.tsx",
  "components/customerOperations/CustomerOpsHome.tsx",
  "pages/app/AgentHome.tsx",
  "components/home/OperationsAreas.tsx",
  "components/home/RepeatedProblemList.tsx",
  "components/home/PreparedWorkList.tsx",
  "components/memory/IssueList.tsx",
  "pages/app/OperationsCaseQueue.tsx",
  // 리뷰, since its canonical redesign (2026-10-03): the same list reading and the same decision pane.
  "pages/app/ReviewRecord.tsx",
  "components/reviews/ReviewReadDetail.tsx",
  "pages/app/CustomerInbox.tsx",
  "components/inbox/InboxDetail.tsx",
  "pages/app/ConnectHub.tsx",
  "components/connect/ChannelList.tsx",
  "components/connect/ChannelStatusSection.tsx",
  "components/connect/CollectionSettingsSection.tsx",
  "components/connect/CollectionHistorySection.tsx",
  "components/connect/FirstSourceSummary.tsx",
  "components/connect/HelperStatusCard.tsx",
  "components/connect/coupang/CapabilityCard.tsx",
  "components/BackfillPanel.tsx",
];

/** One file with its comments removed — the ban is on USING these values, not on explaining them. */
function code(rel: string): string {
  const text = readFileSync(resolve(__dirname, "..", rel), "utf8");
  return text.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/^[ \t]*\/\/.*$/gm, " ");
}

describe("the palette is the palette", () => {
  it.each(ON_THE_SYSTEM)("%s writes no raw hex colour", (rel) => {
    // `surface`/`canvas`/`line`/`ink`/`muted` and the four tones with their `/10` tints say everything
    // these files were spelling out. A literal is how the same card edge came to be drawn at #E5E8EB,
    // #E4E7EC, #EEF0F3 and #DCE0E6 on four screens, which is why identical gaps read as different gaps.
    expect(code(rel)).not.toMatch(/#[0-9A-Fa-f]{3,8}\b/);
  });

  it.each(ON_THE_SYSTEM)("%s draws a card edge as a border, not as a 1px shadow", (rel) => {
    // §4: a resting surface has an edge and no elevation. A `0 0 0 1px` shadow is a border that does not
    // participate in layout, so a card drawn with one is 2px narrower than the card beside it.
    expect(code(rel)).not.toMatch(/shadow-\[0_0_0_1(\.\d+)?px/);
  });

  it.each(ON_THE_SYSTEM)("%s spends one tint strength per tone", (rel) => {
    // §5 gives each tone one tint: `good/10` · `warn/10` · `bad/10` · `brand-50`, and `canvas` for
    // neutral. The connect screens had grown a `bg-bad/5` and a `bg-warn/5` alongside them, so the same
    // 「무언가 잘못됐다」 surface was two different strengths on two screens — and `bg-ink/5` was a
    // neutral tint mixed by hand where the palette already has `canvas`.
    for (const m of code(rel).matchAll(/bg-(good|warn|bad|brand|ink)\/(\d+)/g)) {
      expect(`${m[1]}/${m[2]}`).toBe(`${m[1]}/10`);
    }
  });

  it.each(ON_THE_SYSTEM)("%s uses no gradient except a scroll fade", (rel) => {
    // §5 bans decorative gradients. A fade to `transparent` over the bottom of a scroller is not
    // decoration — it is the affordance that says the content continues, and removing it to satisfy a
    // regex would delete information. So the rule is: a gradient must end in `to-transparent`.
    for (const g of code(rel).matchAll(/bg-gradient-to-[a-z]+[^"`]*/g)) {
      expect(g[0]).toContain("to-transparent");
    }
  });
});

describe("the scales are the scales", () => {
  it.each(ON_THE_SYSTEM)("%s takes its type size from the scale", (rel) => {
    // `text-[15px]` and `text-[13px]` ARE `sm` and `xs` — the literals were the token spelled out, and
    // spelling it out is how a row title stopped being comparable to the row title one screen over.
    expect(code(rel)).not.toMatch(/text-\[\d+px\]/);
  });

  it.each(ON_THE_SYSTEM)("%s takes its radius from the scale", (rel) => {
    // 8px controls, 10px rows, 12px cards (§4). `rounded-[14px]` and `rounded-[16px]` were a fourth and
    // fifth card radius on two of the three screens a seller opens every morning.
    expect(code(rel)).not.toMatch(/rounded-\[\d+px\]/);
  });

  it.each(ON_THE_SYSTEM)("%s spaces its containers on the six steps", (rel) => {
    // 4 · 8 · 12 · 16 · 24 · 32 (§3). Only the steps that change DENSITY are checked: a container's own
    // padding, a stack's rhythm and a grid's gutter. See the docblock on why `gap-0.5` is not here.
    expect(code(rel)).not.toMatch(/\b(space-y|gap|gap-x|gap-y)-(5|7|9|10|11|2\.5|3\.5)\b/);
    expect(code(rel)).not.toMatch(/\b(p|px|py|pt)-(5|7|9|10|11|2\.5|3\.5)\b/);
    // `pb` excludes the mobile tab-bar clearance (`pb-28`), which is a measured gap to a fixed bar
    // rather than a spacing step; everything else on `pb` is checked the same as the rest.
    expect(code(rel)).not.toMatch(/\bpb-(5|7|9|10|11|2\.5|3\.5)\b/);
  });

  it.each(ON_THE_SYSTEM)("%s tops out at font-weight 700", (rel) => {
    // §1's heaviest step is 700. `font-extrabold` existed on exactly two elements and both were a page
    // title competing with nothing.
    expect(code(rel)).not.toContain("font-extrabold");
  });
});

describe("the fence can fail", () => {
  it("reads real files and would notice an empty list", () => {
    // A walk that resolved nothing would pass every assertion above. This is the assertion that the
    // assertions ran.
    expect(ON_THE_SYSTEM.length).toBeGreaterThan(10);
    for (const rel of ON_THE_SYSTEM) {
      expect(code(rel).length).toBeGreaterThan(200);
    }
  });
});
