// Navigation model for the product surface.
//
// ONE model, three renderers. The desktop side nav, the mobile bottom tabs, and the "더보기"
// drawer all derive from `NAV_GROUPS` — the mobile surfaces select from it rather than declaring
// their own lists, so the three IAs cannot drift apart.
//
// The IA is workflow-centric, not channel-centric (product assembly, 2026-08-17 —
// `docs/product_assembly_ia_v1.md` §3): 운영 answers "오늘 내가 확인하거나 조치할 일은 무엇인가?"
// as 홈 / 상품 / 리뷰 / 문의 / 주문, and 연결·설정 is where data comes from. A channel is a filter or
// a capability inside those screens, never a destination of its own.
//
// 상품 joined 운영 with Demo Core Experience v1 (2026-08-24). It is workflow-centric in the same
// sense the others are — a product is what a review, an inquiry and an order are ABOUT, and it is
// where the seller's own product knowledge is written. Its absence was not a decision: the backend
// has served `/api/products` throughout and no screen ever reached it
// (`docs/frontend/frontend_ux_audit_v1.md` §1).
//
// 확인할 일 joined 운영 with the unified case queue. It is the today-inbox this file has been parking since
// Demo Core Experience v1, and it answers 운영's own question — "오늘 내가 확인하거나 조치할 일은 무엇인가?" —
// more directly than any screen below it: one list over 문의 and 리뷰 together, ordered by how long the customer
// has waited. It sits under 홈 because the Home briefs its first rows; the entries below it stay as the places a
// seller goes when they already know WHICH object they want. Until now the queue was reachable only from two
// overflow links inside 고객 운영 관리 — a screen the seller is meant to open every morning cannot be something
// they can only fall into.
//
// 반복 문제 joined 운영 with UI/UX v2 Phase 1 (2026-09-22, product-owner decision). It is one of the seven Demo Core
// screens and was the only one a seller could reach only by falling into it — from the Home's last section or from
// 설정's overflow. It answers 운영's question for the patterns rather than the items: what keeps coming back.
// 홈 became 오늘 in the same decision: the first screen is named for the question it answers.
//
// <b>Three destinations</b> (product-owner decision, 2026-10-27, after the three-screen flow mockups). The menu had grown
// to eleven places and the seller could not see the pipeline in it: 홈 → 리뷰·문의 → 상품·정책, with 연결 · 설정 at the
// foot. Nothing behind the old entries was removed: 할 일, 반복 문제, 주문 and 리포트 keep their routes and are opened
// from inside the screens that lead to them (the Home's backlog and 반복 문제 lines, a review's product, 상품·정책).
// Deliberately absent as before: `/agent` (internal), `/customer-operations` (reached from the Home's status pill) and
// every per-channel page (reached from 연결).

export interface NavItem {
  to: string;
  label: string;
  /** Short label for the mobile tab bar, where the full label will not fit. */
  short?: string;
  /** Icon key resolved by `<NavIcon>`; an unknown key renders a neutral dot, never raw text. */
  icon: string;
  /** Exact-match highlight. Only the home route needs it. */
  end?: boolean;
  /** Other routes this destination owns: 리뷰·문의 is lit on /inquiries too, 상품·정책 on /products. */
  match?: string[];
}

export interface NavGroup {
  heading: string;
  items: NavItem[];
  /** The group's name is for the drawer and the keys, not drawn in the rail. */
  quiet?: boolean;
  /** Pinned to the foot of the rail. */
  foot?: boolean;
}

export const NAV_GROUPS: NavGroup[] = [
  {
    heading: "메뉴",
    quiet: true,
    items: [
      { to: "/", label: "홈", short: "홈", icon: "home", end: true },
      { to: "/reviews", label: "리뷰·문의", short: "리뷰·문의", icon: "review", match: ["/inquiries", "/inbox"] },
      {
        to: "/knowledge",
        label: "상품·정책",
        short: "상품·정책",
        icon: "product",
        match: ["/products", "/settings/policies"],
      },
    ],
  },
  {
    heading: "연결·설정",
    quiet: true,
    foot: true,
    items: [
      { to: "/connect", label: "연결", short: "연결", icon: "link" },
      { to: "/settings", label: "설정", short: "설정", icon: "settings" },
    ],
  },
];

/**
 * The one destination lit for `pathname`: the item whose own route or owned route is the LONGEST match.
 * /settings/policies belongs to 상품·정책 although it sits under 설정 — two lit rows would say the seller is
 * in two places.
 */
export function navOwner(pathname: string): NavItem | null {
  const matches = (route: string) => pathname === route || (route !== "/" && pathname.startsWith(`${route}/`));
  let best: { item: NavItem; length: number } | null = null;
  for (const item of NAV_GROUPS.flatMap((group) => group.items)) {
    for (const route of [item.to, ...(item.match ?? [])]) {
      if (item.end ? pathname !== route : !matches(route)) continue;
      if (!best || route.length > best.length) best = { item, length: route.length };
    }
  }
  return best?.item ?? null;
}

/** True when `item` is the destination lit for `pathname`. */
export function navItemOwns(item: NavItem, pathname: string): boolean {
  return navOwner(pathname) === item;
}

/**
 * The heading a renderer should DRAW for a group — null when drawing it would say nothing.
 *
 * <p>설정 is its own group and its only destination is also called 설정, so printing the heading puts the
 * same word twice, 4px apart, in a 240px rail. A group name earns its line by naming something its items
 * do not; one item that already carries the name is the case where it does not. Derived rather than
 * written as an empty string so the group keeps an identity for the drawer, the keys and this file.
 */
export function navGroupLabel(group: NavGroup): string | null {
  if (!group.heading || group.quiet) return null;
  if (group.items.length === 1 && group.items[0]!.label === group.heading) return null;
  return group.heading;
}

/** Every nav item, flattened. */
export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

/** The route whose surface carries the open connection-alert count. */
export const ALERTS_ROUTE = "/settings/alerts";

/**
 * Mobile bottom-tab destinations, in order: the three destinations plus a "더보기" trigger (which is not a
 * route). 연결 and 설정 are setup work and live in the drawer.
 */
export const MOBILE_TAB_ROUTES = ["/", "/reviews", "/knowledge"] as const;

/** Derived, never re-declared — a tab is the same item the side nav renders. */
export const MOBILE_TABS: NavItem[] = MOBILE_TAB_ROUTES.map((route) => {
  const item = NAV_ITEMS.find((candidate) => candidate.to === route);
  if (!item) {
    // A tab route with no nav entry would ship a bar button that leads somewhere the menu does
    // not know about. Fail at module load rather than render a phantom destination.
    throw new Error(`MOBILE_TAB_ROUTES references an unknown nav route: ${route}`);
  }
  return item;
});
