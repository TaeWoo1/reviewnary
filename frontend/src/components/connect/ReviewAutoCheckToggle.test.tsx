// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReviewAutoCheckToggle } from "./ReviewAutoCheckToggle";

/**
 * <b>판매자가 가진 것은 스위치 하나다.</b>
 *
 * <p>연결하면 켜져 있다. 그래서 이 화면이 할 일은 끄는 길을 하나 두는 것이고, 동의 문구도 기기 선택도 주기
 * 선택도 두지 않는 것이다 — 고를 것이 하나면 그려야 할 것도 하나다.
 *
 * <p>그리고 멈춤은 꺼짐이 아니다. 도우미가 없거나 로그인이 필요해 멈춘 상태는 <b>켜진 채로</b> 멈춰 있는
 * 것이고, 그 둘을 「꺼짐」으로 그리면 판매자는 자기가 하지 않은 결정을 보게 된다.
 */
const reviewAutoCheck = vi.fn();
const setReviewAutoCheck = vi.fn();
const onReport = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    reviewAutoCheck: (...a: unknown[]) => reviewAutoCheck(...a),
    setReviewAutoCheck: (...a: unknown[]) => setReviewAutoCheck(...a),
  },
  getToken: () => null,
}));

beforeEach(() => {
  reviewAutoCheck.mockReset();
  setReviewAutoCheck.mockReset();
  onReport.mockReset();
});

function mount() {
  return render(<ReviewAutoCheckToggle accountId="acct-1" onReport={onReport} />);
}

describe("새 리뷰 자동 확인 — 설정 하나", () => {
  it("연결된 계정에서는 켜진 상태로 서 있다", async () => {
    reviewAutoCheck.mockResolvedValue({ supported: true, enabled: true, paused: null });
    mount();

    await waitFor(() => expect(screen.getByTestId("review-auto-check-toggle")).toHaveTextContent("자동 확인 켜짐"));
    // 주기도, 기기도, 동의 문구도 없다.
    expect(screen.queryByText(/주기/)).toBeNull();
    expect(screen.queryByText(/기기/)).toBeNull();
  });

  it("끌 수 있고, 끈 뒤에는 수동으로 가져올 수 있다고 말한다", async () => {
    reviewAutoCheck.mockResolvedValue({ supported: true, enabled: true, paused: null });
    setReviewAutoCheck.mockResolvedValue({ supported: true, enabled: false, paused: null });
    mount();

    await waitFor(() => expect(screen.getByTestId("review-auto-check-toggle")).toBeInTheDocument());
    await userEvent.click(screen.getByTestId("review-auto-check-toggle"));

    expect(setReviewAutoCheck).toHaveBeenCalledWith("acct-1", false);
    await waitFor(() => expect(screen.getByTestId("review-auto-check-toggle")).toHaveTextContent("자동 확인 꺼짐"));
    expect(onReport).toHaveBeenCalledWith(
      "자동 확인을 껐습니다. 필요할 때 「지금 확인」으로 가져올 수 있습니다.", false);
  });

  it("읽을 화면이 없는 채널에서는 아무것도 그리지 않는다 — 「꺼짐」은 누군가 끈 것처럼 읽힌다", async () => {
    reviewAutoCheck.mockResolvedValue({ supported: false, enabled: false, paused: null });
    const { container } = mount();

    await waitFor(() => expect(reviewAutoCheck).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("멈춤은 꺼짐이 아니다 — 켜진 채로, 무엇을 기다리는지 말한다", async () => {
    reviewAutoCheck.mockResolvedValue({ supported: true, enabled: true, paused: "PAUSED_AUTH" });
    mount();

    await waitFor(() => expect(screen.getByTestId("review-auto-check-toggle")).toHaveTextContent("자동 확인 켜짐"));
    expect(screen.getByText("판매자센터 로그인이 필요해 기다리고 있습니다.")).toBeInTheDocument();
  });

  it("도우미가 없으면 사람이 할 일을 지시하지 않는다 — 연결되면 이어진다고만 말한다", async () => {
    reviewAutoCheck.mockResolvedValue({ supported: true, enabled: true, paused: "PAUSED_DEVICE" });
    mount();

    await waitFor(() =>
      expect(screen.getByText("이 컴퓨터의 도우미가 연결되면 이어서 확인합니다.")).toBeInTheDocument());
  });

  it("설정을 읽지 못하면 아무 말도 하지 않는다 — 읽기 실패는 꺼짐이 아니다", async () => {
    reviewAutoCheck.mockRejectedValue(new Error("down"));
    const { container } = mount();

    await waitFor(() => expect(reviewAutoCheck).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
