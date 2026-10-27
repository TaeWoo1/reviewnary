import { describe, expect, it } from "vitest";
import { neighboursIn } from "./workList";
import { REASON } from "./copy/customerOps";
import type { HomeWorkRow } from "./homeWork";

const row = (id: string, reason: HomeWorkRow["reason"], subject: "REVIEW" | "INQUIRY" = "REVIEW") =>
  ({ key: id, owner: `/reviews/reply/${id}`, to: `/reviews/reply/${id}`, reason, subject, kind: subject }) as HomeWorkRow;

describe("「확정하고 다음」 walks 할 일 in its own order (2026-10-28)", () => {
  it("steps to the next item of the same kind, skipping the others", () => {
    const rows = [row("a1", REASON.approve), row("q1", REASON.reply, "INQUIRY"), row("a2", REASON.approve), row("a3", REASON.approve)];
    const walk = neighboursIn(rows, "/reviews/reply/a2")!;
    expect(walk.prev?.key).toBe("a1");
    expect(walk.next?.key).toBe("a3");
    expect([walk.position, walk.total]).toEqual([2, 3]);
    expect(neighboursIn(rows, "/reviews/reply/a3")!.next).toBeNull();
  });

  it("has no 다음 for an item that is not in 할 일", () => {
    expect(neighboursIn([row("a1", REASON.approve)], "/reviews/reply/zz")).toBeNull();
  });
});
