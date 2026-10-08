import { BRIDGE_TOKEN_KEY, bridgeHttpBase } from "../bridge/bridgeClient";

/**
 * <b>판매자가 자기 판매자센터에 로그인하러 가는 길.</b>
 *
 * <p>수집이 로그인 벽에서 멈췄을 때, 제품은 「로그인한 뒤 다시 수집해 주세요」라고 말할 수 있었지만 판매자가
 * 갈 곳이 없었다. 수집이 운전하는 창은 이 컴퓨터의 도우미 것이고 판매자가 열 수 있는 브라우저가 아니다 —
 * 그래서 그 문장은 닿을 수 없는 세션에 대한 조언이었다(2026-10-07 쿠팡, 10-08 네이버에서 각각 실측).
 *
 * <p>이 모듈이 그 창을 연다. 수집이 쓰는 그 프로필, 그 페이지로. 그리고 <b>지켜보기만</b> 한다 —
 * 아이디·비밀번호·MFA·CAPTCHA는 전부 판매자가 직접 한다. 여기에도, 도우미에도 자격을 넣는 길은 없다.
 *
 * <h2>기억하지 않는다</h2>
 *
 * <p>로그인 확인은 이 탭에서만, 지금만 유효하다. 서버에 저장하지 않고 freshness로도 쓰지 않는다(제품 결정
 * 2026-10-08). 그래서 <b>새로고침하면 사라지고</b>, 화면은 다시 「최근 수집 시 로그인이 필요했습니다」로
 * 돌아간다 — 그 문장은 「마지막 수집 시도」에 대한 것이고 다음 수집까지는 참이기 때문이다. 몇 분 전 probe를
 * 근거로 「지금 로그인되어 있습니다」라고 말하는 쪽이 거짓말에 가깝다.
 */

/** 도우미가 돌려주는 닫힌 단어들. */
export type SignInSessionState = "IDLE" | "WAITING" | "SIGNED_IN" | "NOT_SIGNED_IN" | "UNAVAILABLE";

export type SignInStartResult =
  | { ok: true }
  /** 이 컴퓨터에서 이미 로그인 복구가 열려 있다. 브라우저는 하나고 사람도 한 명이다. */
  | { ok: false; reason: "busy" }
  /** 이 채널에는 로그인 복구가 없다. */
  | { ok: false; reason: "unsupported_channel" }
  /** 이 도우미에는 판매자센터를 열 수 있는 실행기가 없다. */
  | { ok: false; reason: "no_executor" }
  /** 이 Mac의 도우미와 연결이 필요하다. 구현 용어가 아니라 이 한 가지 상태로만 화면에 나간다. */
  | { ok: false; reason: "not_connected" }
  /** 도우미가 응답하지 않는다. */
  | { ok: false; reason: "unreachable" };

function pairingBearer(): string | null {
  try {
    return window.localStorage.getItem(BRIDGE_TOKEN_KEY);
  } catch {
    // 저장소를 못 읽는 브라우저에서는 연결이 없는 것과 같다 — 추측해서 더 말하지 않는다.
    return null;
  }
}

/**
 * 로그인 복구를 시작한다. 페이지가 열리기 시작하면 바로 돌아온다 —
 * 사람이 타이핑을 끝낼 때까지 기다리는 요청은 MFA 길이만큼 열려 있게 된다.
 */
export async function startSignIn(channelCode: string): Promise<SignInStartResult> {
  const bearer = pairingBearer();
  if (!bearer) {
    return { ok: false, reason: "not_connected" };
  }
  let res: Response;
  try {
    res = await fetch(`${bridgeHttpBase()}/bridge/sign-in/start`, {
      method: "POST",
      headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
      body: JSON.stringify({ channelCode }),
    });
  } catch {
    return { ok: false, reason: "unreachable" };
  }
  // 401/403은 「연결이 필요하다」로 모은다. 그 둘의 차이(만료된 pairing · 허용되지 않은 origin)는 판매자가
  // 할 일을 바꾸지 않고, 설명하려면 내부 개념을 꺼내야 한다.
  if (res.status === 401 || res.status === 403) {
    return { ok: false, reason: "not_connected" };
  }
  if (!res.ok) {
    return { ok: false, reason: "unreachable" };
  }
  let body: { ok?: unknown; reason?: unknown };
  try {
    body = (await res.json()) as { ok?: unknown; reason?: unknown };
  } catch {
    return { ok: false, reason: "unreachable" };
  }
  if (body.ok === true) {
    return { ok: true };
  }
  const reason = body.reason;
  return reason === "busy" || reason === "unsupported_channel" || reason === "no_executor"
    ? { ok: false, reason }
    : { ok: false, reason: "unreachable" };
}

/** 지금 그 세션이 어디까지 왔는지. 읽기만 하고, 아무것도 바꾸지 않는다. */
export async function signInStatus(): Promise<SignInSessionState> {
  const bearer = pairingBearer();
  if (!bearer) {
    return "UNAVAILABLE";
  }
  try {
    const res = await fetch(`${bridgeHttpBase()}/bridge/sign-in/status`, {
      headers: { Authorization: `Bearer ${bearer}` },
    });
    if (!res.ok) {
      return "UNAVAILABLE";
    }
    const body = (await res.json()) as { state?: unknown };
    const state = body.state;
    return state === "WAITING" || state === "SIGNED_IN" || state === "NOT_SIGNED_IN" || state === "IDLE"
      ? state
      : "UNAVAILABLE";
  } catch {
    return "UNAVAILABLE";
  }
}

/** 상태를 물어보는 간격. 사람이 로그인하는 창을 1초마다 들여다볼 이유가 없다. */
export const SIGN_IN_POLL_MS = 3000;
/** 90초. 도우미 쪽 세션의 bound(100초)보다 짧지 않게, 그 답을 받을 수 있을 만큼. */
export const SIGN_IN_POLL_LIMIT = 40;

/**
 * **몇 번까지 다시 지켜보는가.**
 *
 * 도우미 쪽 한 세션의 상한은 100초이고, 그건 올릴 수 없다 — Aside 자신의 호출 상한(120초)이 그 위에 있다.
 * 2026-10-09에 그 100초가 실제로 터졌다: 로그인 창이 앞으로 나오지 않아 판매자가 창을 찾는 데 시간을 썼고,
 * 98초에 `NOT_SIGNED_IN`으로 끝났다. 두 번째 시도는 창이 이미 앞에 있어서 34초에 끝났다. 즉 상한이 아니라
 * 포커스가 병목이고, 한 번만 지켜보는 것이 그 병목에 걸린 것이다.
 *
 * <p>그래서 한 번 누름 안에서 세 번까지 이어서 지켜본다. 각 회차는 상한 안에 있고, 로그인이 확인되면 그
 * 자리에서 끝난다. 회차가 바뀔 때 창을 다시 열어 앞으로 가져오는 것은 부수효과가 아니라 이득이다 —
 * 포커스를 못 가져오는 그 문제에 대한 두 번째 기회다.
 */
export const SIGN_IN_ROUNDS = 3;

/**
 * 세션이 끝날 때까지 기다린다. `WAITING`이 아닌 첫 답이 결론이다.
 *
 * @param delay 테스트 seam. 실제로는 타이머.
 */
export async function awaitSignIn(
  poll: () => Promise<SignInSessionState> = signInStatus,
  delay: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
): Promise<SignInSessionState> {
  let latest: SignInSessionState = "WAITING";
  for (let i = 0; i < SIGN_IN_POLL_LIMIT; i += 1) {
    latest = await poll();
    if (latest !== "WAITING") {
      return latest;
    }
    await delay(SIGN_IN_POLL_MS);
  }
  // 우리 쪽 인내심이 먼저 끝났다. 도우미 쪽 세션은 자기 bound로 끝나므로, 다시 물으면 답이 있다.
  return latest;
}

/**
 * **한 번 누름으로 여러 회차를 지켜본다 — 로그인이 확인되면 그 자리에서 끝난다.**
 *
 * 한 세션의 상한은 도우미 쪽에 있고 올릴 수 없다({@link SIGN_IN_ROUNDS} 참고). 여기서 하는 일은 그 상한이
 * 사람의 속도와 맞지 않을 때 **이어서 한 번 더 지켜보는 것**이고, 회차마다 창을 다시 열어 앞으로 가져온다.
 *
 * @param onRound 회차가 시작될 때 화면에 알릴 기회. 몇 분을 기다리게 하면서 아무 말도 하지 않으면, 판매자는
 *                멈춘 것으로 읽는다 — 2026-10-09에 실제로 그렇게 읽혔다.
 */
export async function watchSignIn(
  channel: string,
  onRound: (round: number, rounds: number) => void,
  rounds = SIGN_IN_ROUNDS,
  start: (c: string) => Promise<SignInStartResult> = startSignIn,
  watch: () => Promise<SignInSessionState> = awaitSignIn,
): Promise<{ ok: true; state: SignInSessionState }
  | { ok: false; reason: Exclude<SignInStartResult, { ok: true }>["reason"] }> {
  let latest: SignInSessionState = "WAITING";
  for (let round = 1; round <= rounds; round += 1) {
    // 첫 회차는 호출한 쪽이 이미 열었을 수도 있다. 열기는 멱등이 아니므로 매 회차 새로 시작한다 —
    // 「한 데스크에 한 세션」 규칙 때문에 앞 회차가 끝난 뒤에만 가능하고, 그게 지금 상태다.
    const started = await start(channel);
    if (!started.ok) {
      // 첫 회차에서 열지 못하면 할 말이 그것뿐이다. 뒤 회차에서라면 앞서 본 답을 들고 끝낸다.
      if (round === 1) return { ok: false, reason: started.reason };
      return { ok: true, state: latest };
    }
    onRound(round, rounds);
    latest = await watch();
    if (latest === "SIGNED_IN" || latest === "UNAVAILABLE") {
      return { ok: true, state: latest };
    }
  }
  return { ok: true, state: latest };
}

/** 로그인 복구 한 번의 결과를 판매자 문장으로. 구현 용어는 한 글자도 나오지 않는다. */
export function signInMessage(state: SignInSessionState): { text: string; isError: boolean } | null {
  switch (state) {
    case "SIGNED_IN":
      return { text: "로그인 확인됨. 다시 수집해 주세요.", isError: false };
    case "NOT_SIGNED_IN":
      // <b>왜 끝났는지 말한다.</b> 「아직 확인되지 않았습니다」만 있으면 판매자는 자기가 뭘 잘못했는지 찾는다.
      // 끝난 이유는 지켜보는 시간이 다 된 것이고, 다음에 할 일은 로그인을 마친 뒤 그 버튼을 다시 누르는 것이다.
      return {
        text: "로그인 창을 지켜보는 시간이 끝났습니다. 로그인을 마친 뒤 [판매자센터 로그인]을 다시 눌러 주세요.",
        isError: true,
      };
    case "UNAVAILABLE":
      return { text: "판매자센터 로그인 창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.", isError: true };
    default:
      return null;
  }
}

/** 시작하지 못한 이유를 판매자 문장으로. `not_connected`만 할 일이 다르다 — 연결이 먼저다. */
export function signInStartMessage(reason: Exclude<SignInStartResult, { ok: true }>["reason"]): string {
  switch (reason) {
    case "not_connected":
      return "이 Mac의 도우미와 연결이 필요합니다.";
    case "busy":
      return "이 컴퓨터에서 이미 로그인 창이 열려 있습니다. 그 창에서 로그인을 마쳐 주세요.";
    case "unsupported_channel":
      return "이 채널은 판매자센터 로그인이 필요한 수집 방식이 아닙니다.";
    default:
      return "판매자센터 로그인 창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
