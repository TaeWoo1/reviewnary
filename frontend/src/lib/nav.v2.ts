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
// (`docs/frontend_ux_audit_v1.md` §1).
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
// Deliberately absent from the menu: `/agent` (an internal route — kept, never deleted), `/customer-operations`
// (the control for the handed-over job, reached from 오늘's status pill — Phase 4 audit), and every per-channel page
// (`/connect/channels/:accountId`, the connect wizards) — those are reached from 채널 연결.

export interface NavItem {
  to: string;
  label: string;
  /** Short label for the mobile tab bar, where the full label will not fit. */
  short?: string;
  /** Icon key resolved by `<NavIcon>`; an unknown key renders a neutral dot, never raw text. */
  icon: string;
  /** Exact-match highlight. Only the home route needs it. */
  end?: boolean;
}

export interface NavGroup {
  heading: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  // <b>운영 / 데이터 / Reviewnary / 설정</b> (product-owner decision, 2026-10-01 — Home visual target).
  //
  // The destinations and their order are UI/UX v2 Phase 4's and do not move; the four GROUP NAMES do.
  // 일 / 기록 / 준비 named what the seller comes to DO, which is a good axis for the first group and a
  // strained one for the other three: 리뷰·문의·상품·주문·리포트 are not 「기록을 찾으러」 — they are the
  // objects the product operates on, and a seller opens 상품 to change it as often as to read it. The
  // names now say what each group IS. 지식·연결 are what reviewnary needs from the seller, so the group
  // takes the product's own name, and 설정 stops being the odd third item under it.
  //
  // 오늘 joins 운영 rather than standing alone above the labels: it is the answer to 운영's question, and
  // a heading-less group of one put the most-visited destination outside the structure that names it.
  {
    heading: "운영",
    items: [
      { to: "/", label: "오늘", short: "오늘", icon: "home", end: true },
      { to: "/customer-operations/cases", label: "확인할 일", short: "확인", icon: "inbox" },
      { to: "/memory", label: "반복 문제", short: "반복", icon: "memory" },
    ],
  },
  {
    heading: "데이터",
    items: [
      { to: "/reviews", label: "리뷰", short: "리뷰", icon: "review" },
      { to: "/inquiries", label: "문의", short: "문의", icon: "mail" },
      { to: "/products", label: "상품", short: "상품", icon: "product" },
      { to: "/orders", label: "주문", short: "주문", icon: "orders" },
      // 리포트 joins the menu with Phase 4: it is a record of a period, and 설정 was never its home.
      { to: "/reports", label: "리포트", short: "리포트", icon: "report" },
    ],
  },
  {
    heading: "Reviewnary",
    items: [
      // Knowledge Sources & Acquisition v1: what reviewnary knows is SETUP, not a daily destination —
      // a seller visits it when they have material to hand over or something to confirm, and the rest
      // of the time the knowledge reaches them inside the draft that used it.
      { to: "/knowledge", label: "지식", short: "지식", icon: "list" },
      { to: "/connect", label: "연결", short: "연결", icon: "link" },
    ],
  },
  {
    // 설정 is not something reviewnary needs from the seller, and it is not an object either. It is the
    // one destination that belongs to no group, so it is its own — which is also where the target draws
    // it, separated from the rest by the group gap rather than by a rule.
    heading: "설정",
    items: [{ to: "/settings", label: "설정", short: "설정", icon: "settings" }],
  },
];

/**
 * The heading a renderer should DRAW for a group — null when drawing it would say nothing.
 *
 * <p>설정 is its own group and its only destination is also called 설정, so printing the heading puts the
 * same word twice, 4px apart, in a 240px rail. A group name earns its line by naming something its items
 * do not; one item that already carries the name is the case where it does not. Derived rather than
 * written as an empty string so the group keeps an identity for the drawer, the keys and this file.
 */
export function navGroupLabel(group: NavGroup): string | null {
  if (!group.heading) return null;
  if (group.items.length === 1 && group.items[0]!.label === group.heading) return null;
  return group.heading;
}

/** Every nav item, flattened. */
export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

/** The route whose surface carries the open connection-alert count. */
export const ALERTS_ROUTE = "/settings/alerts";

/**
 * Mobile bottom-tab destinations, in order. Four routes plus a "더보기" trigger (which is not a
 * route) make the five-tab bar. The four are the daily 운영 destinations; 채널 연결 and 설정 are
 * setup work and live in the drawer.
 */
export const MOBILE_TAB_ROUTES = ["/", "/reviews", "/inquiries", "/orders"] as const;

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
