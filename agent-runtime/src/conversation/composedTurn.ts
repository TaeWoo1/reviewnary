/**
 * **A composed turn, before it is persisted.** What every composition lane in `src/conversation/`
 * returns: the status, the message, the artifacts, and the thread state the turn leaves behind.
 *
 * <p>Lifted out of `ConversationService.ts` on 2026-10-12 so that a lane can live in its own file
 * without importing the service back — a shared type belongs to a third file, not to whichever side
 * happened to declare it first (`docs/FINDINGS.md` §B14).
 */
import type {
  ActiveTask, Artifact, PendingHumanAction, PendingKnowledgeCapture, PendingPreparedAction,
  SuggestedAction, TurnStatus, TurnView, WorkingSetView,
} from "./contract";
import type { OperatorAnswer } from "../operator/state/OperatorState";

export interface Composed {
  status: TurnStatus; message: string; artifacts: Artifact[]; suggestedActions: SuggestedAction[];
  /** The run's limits, said apart from the answer (contract `TurnView.notes`). */
  notes?: string[];
  workingSet: WorkingSetView | null; pendingHumanActions: PendingHumanAction[];
  pendingPrepared: PendingPreparedAction | null; failureCode?: string; failureReason?: string;
  budget?: TurnView["budget"]; answer?: OperatorAnswer;
  /** Knowledge Capture v1: the gap to hold open after this turn; `undefined` = carry the thread's as it is. */
  pendingCapture?: PendingKnowledgeCapture | null;
  /** Agent Interaction Model v2 §1-C: the task this turn leaves in flight; `undefined` = derive/carry. */
  activeTask?: ActiveTask | null;
}
