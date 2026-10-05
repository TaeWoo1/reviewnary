import { describe, expect, it } from "vitest";
import { productChannelLabel } from "./productRows";

/**
 * 이 파일에는 전에 `orderProductRows`가 있었다. 화면이 서버에서 받은 20개를 자기 규칙으로 다시 줄
 * 세우던 함수인데, 그 규칙의 두 번째 키가 서버의 것과 달라서 「어느 20개가 페이지에 오르는가」와 「그
 * 20개가 어떤 순서로 서는가」를 서로 다른 수량이 정하고 있었다. 정렬은 카탈로그 전체를 볼 수 있는 한
 * 자리로 옮겼고(`ProductCatalogService` · `ProductCatalogOrderingContractTest`), 화면은 받은 순서를
 * 그대로 그린다(`Products.test.tsx`). 남은 것은 채널 코드를 판매자의 말로 옮기는 일 하나뿐이다.
 */
describe("상품 행의 채널 이름", () => {
  it("판매자가 아는 이름으로 옮긴다", () => {
    expect(productChannelLabel("NAVER")).toBe("네이버");
    expect(productChannelLabel("COUPANG")).toBe("쿠팡");
    expect(productChannelLabel("CAFE24")).toBe("카페24");
  });

  it("모르는 코드는 지어내지 않고 코드 그대로 둔다", () => {
    expect(productChannelLabel("ESM")).toBe("ESM");
  });
});
