import { Status, type StatusTone } from "../ui/Status";
import type { ReviewTriageTier } from "../../lib/types";
import {
  AI_TRIAGE_DISCLOSURE,
  AI_TRIAGE_MARK_CLASS,
  AI_TRIAGE_MARK_LABEL,
  TRIAGE_TIER_LABEL,
} from "../../lib/reviewTriage";

/**
 * The two chips that say what the SYSTEM thinks of a review — the rules tier, and the pilot's mark
 * beside it.
 *
 * They lived inside `ChannelReviews.tsx` while the record was the only screen that showed them. The
 * Decision Workspace shows them too, at the top, where they answer 「왜 확인해야 하는가」 — and a second
 * copy of a chip is how two surfaces end up disagreeing about which colour 확인 필요 is.
 *
 * <b>확인 필요 is the only emphasised tier.</b> A palette where every tier had its own colour would
 * make a list look like a status board and spend the seller's attention evenly over rows that do not
 * deserve it evenly.
 */
const TIER_TONE: Record<ReviewTriageTier, StatusTone> = {
  NEEDS_ATTENTION: "warn",
  WATCH: "neutral",
  FYI: "neutral",
};

export function TriageTierChip({ tier, quiet = false }: { tier: ReviewTriageTier; quiet?: boolean }) {
  return (
    <Status tone={TIER_TONE[tier]} variant={quiet ? "quiet" : "word"}>
      {TRIAGE_TIER_LABEL[tier]}
    </Status>
  );
}

/**
 * The pilot's mark — BESIDE the rules tier, never in its place, so the seller can always tell which
 * mechanism spoke. Same emphasis as 확인 필요 because it sorts with 확인 필요; the wording carries the
 * difference, and the title says it in one line.
 */
export function AiMarkChip({ quiet = false }: { quiet?: boolean } = {}) {
  if (quiet) {
    // The record's row reading: the mark is a word on the metadata line, not a capsule in front of the
    // customer's sentence. Same word, same tone, same table — only the carrier changes, exactly as
    // {@link TriageTierChip}'s quiet reading does for the tier it stands beside.
    return (
      <Status tone="warn" variant="quiet">
        {AI_TRIAGE_MARK_LABEL}
      </Status>
    );
  }
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${AI_TRIAGE_MARK_CLASS}`}
      title={AI_TRIAGE_DISCLOSURE}
    >
      {AI_TRIAGE_MARK_LABEL}
    </span>
  );
}
