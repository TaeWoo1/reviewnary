/**
 * The conversation lane cannot send, approve, verify, collect or touch a credential — asserted on the
 * SOURCE, the same way `plannerFence` and `privilegedPlaneFence` assert their invariants.
 *
 * Two halves. (1) No file under `src/conversation/` references a backend method that writes toward a
 * channel or the privileged plane; the only PREPARE it may reach is `generateDraftFor`, and only from
 * `DraftPreparer.ts`. (2) The Operator tool catalogue is byte-for-byte the declared table and still
 * 100% READ — the lane added three READ tools and no other class.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { memberBody } from "../support/sourceSlice";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { buildOperatorTools, OPERATOR_TOOL } from "../../src/operator/tools/OperatorTools";
import { OperatorToolRegistry } from "../../src/operator/tools/OperatorToolRegistry";
import { FakeOperatorSpringClient } from "../support/FakeOperatorSpringClient";
import { FakeSpringClient } from "../support/FakeSpringClient";
import { FakeIssueSpringClient } from "../support/FakeIssueSpringClient";
import { twoInquiries } from "../support/fixtures";
import { fourIssues } from "../support/issueFixtures";

const DIR = join(__dirname, "../../src/conversation");

/**
 * **Recursive, and that is the point.** This walk was a flat `readdirSync` until 2026-10-12, so
 * `src/conversation/graph/` — the turn graph and its state — was never scanned. The fence read as
 * covering the lane while two files sat outside it: a fence with a blind spot is worse than no fence,
 * because the green tick is read as proof. Same shape as `plannerFence` / `privilegedPlaneFence`.
 * `name` is the path relative to the lane root, so an exception can be granted to one file in a
 * subdirectory without widening it to a basename. See `docs/FINDINGS.md` §B10.
 */
function laneFiles(dir: string, prefix = ""): { name: string; text: string }[] {
  const out: { name: string; text: string }[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      out.push(...laneFiles(full, `${prefix}${entry}/`));
    } else if (entry.endsWith(".ts")) {
      out.push({ name: `${prefix}${entry}`, text: readFileSync(full, "utf8") });
    }
  }
  return out;
}

const FILES = laneFiles(DIR);

/** Backend methods / capabilities the lane must never name. Method-call shapes, so a doc word cannot trip it. */
const FORBIDDEN = [
  /\bconfirmPublish\b/, /\bconfirmInquiryPublish\b/, /\.resume\s*\(/, /\bresumeInquiryPublish\b/,
  /\bverify\w*\s*\(/, /\bapprove\w*\s*\(/, /\bdecideReviewApproval\b/, /\bstartReviewSubmissionRun\b/,
  /\bmanualSync\b/, /\bbackfill\w*/i, /\bcredential\w*/i, /\bproposeInquiry\b/, /\bsaveDraft\b/, /\bsaveReviewDraft\b/, /\brecordReviewTriage\b/,
  /\bcreateOrgKnowledge\b/, /\bcreateProductKnowledgeSource\b/,
];

describe("conversation write fence", () => {
  it("has source files to check", () => {
    expect(FILES.map((f) => f.name)).toEqual(expect.arrayContaining(["ConversationService.ts", "DraftPreparer.ts", "ConversationStore.ts"]));
  });

  // Two named exceptions, each a PREPARE/REFRESH step the plan authorises and nothing else may reach:
  // the review draft seam from the draft preparer, and the product's own one-press collection from the refresher.
  const ALLOWED: Record<string, RegExp[]> = {
    "DraftPreparer.ts": [/\bsaveReviewDraft\b/, /\brecordReviewTriage\b/, /\bproposeInquiry\b/],
    "Refresher.ts": [/\bmanualSync\b/],
    // Knowledge Capture v1: the seller's own knowledge seams, written only after a fingerprint-bound 「저장하고 계속」.
    "KnowledgeCaptureWriter.ts": [/\bcreateOrgKnowledge\b/, /\bcreateProductKnowledgeSource\b/],
    // NOT a backend call. `phases.resume(...)` is the turn graph dispatching its OWN resume phase —
    // the node table calling into the service, which is the opposite direction from the privileged
    // plane. The `/\.resume\s*\(/` pattern exists to catch the backend's publish resume, and it has
    // no way to tell the two apart by shape. The narrow grant is this one path, this one pattern:
    // a real `spring.resume(` anywhere else in the lane — including elsewhere in `graph/` — still fails.
    "graph/turnGraph.ts": [/\.resume\s*\(/],
  };

  it("no conversation file references a write, approval, verification, collection or credential method", () => {
    for (const file of FILES) {
      for (const pattern of FORBIDDEN) {
        if ((ALLOWED[file.name] ?? []).some((a) => a.source === pattern.source)) continue;
        expect(pattern.test(file.text), `${file.name} matches ${pattern}`).toBe(false);
      }
    }
  });

  it("the two exceptions stay in their one file each", () => {
    expect(FILES.filter((f) => /\bsaveReviewDraft\b/.test(f.text)).map((f) => f.name)).toEqual(["DraftPreparer.ts"]);
    expect(FILES.filter((f) => /\bmanualSync\b/.test(f.text)).map((f) => f.name)).toEqual(["Refresher.ts"]);
    expect(FILES.filter((f) => /\bproposeInquiry\b/.test(f.text)).map((f) => f.name)).toEqual(["DraftPreparer.ts"]);
    expect(FILES.filter((f) => /\bcreateOrgKnowledge\b|\bcreateProductKnowledgeSource\b/.test(f.text)).map((f) => f.name)).toEqual(["KnowledgeCaptureWriter.ts"]);
  });

  it("Captured Knowledge Reuse v1: after a save the original work is resumed through the ordinary draft path — no capture-specific bypass hands the saved text to a prompt or an evidence list", () => {
    const service = FILES.find((f) => f.name === "ConversationService.ts")!.text;
    // This slice used to end at the docblock sentence "Which objects a draft/send sentence points
    // at". On 2026-10-12 a refactor moved that docblock to `targetResolution.ts`, `indexOf` returned
    // -1, and `slice(start, -1)` widened this fence from ~200 lines to ~1,500 — green the whole
    // time, because the positive assertions below only get easier as the slice grows. A boundary is
    // structural now, and a missing one throws. See `docs/FINDINGS.md` §B18.
    const decide = memberBody(service, "private async decideCapture(");
    expect(decide).toMatch(/prepareWithView\(/);
    // The resume reads the saved source id back from the backend's own evidence; it never writes one in.
    expect(decide).toMatch(/evidence \?\? \[\]\)\.some\(\(e\) => e\.sourceId === savedId\)/);
    for (const forbidden of [/passages\s*:/, /evidence\s*:\s*\[/, /searchProductKnowledge/, /searchOrgKnowledge/, /planGoal/, /judgeFinding/, /candidate\.content/]) {
      expect(decide, `decideCapture names ${forbidden}`).not.toMatch(forbidden);
    }
  });

  it("a captured fact is never a model's, a customer's or an assistant's sentence: the writer reads only the candidate the seller confirmed", () => {
    const writer = FILES.find((f) => f.name === "KnowledgeCaptureWriter.ts")!.text;
    expect(writer).toMatch(/candidate\.content/);
    for (const forbidden of [/generateDraftFor/, /comments/, /details\b/, /answerBody/, /\bmessage\b/, /searchAnswerMemory/, /planGoal/]) {
      expect(writer, `writer names ${forbidden}`).not.toMatch(forbidden);
    }
  });

  it("generateDraftFor is reached from DraftPreparer.ts and nowhere else in the lane", () => {
    const callers = FILES.filter((f) => /\.generateDraftFor\s*\(/.test(f.text)).map((f) => f.name);
    expect(callers).toEqual(["DraftPreparer.ts"]);
  });

  it("the Operator catalogue is unchanged in kind: exactly the declared table, every class READ", () => {
    const registry = new OperatorToolRegistry(buildOperatorTools({
      operator: new FakeOperatorSpringClient(), inquiry: new FakeSpringClient(twoInquiries()), issue: new FakeIssueSpringClient(fourIssues()),
    }));
    expect(registry.names().sort()).toEqual(Object.values(OPERATOR_TOOL).sort());
    expect(registry.actionClasses()).toEqual(["READ"]);
    expect(registry.names()).toEqual(expect.arrayContaining(["list_recent_reviews", "get_sales_trend", "list_inquiry_workload"]));
    for (const name of registry.names()) {
      expect(name).not.toMatch(/draft|send|publish|sync|approve/);
    }
  });
});
