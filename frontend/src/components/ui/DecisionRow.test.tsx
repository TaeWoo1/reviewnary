// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { DecisionList, DecisionRow } from "./DecisionRow";

/**
 * <b>The two rules a work row is read by</b> (product-owner decision, 2026-10-01): a selected row is
 * visibly the one the pane is showing, and the slot that says 「내가 뭘 해야 하나」 outweighs the slot that
 * says 「이게 뭔가」.
 *
 * <p>Both are about WEIGHT, which is why they are asserted on the marks rather than on the words. Neither
 * rule changes what a row says — the same five facts are on it before and after — and a change that keeps
 * the text while dropping the mark is exactly the regression these two tests exist to catch.
 */

afterEach(cleanup);

const row = (props: Partial<Parameters<typeof DecisionRow>[0]> = {}) =>
  render(
    <MemoryRouter>
      <DecisionList ariaLabel="확인할 일" plain>
        <DecisionRow
          tone="blue"
          icon="star"
          tag="리뷰"
          work="NEEDS_LOOK"
          title="버튼 누르면 컵 두개씩 나옴"
          source="네이버 리뷰"
          to="/reviews/reply/r-1"
          dense
          {...props}
        />
      </DecisionList>
    </MemoryRouter>,
  );

describe("the dense row — selection", () => {
  it("marks the selected row with a fill AND a left accent", () => {
    row({ selected: true });
    const link = screen.getByRole("link");
    // The fill alone was Intercom's reading, and it works where the row and the pane touch. Ours stand
    // 440px apart across a hairline, so the accent is what ties them together.
    expect(link.className).toContain("bg-brand-50");
    expect(link.className).toContain("shadow-selected");
    expect(link).toHaveAttribute("aria-current", "true");
  });

  it("marks an unselected row with neither", () => {
    row();
    const link = screen.getByRole("link");
    expect(link.className).not.toContain("bg-brand-50");
    expect(link.className).not.toContain("shadow-selected");
    expect(link).not.toHaveAttribute("aria-current");
  });

  it("says selection the same way the three-line reading does — one token, both readings", () => {
    const dense = row({ selected: true });
    const roomy = row({ selected: true, dense: false });
    for (const view of [dense, roomy]) {
      const link = view.container.querySelector("a")!;
      expect(link.className).toContain("shadow-selected");
      expect(link.className).toContain("bg-brand-50");
    }
  });
});

describe("the dense row — what outweighs what", () => {
  it("draws the state as a coloured word and the category as plain muted text", () => {
    row();
    const state = screen.getByText("확인 필요");
    const category = screen.getByText("리뷰");
    // A fill is the strongest mark a 12px token carries. It belongs to the state or to nothing: spending it
    // on 「리뷰」 down a list of reviews puts the loudest mark on the least distinguishing fact.
    expect(category.className).not.toMatch(/bg-/);
    expect(category.className).toContain("text-muted");
    expect(state.className).toMatch(/text-(bad|warn|good|brand-700)/);
  });

  it("drops the category entirely when it would repeat the state word", () => {
    // `REASON` carries real categories and three entries that are states wearing a category badge.
    row({ tag: "확인 필요" });
    expect(screen.getAllByText("확인 필요")).toHaveLength(1);
  });

  it("keeps the filled badge in the three-line reading, where a coloured tile anchors the row", () => {
    row({ dense: false });
    expect(screen.getByText("리뷰").className).toMatch(/bg-/);
  });
});
