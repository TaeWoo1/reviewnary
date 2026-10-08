// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CollectNowAction } from "./CollectNowAction";

/**
 * <b>수집이 로그인 벽에서 멈췄을 때, 판매자가 갈 수 있는 곳이 있는가.</b>
 *
 * 2026-10-07 쿠팡, 10-08 네이버 — 두 번 같은 자리에서 끝났다. 제품은 「로그인한 뒤 다시 수집해 주세요」라고
 * 말할 수 있었지만, 수집이 운전하는 창은 도우미 것이라 판매자가 열 방법이 없었다. 조언이 닿지 않는 세션에
 * 대한 것이었다.
 *
 * 이 파일이 지키는 것은 그 길이 생겼다는 것과, 그 길이 <b>자격을 다루지 않는다</b>는 것, 그리고 로그인
 * 확인이 <b>저장되지 않는다</b>는 것이다.
 */
const startSignIn = vi.fn();
const awaitSignIn = vi.fn();
const collectNow = vi.fn();

vi.mock("../../lib/connect/signInRecovery", async (orig) => {
  const actual = (await orig()) as Record<string, unknown>;
  return {
    ...actual,
    startSignIn: (...a: unknown[]) => startSignIn(...a),
    awaitSignIn: (...a: unknown[]) => awaitSignIn(...a),
  };
});

vi.mock("../../lib/apiClient", () => ({
  api: {
    collectNow: (...a: unknown[]) => collectNow(...a),
    screenReadStatus: vi.fn(),
    collectNowReadiness: vi.fn(),
  },
  getToken: () => null,
}));

const onReport = vi.fn();
const onChanged = vi.fn();

function mount(over: Partial<Parameters<typeof CollectNowAction>[0]> = {}) {
  render(
    <CollectNowAction
      accountId="acct-1"
      dataType="REVIEW"
      label="리뷰"
      desk="AUTH_REQUIRED"
      channelCode="NAVER"
      lastSuccessAt="2026-09-02T03:43:03Z"
      showSentence
      onReport={onReport}
      onChanged={onChanged}
      {...over}
    />,
  );
}

beforeEach(() => {
  startSignIn.mockReset();
  awaitSignIn.mockReset();
  collectNow.mockReset();
  onReport.mockReset();
  onChanged.mockReset();
});

describe("로그인 복구 — 판매자가 직접 로그인하고, 제품은 확인만 한다", () => {
  it("두 사실이 한 화면에 같이 선다 — 과거를 잃지 않는다", () => {
    mount();
    // 로그인이 만료됐다는 소식이, 9월 2일에 읽은 것을 없애지는 않는다. 한 칸으로 합쳐 두었을 때 제품은
    // 실제로 읽은 채널을 「확인된 적 없음」이라고 말했다.
    expect(screen.getByText("최근 수집 시 로그인이 필요했습니다.")).toBeInTheDocument();
    expect(screen.getByText("마지막 성공 수집 9월 2일")).toBeInTheDocument();
  });

  it("로그인 벽에서는 「판매자센터 로그인」이 서 있고, 그게 그 채널의 창을 연다", async () => {
    startSignIn.mockResolvedValue({ ok: true });
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));

    expect(startSignIn).toHaveBeenCalledWith("NAVER");
    await waitFor(() => expect(onReport).toHaveBeenCalledWith("로그인 확인됨. 다시 수집해 주세요.", false));
  });

  it("로그인 확인 뒤에도 수집을 자동으로 돌리지 않는다 — 다음 누름이 그 수집의 승인이다", async () => {
    startSignIn.mockResolvedValue({ ok: true });
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(onReport).toHaveBeenCalled());

    expect(collectNow).not.toHaveBeenCalled();
    // 버튼이 「다시 수집하기」로 바뀌고, 누르는 것은 판매자다.
    await waitFor(() => expect(screen.getByTestId("collect-now-REVIEW")).toHaveTextContent("다시 수집하기"));
    expect(screen.getByText("로그인 확인됨. 다시 수집해 주세요.")).toBeInTheDocument();
  });

  it("「다시 수집하기」는 같은 /collect-now 를 쓴다", async () => {
    startSignIn.mockResolvedValue({ ok: true });
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    collectNow.mockResolvedValue({ path: "SCREEN_READ", dataType: "REVIEW", run: null, screenRead: null });
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(screen.getByTestId("collect-now-REVIEW")).toHaveTextContent("다시 수집하기"));
    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));

    await waitFor(() => expect(collectNow).toHaveBeenCalled());
    const [accountId, dataType] = collectNow.mock.calls[0] as [string, string];
    expect(accountId).toBe("acct-1");
    expect(dataType).toBe("REVIEW");
  });

  it("로그인 확인은 이 탭에서만 유효하다 — 다시 그리면 사라진다", async () => {
    startSignIn.mockResolvedValue({ ok: true });
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    const { unmount } = render(
      <CollectNowAction
        accountId="acct-1"
        dataType="REVIEW"
        label="리뷰"
        desk="AUTH_REQUIRED"
        channelCode="NAVER"
        showSentence
        onReport={onReport}
        onChanged={onChanged}
      />,
    );
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(screen.getByText("로그인 확인됨. 다시 수집해 주세요.")).toBeInTheDocument());
    unmount();

    // 서버는 이것을 저장하지 않는다. 새로고침 뒤 화면은 마지막 수집 시도의 사실로 돌아가고, 그 문장은
    // 다음 수집까지 참이다 — 몇 분 전 probe를 근거로 「지금 로그인되어 있습니다」라고 하는 쪽이 거짓이다.
    mount();
    expect(screen.getByText("최근 수집 시 로그인이 필요했습니다.")).toBeInTheDocument();
    expect(screen.queryByText("로그인 확인됨. 다시 수집해 주세요.")).toBeNull();
  });

  it("로그인이 확인되지 않으면 실패가 아니라 「다시」라고 말한다", async () => {
    startSignIn.mockResolvedValue({ ok: true });
    awaitSignIn.mockResolvedValue("NOT_SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    // MFA는 어떤 bound보다 오래 걸릴 수 있다. 그건 고장이 아니다.
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith(
        "로그인 창을 지켜보는 시간이 끝났습니다. 로그인을 마친 뒤 [판매자센터 로그인]을 다시 눌러 주세요.",
        true,
      ),
    );
    expect(screen.getByTestId("collect-now-REVIEW")).toHaveTextContent("지금 수집하기");
  });

  it("이 Mac의 도우미와 연결이 없으면, 내부 개념을 꺼내지 않고 그 한 가지를 말한다", async () => {
    startSignIn.mockResolvedValue({ ok: false, reason: "not_connected" });
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("이 Mac의 도우미와 연결이 필요합니다.", true),
    );
    const said = onReport.mock.calls.map((c) => String(c[0])).join(" ");
    for (const term of ["pairing", "bridge", "carrier", "device grant", "토큰", "bearer"]) {
      expect(said).not.toContain(term);
    }
  });

  it("이미 로그인 창이 열려 있으면 두 번째 창을 열지 않는다", async () => {
    startSignIn.mockResolvedValue({ ok: false, reason: "busy" });
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith(
        "이 컴퓨터에서 이미 로그인 창이 열려 있습니다. 그 창에서 로그인을 마쳐 주세요.",
        true,
      ),
    );
    expect(awaitSignIn).not.toHaveBeenCalled();
  });

  it("로그인 창이 열려 있는 동안에는 수집을 누를 수 없다", async () => {
    startSignIn.mockResolvedValue({ ok: true });
    let settle: (v: string) => void = () => undefined;
    awaitSignIn.mockReturnValue(new Promise((r) => (settle = r as (v: string) => void)));
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(screen.getByTestId("collect-now-REVIEW")).toBeDisabled());
    expect(screen.getByTestId("sign-in-REVIEW")).toHaveTextContent("로그인 창에서 로그인해 주세요…");

    settle("SIGNED_IN");
    await waitFor(() => expect(screen.getByTestId("collect-now-REVIEW")).not.toBeDisabled());
  });

  it("로그인 벽이 아니면 로그인 버튼은 없다", () => {
    for (const desk of ["READY", "BUSY", "UNPAIRED"] as const) {
      onReport.mockReset();
      const { unmount } = render(
        <CollectNowAction
          accountId="acct-1"
          dataType="REVIEW"
          label="리뷰"
          desk={desk}
          channelCode="NAVER"
          showSentence
          onReport={onReport}
          onChanged={onChanged}
        />,
      );
      expect(screen.queryByTestId("sign-in-REVIEW")).toBeNull();
      unmount();
    }
  });

  it("채널을 모르면 로그인 버튼을 그리지 않는다 — 어느 판매자센터인지 추측하지 않는다", () => {
    mount({ channelCode: null });
    expect(screen.queryByTestId("sign-in-REVIEW")).toBeNull();
    expect(screen.getByText("최근 수집 시 로그인이 필요했습니다.")).toBeInTheDocument();
  });

  it("마지막 성공 수집이 없으면 그 줄을 지어내지 않는다", () => {
    mount({ lastSuccessAt: null });
    expect(screen.queryByText(/마지막 성공 수집/)).toBeNull();
  });
});
