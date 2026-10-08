import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  awaitSignIn,
  SIGN_IN_POLL_LIMIT,
  signInMessage,
  signInStartMessage,
  signInStatus,
  startSignIn,
} from "./signInRecovery";
import { BRIDGE_TOKEN_KEY } from "../bridge/bridgeClient";

/**
 * <b>로그인 복구를 부르는 쪽의 계약.</b>
 *
 * 여기서 지키는 것 셋 — 자격은 이 길을 지나가지 않는다, 401/403은 판매자가 할 일 하나(「연결이
 * 필요합니다」)로 모인다, 그리고 모르는 답은 추측하지 않고 `UNAVAILABLE`이 된다.
 */

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
  const store = new Map<string, string>([[BRIDGE_TOKEN_KEY, "pairing-token"]]);
  vi.stubGlobal("window", {
    localStorage: {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    },
  });
});
afterEach(() => vi.unstubAllGlobals());

const json = (status: number, body: unknown) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

describe("로그인 복구를 시작한다", () => {
  it("채널 이름만 보낸다 — 그 외에 아무것도 싣지 않는다", async () => {
    fetchMock.mockResolvedValue(json(200, { ok: true, state: "WAITING" }));

    expect(await startSignIn("NAVER")).toEqual({ ok: true });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/bridge/sign-in/start");
    expect(JSON.parse(String(init.body))).toEqual({ channelCode: "NAVER" });
    // 자격은 이 요청의 본문에 없다. 있는 것은 이 브라우저가 이미 가진 연결 증표뿐이다.
    expect(String(init.body)).not.toContain("password");
  });

  it("연결 증표가 없으면 아무것도 보내지 않고 「연결이 필요하다」로 답한다", async () => {
    vi.stubGlobal("window", { localStorage: { getItem: () => null } });
    expect(await startSignIn("NAVER")).toEqual({ ok: false, reason: "not_connected" });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("401·403은 판매자가 할 일 하나로 모인다", async () => {
    for (const status of [401, 403]) {
      fetchMock.mockResolvedValue(json(status, {}));
      // 만료된 연결과 허용되지 않은 출처는 다른 사정이지만, 판매자가 할 일은 같다. 구별해 말하려면
      // 내부 개념을 꺼내야 하고, 그것이 이 패키지가 피하는 바로 그 일이다.
      expect(await startSignIn("NAVER")).toEqual({ ok: false, reason: "not_connected" });
    }
  });

  it("도우미가 닿지 않으면 그렇게 말한다", async () => {
    fetchMock.mockRejectedValue(new Error("refused"));
    expect(await startSignIn("NAVER")).toEqual({ ok: false, reason: "unreachable" });
  });

  it("도우미가 준 이유는 그대로 전달하고, 모르는 이유는 지어내지 않는다", async () => {
    for (const reason of ["busy", "unsupported_channel", "no_executor"]) {
      fetchMock.mockResolvedValue(json(200, { ok: false, reason }));
      expect(await startSignIn("NAVER")).toEqual({ ok: false, reason });
    }
    fetchMock.mockResolvedValue(json(200, { ok: false, reason: "something_new" }));
    expect(await startSignIn("NAVER")).toEqual({ ok: false, reason: "unreachable" });
  });

  it("읽을 수 없는 본문은 성공으로 읽지 않는다", async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => { throw new Error("html"); } });
    expect(await startSignIn("NAVER")).toEqual({ ok: false, reason: "unreachable" });
  });
});

describe("세션이 끝날 때까지 묻는다", () => {
  it("닫힌 단어만 받아들이고, 나머지는 UNAVAILABLE", async () => {
    for (const state of ["WAITING", "SIGNED_IN", "NOT_SIGNED_IN", "IDLE"]) {
      fetchMock.mockResolvedValue(json(200, { state }));
      expect(await signInStatus()).toBe(state);
    }
    for (const junk of [{ state: "LOGGED_IN" }, {}, { state: 1 }]) {
      fetchMock.mockResolvedValue(json(200, junk));
      expect(await signInStatus()).toBe("UNAVAILABLE");
    }
  });

  it("WAITING이 아닌 첫 답이 결론이다", async () => {
    const answers = ["WAITING", "WAITING", "SIGNED_IN"] as const;
    let i = 0;
    const waits: number[] = [];
    const settled = await awaitSignIn(
      async () => answers[i++]!,
      async (ms) => void waits.push(ms),
    );
    expect(settled).toBe("SIGNED_IN");
    expect(waits).toHaveLength(2);
  });

  it("우리 쪽 인내심도 bounded — 영원히 묻지 않는다", async () => {
    let asked = 0;
    const settled = await awaitSignIn(
      async () => (asked++, "WAITING"),
      async () => undefined,
    );
    expect(settled).toBe("WAITING");
    expect(asked).toBe(SIGN_IN_POLL_LIMIT);
  });
});

describe("판매자에게 하는 말", () => {
  it("세 결과에 각각 한 문장, 그리고 구현 용어가 없다", () => {
    expect(signInMessage("SIGNED_IN")).toEqual({ text: "로그인 확인됨. 다시 수집해 주세요.", isError: false });
    expect(signInMessage("NOT_SIGNED_IN")?.isError).toBe(true);
    expect(signInMessage("UNAVAILABLE")?.isError).toBe(true);
    // 기다리는 중에는 할 말이 없다 — 없는 결과를 문장으로 만들지 않는다.
    expect(signInMessage("WAITING")).toBeNull();
    expect(signInMessage("IDLE")).toBeNull();

    const everything = [
      signInMessage("SIGNED_IN")?.text,
      signInMessage("NOT_SIGNED_IN")?.text,
      signInMessage("UNAVAILABLE")?.text,
      signInStartMessage("not_connected"),
      signInStartMessage("busy"),
      signInStartMessage("unsupported_channel"),
      signInStartMessage("no_executor"),
    ].join(" ");
    for (const term of ["pairing", "bridge", "carrier", "Aside", "device", "token", "probe", "session"]) {
      expect(everything).not.toContain(term);
    }
  });

  it("연결이 필요할 때만 할 일이 다르고, 그 문장은 제품 언어다", () => {
    expect(signInStartMessage("not_connected")).toBe("이 Mac의 도우미와 연결이 필요합니다.");
  });
});

describe("자격은 이 파일을 지나가지 않는다", () => {
  it("아이디·비밀번호·MFA를 다루는 코드가 없다", () => {
    const code = readFileSync(resolve(__dirname, "signInRecovery.ts"), "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/(^|[^:])\/\/.*$/gm, "$1");
    for (const forbidden of ["password", "username", "otp", "mfa", "captcha", "credential"]) {
      expect(code.toLowerCase()).not.toContain(forbidden);
    }
  });
});
