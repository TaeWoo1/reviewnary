import { describe, expect, it } from "vitest";
import {
  ALERTS_ROUTE,
  MOBILE_TABS,
  MOBILE_TAB_ROUTES,
  NAV_GROUPS,
  NAV_ITEMS,
  navGroupLabel,
} from "./nav.v2";
import { isNavIconName } from "../components/icons/NavIcon";

describe("nav.v2 — structure", () => {
  it("declares the IA — 운영 / 데이터 / Reviewnary / 설정 (product-owner decision, 2026-10-01)", () => {
    // The names say what each group IS rather than what the seller came to do with it: 일 / 기록 / 준비
    // was a good axis for the first group and a strained one for 리뷰·문의·상품·주문, which are the
    // objects the product operates on and are opened to change as often as to read.
    expect(NAV_GROUPS.map((group) => group.heading)).toEqual(["운영", "데이터", "Reviewnary", "설정"]);
  });

  it("draws no group label that only repeats its own single destination", () => {
    // 설정 is a group of one whose item carries the group's name, so the rail would print the word twice
    // 4px apart. Every other group names something its items do not.
    expect(NAV_GROUPS.map(navGroupLabel)).toEqual(["운영", "데이터", "Reviewnary", null]);
  });

  it("declares the destinations, in order — 오늘 · 확인할 일 · 반복 문제 / 리뷰 · 문의 · 상품 · 주문 · 리포트 / 지식 · 연결 / 설정", () => {
    // <b>The destinations and their order did not move</b>; the grouping did. 오늘 joined 운영 because it
    // is the answer to 운영's own question, and a heading-less group of one put the most-visited screen
    // outside the structure that names it. 설정 left 준비 because it is not something reviewnary needs.
    expect(NAV_GROUPS.map((group) => group.items.map((item) => item.to))).toEqual([
      // 운영: the morning, the work waiting for a decision, and what keeps coming back.
      ["/", "/customer-operations/cases", "/memory"],
      // 데이터: the objects the product operates on. 리포트 is one too — a period, recorded.
      ["/reviews", "/inquiries", "/products", "/orders", "/reports"],
      // Reviewnary: what the product needs from the seller before it can do any of the above.
      ["/knowledge", "/connect"],
      ["/settings"],
    ]);
  });

  it("labels every destination in seller language", () => {
    expect(NAV_ITEMS.map((item) => item.label)).toEqual([
      "오늘",
      "확인할 일",
      "반복 문제",
      "리뷰",
      "문의",
      "상품",
      "주문",
      "리포트",
      "지식",
      "연결",
      "설정",
    ]);
  });

  it("상품 is reachable from the menu — the backend served it all along and no screen did", () => {
    // The gap this closes was not a decision: `/api/products` predates the v2 shell, this org holds
    // 300 real products, and until Demo Core Experience v1 the only thing that could see the
    // catalogue was the Agent (docs/frontend_ux_audit_v1.md §1).
    expect(NAV_ITEMS.map((item) => item.to)).toContain("/products");
  });

  it("names no channel — a channel is a filter inside a screen, never a destination", () => {
    for (const item of NAV_ITEMS) {
      expect(item.label).not.toMatch(/네이버|쿠팡|카페24|스마트스토어/);
      expect(item.to).not.toMatch(/naver|coupang|cafe24|channels\//);
    }
  });

  it("keeps /agent and the 고객 운영 관리 control out of the menu — both stay routes (Phase 4 audit)", () => {
    // /agent is an internal route. 고객 운영 관리 is the control of the handed-over job, reached from 오늘's status
    // pill and from 설정; a menu entry would be a second door to what 오늘 already reports.
    expect(NAV_ITEMS.map((item) => item.to)).not.toContain("/agent");
    expect(NAV_ITEMS.map((item) => item.to)).not.toContain("/customer-operations");
    expect(NAV_ITEMS.map((item) => item.to)).not.toContain("/inbox");
  });

  it("exact-match highlights only the home route", () => {
    expect(NAV_ITEMS.filter((item) => item.end).map((item) => item.to)).toEqual(["/"]);
  });

  it("resolves every icon key to a real icon, never the fallback dot", () => {
    for (const item of NAV_ITEMS) {
      expect(isNavIconName(item.icon), `${item.label} → ${item.icon}`).toBe(true);
    }
  });

  it("points the alert surface at a route the nav can reach", () => {
    expect(ALERTS_ROUTE).toBe("/settings/alerts");
    expect(ALERTS_ROUTE.startsWith("/settings")).toBe(true);
  });
});

describe("nav.v2 — the operations agent is not a destination", () => {
  it("has no /agent entry", () => {
    // The agent is an action offered inside 운영 홈 / 인박스 / 메모리, not a menu item. Its route
    // still exists; promoting it back to the nav is a product decision, not a nav edit.
    expect(NAV_ITEMS.map((item) => item.to)).not.toContain("/agent");
    expect(NAV_ITEMS.map((item) => item.label)).not.toContain("운영 에이전트");
  });
});

describe("nav.v2 — mobile derives from the same model", () => {
  it("uses the four 운영 destinations plus a 더보기 trigger", () => {
    expect(MOBILE_TAB_ROUTES).toEqual(["/", "/reviews", "/inquiries", "/orders"]);
    expect(MOBILE_TABS).toHaveLength(4);
  });

  it("never re-declares a tab — every tab is the side-nav item itself", () => {
    for (const tab of MOBILE_TABS) {
      expect(NAV_ITEMS).toContain(tab);
    }
  });

  it("keeps setup work (채널 연결, 설정) out of the tab bar", () => {
    expect(MOBILE_TABS.map((tab) => tab.to)).not.toContain("/connect");
    expect(MOBILE_TABS.map((tab) => tab.to)).not.toContain("/settings");
  });

  it("gives every destination a short label for the narrow bar", () => {
    for (const item of NAV_ITEMS) {
      expect(item.short, `${item.label} has no short label`).toBeTruthy();
      expect((item.short as string).length).toBeLessThanOrEqual(4);
    }
  });
});
