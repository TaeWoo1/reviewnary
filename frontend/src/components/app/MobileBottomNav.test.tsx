// @vitest-environment jsdom
import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { KEYBOARD_MIN_PX, MobileBottomNav, isTextEntry, keyboardOpen } from "./MobileBottomNav";

vi.mock("../../lib/useTodayWorkCount", () => ({ useTodayWorkCount: () => 3 }));

function renderNav() {
  return render(
    <MemoryRouter>
      <textarea aria-label="초안" />
      <input aria-label="기록" type="text" />
      <input aria-label="확인" type="checkbox" />
      <button type="button">모의 등록</button>
      <MobileBottomNav onMore={() => undefined} />
    </MemoryRouter>,
  );
}

/** The screen a Galaxy S24 measured (2026-11-14): 748px at rest, 417px with the Korean keyboard up. */
const REST = 748;
const WITH_KEYBOARD = 417;

/** What a phone does when its keyboard opens or closes under `interactive-widget=resizes-content`: the window resizes. */
function screenHeight(px: number) {
  act(() => {
    Object.defineProperty(window, "innerHeight", { configurable: true, writable: true, value: px });
    window.dispatchEvent(new Event("resize"));
  });
}

beforeEach(() => {
  Object.defineProperty(window, "innerHeight", { configurable: true, writable: true, value: REST });
});
afterEach(() => {
  Object.defineProperty(window, "innerHeight", { configurable: true, writable: true, value: 768 });
});

/**
 * <b>The phone's bottom menu steps aside while the keyboard is up</b> (휴대폰 문의 상세, 2026-11-14): with the keyboard
 * up the screen left is ~400px and the fixed 56px bar sat on the field's own 저장 / 확인 buttons. And it comes back
 * when the keyboard goes down, even though the field still holds the focus — Android's back key closes only the
 * keyboard, and on the Galaxy S24 the bar stayed away until the seller tapped something else.
 */
describe("MobileBottomNav — out of the way of a keyboard", () => {
  it("hides while the keyboard is up and comes back when the back key closes only the keyboard", async () => {
    renderNav();
    const nav = screen.getByTestId("mobile-bottom-nav");
    expect(nav).toHaveClass("flex");
    // The tap focuses the field; the keyboard then takes the bottom of the screen.
    act(() => screen.getByLabelText("초안").focus());
    screenHeight(WITH_KEYBOARD);
    expect(nav).toHaveClass("hidden");
    // Back key: the keyboard goes down, the field keeps the focus and what was typed.
    screenHeight(REST);
    expect(screen.getByLabelText("초안")).toHaveFocus();
    expect(nav).toHaveClass("flex");
    // A second tap in the same field brings the keyboard, and the bar steps aside again.
    screenHeight(WITH_KEYBOARD);
    expect(nav).toHaveClass("hidden");
    // Moving from one field to another keeps it hidden — no flash between them.
    act(() => screen.getByLabelText("기록").focus());
    await waitFor(() => expect(nav).toHaveClass("hidden"));
    // A button takes the focus: the keyboard goes down and the bar is back.
    act(() => screen.getByRole("button", { name: "모의 등록" }).focus());
    screenHeight(REST);
    await waitFor(() => expect(nav).toHaveClass("flex"));
  });

  it("a focused field with no keyboard on screen (a hardware keyboard) keeps the bar", () => {
    renderNav();
    act(() => screen.getByLabelText("초안").focus());
    expect(screen.getByTestId("mobile-bottom-nav")).toHaveClass("flex");
  });

  it("the address bar folding away is not a keyboard; a keyboard's height is", () => {
    expect(keyboardOpen(true, REST - 56, REST)).toBe(false);
    expect(keyboardOpen(true, REST - KEYBOARD_MIN_PX, REST)).toBe(true);
    expect(keyboardOpen(true, WITH_KEYBOARD, REST)).toBe(true);
    // The screen shrank but nothing is being typed in — something else took it, not a keyboard.
    expect(keyboardOpen(false, WITH_KEYBOARD, REST)).toBe(false);
  });

  it("only fields that open a keyboard count — a checkbox or a button does not", () => {
    renderNav();
    expect(isTextEntry(screen.getByLabelText("초안"))).toBe(true);
    expect(isTextEntry(screen.getByLabelText("기록"))).toBe(true);
    expect(isTextEntry(screen.getByLabelText("확인"))).toBe(false);
    expect(isTextEntry(screen.getByRole("button", { name: "모의 등록" }))).toBe(false);
    expect(isTextEntry(null)).toBe(false);
  });
});
