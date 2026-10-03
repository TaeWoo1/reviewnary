import { Link } from "react-router-dom";
import { Facts } from "../ui/ObjectRow";
import { CaseLayout, type PaneDepth } from "./CaseLayout";
import { draftRuleNotice } from "../../lib/inquiryNextAction";
import { InquiryResponsePanel } from "../inbox/InquiryResponsePanel";
import { InquiryReply, InquiryReplyDock } from "../inbox/InquiryReply";
import { useInquiryReply } from "../inbox/useInquiryReply";
import { OperationsCaseView } from "../../pages/app/OperationsCase";
import { ReviewCaseView } from "../../pages/app/ReviewReplyTask";
import { elapsedLabel } from "../../lib/copy/customerOps";
import { inquiryReading } from "../../lib/inquiryNextAction";
import { plainText } from "../../lib/plainText";
import type { HomeWorkRow } from "../../lib/homeWork";

/**
 * <b>The right-hand pane of 확인할 일 and 오늘</b> — the selected row, drawn where the seller already is.
 *
 * <p>Each kind is drawn by the screen that owns it, in its pane reading: a case by the case screen, a review by the
 * Review Case, an inquiry by the same response panel 문의 uses. Nothing is re-implemented here, so deciding in the
 * pane and deciding on the full page are the same reads and the same writes. `key` forces a clean mount per item: a
 * half-written draft must never follow the seller to the next row.
 *
 * <p><b>{@link PaneDepth} decides how much of that screen unfolds</b> (Home v3.1). At 「full」 the pane is the
 * workspace, as it has been. At 「preview」 every form is left to the screen that owns it and the pane answers the
 * questions a seller asks before opening anything — what this is, why it was brought up, what was checked, what is
 * recommended — with one way in docked under it. Same components, same state; what differs is what is offered,
 * never what is true.
 */
export function WorkItemPane({ row, now, depth = "full" }: { row: HomeWorkRow; now?: Date; depth?: PaneDepth }) {
  /* <b>확인할 일's inquiry pane is 문의's, docked</b> (canonical, 2026-10-03). 문의 already lifted this
     workflow out of its panel so a page could own the state and dock the one primary action
     ({@link useInquiryReply}); this screen asks for exactly that and so it uses exactly that, rather
     than a second dock built beside it.

     <p>The hook stands above every branch because it is a hook — it is handed `null` for a row that is
     not an inquiry and for the Home's preview depth, and reads nothing then. The Home keeps the panel
     it was frozen on. */
  const canonical = depth === "full";
  const inquiryWorkItemId = canonical && row.kind === "INQUIRY" ? row.workItemId : null;
  const workspace = useInquiryReply(inquiryWorkItemId);

  if (row.kind === "CASE") {
    return <OperationsCaseView key={row.key} caseId={row.subjectId} variant="pane" depth={depth} now={now} />;
  }
  if (row.kind === "REVIEW") {
    return <ReviewCaseView key={row.key} reviewId={row.subjectId} variant="pane" depth={depth} />;
  }
  const wait = elapsedLabel(row.since, row.subject, now);
  if (inquiryWorkItemId) {
    /* <b>The customer's sentence is the heading</b> (canonical, 2026-10-03). The panel used to print it
       as its own first line, so the pane's title was hidden and the question sat a block down; docked,
       the panel draws neither title nor meta and this header owns both — one question, drawn once, at
       the top of the column that is answering it. */
    return (
      <CaseLayout
        key={row.key}
        variant="pane"
        depth={depth}
        reading="document"
        decisionLabel="판매자의 결정"
        label="선택한 확인할 일"
        meta={
          <Facts>
            <span className="font-semibold text-ink">{row.source}</span>
            {wait ? <span className="tabular-nums">{wait}</span> : null}
          </Facts>
        }
        sub={(() => {
          const context = inquiryReading(
            { title: workspace.detail?.title ?? row.title, snippet: workspace.detail?.details ?? row.said },
            plainText,
          ).titleContext;
          return [workspace.detail?.productName, context ? `제목 「${context}」` : null].filter(Boolean).join(" · ") || undefined;
        })()}
        /* The row carries a PREVIEW of the body — it is a row — and the pane is the place that read the
           record, so the question is drawn whole from the detail and falls back to the row only while
           that read is in flight. Same rule as 문의's own pane ({@link inquiryReading}): the body is the
           question, and the subject line stands beside it only while it adds something. */
        title={
          inquiryReading(
            {
              title: workspace.detail?.title ?? row.title,
              snippet: workspace.detail?.details ?? row.said,
            },
            plainText,
          ).question
        }
        headerAction={
          <Link to={`/inquiries/${row.subjectId}`} className="rounded font-semibold text-muted hover:text-ink hover:underline">
            문의에서 보기
          </Link>
        }
        decision={<InquiryReply workspace={workspace} docked />}
        dock={<InquiryReplyDock workspace={workspace} />}
      />
    );
  }
  return (
    <CaseLayout
      key={row.key}
      variant="pane"
      depth={depth}
      decisionLabel="판매자의 결정"
      // The response panel prints the channel and the time with the question; drawn here too it was the same line
      // twice, one block apart. Kept only when there is no panel to say it.
      meta={
        row.workItemId === null ? (
          <Facts>
            <span>{row.source}</span>
            {wait ? <span className="tabular-nums">{wait}</span> : null}
          </Facts>
        ) : undefined
      }
      title={row.title}
      titleHidden={row.workItemId !== null}
      headerAction={
        <Link to={`/inquiries/${row.subjectId}`} className="rounded font-semibold text-muted hover:text-ink hover:underline">
          문의에서 보기
        </Link>
      }
      decision={
        // <b>An inquiry's pane keeps its panel at either depth</b>, and that is not an exception to the preview
        // rule — it is the rule reading correctly. What a preview leaves out are the JUDGMENT forms, and this row
        // has none: the response panel is the reply lane itself, and the row carries only a truncated first line,
        // so a preview of it would be a title with an ellipsis and a button. Rendered once at 440px it was exactly
        // that — an empty pane where the work used to be. The docked link beside it is navigation and is drawn
        // as such, so this panel's own controls are the only pressable weight in the column.
        row.workItemId ? (
          <InquiryResponsePanel workItemId={row.workItemId} />
        ) : (
          // 「제안할 수 없습니다」에는 이유가 없어서, 제품이 이 문의를 다루지 못한다는 뜻으로 읽혔다.
          // 이유는 규칙이다. 이 행은 `status`를 들고 다니지 않으므로 「이미 답변됨」은 말할 수 없고,
          // 말할 수 있는 규칙만 말한다 — 문장은 문의 상세와 같은 곳에서 온다.
          <p className="break-keep text-sm leading-relaxed text-muted">{draftRuleNotice()}</p>
        )
      }
    />
  );
}

/** Where the pane's docked action takes the seller: the full screen that owns the row. */
export function workItemFullScreen(row: HomeWorkRow): string {
  if (row.kind === "CASE") return `/customer-operations/cases/${row.subjectId}`;
  if (row.kind === "REVIEW") return `/reviews/reply/${row.subjectId}?from=work`;
  return `/inquiries/${row.subjectId}`;
}
