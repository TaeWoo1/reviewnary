import { describe, expect, it, vi } from "vitest";
import { SIGN_IN_ROUNDS, signInMessage, watchSignIn } from "./signInRecovery";

/**
 * <b>한 번 누름으로 몇 번까지 지켜보는가 — 2026-10-09가 가르친 것.</b>
 *
 * 도우미 쪽 한 세션의 상한은 100초이고 올릴 수 없다: Aside 자신의 호출 상한이 그 위에 있다. 그날 그 100초가
 * 실제로 터졌다 — 로그인 창이 앞으로 나오지 않아 판매자가 창을 찾는 데 시간을 썼고, 98초에 `NOT_SIGNED_IN`
 * 으로 끝났다. 두 번째 시도는 창이 이미 앞에 있어서 34초였다. 병목은 상한이 아니라 포커스였고, 한 번만
 * 지켜보는 것이 그 병목에 걸린 것이다.
 *
 * 그래서 회차를 이어 붙인다. 각 호출은 상한 안에 있고, 사람에게는 시간이 생기고, 회차마다 창이 다시 앞으로
 * 나온다 — 포커스 문제에 대한 두 번째 기회다.
 */
describe("로그인을 여러 회차에 걸쳐 지켜본다", () => {
  const ok = async () => ({ ok: true as const });

  it("로그인이 확인되면 그 회차에서 끝난다 — 남은 회차를 쓰지 않는다", async () => {
    const start = vi.fn(ok);
    const watch = vi.fn(async () => "SIGNED_IN" as const);
    const rounds: number[] = [];

    const r = await watchSignIn("NAVER", (n) => rounds.push(n), 3, start, watch);

    expect(r).toEqual({ ok: true, state: "SIGNED_IN" });
    expect(start).toHaveBeenCalledTimes(1);
    expect(watch).toHaveBeenCalledTimes(1);
    expect(rounds).toEqual([1]);
  });

  it("확인되지 않으면 상한만큼 이어서 지켜보고, 회차마다 창을 다시 앞으로 가져온다", async () => {
    const start = vi.fn(ok);
    const watch = vi.fn(async () => "NOT_SIGNED_IN" as const);
    const rounds: number[] = [];

    const r = await watchSignIn("NAVER", (n) => rounds.push(n), 3, start, watch);

    expect(r).toEqual({ ok: true, state: "NOT_SIGNED_IN" });
    // 회차마다 새로 연다 = 회차마다 창이 다시 앞으로 나온다.
    expect(start).toHaveBeenCalledTimes(3);
    expect(watch).toHaveBeenCalledTimes(3);
    expect(rounds).toEqual([1, 2, 3]);
  });

  it("두 번째 회차에서 로그인하면 거기서 끝난다 — 100초가 아니라 사람의 속도에 맞는다", async () => {
    const start = vi.fn(ok);
    const watch = vi.fn()
      .mockResolvedValueOnce("NOT_SIGNED_IN")
      .mockResolvedValueOnce("SIGNED_IN");

    const r = await watchSignIn("NAVER", () => undefined, 3, start, watch);

    expect(r).toEqual({ ok: true, state: "SIGNED_IN" });
    expect(start).toHaveBeenCalledTimes(2);
  });

  it("열 수 없는 데스크는 첫 회차에서 그대로 말한다 — 회차를 쓰지 않는다", async () => {
    const start = vi.fn(async () => ({ ok: false as const, reason: "no_executor" as const }));
    const watch = vi.fn(async () => "NOT_SIGNED_IN" as const);

    const r = await watchSignIn("NAVER", () => undefined, 3, start, watch);

    expect(r).toEqual({ ok: false, reason: "no_executor" });
    expect(watch).not.toHaveBeenCalled();
  });

  it("회차 도중 열 수 없게 되면 그때까지 본 답을 들고 끝낸다", async () => {
    const start = vi.fn()
      .mockResolvedValueOnce({ ok: true })
      .mockResolvedValueOnce({ ok: false, reason: "no_executor" });
    const watch = vi.fn(async () => "NOT_SIGNED_IN" as const);

    const r = await watchSignIn("NAVER", () => undefined, 3, start, watch);

    expect(r).toEqual({ ok: true, state: "NOT_SIGNED_IN" });
  });

  it("이미 열려 있는 세션은 막다른 길이 아니다 — 열지 않고 이어서 지켜본다", async () => {
    // 한 데스크에 한 세션이므로 두 번째 열기는 `busy`로 돌아온다. 그것은 보고할 실패가 아니라 지켜볼
    // 세션이다 — 로그인 버튼을 다시 누른 판매자는 열어 달라고 한 것이 아니라 계속 봐 달라고 한 것이다.
    const start = vi.fn(async () => ({ ok: false, reason: "busy" }) as const);
    const watch = vi.fn()
      .mockResolvedValueOnce("NOT_SIGNED_IN" as const)
      .mockResolvedValueOnce("SIGNED_IN" as const);
    const rounds: number[] = [];

    const r = await watchSignIn("NAVER", (round) => rounds.push(round), 3, start, watch);

    expect(r).toEqual({ ok: true, state: "SIGNED_IN" });
    // 창을 새로 열지 않았고, 회차마다 판매자에게 말은 했다.
    expect(watch).toHaveBeenCalledTimes(2);
    expect(rounds).toEqual([1, 2]);
  });

  it("세션이 열려 있지만 로그인이 끝나지 않으면 회차를 다 쓰고 그 답을 들고 끝낸다", async () => {
    const start = vi.fn(async () => ({ ok: false, reason: "busy" }) as const);
    const watch = vi.fn(async () => "NOT_SIGNED_IN" as const);

    const r = await watchSignIn("NAVER", () => undefined, 2, start, watch);

    expect(r).toEqual({ ok: true, state: "NOT_SIGNED_IN" });
    expect(watch).toHaveBeenCalledTimes(2);
  });

  it("브라우저를 열 수 없다는 답은 회차를 더 쓰지 않는다 — 기다려도 달라지지 않는다", async () => {
    const start = vi.fn(ok);
    const watch = vi.fn(async () => "UNAVAILABLE" as const);

    const r = await watchSignIn("NAVER", () => undefined, 3, start, watch);

    expect(r).toEqual({ ok: true, state: "UNAVAILABLE" });
    expect(start).toHaveBeenCalledTimes(1);
  });

  it("상한은 한 사람이 로그인할 만한 시간을 준다", () => {
    expect(SIGN_IN_ROUNDS).toBeGreaterThanOrEqual(2);
  });

  it("시간이 끝났다는 것을 문장이 말한다 — 판매자가 자기 잘못을 찾게 두지 않는다", () => {
    const said = signInMessage("NOT_SIGNED_IN")!;
    expect(said.text).toContain("시간이 끝났습니다");
    expect(said.text).toContain("판매자센터 로그인");
    for (const term of ["session", "timeout", "bound", "poll", "NOT_SIGNED_IN"]) {
      expect(said.text, term).not.toContain(term);
    }
  });
});
