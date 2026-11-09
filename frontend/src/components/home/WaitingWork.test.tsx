// @vitest-environment jsdom
/**
 * <b>기다리는 일</b> (2026-11-03 sixth review) — answered, not finished. Each kind says what it waits on, every row opens
 * the same inquiry, a failed read is not an empty list, and nothing here is a control that sends.
 */
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { WaitingWork } from "./WaitingWork";
import type { InquiryFollowUpRow, InquiryFollowUps } from "../../lib/types";

function row(over: Partial<InquiryFollowUpRow>): InquiryFollowUpRow {
  return {
    workItemId: "w", inquiryId: "i", subject: "[QA] 환불 요청", channelCode: "CAFE24", waitingOn: "ACTION", due: false,
    outcome: "IN_PROGRESS", outcomeKo: "접수·진행 중", note: "환불 대기", nextCheckOn: "2026-11-05", phase: "COMPLETED",
    since: "2026-11-03T05:00:00Z", to: "/inquiries/i", ...over,
  };
}

function draw(followUps: InquiryFollowUps | null | undefined) {
  return render(
    <MemoryRouter>
      <WaitingWork followUps={followUps} />
    </MemoryRouter>,
  );
}

describe("기다리는 일", () => {
  it("says what each one waits on and opens the same inquiry", () => {
    draw({
      due: [], dueTotal: 0, today: "2026-11-03", waitingTotal: 3,
      waiting: [
        row({ workItemId: "w1", inquiryId: "i1", to: "/inquiries/i1", waitingOn: "REGISTRATION", outcome: "DONE", nextCheckOn: null }),
        row({ workItemId: "w2", inquiryId: "i2", to: "/inquiries/i2" }),
        row({ workItemId: "w3", inquiryId: "i3", to: "/inquiries/i3", waitingOn: "CUSTOMER", outcome: "NEEDS_CUSTOMER_INFO", nextCheckOn: null }),
      ],
    });
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/inquiries/i1", "/inquiries/i2", "/inquiries/i3"]);
    expect(links[0]).toHaveTextContent("답변 등록 확인 중");
    expect(links[1]).toHaveTextContent("조치 진행 중");
    expect(links[1]).toHaveTextContent("11월 5일 (목) 다시 확인");
    expect(links[2]).toHaveTextContent("고객 회신 대기");
    expect(screen.getByText(/지금은 사장님 차례가 아니에요/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /보내|등록하기|답변/ })).toBeNull();
  });

  it("draws nothing with nothing waiting, and a failed read is said, not drawn empty", () => {
    const empty = draw({ due: [], dueTotal: 0, waiting: [], waitingTotal: 0, today: "2026-11-03" });
    expect(empty.container).toBeEmptyDOMElement();
    empty.unmount();
    draw(null);
    expect(screen.getByTestId("waiting-work")).toHaveTextContent("기다리는 일을 불러오지 못했어요");
  });

  it("shows three and the rest on request, with the total on the heading", async () => {
    const many = Array.from({ length: 5 }, (_, n) => row({ workItemId: `w${n}`, inquiryId: `i${n}`, to: `/inquiries/i${n}` }));
    draw({ due: [], dueTotal: 0, waiting: many, waitingTotal: 5, today: "2026-11-03" });
    expect(screen.getAllByRole("link")).toHaveLength(3);
    expect(screen.getByText("5건 중 3건")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "모두 보기" }));
    expect(screen.getAllByRole("link")).toHaveLength(5);
  });
});
