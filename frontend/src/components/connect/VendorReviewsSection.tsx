import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Btn } from "../ui/Btn";
import { Chip } from "../ui/Chip";
import { SectionHeader } from "../ui/SectionHeader";
import { api } from "../../lib/apiClient";
import { relativeTime } from "../../lib/format";
import type {
  VendorLinkStatus,
  VendorReviewPullResult,
  VendorReviewStoreView,
  VendorReviewsOverview,
} from "../../lib/types";

const TITLE = "담당자가 올리는 네이버 리뷰";
const HINT = "담당자가 판매자센터 리뷰 엑셀을 reviewnary 서버에 올리면 이 PC가 받아 옵니다.";
const OFFLINE = "reviewnary 서버에 닿지 않습니다. 인터넷 연결을 확인해 주세요. 이미 받은 리뷰는 그대로 있습니다.";
const REVOKED = "이 PC의 연결이 해제됐습니다. 다시 연결해야 리뷰를 받을 수 있습니다.";

const FAILURE: Record<NonNullable<VendorReviewStoreView["failure"]>, string> = {
  UNREACHABLE: "마지막 받기에서 서버에 닿지 않았습니다. 다음 받기에서 다시 시도합니다.",
  NOT_AUTHORIZED: "마지막 받기에서 서버가 이 PC의 요청을 받지 않았습니다.",
  NOT_STORED: "마지막 받기에서 리뷰 일부를 저장하지 못했습니다. 다음 받기가 그 리뷰부터 다시 받습니다.",
};

/**
 * **「담당자가 올리는 네이버 리뷰」 — the seller-PC side of the vendor relay** (docs/vendor_server_contract_v1.md §3.2;
 * mockups pc-a…pc-d confirmed 2026-10-22 with four additions, each kept here):
 *
 * <ul>
 *   <li>The route is said before anything is started: the server's seller account → this PC's organisation.</li>
 *   <li>「리뷰 받기 시작」 binds exactly the stores listed at that moment. A store the operator adds later shows up
 *       as 「아직 받지 않음」 and is started the same way — no pull takes it on its own.</li>
 *   <li>Two times per store, never one: when this PC last asked the server, and when a delegate last uploaded. A PC
 *       that asked a minute ago holds nothing newer than the delegate's last file.</li>
 *   <li>Each store says its own failure, and each can be stopped and started again.</li>
 * </ul>
 *
 * Hidden entirely where the PC cannot link (`OFF`: not a seller-PC install).
 */
export function VendorReviewsSection() {
  const [link, setLink] = useState<VendorLinkStatus | null>(null);
  const [overview, setOverview] = useState<VendorReviewsOverview | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);

  const load = useCallback(async () => {
    try {
      const nextLink = await api.getVendorLink();
      if (!mounted.current) return;
      setLink(nextLink);
      if (nextLink.state === "LINKED") {
        const next = await api.getVendorReviewsOverview();
        if (mounted.current) setOverview(next);
      }
    } catch {
      if (mounted.current) setFailed(true);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
    };
  }, [load]);

  // While a link code waits for approval in the browser, ask once per interval whether it was allowed.
  useEffect(() => {
    if (link?.state !== "PENDING") return undefined;
    const timer = window.setTimeout(async () => {
      try {
        const next = await api.pollVendorLink();
        if (!mounted.current) return;
        setLink(next);
        if (next.state === "LINKED") setOverview(await api.getVendorReviewsOverview());
      } catch {
        if (mounted.current) setLink({ ...link });
      }
    }, Math.max(1, link.pollIntervalSeconds ?? 3) * 1000);
    return () => window.clearTimeout(timer);
  }, [link]);

  const startLink = async (relink: boolean) => {
    setBusy(true);
    setFailed(false);
    try {
      if (relink) await api.unlinkVendor();
      const next = await api.startVendorLink();
      if (!mounted.current) return;
      setLink(next);
      setOverview(null);
      if (next.approvalUrl) window.open(next.approvalUrl, "_blank", "noopener,noreferrer");
    } catch {
      if (mounted.current) setFailed(true);
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const run = async (action: () => Promise<VendorReviewPullResult | void>) => {
    setBusy(true);
    setNotice(null);
    setFailed(false);
    try {
      const result = await action();
      // The rows are read again before the line about what arrived is shown, so the two never disagree.
      const next = await api.getVendorReviewsOverview();
      if (!mounted.current) return;
      setOverview(next);
      if (result) setNotice(pulledLine(result));
    } catch {
      if (mounted.current) setFailed(true);
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  if (link === null || link.state === "OFF") {
    return null;
  }

  const unstarted = overview?.stores.filter((s) => s.standing === "NOT_RECEIVING") ?? [];
  const started = overview?.stores.filter((s) => s.standing === "RECEIVING" || s.standing === "PAUSED") ?? [];
  const linked = link.state === "LINKED";
  const reachable = overview?.state === "PULLED";

  let action = null;
  if (!linked && link.state !== "PENDING") {
    action = (
      <Btn size="sm" disabled={busy} onClick={() => void startLink(false)} data-testid="vendor-reviews-link">
        이 PC 연결
      </Btn>
    );
  } else if (reachable && unstarted.length > 0) {
    action = (
      <Btn
        size="sm"
        disabled={busy}
        onClick={() => void run(() => api.startVendorReviews(unstarted.map((s) => s.storeId)))}
        data-testid="vendor-reviews-start"
      >
        리뷰 받기 시작
      </Btn>
    );
  } else if (reachable && started.length > 0) {
    action = (
      <Btn size="sm" variant="outline" disabled={busy} onClick={() => void run(() => api.pullVendorReviews())}>
        {busy ? "받는 중…" : "지금 받기"}
      </Btn>
    );
  }

  return (
    <section aria-label={TITLE} className="space-y-2" data-testid="vendor-reviews">
      <SectionHeader title={TITLE} hint={HINT} action={action} />

      {failed ? <Banner tone="bad">{OFFLINE}</Banner> : null}

      {!linked && link.state !== "PENDING" ? (
        <>
          <p className="border-t border-line/70 py-2 text-sm text-ink">
            {link.state === "CODE_EXPIRED"
              ? "연결 코드의 시간이 지났습니다. 「이 PC 연결」을 다시 눌러 주세요."
              : "이 PC가 아직 reviewnary 서버에 연결되지 않았습니다. 연결하면 받을 스토어가 여기에 보입니다."}
          </p>
          <p className="break-keep text-xs text-muted">
            「이 PC 연결」은 이 브라우저에서 가입한 계정으로 로그인해 허용하는 한 번의 단계입니다. 네이버 연결 화면의 그 버튼과 같습니다.
          </p>
        </>
      ) : null}

      {link.state === "PENDING" ? (
        <div className="space-y-1 border-t border-line/70 py-2" data-testid="vendor-reviews-pending">
          <p className="text-sm text-ink">브라우저에서 이 PC를 허용하면 연결됩니다.</p>
          {link.userCode ? (
            <p className="text-xs text-muted">
              연결 코드{" "}
              <span className="font-medium tabular-nums text-ink">
                {link.userCode.slice(0, 4)}-{link.userCode.slice(4)}
              </span>
            </p>
          ) : null}
          <Btn
            size="sm"
            variant="ghost"
            onClick={() => link.approvalUrl && window.open(link.approvalUrl, "_blank", "noopener,noreferrer")}
          >
            허용 화면 다시 열기
          </Btn>
        </div>
      ) : null}

      {linked && overview === null && !failed ? <p className="py-2 text-sm text-muted">불러오는 중…</p> : null}

      {linked && overview ? (
        <>
          {overview.state === "UNREACHABLE" ? <Banner tone="bad">{OFFLINE}</Banner> : null}
          {overview.state === "NOT_AUTHORIZED" ? (
            <Banner tone="bad">
              {REVOKED}{" "}
              <Btn size="sm" variant="outline" className="ml-2" disabled={busy} onClick={() => void startLink(true)}>
                이 PC 다시 연결
              </Btn>
            </Banner>
          ) : null}
          {notice ? <Banner tone="info">{notice}</Banner> : null}

          {reachable ? (
            <p className="break-keep text-sm text-muted" data-testid="vendor-reviews-route">
              서버의 판매자 계정 <span className="font-semibold text-ink">{overview.accountEmail ?? "-"}</span>
              {" → "}이 PC의 작업 계정 <span className="font-semibold text-ink">{overview.organisation ?? "-"}</span>
            </p>
          ) : null}

          {reachable && overview.stores.length === 0 ? (
            <p className="border-t border-line/70 py-2 text-sm text-ink">
              이 계정에 아직 스토어가 없습니다. 스토어는 reviewnary 운영자가 만들어 드립니다.
            </p>
          ) : null}

          <ul>
            {overview.stores.map((store) => (
              <StoreRow
                key={store.storeId}
                store={store}
                busy={busy}
                onPause={(paused) => void run(() => api.setVendorStorePaused(store.storeId, paused))}
              />
            ))}
          </ul>

          {reachable && unstarted.length > 0 ? (
            <p className="break-keep text-xs text-muted" data-testid="vendor-reviews-start-note">
              「리뷰 받기 시작」을 누르면 위의 「아직 받지 않음」 스토어 {unstarted.length}곳(
              {unstarted.map((s) => s.label).join(", ")})의 리뷰를 「{overview.organisation ?? "-"}」 작업 계정으로 받습니다. 시작한
              스토어는 이 PC가 켜져 있는 동안 5분마다 자동으로 받습니다. 나중에 생기는 스토어는 여기에 따로 보이고, 시작하기 전에는 받지
              않습니다.
            </p>
          ) : null}
          {started.length > 0 ? (
            <p className="break-keep text-xs text-muted">
              받은 리뷰는{" "}
              <Link
                to="/reviews"
                className="rounded font-semibold text-brand-700 underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                리뷰
              </Link>{" "}
              화면에 다른 채널 리뷰와 함께 보입니다. 이 PC가 꺼져 있던 동안 올라온 리뷰는 다음에 켜질 때 받습니다.
            </p>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

function StoreRow({
  store,
  busy,
  onPause,
}: {
  store: VendorReviewStoreView;
  busy: boolean;
  onPause: (paused: boolean) => void;
}) {
  const ours = store.standing === "RECEIVING" || store.standing === "PAUSED";
  const facts: string[] = [];
  if (ours) facts.push(`서버 확인 ${store.lastCheckedAt ? relativeTime(store.lastCheckedAt) : "아직 없음"}`);
  if (store.standing !== "OTHER_ORGANISATION") {
    facts.push(`담당자 마지막 업로드 ${store.lastUploadAt ? relativeTime(store.lastUploadAt) : "아직 없음"}`);
  }
  if (ours) facts.push(`받은 리뷰 ${store.received.toLocaleString("ko-KR")}건`);

  return (
    <li className="flex flex-wrap items-center justify-between gap-2 border-t border-line/70 py-2 text-sm" data-testid="vendor-store">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="break-keep font-medium text-ink">{store.label}</span>
          <StandingChip store={store} />
        </div>
        {facts.length > 0 ? (
          <p className="mt-0.5 break-keep text-xs text-muted" data-testid="vendor-store-facts">
            {facts.join(" · ")}
          </p>
        ) : null}
        {ours && store.failure ? (
          <p className="mt-0.5 break-keep text-xs text-warn" data-testid="vendor-store-failure">
            {FAILURE[store.failure]}
            {store.failedAt ? ` (${relativeTime(store.failedAt)})` : ""}
          </p>
        ) : null}
      </div>
      {store.standing === "RECEIVING" ? (
        <Btn size="sm" variant="ghost" disabled={busy} onClick={() => onPause(true)}>
          받기 멈추기
        </Btn>
      ) : null}
      {store.standing === "PAUSED" ? (
        <Btn size="sm" variant="ghost" disabled={busy} onClick={() => onPause(false)}>
          다시 받기
        </Btn>
      ) : null}
    </li>
  );
}

function StandingChip({ store }: { store: VendorReviewStoreView }) {
  if (store.standing === "OTHER_ORGANISATION") return <Chip>이 PC의 다른 계정이 받는 중</Chip>;
  if (store.standing === "NOT_RECEIVING") return <Chip>아직 받지 않음</Chip>;
  if (store.standing === "PAUSED") return <Chip>받기 멈춤</Chip>;
  if (store.failure) return <Chip tone="attention">받지 못함</Chip>;
  return <Chip tone="accent">받는 중</Chip>;
}

function Banner({ tone, children }: { tone: "info" | "bad"; children: ReactNode }) {
  return (
    <div
      role={tone === "bad" ? "alert" : "status"}
      className={`rounded-xl px-4 py-3 text-sm ${tone === "bad" ? "bg-bad/10 text-bad" : "bg-brand-50 text-brand-700"}`}
    >
      {children}
    </div>
  );
}

/** One line for what a pull brought, and which stores it could not finish. */
function pulledLine(result: VendorReviewPullResult): string {
  if (result.state === "UNREACHABLE") return OFFLINE;
  if (result.state === "NOT_AUTHORIZED") return REVOKED;
  const ran = result.stores.filter((s) => s.bound && !s.otherOrganisation && !s.paused);
  const inserted = ran.reduce((n, s) => n + s.inserted, 0);
  const updated = ran.reduce((n, s) => n + s.updated, 0);
  const failedStores = ran.filter((s) => s.failed > 0).map((s) => s.label);
  const line = `새 리뷰 ${inserted.toLocaleString("ko-KR")}건 · 바뀐 리뷰 ${updated.toLocaleString("ko-KR")}건을 받았습니다.`;
  return failedStores.length > 0 ? `${line} ${failedStores.join(", ")}은(는) 일부를 받지 못했습니다.` : line;
}
