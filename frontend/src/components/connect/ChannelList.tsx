import { Link, useNavigate } from "react-router-dom";
import { channelSupportDisplay } from "../../lib/channelSupport";
import { channelCardAction, selectChannelAccount } from "../../lib/channelConnection";
import { CAFE24_CONNECT_ROUTE } from "../../lib/cafe24Connect";
import { frontendRunId, isWalkthroughMode, withWalkthroughRun } from "../../lib/guidedConnection/walkthrough";
import { kstDayTime } from "../../lib/format";
import {
  EXPIRY_ATTENTION_SUMMARY,
  expiryNeedsAttention,
  expiryStateView,
  shouldOfferRenewal,
} from "../../lib/coupangExpiry";
import { hasReviewRecord, reviewEntryLabel, reviewRecordPath } from "../../lib/reviewRecord";
import { connectionState, type ConnectionState } from "../../lib/connectionState";
import { channelRowOf } from "../../lib/connect/channelRow";
import type { ScreenReadReadinessState } from "../../lib/acquisitionReadiness";
import { RENEW_CTA_LABEL } from "../coupang/CoupangExpiryPanel";
import type {
  ChannelResponse,
  ConnectionStatusView,
  SellerAccountResponse,
} from "../../lib/types";
import { Btn } from "../ui/Btn";
import { Status, type StatusTone } from "../ui/Status";
import { Disclosure } from "../ui/Disclosure";
import { Empty } from "../ui/Empty";

/**
 * The channel list.
 *
 * WHAT IT MAY AND MAY NOT SAY. Every row is a channel the server actually returned, and every
 * support word on it comes from `channelSupportDisplay`, which turns the server's own support
 * FACTS into conservative copy. This component adds no support claim of its own: it does not
 * describe any channel as automatically connected, and it does not present the catalogue as a list
 * of things that work. The row's action is decided by `channelCardAction` from the account's real
 * connection status, so a label can never get ahead of the account behind it.
 *
 * <p><b>한 연결은 레코드다</b> (2026-10-07). 세 줄이 각각 둥근 상자 안에서 자기 폭을 쓰고 있었고, 상태는
 * 알약으로, 마지막 수집은 문장 속에 묻혀 있었다 — 세 채널을 세로로 비교하는 일이 되지 않았다. 주문의
 * 「읽은 범위」와 같은 문법으로 선다: 열 이름 한 줄, 그 아래로 가는 선에 걸린 행들, 그리고 열마다 제 자리.
 *
 * <p><b>수집 시각은 실제 시각이다.</b> 「마지막 수집 1주 전」은 어제 읽은 것과 여드레 전에 읽은 것을 같은
 * 말로 덮는다 — 그 차이가 이 화면을 여는 이유인데도. 주문·리포트가 쓰는 그 시각 표기를 그대로 쓴다.
 *
 * <p><b>열 이름은 모든 행에 대해 참이다</b> — 「마지막 수집 성공」. 전에는 행마다 문장이 달라서 성공한
 * 행은 「마지막 수집 …」, 실패가 쌓인 행은 「마지막 성공 … · 그 뒤로 수집되지 않았습니다」라고 적었다.
 * 열이 생기면 그 둘을 한 이름으로 불러야 하고, 둘 다에 대해 참인 이름은 「성공」이 붙은 쪽이다. 실패가
 * 쌓였다는 사실은 사라지지 않고 그 칸 안에 한 줄로 남는다.
 *
 * <p><b>그리고 세 가지 「없음」을 섞지 않는다.</b> 연결되지 않은 채널에는 수집 시각 자리에 아무것도 적지
 * 않고(「—」), 연결됐지만 한 번도 성공하지 못한 채널은 「수집 이력 없음」이며, 성공했는데 그 뒤로 실패가
 * 쌓인 채널은 마지막 <b>성공</b> 시각과 함께 그 사실을 말한다. 셋을 한 단어로 적으면 어느 것도 참이 아니다.
 */
function ChannelRow({
  channel,
  account,
  health,
  statusLoading,
  reviewCount,
  reviewLane,
  onNotice,
  onStartReviewSetup,
}: {
  channel: ChannelResponse;
  account: SellerAccountResponse | null;
  health: ConnectionStatusView | null;
  statusLoading: boolean;
  reviewCount: number | null;
  /** 이 계정의 브라우저 수집 사실. 계정이 없거나 아직 읽지 못했으면 null. */
  reviewLane: { readiness: ScreenReadReadinessState | null; lastReadAt: string | null } | null;
  onNotice: (message: string) => void;
  /** 계정이 없으면 만들고 상품평 수집 화면을 연다. 자격은 받지 않는다. */
  onStartReviewSetup: (channel: ChannelResponse, account: SellerAccountResponse | null) => void;
}) {
  const navigate = useNavigate();
  const canUpload =
    channel.status === "FILE_UPLOAD_SUPPORTED" || channel.actionLabel === "파일 업로드";
  const support = channelSupportDisplay(channel);
  const lastCollected = health?.lastSyncedAt ?? channel.lastSyncedAt;
  const failing = !!health && (health.consecutiveFailures > 0 || !!health.lastError);
  const action = channelCardAction(channel, account, canUpload, failing);
  // One word for how this channel stands (A5): 연결됨 · 연결 필요 · 연결 중 · 재연결 필요 · 오류.
  const apiState = connectionState(account, health);
  /**
   * 이 행은 두 lane을 갖는다. 한 capability가 꺼져 있다고 채널 전체를 미완성으로 부르지 않는다
   * (`lib/connect/channelRow.ts`) — 그 판단은 순수 함수 하나가 하고, 여기서는 그 답을 그린다.
   */
  const row = channelRowOf({
    api: apiState,
    screenReadReviews: channel.support?.screenReadReviews === true,
    reviewReadiness: reviewLane?.readiness ?? null,
    lastScreenReadAt: reviewLane?.lastReadAt ?? null,
    hasAccount: !!account,
  });
  const state = row.state ?? apiState;

  // Credential-expiry surfacing (Coupang). The backend supplies the expiry sub-view on the health read;
  // WARN_* / DATE_PASSED / EXPIRED flag "만료 예정·조치 필요", and from WARN_14 (renewRecommended) the row
  // offers the guided-renewal CTA. Absent expiry ⇒ nothing shown (channels without a token-expiry concept).
  const expiry = health?.expiry ?? null;
  const expiryFlagged = !!expiry && expiryNeedsAttention(expiry.state);
  const offerRenewal = !!account && shouldOfferRenewal(expiry);

  // The way into what this channel collected. It needs an account because the record is that
  // account's, and it needs nothing else — not a count, not a healthy connection. A seller whose
  // collection is failing still has the 상품평 gathered before it broke, and hiding the entry until
  // the numbers look right is how a working feature became invisible in the first place.
  const showReviewEntry = hasReviewRecord(channel.code) && !!account;

  // Route targets updated to the v2 IA; the decision logic itself is untouched.
  function handleAction() {
    switch (action.intent) {
      case "manage":
        if (account) {
          navigate(`/connect/channels/${account.id}`);
        }
        return;
      case "connect-cafe24":
        navigate(`${CAFE24_CONNECT_ROUTE}/tutorial`);
        return;
      case "reconnect":
        navigate(CAFE24_CONNECT_ROUTE);
        return;
      case "connect-naver":
        // Preserve the disposable walkthrough run id when one is bound to this frontend build. A bare
        // navigate("/connect/naver") would land the guided page with no `?walkthroughRun=`, which the
        // env-binding reads as `MISSING_URL_RUN` and fail-closes — the campaign's first in-app entry then
        // dead-ends at the mismatch screen. `frontendRunId()` is the build-injected id (never a guess), and
        // `withWalkthroughRun` is a no-op outside walkthrough mode, so normal sellers still get the bare path.
        navigate(withWalkthroughRun("/connect/naver", isWalkthroughMode() ? frontendRunId() : null));
        return;
      case "connect-coupang":
        // Same disposable-run preservation as NAVER: carry the bound run id into the Coupang connect page so
        // its env-binding gate reads a matching `?walkthroughRun=` instead of fail-closing on MISSING_URL_RUN.
        // No-op outside walkthrough mode, so normal sellers still get the bare `/connect/coupang`.
        navigate(withWalkthroughRun("/connect/coupang", isWalkthroughMode() ? frontendRunId() : null));
        return;
      case "upload":
        navigate(`/connect/upload?channelId=${channel.id}`);
        return;
      case "notice":
        // Unreachable for the three product channels (each has a connect flow); kept as the honest
        // answer if the catalog ever hands this list a channel without one.
        onNotice("이 채널은 지금 연결할 수 없습니다.");
        return;
    }
  }

  // ONE primary action per row (docs/reviewnary_design.md §7 채널 연결): the state decides what it is.
  // The record link is a text link; the health detail folds.
  // The seller's sentence for the failure (backend `ConnectorErrorWording`), and the raw connector string
  // one fold deeper for whoever is helping them — the seller never reads a gateway code, support never
  // loses it (Local Helper Pilot Packaging v1 §7).
  const detailLines = [
    failing ? (health?.lastErrorKo ?? "최근 수집에서 오류가 있었습니다. 연결 관리에서 확인해 주세요.") : null,
  ].filter((line): line is string => !!line);
  const diagnostic = failing && health?.lastError ? health.lastError : null;

  /**
   * 이름 아래 한 줄에 설 사실들. 연결되지 않은 채널에서는 이 채널이 무엇을 줄 수 있는지(지원 요약),
   * 리뷰 lane이 따로 서 있으면 그 lane의 한 마디. 둘 다 상태가 아니므로 색도 테두리도 없다.
   */
  const sub = [
    ...(account ? [] : [support.primaryLabel, ...support.chips]),
    ...(account || !support.uploadQualifier ? [] : [support.uploadQualifier]),
    // `row.reviewLine`은 여기 적히지 않는다. 「리뷰 수집 · 마지막 수집」은 바로 오른쪽 「마지막 수집 성공」
    // 열이 이미 말하는 것이고, 그래서 세 행 중 한 행만 부제를 갖는 화면이 됐다. 그 사실은 사라지지 않고
    // 아래 `read`에서 <b>어느 lane의 시각을 적을지</b>를 그대로 정한다 — 표시만 그만둔다.
  ];

  // 연결되지 않음 · 읽은 적 없음 · 읽었음 — 셋은 서로 다른 사실이고, 이 열에서 서로 다르게 생겼다.
  const read = (() => {
    // 연결한 적 없는 채널에 「수집 이력 없음」은 고칠 것이 있다는 뜻으로 읽힌다. 아직 아무 약속도 하지
    // 않은 채널이고, 이 열에는 할 말이 없다.
    if (!account) {
      return "—";
    }
    if (row.reviewLine) {
      return reviewLane?.lastReadAt ? kstDayTime(reviewLane.lastReadAt) : "수집 이력 없음";
    }
    if (!row.showApiCollectionLine) {
      return "—";
    }
    return lastCollected ? kstDayTime(lastCollected) : "수집 이력 없음";
  })();

  return (
    <li className="flex flex-wrap items-center border-t border-line/70 py-2 text-sm">
      <div className={COL.name}>
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <span className="break-keep font-medium text-ink">{channel.nameKo}</span>
          {expiryFlagged && expiry ? (
            <span className="text-xs font-semibold text-warn" data-testid="channel-expiry">
              {expiryStateView(expiry.state).label} · {EXPIRY_ATTENTION_SUMMARY}
            </span>
          ) : null}
        </div>
        {/* 보조 줄 — 이 채널에 대해 더 할 말이 있을 때만. 알약이 아니라 글자다: 「문의 수집」과 「주문 요약」은
            상태가 아니라 이 채널이 무엇을 줄 수 있는지에 대한 사실이고, 색이 필요한 자리가 아니다. */}
        {sub.length > 0 || detailLines.length > 0 || (showReviewEntry && account) ? (
          <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted">
            {sub.map((text) => (
              <span key={text} className="break-keep">
                {text}
              </span>
            ))}
            {showReviewEntry && account ? (
              <Link
                to={reviewRecordPath(account.id)}
                aria-label={`${channel.nameKo} ${reviewEntryLabel(reviewCount, channel.code)}`}
                className="rounded font-semibold text-brand-700 underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                {reviewEntryLabel(reviewCount, channel.code)}
              </Link>
            ) : null}
            {detailLines.length > 0 ? (
              <Disclosure label="자세히" summaryClassName="px-0 text-xs">
                <div className="mt-1 space-y-1">
                  {detailLines.map((line) => (
                    <p key={line} className="break-keep text-sm text-warn">
                      {line}
                    </p>
                  ))}
                  {diagnostic ? (
                    <Disclosure label="기술 정보" summaryClassName="px-0 text-xs">
                      <p className="mt-1 break-all font-mono text-xs text-muted" data-testid="connection-diagnostic">
                        {diagnostic}
                      </p>
                    </Disclosure>
                  ) : null}
                </div>
              </Disclosure>
            ) : null}
          </div>
        ) : null}
      </div>
      <div className={COL.state} data-testid="connection-state">
        <Status tone={statusLoading && !!account ? "neutral" : TONE[state.tone]} variant="quiet">
          {statusLoading && !!account ? "상태 확인 중" : state.label}
        </Status>
      </div>
      <div className={COL.read}>
        <span className={failing ? "text-warn" : "text-muted"}>{read}</span>
        {/* 실패가 쌓인 연결에서 이 시각은 「마지막으로 읽은 때」가 아니라 「마지막으로 성공한 때」다.
            그 뒤의 시도들은 아무것도 남기지 않았고, 그 사실이 시각 옆에 없으면 이 행은 어제 읽은 행처럼
            읽힌다 — 실측 org에서 그 뒤의 시도는 일곱 번이었다. */}
        {failing && lastCollected ? (
          <span className="mt-0.5 block break-keep text-xs text-warn">마지막 성공 · 그 뒤로 수집되지 않았습니다</span>
        ) : null}
      </div>
      <div className={COL.action}>
        {offerRenewal && account ? (
          <Btn size="sm" variant="outline" onClick={() => navigate(`/connect/coupang/renew/${account.id}`)}>
            {RENEW_CTA_LABEL}
          </Btn>
        ) : null}
        {row.primary.kind === "DEFER" ? (
          <Btn
            size="sm"
            variant={action.intent === "manage" ? "outline" : "solid"}
            onClick={handleAction}
            disabled={action.disabled || statusLoading}
          >
            {action.label}
          </Btn>
        ) : row.primary.kind === "MANAGE" ? (
          <Btn
            size="sm"
            variant="outline"
            onClick={() => account && navigate(`/connect/channels/${account.id}`)}
            data-testid="channel-manage"
          >
            {row.primary.label}
          </Btn>
        ) : (
          <Btn
            size="sm"
            onClick={() => onStartReviewSetup(channel, account)}
            disabled={statusLoading}
            data-testid="channel-review-setup"
          >
            {row.primary.label}
          </Btn>
        )}
      </div>
    </li>
  );
}

/**
 * 한 열에 여럿이 서는 자리의 폭 — 세 채널이 세로로 비교되려면 열이 제 자리를 지켜야 한다(주문과 같다).
 *
 * <p><b>이 화면의 모든 행이 이 열을 쓴다</b> (2026-10-09). 아래 「이 Mac」 구역은 제 나름의 자유 배치였고,
 * 그래서 같은 화면에서 상태가 두 군데, 시각이 두 군데에 있었다. 폭은 여기서만 정해지고 그 구역이 가져다
 * 쓴다 — 두 벌이 생기면 두 벌이 어긋난다.
 */
export const COL = {
  name: "min-w-0 flex-1 pr-4",
  state: "w-[110px] shrink-0 pr-4",
  read: "w-[220px] shrink-0 pr-4 text-right tabular-nums",
  action: "flex w-[190px] shrink-0 flex-wrap items-center justify-end gap-2",
};

/**
 * 상태 한 단어 — 알약이 아니라 글자다. 「연결됨」 셋이 나란히 선 화면에서 알약 셋은 상태가 아니라 장식이고,
 * 그 셋이 가장 진한 물건이 되면 정작 하나뿐인 「연결 필요」가 그 안에 묻힌다. 이 제품에는 이 읽기를 위한
 * 렌더러가 이미 있다({@code Status}의 `quiet` — 확인할 일이 쓰는 그것), 그래서 새로 만들지 않는다.
 */
const TONE: Record<ConnectionState["tone"], StatusTone> = {
  good: "good",
  muted: "neutral",
  warn: "warn",
  bad: "bad",
};

export function ChannelList({
  channels,
  accounts,
  health,
  statusLoading,
  /** Collected 상품평 per account, for the rows that have a record. Absent = unknown, never zero. */
  reviewCounts,
  reviewLanes,
  onNotice,
  onStartReviewSetup,
  /** True while the catalog itself is still loading (as opposed to loaded-and-empty or failed). */
  channelsLoading = false,
  /** True when the catalog read failed — the list then says so instead of rendering nothing. */
  channelsError = false,
}: {
  channels: readonly ChannelResponse[];
  accounts: SellerAccountResponse[] | null;
  health: Map<string, ConnectionStatusView>;
  statusLoading: boolean;
  reviewCounts?: Map<string, number>;
  /** 계정별 브라우저 수집 사실(준비 상태 · 화면 수집의 마지막 성공). 읽지 못한 계정은 목록에 없다. */
  reviewLanes?: Map<string, { readiness: ScreenReadReadinessState | null; lastReadAt: string | null }>;
  onNotice: (message: string) => void;
  onStartReviewSetup: (channel: ChannelResponse, account: SellerAccountResponse | null) => void;
  channelsLoading?: boolean;
  channelsError?: boolean;
}) {
  if (channelsLoading && channels.length === 0) {
    return <p className="py-2 text-sm text-muted">불러오는 중…</p>;
  }
  if (channels.length === 0) {
    return channelsError ? (
      <Empty title="채널 정보를 불러오지 못했습니다" body="연결 상태를 확인한 뒤 다시 시도해 주세요." />
    ) : (
      <Empty
        title="연결할 수 있는 채널이 없습니다"
        body="네이버 스마트스토어, 쿠팡, 카페24가 표시되어야 합니다. 잠시 후 다시 시도해 주세요."
      />
    );
  }
  return (
    <>
      {/* aria-hidden: 각 행이 제 이름을 달고 있다. 열 이름은 눈으로 비교하는 쪽을 위한 것이다. */}
      <div aria-hidden="true" className="flex items-center pb-1 text-xs text-muted">
        <span className={COL.name}>채널</span>
        <span className={COL.state}>연결</span>
        <span className={COL.read}>마지막 수집 성공</span>
        <span className={COL.action} />
      </div>
      <ul aria-label="채널 목록">
        {channels.map((channel) => {
          const account = selectChannelAccount(accounts, channel.id);
          return (
            <ChannelRow
              key={channel.id}
              channel={channel}
              account={account}
              health={account ? health.get(account.id) ?? null : null}
              statusLoading={statusLoading}
              reviewCount={account ? reviewCounts?.get(account.id) ?? null : null}
              reviewLane={account ? reviewLanes?.get(account.id) ?? null : null}
              onNotice={onNotice}
              onStartReviewSetup={onStartReviewSetup}
            />
          );
        })}
      </ul>
    </>
  );
}
