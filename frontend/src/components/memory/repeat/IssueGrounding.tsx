import { Link } from "react-router-dom";
import {
  knowledgeGapAction,
  knowledgeLine,
  knowledgeScopeLine,
} from "../../../lib/repeatedIssue";
import type { IssueKnowledgeOnHand } from "../../../lib/types";
import { PaneBlock } from "./PaneBlock";

/**
 * <b>우리가 써 둔 것</b> — what the company's own library already says.
 *
 * <b>It answers what exists, never whether it is good enough.</b> Whether a registered guidance
 * actually answers a customer is the drafting lane's question and costs model calls; opening a
 * repeated problem must not. So this block reports counts and the seller's own sentences, and stops.
 *
 * <b>The three states are kept apart on purpose.</b> An empty library, a library that holds things
 * none of which name this problem, and a library that answers it lead to three different next steps
 * — and telling a seller who has already written the answer to go write it is the failure this
 * separation exists to prevent.
 */
export function IssueGrounding({
  knowledge,
  failed,
}: {
  knowledge: IssueKnowledgeOnHand | null;
  failed: boolean;
}) {
  if (failed || !knowledge) return null;

  const scope = knowledgeScopeLine(knowledge);
  const action = knowledgeGapAction(knowledge);

  return (
    <PaneBlock
      label="우리가 써 둔 것"
      side={
        action ? (
          <Link
            to="/knowledge"
            className="rounded font-semibold text-muted underline decoration-line underline-offset-4 hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            {action}
          </Link>
        ) : null
      }
    >
      <p className="break-keep leading-relaxed text-ink">{knowledgeLine(knowledge)}</p>
      {scope ? <p className="break-keep text-xs leading-relaxed text-muted">{scope}</p> : null}
      {knowledge.excerpts.length > 0 ? (
        <ul className="space-y-2 border-l-2 border-line pl-3">
          {knowledge.excerpts.map((excerpt) => (
            // The seller's own sentence, bounded by the read. Quoted so it is plainly theirs and not
            // something this screen composed.
            <li key={excerpt} className="break-keep text-sm leading-relaxed text-ink">
              「{excerpt}」
            </li>
          ))}
        </ul>
      ) : null}
    </PaneBlock>
  );
}
