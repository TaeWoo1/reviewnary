// @vitest-environment jsdom
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { SellerCheckFocus } from "./SellerCheckFocus";

function draw(path: string, ready: boolean, current: string | null) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SellerCheckFocus ready={ready} currentCheckId={current}>
        <p>합성-판매자 확인 기록</p>
      </SellerCheckFocus>
    </MemoryRouter>,
  );
}

describe("arriving on a record from the Home", () => {
  it("lands focus on the record once it is on screen", () => {
    draw("/inquiries/i-1?focus=seller-check&checkId=chk-1", true, "chk-1");
    expect(document.activeElement).toBe(screen.getByTestId("seller-check-focus"));
    expect(screen.queryByTestId("seller-check-changed")).toBeNull();
  });

  it("waits for the record: nothing is focused before it is read", () => {
    const view = draw("/inquiries/i-1?focus=seller-check&checkId=chk-1", false, null);
    expect(document.activeElement).not.toBe(screen.getByTestId("seller-check-focus"));
    view.rerender(
      <MemoryRouter initialEntries={["/inquiries/i-1?focus=seller-check&checkId=chk-1"]}>
        <SellerCheckFocus ready currentCheckId="chk-1">
          <p>합성-판매자 확인 기록</p>
        </SellerCheckFocus>
      </MemoryRouter>,
    );
    expect(document.activeElement).toBe(screen.getByTestId("seller-check-focus"));
  });

  it("says so when the record was rewritten after the Home showed it", () => {
    draw("/inquiries/i-1?focus=seller-check&checkId=chk-1", true, "chk-2");
    expect(screen.getByTestId("seller-check-changed")).toHaveTextContent("홈에서 본 뒤 기록이 새로 적혔어요. 지금 기록을 보여 드려요.");
  });

  it("an ordinary visit takes no focus and adds nothing", () => {
    draw("/inquiries/i-1", true, "chk-1");
    expect(screen.getByTestId("seller-check-focus")).not.toHaveAttribute("tabindex");
    expect(document.activeElement).toBe(document.body);
  });
});
