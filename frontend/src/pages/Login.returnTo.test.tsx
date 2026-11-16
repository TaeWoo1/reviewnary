// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

/** After signing in, the seller lands where the alert link was going — and never off this service. */
const login = vi.fn(() => Promise.resolve());
vi.mock("../lib/auth", () => ({ useAuth: () => ({ login }) }));
vi.mock("../hooks/useDemoEntry", () => ({ useDemoEntry: () => false }));
vi.mock("../lib/apiClient", async (orig) => {
  const mod = await orig<typeof import("../lib/apiClient")>();
  return {
    ...mod,
    api: {
      ...mod.api,
      passwordResetConfig: () => Promise.resolve({ enabled: false, devOutbox: false }),
      socialProviders: () => Promise.resolve([]),
    },
  };
});

import { Login } from "./Login";

function Where() {
  const l = useLocation();
  return <p>at:{`${l.pathname}${l.search}`}</p>;
}

function at(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function signIn() {
  fireEvent.change(await screen.findByLabelText("이메일"), { target: { value: "a@b.c" } });
  fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "pw" } });
  fireEvent.click(screen.getByRole("button", { name: "로그인" }));
}

describe("로그인 후 원래 화면으로", () => {
  beforeEach(() => {
    sessionStorage.clear();
    login.mockClear();
  });

  it("goes back to the alert's notice page", async () => {
    at("/login?next=%2Fnotice%2Fd1");
    expect(sessionStorage.getItem("sellerops_return_to")).toBe("/notice/d1");
    await signIn();
    expect(await screen.findByText("at:/notice/d1")).toBeInTheDocument();
  });

  it("an outside address is ignored and the seller lands on home", async () => {
    at("/login?next=%2F%2Fevil.example%2Fx");
    await signIn();
    expect(await screen.findByText("at:/")).toBeInTheDocument();
    expect(sessionStorage.getItem("sellerops_return_to")).toBeNull();
  });
});
