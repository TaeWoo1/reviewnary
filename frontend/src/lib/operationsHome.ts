import type { HomeRepeatedProblems } from "./types";

/**
 * Home이 자기가 계산하지 않은 숫자에 대해 말할 수 있는 것.
 *
 * <b>이 모듈이 지키는 규칙: 급함을 지어내지 않는다.</b> 한 모집단을 이름으로 부르고 그것에 무슨 일이
 * 있었는지만 말한다 — 두 수를 더하지 않고, 작은 수를 경보로 바꾸지 않는다.
 *
 * <p>2026-10-06에 이 모듈은 한 함수로 줄었다. 나머지 다섯은 책임 런타임이 열려 있지 않은 배포가 보던 두
 * 번째 Home(「지금 확인할 리뷰 / 반복 문제 / 최근 수집 상태 / 준비된 작업」)의 문장이었고, Home이 하나가
 * 되면서 그 화면과 함께 사라졌다. 같은 사실들은 canonical Home이 제 섹션에서 말한다.
 */

/** 반복 문제의 한 문장 — 결정이 필요한 것과 지켜보는 것은 다른 수이고, 더해지지 않는다. */
export function problemLine(problems: HomeRepeatedProblems): string {
  if (problems.decidable > 0) {
    // The observing clause is not decoration: both populations are DRAWN below this sentence, so stating only the
    // decidable one put 「1건 있습니다」 over two rows and left the seller counting. They stay two sentences because
    // they are two facts the server returns separately and documents as un-addable — 「2건」 would be the sum this
    // line has never been allowed to print.
    const watching =
      problems.observing > 0 ? ` ${problems.observing.toLocaleString("ko-KR")}건은 지켜보고 있습니다.` : "";
    return `지금 판단이 필요한 반복 문제가 ${problems.decidable.toLocaleString("ko-KR")}건 있습니다.${watching}`;
  }
  if (problems.observing > 0) {
    return `지금 판단이 필요한 반복 문제는 없습니다. ${problems.observing.toLocaleString("ko-KR")}건을 지켜보고 있습니다.`;
  }
  // Nothing is happening now, but something happened. The Home only carries problems whose evidence is still
  // inside the observation window, so an org whose problems all went quiet would otherwise read
  // 「아직 모인 반복 문제가 없습니다」 while 고객운영 메모리 holds every one of them — the screen denying records
  // the seller can open on the next click. It says when and how many, never which: naming them here would be
  // listing them, which is the thing the window decided not to do. It also does not name the window's length:
  // that number is `ReviewIssueThresholds.PERSIST_LOOKBACK_WEEKS`, and a second copy of it in Korean prose is
  // the copy that goes stale the day the threshold moves.
  if (problems.dormant > 0) {
    return `최근에 다시 확인된 반복 문제는 없습니다. 이전에 모인 ${problems.dormant.toLocaleString("ko-KR")}건은 고객운영 메모리에 있습니다.`;
  }
  return "아직 모인 반복 문제가 없습니다.";
}

/** 준비된 작업, or null when nothing is prepared — this area never grows to fill the space. */
