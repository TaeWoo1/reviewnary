// @vitest-environment jsdom
import { describe, it, expect } from "vitest";
import { renderWithRouter, screen } from "../../test/renderWithRouter";
import { HomeReviewOpsCard } from "./HomeReviewOpsCard";
import { UI_SCENARIOS } from "../../lib/actionWindow/fixtures";
import {
  CHECKPOINT_PROMPT_TITLE,
  HOME_REVIEW_OPS_COPY,
  resolveCopy,
} from "../../lib/actionWindow/copy";

// These scenarios always carry a run (unlike the null-run "ready-to-start").
const CHECKPOINT_RUN = UI_SCENARIOS["human-action-required"].run!;
const RUNNING_RUN = UI_SCENARIOS["observing"].run!;

describe("HomeReviewOpsCard", () => {
  // 아무 일도 일어나지 않는 화면에 「아무 일도 일어나지 않습니다」라고 적지 않는다. 그 문장이 가르치는
  // 것은 우리 쪽 작업의 이름뿐이었다 (연결 화면 정리, 2026-10-09).
  it("진행 중인 일이 없으면 이 줄 자체가 없다", () => {
    const { container } = renderWithRouter(<HomeReviewOpsCard run={null} />);
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("region", { name: HOME_REVIEW_OPS_COPY.sectionTitle })).toBeNull();
    expect(screen.queryByText(CHECKPOINT_PROMPT_TITLE)).toBeNull();
  });

  it("checkpoint run: shows the run title, checkpoint prompt, and a link to the run detail", () => {
    renderWithRouter(<HomeReviewOpsCard run={CHECKPOINT_RUN} />);
    expect(
      screen.getByText(resolveCopy(CHECKPOINT_RUN.runCopyKey, CHECKPOINT_RUN.runCopyParams)),
    ).toBeInTheDocument();
    expect(screen.getByText(CHECKPOINT_PROMPT_TITLE)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: new RegExp(HOME_REVIEW_OPS_COPY.goToCheckpoint) }),
    ).toHaveAttribute("href", "/connect/imports/current");
  });

  it("non-checkpoint run: shows progress and links to the landing, no checkpoint prompt", () => {
    renderWithRouter(<HomeReviewOpsCard run={RUNNING_RUN} />);
    const region = screen.getByRole("region", { name: HOME_REVIEW_OPS_COPY.sectionTitle });
    expect(region).toHaveTextContent("진행");
    expect(screen.queryByText(CHECKPOINT_PROMPT_TITLE)).toBeNull();
    expect(
      screen.getByRole("link", { name: new RegExp(HOME_REVIEW_OPS_COPY.open) }),
    ).toHaveAttribute("href", "/connect/imports");
  });
});
