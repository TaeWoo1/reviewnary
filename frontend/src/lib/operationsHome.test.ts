import { describe, expect, it } from "vitest";
import { problemLine } from "./operationsHome";
import type { HomeRepeatedProblems } from "./types";

/**
 * 반복 문제의 한 문장.
 *
 * <p>이 파일은 2026-10-06에 한 블록으로 줄었다 — 나머지(지금 확인할 리뷰 · 준비된 작업 · 최근 수집 상태 ·
 * 영역을 그릴지 말지)는 책임 런타임이 없는 배포가 보던 두 번째 Home의 문장이었고, Home이 하나가 되면서
 * 그 화면과 함께 사라졌다.
 */
function problems(over: Partial<HomeRepeatedProblems> = {}): HomeRepeatedProblems {
  return { decidable: 1, observing: 19, dormant: 0, rows: [], ...over };
}

describe("반복 문제", () => {
  /**
   * Both populations are DRAWN under this line, so both are named — but as two sentences, never as one number.
   * Stating only the decidable half put 「1건 있습니다」 over a list of two rows and left the seller counting;
   * adding them would print 「20건」, which is the sum this line has never been allowed to say.
   */
  it("names both populations it draws, and never their sum", () => {
    const line = problemLine(problems());
    expect(line).toContain("판단이 필요한 반복 문제가 1건");
    expect(line).toContain("19건은 지켜보고 있습니다");
    expect(line).not.toContain("20건");
  });

  it("says nothing about 관찰 중 when there is none, rather than 「0건」", () => {
    expect(problemLine(problems({ observing: 0 }))).not.toContain("지켜보고");
  });

  /**
   * 관찰 중 is stated, never drawn as pending work. Measured on this org: 19 observed against one
   * that is anybody's move — a line reading 「반복 문제 20건」 beside a task list would make twenty
   * observations look like twenty jobs.
   */
  it("reports 관찰 중 as watching, and only once nothing needs a decision", () => {
    const line = problemLine(problems({ decidable: 0 }));
    expect(line).toContain("판단이 필요한 반복 문제는 없습니다");
    expect(line).toContain("19건을 지켜보고 있습니다");
  });

  /**
   * The Home only carries problems whose evidence is still inside the observation window. That makes 「없습니다」
   * two different situations, and only one of them is an empty library: an org whose problems all went quiet
   * months ago still HAS them, and would be told by this line that it does not — while 고객운영 메모리, one click
   * away, lists every one with its evidence.
   */
  it("separates 「아직 없다」 from 「최근에 없다」, because only one of them is an empty library", () => {
    const quiet = problemLine(problems({ decidable: 0, observing: 0, dormant: 19 }));
    expect(quiet).toContain("최근에 다시 확인된 반복 문제는 없습니다");
    expect(quiet).toContain("이전에 모인 19건");
    expect(quiet).toContain("고객운영 메모리");
    // Never the sentence for an org that has none at all.
    expect(quiet).not.toContain("아직 모인");
    // It says how many and where, never which — naming them here would be listing them, which is what the
    // window decided not to do.
    expect(quiet).not.toContain("·");

    // The window's length lives in ReviewIssueThresholds; a second copy of it here is the one that goes stale.
    expect(quiet).not.toMatch(/\d+\s*주|\d+\s*일/);
  });

  it("says nothing has gathered only when there is genuinely nothing", () => {
    expect(problemLine(problems({ decidable: 0, observing: 0, dormant: 0 }))).toBe("아직 모인 반복 문제가 없습니다.");
  });
});
