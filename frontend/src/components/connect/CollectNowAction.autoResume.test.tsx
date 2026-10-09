// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CollectNowAction, coverageSentence, freshnessSentence } from "./CollectNowAction";

/**
 * <b>첫 「지금 수집하기」가 그 수집의 승인이었다 — 그리고 이제 승인은 둘 중 하나다.</b>
 *
 * <p>2026-10-08 라이브: 판매자가 수집을 눌렀고, 로그인 벽에서 멈췄고, 같은 화면에서 판매자센터 로그인까지
 * 이어왔다 — 그리고 수집을 <b>한 번 더</b> 눌러야 했다. 두 번째 누름은 새로운 결정이 아니었다. 같은 조직,
 * 같은 계정, 같은 자료, 같은 세션에서 이미 승인된 그 한 건이 로그인을 기다리고 있었을 뿐이다.
 *
 * <p>이 파일이 지키는 것은 그 재개가 <b>정확히 한 번</b>이고, <b>기다리던 일이 있을 때만</b> 일어난다는
 * 것이다. 혼자 로그인한 판매자에게 수집이 저절로 시작되면, 그 수집은 아무도 승인한 적이 없는 수집이다.
 *
 * <p><b>바뀐 것 하나.</b> 이어가기는 「수집을 한 번 더 누르기」가 아니라 하나의 resume 호출이고, 멈춰 있던
 * 일이 누름이었는지 자동 확인이었는지는 <b>서버가</b> 안다 — 그 row가 자기 trigger를 들고 있다. 화면이
 * 그것을 판단하면 provenance를 밖에서 정하는 셈이고, 그렇게 해서 아무도 누르지 않은 읽기가 「눌렸다」로
 * 기록된다. 그래서 이 탭은 「로그인이 확인됐다」만 전한다.
 */
const startSignIn = vi.fn();
const awaitSignIn = vi.fn();
const collectNow = vi.fn();
const collectNowResume = vi.fn();
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
    collectNowResume: (...a: unknown[]) => collectNowResume(...a),
    screenReadStatus: (...a: unknown[]) => screenReadStatus(...a),
    collectNowReadiness: (...a: unknown[]) => collectNowReadiness(...a),
  },
  getToken: () => null,
}));

const collectNowReadiness = vi.fn();
const onReport = vi.fn();
const onChanged = vi.fn();

/**
 * 서버가 들고 있는 사실: 이 자료의 확인 하나가 로그인을 기다리는가.
 *
 * <p>이어가면 그 일이 다시 책상에 올라가므로, resume은 <b>그 작업</b>을 돌려준다. 기다리던 것이 없으면
 * `null`이고, 그것은 실패가 아니라 「이어갈 것이 없다」다 — 화면은 그때 아무것도 시작하지 않는다.
 */
function pausedOnServer(paused: boolean) {
  collectNowResume.mockResolvedValue(
    paused ? { jobId: "job-resumed", state: "RUNNING", inserted: null, changed: null } : null,
  );
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
  collectNowResume.mockReset();
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
  it("이 탭이 잊었어도, 서버에 멈춘 확인이 있으면 로그인 확인 뒤 이어진다", async () => {
    // 수집을 누른 적이 없는 탭(새로고침한 뒤) — 그래도 서버에는 멈춘 의도가 있다. 그 의도가 누름이었는지
    // 자동 확인이었는지 이 탭은 묻지 않는다: 하나의 resume이고, 서버가 그 row의 trigger 그대로 이어간다.
    pausedOnServer(true);
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    screenReadStatus.mockResolvedValue({
      jobId: "job-resumed", state: "SUCCESS", observed: 45, inserted: 45, changed: 0,
    });

    mount();
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(collectNowResume).toHaveBeenCalledTimes(1));
    expect(collectNowResume).toHaveBeenCalledWith("acct-1", "REVIEW");
    // 새 수집을 시작하지 않는다 — 이어가기는 시작이 아니다.
    expect(collectNow).not.toHaveBeenCalled();
    expect(onReport).toHaveBeenCalledWith("로그인 확인됨. 멈췄던 확인을 이어서 진행합니다.", false);
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
    collectNowResume.mockRejectedValue(new Error("down"));
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
    // 로그인이 확인되지 않았으면 서버에 물어볼 일도 없다 — 이어갈 조건 자체가 성립하지 않는다.
    expect(collectNowResume).not.toHaveBeenCalled();
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
  it("수집 → 로그인 벽 → 로그인 확인 → 멈췄던 그 확인이 이어진다", async () => {
    collectHitsTheWall();
    pausedOnServer(true);
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));

    // 승인을 다시 묻지 않는다 — 재개는 resume 한 번이고, 판매자는 아무것도 더 누르지 않았다.
    await waitFor(() => expect(collectNowResume).toHaveBeenCalledTimes(1));
    // 그리고 그것은 새 수집이 아니다: collectNow는 판매자가 누른 그 한 번뿐이다.
    expect(collectNow).toHaveBeenCalledTimes(1);
    const said = onReport.mock.calls.map((c) => String(c[0]));
    expect(said).toContain("로그인 확인됨. 멈췄던 확인을 이어서 진행합니다.");
    // 이어서 할 일이 우리 쪽에 있을 때는 「다시 수집해 주세요」라고 하지 않는다.
    expect(said).not.toContain("로그인 확인됨. 다시 수집해 주세요.");
    expect(collectNowResume).toHaveBeenCalledWith("acct-1", "REVIEW");
  });

  it("정확히 한 번이다 — 두 번째 로그인은 아무것도 시작하지 않는다", async () => {
    collectHitsTheWall();
    pausedOnServer(true);
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    mount();

    await userEvent.click(screen.getByTestId("collect-now-REVIEW"));
    await waitFor(() => expect(collectNow).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));
    await waitFor(() => expect(collectNowResume).toHaveBeenCalledTimes(1));

    // 1회성은 서버의 상태 전이가 보장한다: 이어진 run은 더 이상 PAUSED_AUTH가 아니므로 두 번째 알림은
    // 아무것도 찾지 못한다. 그때 화면이 새 수집을 시작하지는 않는다.
    expect(screen.queryByTestId("sign-in-REVIEW")).toBeNull();
    expect(collectNow).toHaveBeenCalledTimes(1);
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

  it("벽이 아닌 결말은 이어갈 것을 남기지 않는다 — 성공한 수집을 로그인으로 또 돌리지 않는다", async () => {
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

  it("로그인이 확인되지 않으면 이어가지 않는다 — 자리를 떠난 사람 뒤에서 수집이 시작되지 않는다", async () => {
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

describe("정상 상태의 한 줄은 신선도다 — 경계는 메울 것이 있을 때만", () => {
  it("메울 것이 남아 있으면 신선도와 경계가 한 화면에 같이 선다", () => {
    mount({ desk: "READY", coverageThrough: "2026-09-02", coverageGapDays: 29 });
    // 9-02 성공은 사실이고, 그 뒤 29일은 아무도 읽지 않았다. 두 줄이 같이 서야 29일이 보인다.
    expect(screen.getByText("9월 2일 확인")).toBeInTheDocument();
    expect(screen.getByText("9월 2일까지 빠짐없이 확인 · 이후 29일은 아직")).toBeInTheDocument();
  });

  it("경계가 없으면 아무 말도 하지 않는다", () => {
    expect(coverageSentence(null, 12)).toBeNull();
    mount({ desk: "READY", coverageThrough: null, coverageGapDays: null });
    expect(screen.queryByText(/빠짐없이 확인/)).toBeNull();
  });

  it("정상 상태에서는 경계를 말하지 않는다 — 「오늘은?」이라는 답 없는 질문을 만들지 않는다", () => {
    // 경계는 어제까지이고, 오늘은 경계가 될 수 없는 하루다. 모든 것이 제대로 돌아가는 화면에서 그것을
    // 읽으면 판매자는 오늘을 묻게 되고, 그 질문에는 답이 없다. 정상일 때 필요한 사실은 신선도뿐이다.
    expect(coverageSentence("2026-10-07", 0)).toBeNull();
    expect(coverageSentence("2026-10-07", null)).toBeNull();
    mount({ desk: "READY", coverageThrough: "2026-10-07", coverageGapDays: 0 });
    expect(screen.queryByText(/빠짐없이 확인/)).toBeNull();
  });

  it("오늘·어제는 시각까지, 그보다 오래되면 날짜로", () => {
    const now = new Date("2026-10-09T05:00:00Z"); // 14:00 KST
    expect(freshnessSentence("2026-10-09T04:40:00Z", now)).toBe("오늘 13:40 확인");
    expect(freshnessSentence("2026-10-08T00:12:00Z", now)).toBe("어제 09:12 확인");
    expect(freshnessSentence("2026-09-02T03:43:03Z", now)).toBe("9월 2일 확인");
    expect(freshnessSentence(null, now)).toBeNull();
  });
});
