// @vitest-environment jsdom
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ScreenChangesProvider, useScreenChanges, type ScreenChangesClient } from "./screenChanges";

/** A server whose every answer the test gives by hand: `answer(n)` ends the question now open with number n. */
function handServer() {
  const asked: (number | null)[] = [];
  let pending: ((n: number) => void) | null = null;
  const client: ScreenChangesClient = {
    waitForScreenChanges: (after) => {
      asked.push(after);
      return new Promise<number>((resolve) => {
        pending = resolve;
      });
    },
  };
  const answer = async (n: number) => {
    const resolve = pending;
    pending = null;
    await act(async () => {
      resolve?.(n);
      await Promise.resolve();
    });
  };
  return { client, asked, answer };
}

function Count() {
  return <p>changes {useScreenChanges()}</p>;
}

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  document.dispatchEvent(new Event("visibilitychange"));
}

afterEach(() => {
  setVisibility("visible");
  vi.useRealTimers();
});

describe("ScreenChangesProvider", () => {
  it("counts a change only when the number moves, and asks again after each answer with the number it saw", async () => {
    const server = handServer();
    vi.useFakeTimers({ toFake: ["Date"] });
    render(
      <ScreenChangesProvider client={server.client}>
        <Count />
      </ScreenChangesProvider>,
    );
    expect(server.asked).toEqual([null]);

    await server.answer(100);
    expect(screen.getByText("changes 0")).toBeInTheDocument();
    expect(server.asked).toEqual([null, 100]);

    vi.setSystemTime(Date.now() + 25_000);
    await server.answer(100);
    expect(screen.getByText("changes 0")).toBeInTheDocument();
    expect(server.asked, "a held question that ran out is asked again at once").toEqual([null, 100, 100]);

    await server.answer(101);
    expect(screen.getByText("changes 1")).toBeInTheDocument();
    expect(server.asked).toEqual([null, 100, 100, 101]);
  });

  it("keeps a change that came while the window was away and shows it once when it comes back", async () => {
    const server = handServer();
    render(
      <ScreenChangesProvider client={server.client}>
        <Count />
      </ScreenChangesProvider>,
    );
    await server.answer(7);
    setVisibility("hidden");
    await server.answer(8);
    await server.answer(9);
    expect(screen.getByText("changes 0")).toBeInTheDocument();

    act(() => setVisibility("visible"));
    expect(screen.getByText("changes 1")).toBeInTheDocument();
  });

  it("rests instead of spinning when the server answers at once without a change or fails", async () => {
    vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"] });
    const server = handServer();
    render(
      <ScreenChangesProvider client={server.client}>
        <Count />
      </ScreenChangesProvider>,
    );
    await server.answer(5);
    await server.answer(5);
    expect(server.asked, "an instant same answer is not a held question").toEqual([null, 5]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(server.asked).toEqual([null, 5, 5]);

    const failing: ScreenChangesClient = { waitForScreenChanges: vi.fn().mockRejectedValue(new Error("down")) };
    render(
      <ScreenChangesProvider client={failing}>
        <Count />
      </ScreenChangesProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(29_000);
    });
    expect(failing.waitForScreenChanges).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(failing.waitForScreenChanges).toHaveBeenCalledTimes(2);
  });

  it("never moves outside the provider", () => {
    render(<Count />);
    expect(screen.getByText("changes 0")).toBeInTheDocument();
  });
});
