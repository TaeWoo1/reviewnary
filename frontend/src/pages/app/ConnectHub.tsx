import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import type { ReactNode } from "react";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { Disclosure } from "../../components/ui/Disclosure";
import { BtnLink } from "../../components/ui/Btn";
import { ChannelList } from "../../components/connect/ChannelList";
import { HelperStatusCard } from "../../components/connect/HelperStatusCard";
import { HomeReviewOpsCard } from "../../components/actionWindow/HomeReviewOpsCard";
import { useOperationsStore } from "../../hooks/useOperationsStore";
import { isFixturePreviewEnabled } from "../../lib/actionWindow/devMode";
import { useOpenAlerts } from "../../lib/openAlerts";
import { api } from "../../lib/apiClient";
import { selectChannelAccount } from "../../lib/channelConnection";
import { hasReviewRecord } from "../../lib/reviewRecord";
import { lastScreenRead } from "../../lib/connect/reviewCollection";
import { reviewCollectionPath } from "../../lib/connect/coupangCapabilities";
import type { ScreenReadReadinessState } from "../../lib/acquisitionReadiness";
import type {
  ChannelResponse,
  ConnectionStatusView,
  SellerAccountResponse,
} from "../../lib/types";

/**
 * 채널 연결 — where the product's data comes from, and the one place every route into it converges.
 *
 * Only the product channels are listed (NAVER / Coupang / Cafe24 — `lib/productChannels.ts`,
 * `docs/product_assembly_ia_v1.md` §2): a channel on this screen is a channel a seller can actually
 * use. The catalog rows the backend keeps for other channels are not shown here.
 *
 * The channel list lives here rather than on a separate page: splitting "the hub" from "the list"
 * meant the hub had nothing to say except "the list is over there". `/connect/channels` now
 * redirects here.
 *
 * The in-progress strip reuses the Action Window card unchanged, including its honesty gate: the
 * operations store seeds a demo run even in production, so a run is shown only when a live agent is
 * driving it or the dev fixture preview is on.
 *
 * <p><b>구역이 상자를 두르지 않는다</b> (2026-10-07). 세 구역이 각각 둥근 테두리 상자였고, 그 안의 세
 * 채널 행도 각자 제 폭을 썼다 — 한 화면에 떠 있는 물건이 셋, 그 안에 또 셋. 기능도 연동 계약도 그대로이고
 * 바뀐 것은 문법뿐이다: 구역의 이름과 그 아래 선 하나, 그리고 열이 제 자리를 지키는 행들 — 주문·지식·
 * 설정·리포트가 쓰는 그것.
 */
export function ConnectHub() {
  const [channels, setChannels] = useState<ChannelResponse[]>([]);
  const [channelsLoading, setChannelsLoading] = useState(true);
  const [channelsError, setChannelsError] = useState(false);
  const [accounts, setAccounts] = useState<SellerAccountResponse[] | null>(null);
  const [accountsLoading, setAccountsLoading] = useState(true);
  const [accountsError, setAccountsError] = useState(false);
  const [health, setHealth] = useState<Map<string, ConnectionStatusView>>(new Map());
  const [reviewCounts, setReviewCounts] = useState<Map<string, number>>(new Map());
  /**
   * <b>브라우저 상품평 수집의 사실</b> — 계정별로.
   *
   * 이 행은 오랫동안 API 연결 하나만 읽었고, 그래서 상품평을 방금 가져온 판매자에게 「연결 중 · 수집 이력
   * 없음 · [연결 계속하기]」라고 말했다. 두 사실을 여기서 읽어 행에 준다: 이 계정이 화면 수집을 쓸 수 있는가
   * (backend readiness)와 그 lane의 마지막 성공이 언제인가(`SELLER_CENTER_READ` 실행). 둘 다 fail-soft —
   * 읽지 못한 계정은 이 map에 없고, 행은 예전처럼 행동한다.
   */
  const [reviewLanes, setReviewLanes] = useState<
    Map<string, { readiness: ScreenReadReadinessState | null; lastReadAt: string | null }>
  >(new Map());
  const navigate = useNavigate();
  const [notice, setNotice] = useState<string | null>(null);
  const { openCount } = useOpenAlerts();

  useEffect(() => {
    let active = true;
    // Strict: a dead backend says so here rather than rendering the demo catalog behind the seller's back.
    void api
      .getChannelsStrict()
      .then((list) => {
        if (active) {
          setChannels(list);
          setChannelsLoading(false);
        }
      })
      .catch(() => {
        if (active) {
          setChannels([]);
          setChannelsError(true);
          setChannelsLoading(false);
        }
      });
    void api
      .getSellerAccountsStrict()
      .then((list) => {
        if (active) {
          setAccounts(list);
          setAccountsLoading(false);
        }
      })
      .catch(() => {
        if (active) {
          setAccounts(null);
          setAccountsError(true);
          setAccountsLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  // Per-account health. Fail-soft per account: a failed status read leaves that row on its catalog
  // state rather than blocking the list.
  useEffect(() => {
    const connected = (accounts ?? []).filter((account) => !account.fileUpload);
    if (connected.length === 0) {
      return;
    }
    let cancelled = false;
    void Promise.allSettled(
      connected.map((account) =>
        api.getConnectionStatusStrict(account.id).then((status) => [account.id, status] as const),
      ),
    ).then((results) => {
      if (cancelled) {
        return;
      }
      const map = new Map<string, ConnectionStatusView>();
      for (const result of results) {
        if (result.status === "fulfilled") {
          map.set(result.value[0], result.value[1]);
        }
      }
      setHealth(map);
    });
    return () => {
      cancelled = true;
    };
  }, [accounts]);

  // How many 상품평 each review-record channel has actually collected, so the row can say so instead
  // of making the seller open the page to find out. Read per account and fail-soft in both
  // directions: a rejected read leaves that account out of the map, and a row with no entry in the
  // map shows the way in without a number. The count is decoration on a link; it never gates it.
  useEffect(() => {
    const targets = channels
      .filter((channel) => hasReviewRecord(channel.code))
      .map((channel) => selectChannelAccount(accounts, channel.id))
      .filter((account): account is SellerAccountResponse => account !== null);
    if (targets.length === 0) {
      return;
    }
    let cancelled = false;
    // `size: 1` because only `total` is wanted here — the list itself belongs to the page this
    // links to, and the hub has no business pulling a screenful of what buyers wrote.
    void Promise.allSettled(
      targets.map((account) =>
        api
          .getChannelReviewsStrict(account.id, { size: 1 })
          .then((view) => [account.id, view.total] as const),
      ),
    ).then((results) => {
      if (cancelled) {
        return;
      }
      const map = new Map<string, number>();
      for (const result of results) {
        if (result.status === "fulfilled") {
          map.set(result.value[0], result.value[1]);
        }
      }
      setReviewCounts(map);
    });
    return () => {
      cancelled = true;
    };
  }, [channels, accounts]);

  // 화면 수집을 갖는 채널의 계정만. 두 읽기 모두 계정당 1회이고, 실패한 쪽은 그냥 빠진다.
  useEffect(() => {
    const targets = channels
      .filter((channel) => channel.support?.screenReadReviews === true)
      .map((channel) => selectChannelAccount(accounts, channel.id))
      .filter((account): account is SellerAccountResponse => account !== null);
    if (targets.length === 0) {
      return;
    }
    let cancelled = false;
    void Promise.allSettled(
      targets.map(async (account) => {
        const [readiness, runs] = await Promise.all([
          api.getReviewAcquisitionReadiness(account.id).catch(() => null),
          api.getSyncRunsStrict({ sellerAccountId: account.id }).catch(() => []),
        ]);
        return [
          account.id,
          {
            readiness: (readiness?.state ?? null) as ScreenReadReadinessState | null,
            lastReadAt: lastScreenRead(runs)?.finishedAt ?? null,
          },
        ] as const;
      }),
    ).then((results) => {
      if (cancelled) {
        return;
      }
      const map = new Map<string, { readiness: ScreenReadReadinessState | null; lastReadAt: string | null }>();
      for (const result of results) {
        if (result.status === "fulfilled") {
          map.set(result.value[0], result.value[1]);
        }
      }
      setReviewLanes(map);
    });
    return () => {
      cancelled = true;
    };
  }, [channels, accounts]);

  /**
   * <b>상품평 수집을 시작한다 — 자격 없이.</b>
   *
   * 쿠팡 계정 행을 만드는 코드는 오랫동안 하나뿐이었고, 그것은 판매자가 Access Key·Secret Key·업체코드를
   * <b>제출하는 순간</b>에만 돌았다. 그래서 API 키가 필요 없는 lane을 쓰려는 판매자도 키 발급 화면을 지나야
   * 계정이 생겼다. 여기서 쓰는 것은 그 화면이 쓰던 <b>같은 find-or-create</b>이고(멱등, PENDING, 자격 0),
   * 바뀐 것은 그것을 부르는 순간뿐이다 — 자격 제출이 아니라 판매자의 press.
   */
  const startReviewSetup = useCallback(
    async (channel: ChannelResponse, account: SellerAccountResponse | null) => {
      try {
        const id = account?.id ?? (await api.createApiChannelAccount(channel.id)).id;
        navigate(reviewCollectionPath(id), { state: { start: true } });
      } catch {
        setNotice("상품평 수집을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    },
    [navigate],
  );

  const ops = useOperationsStore();
  const liveRun = ops.sourceMode === "bridge" || isFixturePreviewEnabled() ? ops.run : null;
  // The NAVER account's status carries the helper's last login observation; the helper card reads it.
  const naverChannel = channels.find((channel) => channel.code === "NAVER") ?? null;
  const naverAccount = naverChannel ? selectChannelAccount(accounts, naverChannel.id) : null;
  const naverHealth = naverAccount ? (health.get(naverAccount.id) ?? null) : null;

  return (
    <div className="space-y-6">
      <PageHead title="채널 연결" />

      {openCount > 0 ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-warn/30 bg-warn/10 px-4 py-3">
          <span className="text-sm font-semibold text-warn">확인이 필요한 연결 알림 {openCount}건</span>
          <BtnLink to="/settings/alerts" size="sm" variant="outline">
            확인하기
          </BtnLink>
        </div>
      ) : null}

      {notice ? <div className="rounded-xl bg-brand-50 px-4 py-3 text-sm text-brand-700">{notice}</div> : null}

      {accountsError ? (
        <div className="rounded-xl bg-bad/10 px-4 py-3 text-sm text-bad">연결 상태를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</div>
      ) : null}

      <Block title="채널">
        <ChannelList
            channels={channels}
            accounts={accounts}
            health={health}
            statusLoading={accountsLoading}
            reviewCounts={reviewCounts}
            reviewLanes={reviewLanes}
            onNotice={setNotice}
            onStartReviewSetup={(channel, account) => void startReviewSetup(channel, account)}
          channelsLoading={channelsLoading}
          channelsError={channelsError}
        />
      </Block>

      {/* The helper is the second thing on this screen, after the channels it serves: the seller who
          arrives here from the installer sees the state and the one control that changes it, and never a
          port, a token or a pairing word (Local Helper Pilot Packaging v1). */}
      <Block
        title="reviewnary 도우미"
        note="판매자센터 화면과 함께 일할 때 필요합니다"
        action={
          <Go to="/connect/helper">설치·업데이트 안내</Go>
        }
      >
        <HelperStatusCard naverHealth={naverHealth} />
      </Block>
      {/* One section for getting data in, with its two ways side by side. The old pair — 「정기 자료
          가져오기」 and 「리뷰 수집 실행」 — described the same job twice and pointed at a third screen it
          called 「작업대」, a word from our side of the desk. */}
      <Block
        title="자료 가져오기"
        note="연결이 어려운 채널은 파일로, 네이버 리뷰는 판매자센터 화면에서 기간별로"
        action={
          <div className="flex flex-wrap items-center gap-3">
            <Go to="/connect/upload">자료 넘기기</Go>
            <Go to="/connect/review-history">기간별로 가져오기</Go>
          </div>
        }
      >
        <HomeReviewOpsCard run={liveRun} />
        <p className="break-keep border-t border-line/70 py-2 text-sm text-muted">
          지난 실행과 구간별 이력은 <Go to="/connect/imports">실행 기록</Go>에서 볼 수 있습니다.
        </p>
        <Disclosure label="파일로 넘기면 어떻게 진행되나요">
          <ol className="mt-2 space-y-2 text-sm text-muted">
            {[
              "가져올 자료를 고릅니다.",
              "형식과 기간이 맞는지 먼저 확인합니다.",
              "중복을 걸러내고 채널이 달라도 같은 형태로 정리합니다.",
              "리뷰·문의 화면과 리포트에 반영됩니다.",
            ].map((step, index) => (
              <li key={step} className="flex gap-2 break-keep">
                <span className="tabular-nums text-brand-700">{index + 1}.</span>
                {step}
              </li>
            ))}
          </ol>
        </Disclosure>
      </Block>
    </div>
  );
}

/** 한 구역 — 이름, 그 옆의 한 조각, 그 아래 선 하나. 상자가 아니다. */
function Block({
  title,
  note,
  action,
  children,
}: {
  title: string;
  note?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className="space-y-2">
      {/* 머리말은 제목 안이 아니라 제목 아래에 선다 — 구역의 이름은 이름이고, 그 옆에 붙은 문장은 읽어
          주는 쪽에서 이름의 일부가 된다. */}
      <SectionHeader title={title} hint={note} action={action} />
      {children}
    </section>
  );
}

/** 다른 화면으로 가는 길 — 글자이고, 단추가 아니다. 이 화면의 단추는 행마다의 primary 하나뿐이다. */
function Go({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      to={to}
      className="rounded text-sm font-semibold text-brand-700 underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
    >
      {children}
    </Link>
  );
}
