import { describe, expect, it } from "vitest";
import {
  ALERTS_ROUTE,
  MOBILE_TABS,
  MOBILE_TAB_ROUTES,
  NAV_GROUPS,
  NAV_ITEMS,
  navGroupLabel,
  navItemOwns,
} from "./nav.v2";
import { isNavIconName } from "../components/icons/NavIcon";

describe("nav.v2 — structure", () => {
  it("declares three destinations and 연결 · 설정 at the foot (product-owner decision, 2026-10-27)", () => {
    // The menu had grown to eleven places and the pipeline could not be seen in it. The groups are quiet: the
    // rail draws no group names, the foot group is pinned to the bottom.
    expect(NAV_GROUPS.map((group) => group.items.map((item) => item.to))).toEqual([
      ["/", "/reviews", "/knowledge"],
      ["/connect", "/settings"],
    ]);
    expect(NAV_GROUPS.map((group) => !!group.foot)).toEqual([false, true]);
    expect(NAV_GROUPS.map(navGroupLabel)).toEqual([null, null]);
  });

  it("labels every destination in seller language", () => {
    expect(NAV_ITEMS.map((item) => item.label)).toEqual(["홈", "리뷰·문의", "상품·정책", "연동", "설정"]);
  });

  it("keeps the routes the menu no longer names, owned by the destination that leads to them", () => {
    // 할 일, 반복 문제, 주문 and 리포트 left the menu; their routes did not. 문의 and 상품 are lit under the
    // destination that now holds them, so a seller on /inquiries still sees where they are.
    const owner = (path: string) => NAV_ITEMS.filter((item) => navItemOwns(item, path)).map((item) => item.label);
    expect(owner("/inquiries")).toEqual(["리뷰·문의"]);
    expect(owner("/inquiries/abc")).toEqual(["리뷰·문의"]);
    expect(owner("/reviews/reply/r1")).toEqual(["리뷰·문의"]);
    expect(owner("/products/p1")).toEqual(["상품·정책"]);
    // Under /settings, but 정책 is 상품·정책's: the longest owned route wins, so exactly one row is lit.
    expect(owner("/settings/policies")).toEqual(["상품·정책"]);
    expect(owner("/settings/alerts")).toEqual(["설정"]);
    expect(owner("/")).toEqual(["홈"]);
    expect(owner("/orders")).toEqual([]);
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
  it("uses the three destinations plus a 더보기 trigger", () => {
    expect(MOBILE_TAB_ROUTES).toEqual(["/", "/reviews", "/knowledge"]);
    expect(MOBILE_TABS).toHaveLength(3);
  });

  it("never re-declares a tab — every tab is the side-nav item itself", () => {
    for (const tab of MOBILE_TABS) {
      expect(NAV_ITEMS).toContain(tab);
    }
  });

  it("keeps setup work (채널 연동, 설정) out of the tab bar", () => {
    expect(MOBILE_TABS.map((tab) => tab.to)).not.toContain("/connect");
    expect(MOBILE_TABS.map((tab) => tab.to)).not.toContain("/settings");
  });

  it("gives every destination a short label for the narrow bar", () => {
    for (const item of NAV_ITEMS) {
      expect(item.short, `${item.label} has no short label`).toBeTruthy();
      // Five: 「리뷰·문의」 and 「상품·정책」 are whole names, and the bar now holds four buttons, not five.
      expect((item.short as string).length).toBeLessThanOrEqual(5);
    }
  });
});
