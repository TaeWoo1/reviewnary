// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { SettingsHome } from "./SettingsHome";
import { expectNoAxeViolations } from "../../test/axe";

const logout = vi.fn();
vi.mock("../../lib/auth", () => ({
  useAuth: () => ({
    user: {
      id: "u1",
      email: "operator@example.test",
      name: "운영자",
      orgId: "o1",
      orgName: "테스트 스토어",
    },
    ready: true,
    login: vi.fn(),
    logout,
  }),
}));

function renderSettings() {
  return render(
    <MemoryRouter>
      <SettingsHome />
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe("설정", () => {
  it("shows only facts already in the session", () => {
    renderSettings();
    expect(screen.getByRole("heading", { level: 1, name: "설정" })).toBeInTheDocument();
    expect(screen.getByText("테스트 스토어")).toBeInTheDocument();
    expect(screen.getByText("운영자")).toBeInTheDocument();
    expect(screen.getByText("operator@example.test")).toBeInTheDocument();
  });

  it("states plainly which data the screens are showing", () => {
    vi.stubEnv("VITE_USE_MOCKS", "true");
    renderSettings();
    expect(screen.getByText("데모 데이터")).toBeInTheDocument();
  });

  it("routes to the company profile, and says what it is for and what it is not", () => {
    renderSettings();
    expect(screen.getByRole("link", { name: "회사 소개 적기" })).toHaveAttribute("href", "/settings/company");
    expect(screen.getByText(/배송·환불·규격의 근거는 아닙니다/)).toBeInTheDocument();
  });

  it("routes to the alert list", () => {
    renderSettings();
    expect(screen.getByRole("link", { name: "알림 보기" })).toHaveAttribute(
      "href",
      "/settings/alerts",
    );
  });

  it("offers the account action", () => {
    renderSettings();
    expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
  });

  /**
   * <b>네 구역이 이름을 가진다.</b> 전에는 이 화면에 `h2`가 하나도 없었다 — 구역의 이름은 전부
   * `aria-label`에만 있었고, 눈으로 읽는 사람에게 이 화면의 구조는 상자 넷의 간격뿐이었다.
   */
  it("names its four groups where everyone can read them, not only screen readers", () => {
    renderSettings();
    for (const group of ["워크스페이스", "AI 답변과 운영 기준", "자동 운영", "계정"]) {
      expect(screen.getByRole("heading", { level: 2, name: group })).toBeInTheDocument();
    }
  });

  /**
   * 한 줄은 이름 · 지금 값(또는 이 설정이 정하는 것) · 들어가는 길이다. 「— 로 시작하는 설명문」이 여덟
   * 줄에 반복되면 그 문장들은 함께 읽히지 않는다. 길이를 줄인 것이지 뜻을 줄인 것은 아니다.
   */
  it("keeps the boundary that would make a row false if it were trimmed away", () => {
    renderSettings();
    // 회사 정보가 답변의 표현에만 쓰이고 배송·환불·규격의 근거가 아니라는 것은 이 줄의 뜻 자체다.
    expect(screen.getByText(/배송·환불·규격의 근거는 아닙니다/)).toBeInTheDocument();
    // em dash로 시작하던 설명 문장은 남지 않았다.
    expect(document.body.textContent).not.toContain("어떤 회사인지 —");
    expect(document.body.textContent).not.toContain("무엇을 안내할지 —");
    expect(document.body.textContent).not.toContain("어떻게 말할지 —");
  });

  /**
   * 강조는 되돌리는 값이 다른 곳에만 — 계정에서 나가는 일과 이 계정에 붙은 기기. 나머지 여섯 줄은 다른
   * 화면으로 가는 길이고, 길은 단추가 아니라 글자다.
   */
  it("spends emphasis on the two account actions and on nothing else", () => {
    renderSettings();
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
    const strong = screen
      .getAllByRole("link")
      .filter((link) => link.className.includes("text-brand-700"));
    expect(strong.map((link) => link.textContent)).toEqual(["기기 보기"]);
  });

  it("builds no setting that controls nothing", () => {
    // A switch that flips nothing is a promise the product does not keep. Settings arrive when the
    // capability behind them does.
    const { container } = renderSettings();
    expect(container.querySelectorAll('input[type="checkbox"]')).toHaveLength(0);
    expect(container.querySelectorAll("select")).toHaveLength(0);
    // The only control is the account action.
    expect(screen.getAllByRole("button")).toHaveLength(1);
  });

  it("has no axe violations", async () => {
    const { container } = renderSettings();
    await expectNoAxeViolations(container);
  });
});
