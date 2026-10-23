// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { AnswerStyle, previewLines } from "./AnswerStyle";
import type { AnswerStyleView } from "../../lib/types";

const getAnswerStyle = vi.fn();
const saveAnswerStyle = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getAnswerStyle: (...args: unknown[]) => getAnswerStyle(...args),
    saveAnswerStyle: (...args: unknown[]) => saveAnswerStyle(...args),
  },
}));

function style(over: Partial<AnswerStyleView> = {}): AnswerStyleView {
  return {
    tone: "POLITE",
    lengthPreference: "NORMAL",
    emojiPolicy: "NONE",
    greeting: null,
    closing: null,
    customerAddress: null,
    requiredPhrases: [],
    forbiddenPhrases: [],
    unknownFallbackTemplate: null,
    configured: false,
    version: 0,
    ...over,
  };
}

function renderScreen() {
  return render(
    <MemoryRouter>
      <AnswerStyle />
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.clearAllMocks();
});

describe("AI 답변 스타일", () => {
  it("says nothing is set rather than implying somebody chose the defaults", async () => {
    getAnswerStyle.mockResolvedValue(style());
    renderScreen();

    expect(await screen.findByText(/아직 설정하지 않으셨습니다/)).toBeTruthy();
  });

  it("uses the seller's own words on every control — no prompt, no model, no temperature", async () => {
    getAnswerStyle.mockResolvedValue(style({ configured: true, version: 2 }));
    renderScreen();

    await screen.findByLabelText("답변 말투");
    for (const label of ["답변 길이", "이모지", "고객 호칭", "첫 인사", "끝 인사",
      "꼭 포함할 표현", "절대 쓰지 않을 표현", "피할 표현 1", "대신 쓸 표현 1", "답을 모를 때 사용할 문구"]) {
      expect(screen.getByLabelText(label)).toBeTruthy();
    }
    for (const forbidden of ["프롬프트", "prompt", "system", "temperature", "모델"]) {
      expect(screen.queryByText(new RegExp(forbidden, "i"))).toBeNull();
    }
  });

  it("saves the whole form, with the phrase boxes split one per line", async () => {
    getAnswerStyle.mockResolvedValue(style());
    saveAnswerStyle.mockResolvedValue(style({ configured: true, version: 1 }));
    renderScreen();

    await screen.findByLabelText("첫 인사");
    await userEvent.type(screen.getByLabelText("첫 인사"), "안녕하세요. 선바로입니다.");
    await userEvent.type(screen.getByLabelText("꼭 포함할 표현"), "정성껏 준비하겠습니다\n\n잘 부탁드립니다");
    await userEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(saveAnswerStyle).toHaveBeenCalledTimes(1));
    expect(saveAnswerStyle.mock.calls[0][0]).toMatchObject({
      tone: "POLITE",
      greeting: "안녕하세요. 선바로입니다.",
      requiredPhrases: ["정성껏 준비하겠습니다", "잘 부탁드립니다"],
      forbiddenPhrases: [],
      unknownFallbackTemplate: null,
      avoidPhrases: [],
    });
    expect(await screen.findByText("저장했습니다.")).toBeTruthy();
  });

  /** The split the owner approved on 2026-10-23 (mockup ①). */
  it("keeps a ban and a preference apart, and sends each preference with what to write instead", async () => {
    getAnswerStyle.mockResolvedValue(style({ configured: true, version: 4, forbiddenPhrases: ["고객님 탓"],
      avoidPhrases: [{ phrase: "죄송하지만", replacement: "번거로우시겠지만" }] }));
    saveAnswerStyle.mockImplementation(async (form) => style({ ...form, configured: true, version: 5 }));
    renderScreen();

    expect(await screen.findByDisplayValue("고객님 탓")).toBeTruthy();
    expect(screen.getByText("발견되면 승인 · 등록 보류")).toBeTruthy();
    expect(screen.getByText("대신 쓸 표현을 제안 · 그대로 둬도 승인 가능")).toBeTruthy();
    expect((screen.getByLabelText("피할 표현 1") as HTMLInputElement).value).toBe("죄송하지만");
    expect((screen.getByLabelText("대신 쓸 표현 1") as HTMLInputElement).value).toBe("번거로우시겠지만");

    await userEvent.type(screen.getByLabelText("피할 표현 2"), "양해 부탁드립니다");
    await userEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(saveAnswerStyle).toHaveBeenCalledTimes(1));
    expect(saveAnswerStyle.mock.calls[0][0]).toMatchObject({
      forbiddenPhrases: ["고객님 탓"],
      avoidPhrases: [
        { phrase: "죄송하지만", replacement: "번거로우시겠지만" },
        { phrase: "양해 부탁드립니다", replacement: null },
      ],
    });
    await userEvent.click(await screen.findByRole("button", { name: "「죄송하지만」 빼기" }));
    expect((screen.getByLabelText("피할 표현 1") as HTMLInputElement).value).toBe("양해 부탁드립니다");
  });

  it("warns as it is typed that a bare topic word would block plain guidance, with the right particle", async () => {
    getAnswerStyle.mockResolvedValue(style());
    renderScreen();

    await screen.findByLabelText("절대 쓰지 않을 표현");
    expect(screen.queryByRole("note")).toBeNull();
    await userEvent.type(screen.getByLabelText("절대 쓰지 않을 표현"), "환불\n무조건 전액 환불\n회수");
    const notes = screen.getAllByRole("note");
    expect(notes).toHaveLength(2);
    expect(notes[0].textContent).toContain("「환불은 주문 내역에서 접수해 주세요」");
    expect(notes[0].textContent).toContain("이 줄은 리뷰에서는 적용하지 않습니다");
    expect(notes[1].textContent).toContain("「회수는 주문 내역에서");
  });

  it("shows the server's own refusal — it names which phrase, and that is the useful part", async () => {
    getAnswerStyle.mockResolvedValue(style());
    saveAnswerStyle.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: "「꼭 포함할 표현」에는 사실을 단정하는 문장을 넣을 수 없습니다: \"당일 발송됩니다\" (\"발송\")." } },
    });
    renderScreen();

    await screen.findByLabelText("꼭 포함할 표현");
    await userEvent.type(screen.getByLabelText("꼭 포함할 표현"), "당일 발송됩니다");
    await userEvent.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByText(/사실을 단정하는 문장을 넣을 수 없습니다/)).toBeTruthy();
  });

  it("previews the style and nothing else — the body stays a placeholder", async () => {
    getAnswerStyle.mockResolvedValue(
      style({ configured: true, version: 3, greeting: "안녕하세요. 선바로입니다.", closing: "감사합니다." }),
    );
    renderScreen();

    expect(await screen.findByText("배송은 언제 되나요?")).toBeTruthy();
    expect(screen.getByText(/여기에 등록된 답변 기준으로/)).toBeTruthy();
    // The one thing a style preview must never do: answer the synthetic question as if it were policy.
    expect(screen.queryByText(/영업일/)).toBeNull();
    expect(screen.queryByText(/출고/)).toBeNull();
  });
});

describe("previewLines", () => {
  const base = {
    tone: "POLITE" as const,
    length: "NORMAL" as const,
    greeting: "",
    closing: "",
    address: "",
    required: "",
    emoji: "NONE" as const,
  };

  it("is a pure function of the form — it calls nothing and invents no fact", () => {
    const lines = previewLines({ ...base, greeting: "안녕하세요.", closing: "감사합니다." });
    expect(lines[0].text).toBe("안녕하세요.");
    expect(lines[1].placeholder).toBe(true);
    expect(lines[lines.length - 1].text).toBe("감사합니다.");
  });

  it("puts a required phrase in the reply and an emoji only where allowed", () => {
    const lines = previewLines({
      ...base,
      required: "정성껏 준비하겠습니다",
      closing: "감사합니다.",
      emoji: "LIMITED",
    });
    expect(lines.map((l) => l.text)).toContain("정성껏 준비하겠습니다");
    expect(lines[lines.length - 1].text).toContain("🙂");
  });

  it("says how long the answer will be, without writing one", () => {
    expect(previewLines({ ...base, length: "SHORT" })[0].text).toContain("짧은");
    expect(previewLines({ ...base, length: "DETAILED" })[0].text).toContain("자세한");
  });
});
