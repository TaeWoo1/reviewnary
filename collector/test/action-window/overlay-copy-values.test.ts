/**
 * The guidance panel's copy rows — the one place a walk writes the seller's clipboard. Pins the two properties
 * that make the exception narrow: only well-formed IPv4 (at most three) ever reaches the page, and the in-page
 * script writes and never reads.
 */
import { describe, expect, it } from "vitest";
import { buildPanelCopyRowsScript, copyableIpv4s, MAX_COPY_ROWS } from "../../src/action-window/overlay-copy-values";

const COPY = { copyLabel: "복사", copiedLabel: "복사됨", failedLabel: "직접 복사해 주세요", emptyNote: "없음" };

describe("copyableIpv4s — only dotted-quad IPv4 reaches a panel", () => {
  it("keeps valid addresses in order, de-duplicated", () => {
    expect(copyableIpv4s(["203.0.113.7", "198.51.100.1", "203.0.113.7"])).toEqual(["203.0.113.7", "198.51.100.1"]);
  });

  it.each([
    ["an IPv6 address", "2001:db8::1"],
    ["an out-of-range octet", "256.1.1.1"],
    ["a leading zero", "01.2.3.4"],
    ["three octets", "1.2.3"],
    ["trailing text", "1.2.3.4 "],
    ["a script", "1.2.3.4');alert(1);('"],
    ["a credential-shaped string", "abcdef0123456789abcdef"],
  ])("refuses %s", (_label, value) => {
    expect(copyableIpv4s([value])).toEqual([]);
  });

  it("refuses non-strings", () => {
    expect(copyableIpv4s([null, undefined, 1234, { ip: "1.2.3.4" }])).toEqual([]);
  });

  it("offers at most three — NAVER's own limit", () => {
    expect(MAX_COPY_ROWS).toBe(3);
    expect(copyableIpv4s(["1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"])).toEqual(["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
  });
});

describe("buildPanelCopyRowsScript — write-only, and it carries only what it was given", () => {
  it("writes with clipboard.writeText and never reads the clipboard, a field, or page text", () => {
    const s = buildPanelCopyRowsScript(["203.0.113.7"], COPY);
    expect(s).toContain("navigator.clipboard.writeText(ip)");
    for (const tok of ["readText", "clipboard.read(", ".value", "inputValue", "innerText", "innerHTML", "outerHTML", "getAttribute("]) {
      expect(s, tok).not.toContain(tok);
    }
    // `.textContent` appears only as an assignment target, never read.
    for (const m of s.matchAll(/\.textContent\b(.{0,3})/g)) expect(m[1]).toMatch(/^ = /);
  });

  it("honours a person's press only", () => {
    expect(buildPanelCopyRowsScript(["203.0.113.7"], COPY)).toContain("ev.isTrusted !== true");
  });

  it("screens again at build time — a bad value never reaches the script", () => {
    const s = buildPanelCopyRowsScript(["203.0.113.7", "x');evil('"], COPY);
    expect(s).toContain('var ROWS = ["203.0.113.7"];');
    expect(s).not.toContain("evil");
  });

  it("says where to look when there is nothing to copy", () => {
    const s = buildPanelCopyRowsScript([], COPY);
    expect(s).toContain("var ROWS = [];");
    expect(s).toContain('"emptyNote":"없음"');
  });
});
