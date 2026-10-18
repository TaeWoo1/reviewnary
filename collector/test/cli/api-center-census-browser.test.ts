/**
 * REAL-CHROMIUM check of the API-centre census's page markers (2026-10-18, run 5): the logged-out landing and
 * the 「내 솔루션」 page both have menu lists and both read as `app_list`. Gated behind RUN_INTEGRATION=1.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { chromium, type Browser, type Page } from "playwright";
import { EXTRACT_API_CENTER_CENSUS, observeFrom, type ApiCenterStructuralCensus } from "../../src/cli/observe-api-center";

const RUN = process.env.RUN_INTEGRATION === "1";

const HEADER = (loggedIn: boolean) =>
  `<header><a href="#">API문서</a> ${loggedIn ? '<a href="#">데이터수집</a> <a href="#">로그아웃</a>' : '<a href="#">계정생성</a> <a href="#">로그인</a>'}</header>`;
const MENU = `<nav><ul><li>내 솔루션</li><li>내 스토어 애플리케이션</li><li>API 사용통계</li></ul></nav>`;

const PAGES = {
  landing: `${HEADER(false)}<h1>내 비즈니스가 쉬워지는 네이버 커머스API</h1><a href="#">커머스API센터 시작하기</a><footer><ul><li>약관</li><li>정책</li></ul></footer>`,
  solutions: `${HEADER(true)}${MENU}<h2>먼저 마켓에 입점하시고 솔루션 등록을 시작해보세요.</h2><button>입점하기</button>`,
  appList: `${HEADER(true)}${MENU}<h2>내 스토어 애플리케이션</h2><button>애플리케이션 등록</button><table><tbody><tr><td>앱</td></tr><tr><td>-</td></tr></tbody></table>`,
  // The menu ITEM reads 「내 스토어 애플리케이션」 on every page — it is a list item, not a heading or a control.
  menuOnly: `${HEADER(true)}${MENU}<h2>API 사용통계</h2>`,
} as const;

describe.skipIf(!RUN)("API-centre census page markers — real Chromium", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage();
  });
  afterAll(async () => {
    await browser?.close();
  });

  const read = async (html: string) => {
    await page.setContent(`<!doctype html><meta charset="utf-8"><body>${html}</body>`);
    const census = (await page.evaluate(EXTRACT_API_CENTER_CENSUS)) as ApiCenterStructuralCensus;
    return { census, category: observeFrom("api_center_host", census).pageCategory };
  };

  it("**the logged-out landing reads as login**", async () => {
    const { census, category } = await read(PAGES.landing);
    expect(census.loggedOutMarkerPresent).toBe(true);
    expect(category).toBe("login");
  });

  it("**the 「내 솔루션」 page is not the applications list**", async () => {
    const { census, category } = await read(PAGES.solutions);
    expect(census.appListMarkerPresent).toBe(false);
    expect(census.loggedOutMarkerPresent).toBe(false);
    expect(category).not.toBe("app_list");
  });

  it("the applications list is, by its heading and register control", async () => {
    const { census, category } = await read(PAGES.appList);
    expect(census.appListMarkerPresent).toBe(true);
    expect(category).toBe("app_list");
  });

  it("the left menu's 「내 스토어 애플리케이션」 item alone is not the list marker", async () => {
    const { census } = await read(PAGES.menuOnly);
    expect(census.appListMarkerPresent).toBe(false);
  });
});

describe.skipIf(!RUN)("the login notice on the real driver — real Chromium, NAVER URLs answered locally", () => {
  let browser: Browser;
  let page: Page;
  beforeAll(async () => {
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage();
    // Every request is answered here — nothing reaches NAVER.
    await page.route("**/*", (route) => {
      const url = route.request().url();
      const html = url.includes("nid.naver.com")
        ? `<!doctype html><meta charset="utf-8"><body><form><input id="id"><input type="password"></form></body>`
        : url.includes("/landing")
          ? `<!doctype html><meta charset="utf-8"><body>${PAGES.landing}</body>`
          : `<!doctype html><meta charset="utf-8"><body>${PAGES.solutions}</body>`;
      return route.fulfill({ status: 200, contentType: "text/html", body: html });
    });
  });
  afterAll(async () => {
    await browser?.close();
  });

  const notice = () => page.evaluate(() => !!document.getElementById("__aw_advance_panel__"));

  it("**paints on the API centre's logged-out landing; refuses NAVER's login form; clears once logged in**", async () => {
    const { NaverIssuanceDriver } = await import("../../src/action-window/naver-issuance-driver");
    const driver = new NaverIssuanceDriver(page, {});

    await page.goto("https://apicenter.commerce.naver.com/landing");
    expect(await driver.showParkNotice("LOGIN_REQUIRED")).toBe(true);
    expect(await notice()).toBe(true);
    expect(await driver.parkCleared("LOGIN_REQUIRED")).toBe(false);

    await page.goto("https://nid.naver.com/nidlogin.login");
    expect(await driver.showParkNotice("LOGIN_REQUIRED")).toBe(false);
    expect(await notice()).toBe(false);
    expect(await driver.parkCleared("LOGIN_REQUIRED")).toBe(false);

    await page.goto("https://apicenter.commerce.naver.com/solutions");
    expect(await driver.parkCleared("LOGIN_REQUIRED")).toBe(true);
  });
});
