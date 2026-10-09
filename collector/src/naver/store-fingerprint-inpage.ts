/**
 * <b>이 화면이 어느 가게인가 — 원문이 아니라 digest 하나로.</b>
 *
 * <p>리뷰 한 줄도 없는 기간을 읽으면 상품번호가 없고, 상품번호가 없으면 카탈로그 fence가 그 화면을 이
 * 조직의 가게로 확인할 수 없다(2026-10-10 실측). 그런데 판매자 센터의 chrome에는 행과 무관하게 늘 같은 두
 * 가지가 찍혀 있다 — 전역 내비의 <b>판매자 계정 식별자</b>(「… 님」, 판매자 정보로 가는 링크)와 좌측
 * 내비의 <b>스토어 표시명</b>. 이 스크립트는 그 둘을 normalize해 **domain-separated composite digest** 하나로
 * 만들어 돌려준다.
 *
 * <h2>원문은 넘어오지 않는다</h2>
 *
 * <p>이 모듈이 존재하는 이유의 절반이다. 판매자 계정 식별자는 기록에 남겨서는 안 되는 값이고, digest는
 * 비교 장치이지 은폐 장치가 아니다 — 그래서 해싱을 <b>페이지 안에서</b> 하고, 경계를 넘는 것은 64자 hex
 * 하나뿐이다. 읽어 들인 글자는 이 함수의 스코프를 벗어나지 않는다.
 *
 * <h2>스스로는 아무것도 증명하지 않는다</h2>
 *
 * <p>digest는 「이 화면이 전에 본 그 화면과 같다」만 말할 수 있다. 「그 화면이 이 조직의 가게다」를 말하는
 * 것은 여전히 카탈로그 fence이고, 이 값은 fence가 MATCH를 낸 읽기에서만 저장된다. 화면이 스스로를
 * 증명하는 경로는 만들지 않는다.
 */

/** 도메인 분리자. 다른 쓰임의 같은 문자열과 같은 digest가 되지 않도록. */
export const NAVER_SCREEN_STORE_DOMAIN = "NAVER:SCREEN_STORE_V1";

/**
 * `(async () => …)()` — `crypto.subtle`이 비동기라서다. 평가기는 Promise를 기다린다(`tab.evaluate`).
 *
 * <p>어느 한 조각이라도 읽히지 않으면 <b>null</b>이다. 반쪽 digest는 다음 읽기에서 조용히 달라지고,
 * 그러면 그것은 「가게가 바뀌었다」로 읽힌다 — 모르는 것은 모른다고 말하는 편이 싸다.
 */
export function buildNaverStoreFingerprintScript(): string {
  return `(async function () {
  function text(el) { return el && typeof el.innerText === 'string' ? el.innerText : ''; }
  // 전역 내비에서 「<식별자> 님」을 들고 있는 링크. 그 링크가 가리키는 곳이 판매자 정보다 — 스토어가 아니라
  // 계정이라는 것을 route가 말해 준다.
  function accountId() {
    var nav = document.querySelector('nav.seller-navbar') || document.querySelector('nav');
    if (!nav) { return null; }
    var links = nav.querySelectorAll('a');
    for (var i = 0; i < links.length; i++) {
      var raw = text(links[i]).trim();
      if (raw.indexOf('님') < 0) { continue; }
      var m = /^(\\S+?)님/.exec(raw);
      if (!m) { continue; }
      var href = links[i].getAttribute('href') || '';
      if (href.indexOf('/seller/member') < 0) { continue; }
      return m[1];
    }
    return null;
  }
  // 좌측 내비의 첫 줄이 스토어 표시명이다. 단독 신원으로 쓰지 않는다 — 바뀔 수 있는 값이고, 여기서는
  // 식별자와 함께 묶여 「같은 화면인가」를 조금 더 좁히는 역할만 한다.
  function storeName() {
    var side = document.querySelector('.seller-side-nav');
    if (!side) { return null; }
    var lines = text(side).split('\\n');
    for (var j = 0; j < lines.length; j++) {
      var line = lines[j].trim();
      if (!line) { continue; }
      if (line.indexOf('내비게이션') >= 0) { continue; }
      return line;
    }
    return null;
  }
  function normalize(v) {
    return String(v == null ? '' : v).normalize('NFKC').trim().replace(/\\s+/g, ' ');
  }
  try {
    if (String(location.host || '').toLowerCase() !== 'sell.smartstore.naver.com') { return null; }
    var id = normalize(accountId());
    var name = normalize(storeName());
    if (!id || !name) { return null; }
    var subtle = window.crypto && window.crypto.subtle;
    if (!subtle || typeof subtle.digest !== 'function') { return null; }
    var material = ${JSON.stringify(NAVER_SCREEN_STORE_DOMAIN)} + ':' + id + ':' + name;
    var bytes = new TextEncoder().encode(material);
    var buf = await subtle.digest('SHA-256', bytes);
    var out = '';
    var view = new Uint8Array(buf);
    for (var k = 0; k < view.length; k++) {
      out += ('0' + view[k].toString(16)).slice(-2);
    }
    return out;
  } catch (e) {
    return null;
  }
})()`;
}

/** 64자 소문자 hex, 또는 null. 경계를 넘어온 값이 digest의 모양이 아니면 없는 것으로 친다. */
export function sanitizeStoreFingerprint(raw: unknown): string | null {
  return typeof raw === "string" && /^[0-9a-f]{64}$/.test(raw) ? raw : null;
}
