// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { MasterDetail } from "./MasterDetail";

/**
 * <b>확인할 일 has its own pane; every other screen still has the layout's</b> (product-owner decision,
 * 2026-10-02).
 *
 * <p>The ask was an override on one route, not a wider pane everywhere. The danger in granting the
 * parameter at all is exactly that it stops being one route's: a second caller passes it, then a third,
 * and the layout has no pane of its own any more. So the default is asserted on the component and the
 * override is counted across the whole source tree.
 */
function files(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) files(path, out);
    else if (/\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name)) out.push(path);
  }
  return out;
}

const SRC = files("src");

describe("master-detail pane", () => {
  it("a caller that does not ask gets the pane it has always had", () => {
    render(<MasterDetail wide list={<p>list</p>} detail={<p>detail</p>} detailLabel="상세" />);
    const className = screen.getByTestId("master-detail").className;
    expect(className).toContain("w-[440px]");
    expect(className).not.toContain("w-[576px]");
    // Including its trailing air: 문의 · 리뷰 · 기억 keep the 32px they shipped with.
    expect(className).toContain("pb-8");
  });

  it("the Decision Workspace's pane is a contract: 576 from 1440 up, 440 below, and a floor it ends on", () => {
    render(<MasterDetail wide pane="decision" list={<p>list</p>} detail={<p>detail</p>} detailLabel="상세" />);
    const className = screen.getByTestId("master-detail").className;
    // Below the breakpoint it is exactly the pane every other screen has — measured at 1366×768, a flat
    // 576 left the list a 486px column with the tabs on two rows and every second line cut mid-word.
    expect(className).toContain("w-[440px]");
    expect(className).toContain("min-[1440px]:w-[576px]");
    expect(className).toContain("min-[1440px]:min-w-[576px]");
    // And nothing between: every 576 class is behind the breakpoint. An unconditional one would be the
    // flat width the 1366 measurement rejected.
    const unguarded = className.split(/\s+/).filter((c) => c.includes("576") && !c.startsWith("min-[1440px]:"));
    expect(unguarded).toEqual([]);
    /* 12px, not the shell's 32: measured at 1366×768 the decision flow ended at y=753 — inside the fold —
       and the trailing air alone pushed the column's scroll height to 785, so a pane that fitted still
       offered a scrollbar. */
    expect(className).toContain("pb-3");
    expect(className).not.toContain("pb-8");
  });

  it("exactly one screen overrides it — 확인할 일", () => {
    const callers = SRC.filter((f) => readFileSync(f, "utf8").includes('pane="decision"'));
    expect(callers.map((f) => f.replace(/\\/g, "/"))).toEqual(["src/pages/app/OperationsCaseQueue.tsx"]);
  });

  it("문의 · 리뷰 · 오늘 keep the layout's own width", () => {
    for (const screenFile of [
      "src/pages/app/CustomerInbox.tsx",
      "src/components/customerOperations/CustomerOpsHome.tsx",
    ]) {
      expect(readFileSync(screenFile, "utf8")).not.toContain('pane="decision"');
    }
  });
});
