import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  NAVER_SCREEN_STORE_DOMAIN,
  buildNaverStoreFingerprintScript,
  sanitizeStoreFingerprint,
} from "../../src/naver/store-fingerprint-inpage.js";

/**
 * <b>빈 기간에도 화면은 자기가 어느 가게인지 말한다 — 그리고 넘어오는 것은 digest 하나뿐이다.</b>
 */
function fakePage(opts: {
  host?: string;
  navId?: string | null;
  navHref?: string;
  sideLines?: string[] | null;
}) {
  const navLinks = [
    { innerText: "네이버", getAttribute: () => "" },
    ...(opts.navId === null
      ? []
      : [{
          innerText: `${opts.navId ?? "seller0001"}님\n내정보`,
          getAttribute: (k: string) => (k === "href" ? (opts.navHref ?? "#/seller/member") : null),
        }]),
    { innerText: "로그아웃", getAttribute: () => "" },
  ];
  const side = opts.sideLines === null ? null : { innerText: (opts.sideLines ?? ["좌측 내비게이션 펼쳐보기", "나누리샵.", "통합 매니저"]).join("\n") };
  const document = {
    querySelector: (sel: string) => {
      if (sel.indexOf("seller-navbar") >= 0 || sel === "nav") return { querySelectorAll: () => navLinks };
      if (sel.indexOf("seller-side-nav") >= 0) return side;
      return null;
    },
  };
  const location = { host: opts.host ?? "sell.smartstore.naver.com" };
  const win = { crypto: globalThis.crypto };
  return new Function("document", "window", "location", `return (${buildNaverStoreFingerprintScript()});`)(
    document, win, location,
  ) as Promise<string | null>;
}

const expected = (id: string, name: string) =>
  createHash("sha256").update(`${NAVER_SCREEN_STORE_DOMAIN}:${id}:${name}`, "utf8").digest("hex");

describe("화면의 가게 지문", () => {
  it("계정 식별자와 스토어 표시명을 domain-separated composite digest 하나로 만든다", async () => {
    await expect(fakePage({})).resolves.toBe(expected("seller0001", "나누리샵."));
  });

  it("좌측 내비의 첫 줄이 스토어 표시명이다 — 「내비게이션」 안내 줄은 건너뛴다", async () => {
    await expect(fakePage({ sideLines: ["좌측 내비게이션 펼쳐보기", "다른샵", "통합 매니저"] }))
      .resolves.toBe(expected("seller0001", "다른샵"));
  });

  it("둘 중 하나라도 읽히지 않으면 null — 반쪽 지문은 「가게가 바뀌었다」로 읽힌다", async () => {
    await expect(fakePage({ navId: null })).resolves.toBeNull();
    await expect(fakePage({ sideLines: null })).resolves.toBeNull();
    await expect(fakePage({ sideLines: [] })).resolves.toBeNull();
  });

  it("판매자 정보로 가는 링크가 아니면 계정 식별자가 아니다", async () => {
    await expect(fakePage({ navHref: "#/home/dashboard" })).resolves.toBeNull();
  });

  it("판매자 센터가 아닌 호스트에서는 아무것도 말하지 않는다", async () => {
    await expect(fakePage({ host: "example.test" })).resolves.toBeNull();
  });

  it("공백과 유니코드 정규화 차이는 같은 지문이다 — 화면의 들쑥날쑥이 가게 변경으로 읽히지 않도록", async () => {
    const a = await fakePage({ navId: "seller0001", sideLines: ["  나누리샵.  ", "통합 매니저"] });
    const b = await fakePage({ navId: "seller0001", sideLines: ["나누리샵.", "통합 매니저"] });
    expect(a).toBe(b);
    expect(a).toBe(expected("seller0001", "나누리샵."));
  });

  it("digest 모양이 아닌 것은 넘어와도 없는 것으로 친다", () => {
    expect(sanitizeStoreFingerprint("a".repeat(64))).toBe("a".repeat(64));
    expect(sanitizeStoreFingerprint("A".repeat(64))).toBeNull();
    expect(sanitizeStoreFingerprint("a".repeat(63))).toBeNull();
    expect(sanitizeStoreFingerprint(null)).toBeNull();
    expect(sanitizeStoreFingerprint(123)).toBeNull();
  });
});
