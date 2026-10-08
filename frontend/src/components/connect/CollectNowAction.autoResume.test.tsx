// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CollectNowAction, coverageSentence } from "./CollectNowAction";

/**
 * <b>첫 「지금 수집하기」가 그 수집의 승인이었다.</b>
 *
 * <p>2026-10-08 라이브: 판매자가 수집을 눌렀고, 로그인 벽에서 멈췄고, 같은 화면에서 판매자센터 로그인까지
 * 이어왔다 — 그리고 수집을 <b>한 번 더</b> 눌러야 했다. 두 번째 누름은 새로운 결정이 아니었다. 같은 조직,
 * 같은 계정, 같은 자료, 같은 세션에서 이미 승인된 그 한 건이 로그인을 기다리고 있었을 뿐이다.
 *
 * <p>이 파일이 지키는 것은 그 재개가 <b>정확히 한 번</b>이고, <b>그 수집에서 출발한 로그인에만</b> 붙는다는
 * 것이다. 혼자 로그인한 판매자에게 수집이 저절로 시작되면, 그 수집은 아무도 승인한 적이 없는 수집이다.
 */
const startSignIn = vi.fn();
const awaitSignIn = vi.fn();
const collectNow = vi.fn();
const screenReadStatus = vi.fn();

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
    screenReadStatus: (...a: unknown[]) => screenReadStatus(...a),
    collectNowReadiness: (...a: unknown[]) => collectNowReadiness(...a),
  },
  getToken: () => null,
}));

const collectNowReadiness = vi.fn();
const onReport = vi.fn();
const onChanged = vi.fn();

/** 서버가 들고 있는 사실: 이 자료의 catch-up 하나가 로그인을 기다리는가. */
function pausedOnServer(paused: boolean) {
  collectNowReadiness.mockResolvedValue({
    path: "SCREEN_READ", localAgent: "AUTH_REQUIRED", lastSuccessAt: null,
    latestAttemptOutcome: "AUTH_REQUIRED", coverageThrough: "2026-09-02", coverageGapDays: 37,
    pausedCatchUp: paused,
  });
}

function mount(over: Record<string, unknown> = {}) {
  return render(
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

/** 수집 한 번이 로그인 벽에서 끝난다 — 2026-10-07 쿠팡·10-08 네이버에서 실제로 그렇게 끝났다. */
function collectHitsTheWall() {
  collectNow.mockResolvedValue({
    path: "SCREEN_READ",
    dataType: "REVIEW",
    run: null,
    screenRead: { jobId: "job-1", state: "RUNNING", inserted: null, changed: null },
  });
  screenReadStatus.mockResolvedValue({ jobId: "job-1", state: "AUTH_REQUIRED", inserted: null, changed: null });
}

beforeEach(() => {
  startSignIn.mockReset();
  awaitSignIn.mockReset();
  collectNow.mockReset();
  screenReadStatus.mockReset();
  onReport.mockReset();
  onChanged.mockReset();
  collectNowReadiness.mockReset();
  startSignIn.mockResolvedValue({ ok: true });
  // 기본값은 「서버에 멈춘 의도가 없다」 — 그러면 이 탭의 기억만이 이어갈 근거다.
  pausedOnServer(false);
});

/**
 * <b>2026-10-09: 로그인했는데 아무것도 이어지지 않았다.</b>
 *
 * 첫 로그인 시도가 지켜보는 시간이 끝나 `NOT_SIGNED_IN`으로 끝나는 순간, 이어갈 승인은 이 탭의 메모리에서
 * 버려졌다. 30초 뒤 두 번째 시도가 로그인을 확인했을 때는 이어갈 표식이 없었고, 판매자는 「로그인 확인됨」을
 * 보고도 멈춘 화면 앞에 있었다. 새로고침하면 더 확실히 사라졌다.
 *
 * 이어갈 일이 있는지는 서버가 안다 — 멈춘 catch-up은 row이고, 그 row는 이 탭이 무엇을 기억하든 거기 있다.
 */
describe("이어갈 일이 있는지는 서버가 안다 — 이 탭의 기억이 아니라", () => {
  it("이 탭이 잊었어도, 서버에 멈춘 catch-up이 있으면 로그인 확인 뒤 이어진다", async () => {
    // 수집을 누른 적이 없는 탭(새로고침한 뒤) — 그래도 서버에는 멈춘 의도가 있다.
    pausedOnServer(true);
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    collectNow.mockResolvedValue({
      path: "SCREEN_READ", dataType: "REVIEW", run: null,
      screenRead: { jobId: "job-2", state: "RUNNING", inserted: null, changed: null },
    });
    screenReadStatus.mockResolvedValue({ jobId: "job-2", state: "OBSERVED", observed: 45, inserted: 45, changed: 0 });

    mount();
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));
    expect(onReport).toHaveBeenCalledWith("로그인 확인됨. 멈췄던 수집을 이어서 진행합니다.", false);
  });

  it("서버에 멈춘 의도가 없으면 혼자 로그인한 것으로 수집이 시작되지 않는다", async () => {
    // 계약은 그대로다: standalone login은 수집을 시작하지 않는다.
    pausedOnServer(false);
    awaitSignIn.mockResolvedValue("SIGNED_IN");

    mount();
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("로그인 확인됨. 다시 수집해 주세요.", false));
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("서버에 물어보지 못했으면 시작하지 않는다 — 모른다는 이유로 수집을 돌리지 않는다", async () => {
    collectNowReadiness.mockRejectedValue(new Error("down"));
    awaitSignIn.mockResolvedValue("SIGNED_IN");

    mount();
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("로그인 확인됨. 다시 수집해 주세요.", false));
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("로그인이 확인되지 않았으면 서버에 멈춘 의도가 있어도 시작하지 않는다", async () => {
    pausedOnServer(true);
    awaitSignIn.mockResolvedValue("NOT_SIGNED_IN");

    mount();
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(onReport).toHaveBeenCalledWith(
      "로그인 창을 지켜보는 시간이 끝났습니다. 로그인을 마친 뒤 [판매자센터 로그인]을 다시 눌러 주세요.", true));
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("회차가 바뀌면 그 사실을 말한다 — 몇 분을 말없이 기다리게 두지 않는다", async () => {
    pausedOnServer(false);
    awaitSignIn.mockResolvedValue("NOT_SIGNED_IN");

    mount();
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(onReport).toHaveBeenCalledWith(
      expect.stringContaining("아직 기다리고 있습니다"), false));
  });
});

describe("로그인 복구 뒤 멈췄던 수집을 이어간다 — 한 번만", () => {
  it("수집 → 로그인 벽 → 로그인 확인 → 수집이 저절로 이어진다", async () => {
    collectHitsTheWall();
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));

    // 승인을 다시 묻지 않는다 — 재개가 두 번째 호출이고, 판매자는 아무것도 더 누르지 않았다.
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(2));
    const said = onReport.mock.calls.map((c) => String(c[0]));
    expect(said).toContain("로그인 확인됨. 멈췄던 수집을 이어서 진행합니다.");
    // 이어서 할 일이 우리 쪽에 있을 때는 「다시 수집해 주세요」라고 하지 않는다.
    expect(said).not.toContain("로그인 확인됨. 다시 수집해 주세요.");
    // 두 호출 모두 같은 계정·같은 자료다.
    for (const call of collectNow.mock.calls) {
      expect(call[0]).toBe("acct-1");
      expect(call[1]).toBe("REVIEW");
    }
  });

  it("정확히 한 번이다 — 두 번째 로그인은 아무것도 시작하지 않는다", async () => {
    collectHitsTheWall();
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(2));

    // 재개된 수집도 벽에서 끝났다면 그건 새로운 pending이지만, 승인 하나가 두 번 쓰이지는 않는다:
    // 로그인 버튼이 사라졌으므로 같은 승인으로 또 이어갈 길이 없다.
    expect(screen.queryByTestId("sign-in-REVIEW")).toBeNull();
    expect(collectNow).toHaveBeenCalledTimes(2);
  });

  it("혼자 로그인한 것으로는 수집이 시작되지 않는다", async () => {
    // 설정 화면에서 그냥 로그인만 한 판매자. 승인한 수집이 없으므로 시작할 수집도 없다.
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(onReport).toHaveBeenCalled());

    expect(collectNow).not.toHaveBeenCalled();
    expect(onReport.mock.calls.map((c) => String(c[0]))).toContain("로그인 확인됨. 다시 수집해 주세요.");
    await waitFor(() => expect(screen.getByTestId("collect-now-REVIEW")).toHaveTextContent("다시 수집하기"));
  });

  it("벽이 아닌 결말은 승인을 남기지 않는다 — 성공한 수집을 로그인으로 또 돌리지 않는다", async () => {
    collectNow.mockResolvedValue({
      path: "SCREEN_READ",
      dataType: "REVIEW",
      run: null,
      screenRead: { jobId: "job-1", state: "SUCCESS", inserted: 45, changed: 0 },
    });
    screenReadStatus.mockResolvedValue({ jobId: "job-1", state: "SUCCESS", inserted: 45, changed: 0 });
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount({ desk: "AUTH_REQUIRED" });

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(onReport).toHaveBeenCalled());

    expect(collectNow).toHaveBeenCalledTimes(1);
  });

  it("로그인이 확인되지 않으면 승인은 버려진다 — 자리를 떠난 사람 뒤에서 수집이 시작되지 않는다", async () => {
    collectHitsTheWall();
    awaitSignIn.mockResolvedValue("NOT_SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith(
        "로그인 창을 지켜보는 시간이 끝났습니다. 로그인을 마친 뒤 [판매자센터 로그인]을 다시 눌러 주세요.",
        true,
      ),
    );
    expect(collectNow).toHaveBeenCalledTimes(1);
  });

  it("로그인 창을 열지 못했으면 승인도 버린다", async () => {
    collectHitsTheWall();
    startSignIn.mockResolvedValue({ ok: false, reason: "not_connected" });
    mount();

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("이 Mac의 도우미와 연결이 필요합니다.", true),
    );
    expect(awaitSignIn).not.toHaveBeenCalled();
    expect(collectNow).toHaveBeenCalledTimes(1);
  });
});

describe("창을 열었다는 말을 먼저 한다", () => {
  it("로그인 창이 열리면 그 사실을 알린다 — 앞으로 나오지 않았을 수도 있으니까", async () => {
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(onReport).toHaveBeenCalled());
    expect(onReport.mock.calls.map((c) => String(c[0]))[0])
      .toBe("판매자센터 로그인 창을 열었습니다. 그 창에서 로그인해 주세요.");
  });
});

describe("어디까지 빠짐없이 확인했는가", () => {
  it("성공 시각과 경계가 한 화면에 같이 선다", () => {
    mount({ desk: "READY", coverageThrough: "2026-09-02", coverageGapDays: 29 });
    // 10-08 성공은 사실이고, 그 성공이 덮은 것은 최근 7일이었다. 두 줄이 같이 서야 29일이 보인다.
    expect(screen.getByText("마지막 성공 수집 9월 2일")).toBeInTheDocument();
    expect(screen.getByText("9월 2일까지 빠짐없이 확인 · 이후 29일은 아직")).toBeInTheDocument();
  });

  it("경계가 없으면 아무 말도 하지 않는다", () => {
    expect(coverageSentence(null, 12)).toBeNull();
    mount({ desk: "READY", coverageThrough: null, coverageGapDays: null });
    expect(screen.queryByText(/빠짐없이 확인/)).toBeNull();
  });

  it("공백이 없으면 0일을 세어 보여 주지 않는다", () => {
    expect(coverageSentence("2026-10-08", 0)).toBe("10월 8일까지 빠짐없이 확인");
    expect(coverageSentence("2026-10-08", null)).toBe("10월 8일까지 빠짐없이 확인");
  });
});
