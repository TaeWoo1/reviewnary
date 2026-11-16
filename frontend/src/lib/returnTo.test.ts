// @vitest-environment jsdom
import { describe, expect, it, beforeEach } from "vitest";
import { loginPathFor, rememberReturnTo, safeReturnPath, takeReturnTo } from "./returnTo";

describe("safeReturnPath — 서비스 안 경로만", () => {
  it("keeps an in-service path with its query", () => {
    expect(safeReturnPath("/notice/1b2c")).toBe("/notice/1b2c");
    expect(safeReturnPath("/inquiries/abc?days=90")).toBe("/inquiries/abc?days=90");
  });

  it.each([
    "https://evil.example/x",
    "//evil.example/x",
    "/\\evil.example",
    "/%2F%2Fevil.example",
    "/%5Cevil.example",
    "javascript:alert(1)",
    "/javascript:alert(1)",
    "\\\\evil",
    "notice/1",
    "/notice/1\n",
    "/login?next=/x",
    "/auth/callback?code=x",
    "/signup",
    "",
    null,
    undefined,
    `/${"a".repeat(600)}`,
  ])("refuses %s", (raw) => {
    expect(safeReturnPath(raw as string | null | undefined)).toBeNull();
  });

  it("builds the login link only for a safe, non-home path", () => {
    expect(loginPathFor("/notice/abc")).toBe("/login?next=%2Fnotice%2Fabc");
    expect(loginPathFor("/")).toBe("/login");
    expect(loginPathFor("//evil")).toBe("/login");
    expect(loginPathFor("/notice/abc", "/login?expired=1")).toBe("/login?expired=1&next=%2Fnotice%2Fabc");
  });
});

describe("remember/take — 소셜 로그인 왕복", () => {
  beforeEach(() => sessionStorage.clear());

  it("returns the remembered path once", () => {
    rememberReturnTo("/notice/abc");
    expect(takeReturnTo()).toBe("/notice/abc");
    expect(takeReturnTo()).toBeNull();
  });

  it("re-checks what storage holds", () => {
    sessionStorage.setItem("sellerops_return_to", "//evil.example");
    expect(takeReturnTo()).toBeNull();
  });
});
