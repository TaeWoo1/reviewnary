// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { Cafe24ConnectResult } from "./Cafe24ConnectResult";

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname + location.search}</p>;
}

function land(query: string) {
  render(
    <MemoryRouter initialEntries={[`/connect/cafe24/result?${query}`]}>
      <Routes>
        <Route path="/connect/cafe24/result" element={<Cafe24ConnectResult />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

// B213 (2026-11-18): a seller whose Cafe24 consent had lapsed consented again and was walked through the
// first-connection tutorial for a channel they had been using for two months.
describe("Cafe24 consent result — where the seller lands", () => {
  afterEach(cleanup);
  it("a restored connection goes back to that channel, marked as a reconnect", async () => {
    land("status=connected&accountId=acc-1&reconnected=1");
    expect((await screen.findByTestId("where")).textContent).toBe("/connect/channels/acc-1?reconnected=1");
  });

  it("a first connection still goes into the first-connection walk", async () => {
    land("status=connected&accountId=acc-1");
    expect((await screen.findByTestId("where")).textContent).toBe(
      "/connect/cafe24/tutorial?status=connected&accountId=acc-1",
    );
  });
});
