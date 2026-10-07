import type { StatusTone } from "../../components/ui/Status";
import type { AcquisitionReadinessView } from "../acquisitionReadiness";
import type {
  ChannelCapabilityOverview,
  ConnectionInfoView,
  ConnectionStatusView,
  LocalAgentRunState,
} from "../types";

/**
 * <b>쿠팡 채널 화면이 말하는 것은 두 가지뿐이다.</b>
 *
 * <p>판매자가 이 화면에서 이해해야 하는 것은 「리뷰는 어떻게 들어오고, 문의·주문은 어떻게 들어오는가」
 * 둘이다. 지금까지 이 페이지는 그 둘을 <b>열세 개의 제목과 스물다섯 개의 컨트롤</b>로 흩어 놓았고, 그중
 * 두 곳은 같은 채널을 서로 반대로 설명했다(실측 2026-09-14, 1440×900: 「이 채널은 자동 수집을 지원하지
 * 않습니다」와 「쿠팡 문의와 주문은 API로 자동 수집합니다」가 같은 페이지에 700px 간격으로).
 *
 * <p>이 모듈은 그 두 카드의 상태를 <b>한 곳에서</b> 정한다. 두 카드가 각자 자기 사실을 재도출하면 둘은
 * 언젠가 어긋나고, 어긋난 쪽이 어느 쪽인지 말할 사람이 없다.
 *
 * <p><b>배포에서 쓸 수 없는 capability는 설정하게 하지 않는다</b>(product-owner decision, 2026-09-14).
 * 쿠팡 OpenAPI 커넥터가 꺼진 배포에서는 API 카드가 <b>존재하지 않는다</b> — 예전에는 커넥터가 꺼져 있어도
 * 자격 입력 폼 세 칸이 그대로 떠 있었고, 판매자가 키를 넣어도 아무것도 수집되지 않았다. 확인할 수 없을
 * 때(읽기 실패)도 그리지 않는다: 될지 모르는 설정을 권하는 것이 이 결정이 막는 바로 그 일이다.
 */

export interface CapabilityCardStatus {
  tone: StatusTone;
  label: string;
}

export type ReviewCardKind = "READY" | "SETUP" | "CHECKING";

export interface ReviewCardView {
  status: CapabilityCardStatus;
  kind: ReviewCardKind;
}

/**
 * 리뷰 수집 카드의 <b>상태</b>. 수집 동작은 더 이상 여기서 나오지 않는다.
 *
 * <p><b>왜 `primaryLabel`이 사라졌는가</b>(2026-10-07 라이브). 이 카드는 「리뷰 수집 연결하기」를 가장 강한
 * 컨트롤로 들고 있었고, 바로 아래 「수집 주기」 안에는 통합 「지금 수집하기」가 있었다. 리뷰를 가져오려던
 * 판매자가 눈에 먼저 드는 쪽을 눌렀고, 그것은 canonical 경로가 아니라 안내 carrier였다 — 같은 자료에
 * 수집처럼 보이는 입구가 둘 있었다는 뜻이다. 리뷰의 수집 primary는 이제 `CollectNowAction` 하나이고,
 * 이 카드는 상태와, 막혀 있을 때의 <b>복구</b> 경로만 말한다.
 *
 * <p>`CHANNEL_NOT_SUPPORTED` · `FILE_UPLOAD_ACCOUNT`는 카드 자체가 없다(호출자가 `null`을 받는다):
 * 「가져오기」 제목을 달아 놓고 왜 비었는지 설명하는 것은, 이 채널이 갖지 않은 능력을 실패처럼 보이게 한다.
 */
export function reviewCardOf(readiness: AcquisitionReadinessView | null): ReviewCardView | null {
  if (!readiness) {
    return { status: { tone: "neutral", label: "확인 중" }, kind: "CHECKING" };
  }
  if (readiness.state === "CHANNEL_NOT_SUPPORTED" || readiness.state === "FILE_UPLOAD_ACCOUNT") {
    return null;
  }
  if (readiness.state === "READY") {
    return { status: { tone: "good", label: "연결됨" }, kind: "READY" };
  }
  // HELPER_NOT_LINKED · STORE_IDENTITY_UNKNOWN — 둘 다 셋업이 답한다. 어느 쪽인지는 셋업의 첫 걸음이
  // 스스로 알아내므로, 카드가 그 둘을 구별해 말할 이유가 없다.
  return { status: { tone: "warn", label: "연결 필요" }, kind: "SETUP" };
}

/**
 * 막힌 걸음을 푸는 동작의 이름 — 책상 상태가 정한다. 수집 동작이 아니므로 「수집」이라는 말을 쓰지 않는다.
 *
 * <p>`null`은 복구할 것이 없다는 뜻이고, 그때 이 화면에 다른 입구는 그려지지 않는다.
 */
export function reviewRecoveryLabel(desk: LocalAgentRunState | null): string | null {
  switch (desk) {
    case "UNPAIRED":
      return "이 Mac 연결하기";
    case "AUTH_REQUIRED":
      return "판매자 센터 로그인";
    default:
      return null;
  }
}

export type ApiCardKind = "READY" | "REAUTH" | "SETUP";

export interface ApiCardView {
  status: CapabilityCardStatus;
  kind: ApiCardKind;
  primaryLabel: string;
}

/**
 * 문의·주문 자동 수집 카드, 또는 `null`.
 *
 * `null`인 경우가 둘이고 이유가 같다 — <b>이 배포에서 돌지 않는 것을 설정하게 하지 않는다</b>:
 * 커넥터가 해석되지 않는 배포(`autoCollectSupported=false`)와, 그것을 확인하지 못한 경우(읽기 실패).
 */
export function apiCardOf(
  overview: ChannelCapabilityOverview | null,
  info: ConnectionInfoView | null,
  status: ConnectionStatusView | null,
): ApiCardView | null {
  if (!overview || !overview.autoCollectSupported) return null;
  if (!info) {
    return { status: { tone: "warn", label: "연결 필요" }, kind: "SETUP", primaryLabel: "API 연결하기" };
  }
  const state = status?.state;
  if (state === "EXPIRED" || state === "NEEDS_REAUTH" || state === "DISCONNECTED") {
    return { status: { tone: "bad", label: "다시 연결 필요" }, kind: "REAUTH", primaryLabel: "연결 정보 갱신" };
  }
  return { status: { tone: "good", label: "연결됨" }, kind: "READY", primaryLabel: "지금 가져오기" };
}

/** 이 채널의 리뷰 수집 화면 경로. 한 정의 — 카드와 라우트가 서로 다른 문자열을 쓰지 않는다. */
export function reviewCollectionPath(accountId: string): string {
  return `/connect/channels/${accountId}/review-collection`;
}
