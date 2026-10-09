// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CollectNowAction } from "./CollectNowAction";

/**
 * <b>판매자가 어디서 로그인했든, 돌아오면 이어진다.</b>
 *
 * <p>2026-10-09 라이브에서 측정된 구멍: 2차 인증 때문에 제품 창을 닫고 자기 브라우저의 다른 탭에서
 * 로그인했다. 세션은 돌아왔고, 지켜보던 탭이 없었으므로 아무도 몰랐고, 멈춘 확인은 그대로 있었다.
 * 「벽은 사람이 푼다」는 벽에 대해서는 맞고 <b>세션</b>에 대해서는 틀렸다 — 세션은 그 사람이 어디서
 * 로그인해도 돌아오고, 제품이 그것을 알 수 있는 순간은 판매자가 돌아온 그 순간이다.
 *
 * <p>이 파일이 지키는 네 문장:
 * <ul>
 *   <li>지켜보는 중에 로그인하면 <b>즉시</b> 이어진다(기존 경로, 유지);</li>
 *   <li>지켜보기를 놓쳐도 Reviewnary로 <b>돌아오면</b> 가벼운 확인 한 번으로 이어진다;</li>
 *   <li>아직 로그아웃이면 <b>아무것도 시작하지 않는다</b> — 확인만 하고 그대로 기다린다;</li>
 *   <li>화면에 들어온 것만으로 <b>수집이 시작되지 않는다</b> — 기다리는 일이 없으면 묻지도 않는다.</li>
 * </ul>
 */
const startSignIn = vi.fn();
const awaitSignIn = vi.fn();
const checkSignIn = vi.fn();
const collectNow = vi.fn();
const collectNowResume = vi.fn();
const screenReadStatus = vi.fn();
const onReport = vi.fn();
const onChanged = vi.fn();

vi.mock("../../lib/connect/signInRecovery", async (orig) => {
  const actual = (await orig()) as Record<string, unknown>;
  return {
    ...actual,
    startSignIn: (...a: unknown[]) => startSignIn(...a),
    awaitSignIn: (...a: unknown[]) => awaitSignIn(...a),
    checkSignIn: (...a: unknown[]) => checkSignIn(...a),
  };
});

vi.mock("../../lib/apiClient", () => ({
  api: {
    collectNow: (...a: unknown[]) => collectNow(...a),
    collectNowResume: (...a: unknown[]) => collectNowResume(...a),
    screenReadStatus: (...a: unknown[]) => screenReadStatus(...a),
    collectNowReadiness: vi.fn(),
  },
  getToken: () => null,
}));

beforeEach(() => {
  startSignIn.mockReset();
  awaitSignIn.mockReset();
  checkSignIn.mockReset();
  collectNow.mockReset();
  collectNowResume.mockReset();
  screenReadStatus.mockReset();
  onReport.mockReset();
  onChanged.mockReset();
  startSignIn.mockResolvedValue({ ok: true });
  screenReadStatus.mockResolvedValue({ jobId: "job-r", state: "SUCCESS", observed: 3, inserted: 0, changed: 0 });
});

function mount(over: Record<string, unknown> = {}) {
  return render(
    <CollectNowAction
      accountId="acct-1"
      dataType="REVIEW"
      label="리뷰"
      desk="AUTH_REQUIRED"
      channelCode="NAVER"
      pausedSignIn
      showSentence
      onReport={onReport}
      onChanged={onChanged}
      {...over}
    />,
  );
}

/** 서버가 이어갈 일을 들고 있다 — 이어가면 그 작업을 돌려준다. */
function serverHasSomethingWaiting() {
  collectNowResume.mockResolvedValue({ jobId: "job-r", state: "RUNNING", inserted: null, changed: null });
}

describe("지켜보기를 놓쳐도 돌아오면 이어진다", () => {
  it("돌아온 순간 가벼운 확인 한 번 — 로그인되어 있으면 이어간다", async () => {
    checkSignIn.mockResolvedValue("SIGNED_IN");
    serverHasSomethingWaiting();

    mount();

    await waitFor(() => expect(checkSignIn).toHaveBeenCalledWith("NAVER"));
    await waitFor(() => expect(collectNowResume).toHaveBeenCalledWith("acct-1", "REVIEW"));
    // 수집을 시작한 것이 아니다 — 이어간 것이다.
    expect(collectNow).not.toHaveBeenCalled();
    // 그리고 창을 열지 않았다: 확인은 로그인 복구 세션이 아니다.
    expect(startSignIn).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(onReport).toHaveBeenCalledWith("로그인 확인됨. 멈췄던 확인을 이어서 진행합니다.", false));
  });

  it("아직 로그아웃이면 확인만 하고 그대로 기다린다 — read 0, resume 0", async () => {
    checkSignIn.mockResolvedValue("NOT_SIGNED_IN");

    mount();

    await waitFor(() => expect(checkSignIn).toHaveBeenCalledTimes(1));
    expect(collectNowResume).not.toHaveBeenCalled();
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("도우미가 답하지 못해도 아무것도 시작하지 않는다 — 모른다는 이유로 수집하지 않는다", async () => {
    checkSignIn.mockResolvedValue("UNAVAILABLE");

    mount();

    await waitFor(() => expect(checkSignIn).toHaveBeenCalledTimes(1));
    expect(collectNowResume).not.toHaveBeenCalled();
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("데스크에 세션이 열려 있으면(WAITING) 두 번째 창을 만들지 않는다", async () => {
    checkSignIn.mockResolvedValue("WAITING");

    mount();

    await waitFor(() => expect(checkSignIn).toHaveBeenCalledTimes(1));
    expect(collectNowResume).not.toHaveBeenCalled();
    expect(startSignIn).not.toHaveBeenCalled();
  });

  it("기다리는 일이 없으면 묻지도 않는다 — 화면 진입이 수집도, 확인도 아니다", async () => {
    mount({ pausedSignIn: false, desk: "READY" });

    await new Promise((r) => setTimeout(r, 20));
    expect(checkSignIn).not.toHaveBeenCalled();
    expect(collectNowResume).not.toHaveBeenCalled();
    expect(collectNow).not.toHaveBeenCalled();
  });

  it("한 번의 활성화에 한 번만 묻는다 — 다시 그려도 두 번 열지 않는다", async () => {
    checkSignIn.mockResolvedValue("NOT_SIGNED_IN");

    const { rerender } = mount();
    await waitFor(() => expect(checkSignIn).toHaveBeenCalledTimes(1));
    rerender(
      <CollectNowAction
        accountId="acct-1"
        dataType="REVIEW"
        label="리뷰"
        desk="AUTH_REQUIRED"
        channelCode="NAVER"
        pausedSignIn
        showSentence
        onReport={onReport}
        onChanged={onChanged}
      />,
    );
    await new Promise((r) => setTimeout(r, 20));
    expect(checkSignIn).toHaveBeenCalledTimes(1);
  });

  it("탭이 다시 보이면 다시 묻는다 — 그 사이에 로그인했을 수 있다", async () => {
    checkSignIn.mockResolvedValueOnce("NOT_SIGNED_IN").mockResolvedValueOnce("SIGNED_IN");
    serverHasSomethingWaiting();

    mount();
    await waitFor(() => expect(checkSignIn).toHaveBeenCalledTimes(1));
    expect(collectNowResume).not.toHaveBeenCalled();

    // 판매자가 다른 탭에서 로그인하고 돌아온다.
    document.dispatchEvent(new Event("visibilitychange"));

    await waitFor(() => expect(checkSignIn).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(collectNowResume).toHaveBeenCalledTimes(1));
  });
});

describe("지켜보는 중에 로그인하면 즉시 이어진다 — 기존 경로는 그대로", () => {
  it("버튼을 누른 흐름에서는 확인을 기다리지 않고 바로 resume한다", async () => {
    // 이 흐름에서는 돌아오기 확인이 아니라 watcher가 답을 준다.
    checkSignIn.mockResolvedValue("NOT_SIGNED_IN");
    awaitSignIn.mockResolvedValue("SIGNED_IN");
    serverHasSomethingWaiting();

    mount();
    await waitFor(() => expect(checkSignIn).toHaveBeenCalled());
    collectNowResume.mockClear();

    await userEvent.click(screen.getByTestId("sign-in-REVIEW"));

    await waitFor(() => expect(collectNowResume).toHaveBeenCalledWith("acct-1", "REVIEW"));
    expect(collectNow).not.toHaveBeenCalled();
  });
});
