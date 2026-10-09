import { describe, expect, it } from "vitest";
import { compareVersions, helperStatusOf, naverSessionOf, MIN_HELPER_VERSION } from "./helperStatus";

const INTERNAL = ["bridge", "carrier", "pairing", "token", "port", "profile", "localhost", "127.0.0.1", "ws", "http"];

function noInternalWords(...texts: (string | null | undefined)[]) {
  for (const t of texts) {
    if (!t) continue;
    for (const w of INTERNAL) expect(t.toLowerCase(), `${t} names ${w}`).not.toContain(w);
  }
}

describe("helperStatusOf — six words, one action each, no internal concept", () => {
  it("paired is 연결됨 with nothing to press", () => {
    const s = helperStatusOf({ phase: "paired", pairedBefore: true, agentVersion: MIN_HELPER_VERSION });
    expect(s).toMatchObject({ key: "CONNECTED", label: "연결됨", action: null });
  });

  it("nothing on loopback: 설치 필요 for a browser that never met a helper, 실행 필요 for one that has", () => {
    const fresh = helperStatusOf({ phase: "unreachable", pairedBefore: false, agentVersion: null });
    expect(fresh).toMatchObject({ key: "INSTALL", label: "설치 필요", action: { kind: "install" } });
    const known = helperStatusOf({ phase: "unreachable", pairedBefore: true, agentVersion: null });
    expect(known).toMatchObject({ key: "START", label: "실행 필요", action: { kind: "retry" } });
    noInternalWords(fresh.note, known.note, fresh.action?.label, known.action?.label);
  });

  it("a running helper this browser is not connected to is 다시 연결 필요 — with the reason when there is one", () => {
    expect(helperStatusOf({ phase: "unpaired", pairedBefore: false, agentVersion: MIN_HELPER_VERSION })).toMatchObject({ key: "RECONNECT", label: "연결 필요", action: { kind: "connect" } });
    // 「다시」 is earned by a previous pairing, not implied by the state name.
    expect(helperStatusOf({ phase: "unpaired", pairedBefore: true, agentVersion: MIN_HELPER_VERSION })).toMatchObject({ key: "RECONNECT", label: "다시 연결 필요" });
    expect(helperStatusOf({ phase: "revoked", pairedBefore: false, agentVersion: MIN_HELPER_VERSION })).toMatchObject({ key: "RECONNECT", label: "다시 연결 필요" });
    expect(helperStatusOf({ phase: "revoked", pairedBefore: true, agentVersion: MIN_HELPER_VERSION }).note).toContain("해제");
    expect(helperStatusOf({ phase: "pairing_denied", pairedBefore: true, agentVersion: MIN_HELPER_VERSION }).note).toContain("거부");
    expect(helperStatusOf({ phase: "unpaired", pairedBefore: true, agentVersion: MIN_HELPER_VERSION, pairingHint: "no_response" }).note).toContain("응답이 없어");
  });

  it("an old helper is 업데이트 필요 whether the wire refused it or the version is simply below the minimum", () => {
    expect(helperStatusOf({ phase: "incompatible_version", pairedBefore: true, agentVersion: null })).toMatchObject({ key: "UPDATE", action: { kind: "update" } });
    expect(helperStatusOf({ phase: "unpaired", pairedBefore: true, agentVersion: "0.0.1-poc" })).toMatchObject({ key: "UPDATE" });
    expect(helperStatusOf({ phase: "paired", pairedBefore: true, agentVersion: "0.0.9" })).toMatchObject({ key: "UPDATE" });
    // Not below: stays what the phase says.
    expect(helperStatusOf({ phase: "paired", pairedBefore: true, agentVersion: MIN_HELPER_VERSION }).key).toBe("CONNECTED");
    expect(helperStatusOf({ phase: "paired", pairedBefore: true, agentVersion: "1.2.3" }).key).toBe("CONNECTED");
  });

  it("a pending approval says where to look, and never shows a code word", () => {
    const s = helperStatusOf({ phase: "pairing_pending", pairedBefore: false, agentVersion: MIN_HELPER_VERSION, attestedApproval: true });
    expect(s.key).toBe("PENDING");
    expect(s.note).toContain("허용");
    noInternalWords(s.note);
  });

  it("every note and label is free of internal words", () => {
    for (const phase of ["connecting", "unreachable", "unpaired", "pairing_pending", "pairing_denied", "paired", "incompatible_version", "disconnected", "revoked"]) {
      for (const pairedBefore of [true, false]) {
        const s = helperStatusOf({ phase, pairedBefore, agentVersion: MIN_HELPER_VERSION, maybeNeedsLocalNetworkAccess: true });
        noInternalWords(s.label, s.note, s.action?.label);
      }
    }
  });
});

describe("compareVersions", () => {
  it("orders numerically and treats the unparsable as oldest", () => {
    expect(compareVersions("0.1.0", "0.1.0")).toBe(0);
    expect(compareVersions("0.9.9", "0.10.0")).toBe(-1);
    expect(compareVersions("1.0.0", "0.99.99")).toBe(1);
    expect(compareVersions("0.0.1-poc", "0.1.0")).toBe(-1);
    expect(compareVersions("poc", "0.1.0")).toBe(-1);
  });
});

describe("naverSessionOf — only the helper's own last observation", () => {
  // 확인 시각은 이 함수가 아니라 「마지막 확인」 열이 말한다 — 정상 상태의 이 줄에는 단어 하나뿐이다.
  it("READY는 한 단어뿐이고, 모르는 값은 무엇이든 확인되지 않음이다 — 절대 로그인됨이 아니다", () => {
    expect(naverSessionOf("READY")).toMatchObject({ key: "LOGGED_IN", label: "로그인됨", note: null });
    expect(naverSessionOf("UNOBSERVED_EXTERNAL").key).toBe("UNOBSERVED");
    expect(naverSessionOf(null).key).toBe("UNOBSERVED");
    expect(naverSessionOf("SOMETHING_NEW").key).toBe("UNOBSERVED");
  });

  it("login, expiry and a challenge each get the one action that fixes them", () => {
    expect(naverSessionOf("LOGIN_REQUIRED")).toMatchObject({ key: "LOGIN_REQUIRED", label: "로그인 필요", action: { label: "네이버 로그인" } });
    expect(naverSessionOf("EXPIRED").key).toBe("LOGIN_REQUIRED");
    expect(naverSessionOf("TWO_FACTOR_REQUIRED").key).toBe("AUTH_CHALLENGE");
    expect(naverSessionOf("ACCOUNT_AMBIGUOUS").key).toBe("ACCOUNT_AMBIGUOUS");
    noInternalWords(naverSessionOf("LOGIN_REQUIRED").note);
  });
});

describe("helperStatusOf — the account link is its own axis (Helper Device Authentication v1)", () => {
  const paired = (device: Parameters<typeof helperStatusOf>[0]["device"]) =>
    helperStatusOf({ phase: "paired", pairedBefore: true, agentVersion: MIN_HELPER_VERSION, device });

  it("paired + linked is 연결됨; paired + unlinked is 연결 필요 with 연결", () => {
    expect(paired("linked")).toMatchObject({ key: "CONNECTED", label: "연결됨", action: null });
    const s = paired("unlinked");
    // 2026-10-08: 두 handshake(이 브라우저↔도우미, 도우미↔계정)는 화면에 한 번에 하나만 나오므로,
    // 판매자에게는 한 가지 일이다. 그 구분이 바로 새어 나가던 내부 개념이었다.
    // 2026-10-09: 그 한 가지 일의 이름은 이제 「연결」이다 — 무엇을 연결하는지는 구역의 제목이 말한다.
    expect(s).toMatchObject({ key: "LINK", label: "연결 필요", tone: "warn", action: { kind: "link", label: "연결" } });
    noInternalWords(s.note, s.action?.label);
    // A surface that never asked about the link (undefined) is unchanged: pairing alone is 연결됨 there.
    expect(paired(undefined).key).toBe("CONNECTED");
  });

  it("linking is a waiting word, denied/expired keep the one control, unreachable names the server side", () => {
    expect(paired("linking")).toMatchObject({ key: "LINKING", label: "연결 확인 중", action: null });
    expect(paired("denied")).toMatchObject({ key: "LINK", action: { kind: "link" } });
    expect(paired("denied").note).toContain("거부");
    expect(paired("expired").note).toContain("시간이 지났");
    expect(paired("unreachable")).toMatchObject({ key: "LINK_SERVER", label: "서버 연결 확인 필요", action: { kind: "link", label: "다시 시도" } });
    expect(paired("unknown")).toMatchObject({ key: "CHECKING", action: null });
    for (const d of ["linking", "denied", "expired", "unreachable"] as const) noInternalWords(paired(d).note, paired(d).label);
  });

  it("a helper older than the first device-linking release must update — it still holds the password model", () => {
    expect(compareVersions("0.1.0", MIN_HELPER_VERSION)).toBe(-1);
    expect(helperStatusOf({ phase: "paired", pairedBefore: true, agentVersion: "0.1.0", device: "linked" }).key).toBe("UPDATE");
  });
});
