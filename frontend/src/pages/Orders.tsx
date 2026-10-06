import { Link, useSearchParams } from "react-router-dom";
import type { ReactNode } from "react";
import { PageHead } from "../components/ui/PageHead";
import { SectionHeader } from "../components/ui/SectionHeader";
import { AgentLaunch } from "../components/ui/AgentLaunch";
import { OrderStatusText } from "../components/order/OrderStatusText";
import { dataStateLabel, hasObservations } from "../components/ui/DataState";
import { useApiData } from "../lib/useApiData";
import { api } from "../lib/apiClient";
import { count, won, kstDayTime, kstMonthDay, kstShortDate, kstShortDateTime, kstToday } from "../lib/format";
import { lastRead, wasRead } from "../lib/orderRecords";
import { useAgentSurface } from "../lib/agentPanel";
import type { ChannelDataState, ChannelResponse, OrderReadState, OrderRecordRow } from "../lib/types";

const PRESETS = [7, 14, 30] as const;
type Preset = (typeof PRESETS)[number];
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

function parsePreset(raw: string | null): Preset {
  const n = Number(raw);
  return (PRESETS as readonly number[]).includes(n) ? (n as Preset) : 30;
}

/** 한 열에 여럿이 서는 자리의 폭 — 목록이 표로 읽히려면 열이 자기 자리를 지켜야 한다. */
const COL = {
  id: "min-w-0 flex-1 truncate pr-4 tabular-nums",
  channel: "w-[170px] shrink-0 truncate pr-4",
  lines: "w-[72px] shrink-0 pr-4 text-right tabular-nums",
  amount: "w-[130px] shrink-0 pr-4 text-right tabular-nums",
  status: "w-[210px] shrink-0 truncate pr-4",
  paid: "w-[130px] shrink-0 pr-4 text-right tabular-nums",
  seen: "w-[100px] shrink-0 text-right tabular-nums",
};
const READ_COL = {
  channel: "min-w-0 flex-1 truncate pr-4",
  state: "w-[150px] shrink-0 truncate pr-4",
  lines: "w-[150px] shrink-0 pr-4 text-right tabular-nums",
  seen: "w-[170px] shrink-0 text-right tabular-nums",
};

/**
 * 주문 — <b>결제 단위 기록</b>의 목록 (docs/product_assembly_ia_v1.md §4d).
 *
 * <b>이 화면은 집계 대시보드가 아니라 기록이다.</b> 전에는 기간 KPI 넷과 추이 차트가 화면의 전부였고,
 * 주문 한 건은 어디에도 없었다. 그 화면에는 두 가지 결함이 있었다: (1) 수집이 9월 5일에 멈춘 채로
 * 「최근 7일」을 물으면 「주문 0건 · 매출 0원」이 그려졌다 — 읽지 못한 것과 없었던 것이 같은 모양이었다;
 * (2) `?days=30&date=…`가 함께 오면 KPI는 하루를, 차트는 30일을 말하면서 같은 화면에 섰다.
 *
 * <b>그래서 순서가 뒤집혔다.</b> 무엇을 어디까지 읽었는지가 숫자 위에 서고(읽은 범위), 그 아래가
 * 레코드이며, 기간 매출은 맨 아래에 한 단계 약하게 남는다. 숫자의 출처도 하나가 아니다 — 레코드와
 * 머리 숫자는 `GET /api/orders`(결제 단위)에서, 기간 매출은 예전부터 있던 `/api/orders/summary`
 * (일자 집계, 카페24 포함)에서 온다. 두 수가 다른 것은 결함이 아니라 서로 다른 것을 세기 때문이고,
 * 화면이 그렇게 적는다.
 *
 * <b>정렬은 서버의 것이다.</b> 행은 결제 시각 최신순으로 이미 서 있고, 화면은 다시 줄 세우지 않는다.
 * 응답이 잘렸는지도 추론하지 않는다 — `hasMore`가 말한다.
 */
export function Orders() {
  const [searchParams, setSearchParams] = useSearchParams();
  const range = parsePreset(searchParams.get("days"));
  const rawDate = searchParams.get("date");
  const date = rawDate && ISO_DAY.test(rawDate) ? rawDate : null;

  useAgentSurface({ surface: "orders", label: "주문" });

  const records = useApiData(() => api.getOrderRecordsStrict());
  const { data: channels } = useApiData(() => api.getChannelsStrict());

  const extent = records.data?.extent ?? null;
  const reads = records.data?.reads ?? [];
  const read = lastRead(reads);
  const nameOf = (code: string | null) =>
    (channels ?? []).find((c: ChannelResponse) => c.code === code)?.nameKo ?? code ?? "채널을 확인할 수 없음";

  return (
    <div className="space-y-5">
      <PageHead
        title="주문"
        meta={<span className="text-sm text-muted">주문 처리는 각 판매자센터에서 합니다</span>}
        action={<AgentLaunch context={{ surface: "orders" }} label="주문에 대해 물어보기" />}
      />

      {records.loading ? (
        <p className="text-sm text-muted">불러오는 중…</p>
      ) : records.error || !records.data || !extent ? (
        <div className="rounded-xl bg-bad/10 px-4 py-3 text-sm text-bad">
          주문 기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </div>
      ) : (
        <>
          {/* 머리 숫자 — 결제 몇 번과 상품주문 몇 줄은 다른 수이고, 둘 다 적는다. 기간이 없으면 숫자도
              읽을 수 없으므로 기간이 같은 줄에 선다. */}
          <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1 text-base" data-testid="orders-extent">
            <Figure label="주문" value={count(extent.paymentUnitCount)} unit="건" />
            <Figure label="상품주문" value={count(extent.orderLineCount)} unit="줄" />
            <Figure label="합계" value={won(extent.totalAmount)} />
            <span className="text-sm text-muted">
              {extent.periodFrom && extent.periodTo
                ? `${kstMonthDay(extent.periodFrom)} – ${kstMonthDay(extent.periodTo)}`
                : "읽어 둔 주문이 없습니다"}
            </span>
            <Figure label="문의가 가리킨 주문" value={count(extent.linkedInquiryCount)} muted />
          </div>

          <Block
            title="읽은 범위"
            note={
              read && read.daysAgo >= 1 ? (
                <span className="font-medium text-warn">
                  {kstDayTime(read.at)} 이후로 새로 읽은 주문이 없습니다 — 오늘까지 {read.daysAgo}일.
                  아래 숫자는 그때까지 읽은 것입니다.
                </span>
              ) : (
                <span>한 건씩 읽은 주문만 아래 목록에 섭니다.</span>
              )
            }
          >
            <div aria-hidden="true" className="flex items-center pb-1 text-xs text-muted">
              <span className={READ_COL.channel}>채널</span>
              <span className={READ_COL.state}>읽기 상태</span>
              <span className={READ_COL.lines}>상품주문</span>
              <span className={READ_COL.seen}>마지막 수집</span>
            </div>
            {reads.length === 0 ? (
              <p className="border-t border-line/70 py-3 text-sm text-muted">연결된 채널이 없습니다.</p>
            ) : (
              <ul data-testid="orders-reads">
                {reads.map((row) => (
                  <ReadRow key={row.accountId} row={row} name={nameOf(row.channelCode)} />
                ))}
              </ul>
            )}
          </Block>

          <Block title="주문" note="결제 한 건이 한 줄입니다. 상태는 채널이 보낸 값이고, 뜻을 확인한 코드만 우리 말로 옮깁니다." right="결제 시각 최신순">
            <div aria-hidden="true" className="flex items-center pb-1 text-xs text-muted">
              <span className={COL.id}>주문 번호</span>
              <span className={COL.channel}>채널</span>
              <span className={COL.lines}>상품주문</span>
              <span className={COL.amount}>결제 금액</span>
              <span className={COL.status}>채널 상태</span>
              <span className={COL.paid}>결제 시각</span>
              <span className={COL.seen}>마지막 확인</span>
            </div>
            {records.data.rows.length === 0 ? (
              <p className="border-t border-line/70 py-3 text-sm text-muted">
                {reads.some((r) => wasRead(r.state))
                  ? "읽은 범위 안에 주문이 없습니다."
                  : "아직 읽은 주문이 없습니다. 위의 읽기 상태를 확인해 주세요."}
              </p>
            ) : (
              <ul data-testid="orders-records">
                {records.data.rows.map((row) => (
                  <RecordRow
                    key={`${row.channelCode}:${row.accountId}:${row.parentOrderId}`}
                    row={row}
                    name={nameOf(row.channelCode)}
                  />
                ))}
              </ul>
            )}
            {/* 잘렸다는 사실은 서버가 말한다 — 두 숫자를 비교해 알아내게 두지 않는다. */}
            {records.data.hasMore ? (
              <p className="border-t border-line py-2.5 text-sm text-muted" data-testid="orders-has-more">
                주문 <b className="font-semibold tabular-nums text-ink">{count(extent.paymentUnitCount)}</b>건 가운데
                결제 시각이 가장 최근인 <b className="font-semibold tabular-nums text-ink">{count(records.data.rows.length)}</b>건입니다.
                나머지는 아직 이 화면에서 열 수 없습니다.
              </p>
            ) : null}
          </Block>

          <Sales
            range={range}
            date={date}
            periodFrom={extent.periodFrom}
            periodTo={extent.periodTo}
            onRange={(days) => write(setSearchParams, { days, date: null })}
            onClearDate={() => write(setSearchParams, { date: null })}
          />
        </>
      )}
    </div>
  );
}

function write(
  setSearchParams: ReturnType<typeof useSearchParams>[1],
  patch: { days?: number; date?: string | null },
) {
  setSearchParams(
    (prev) => {
      const params = new URLSearchParams(prev);
      if (patch.days !== undefined) {
        params.set("days", String(patch.days));
      }
      if (patch.date !== undefined) {
        if (patch.date) {
          params.set("date", patch.date);
        } else {
          params.delete("date");
        }
      }
      return params;
    },
    { replace: true },
  );
}

/**
 * 한 구역 — 제목과 그 아래 한 줄의 hairline.
 *
 * <p><b>상자가 아니고, 이 제품의 다른 화면과 같은 렌더러를 쓴다</b>({@link SectionHeader}). 전에는 두
 * 표가 각각 둥근 테두리 상자 안에 들어 있어서 한 화면에 떠 있는 물건이 셋이었고(머리 숫자 · 읽은 범위
 * 상자 · 주문 상자), 같은 레코드를 여는 상세 화면은 구분선만 쓰고 있었다 — 같은 제품의 두 화면이 다른
 * 문법으로 읽혔다. 구역의 이름과 그 아래 선 하나가 상품 상세·홈이 쓰는 그 문법이다.
 */
function Block({
  title,
  note,
  right,
  children,
}: {
  title: string;
  note?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className="space-y-2">
      <SectionHeader
        title={
          <span className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            {title}
            {note ? <span className="min-w-0 break-keep text-sm font-normal text-muted">{note}</span> : null}
          </span>
        }
        action={right ? <span className="text-xs text-muted">{right}</span> : undefined}
      />
      {children}
    </section>
  );
}

function Figure({ label, value, unit, muted = false }: { label: string; value: string; unit?: string; muted?: boolean }) {
  return (
    <span className="text-muted">
      {label}{" "}
      <b className={`font-bold tabular-nums ${muted ? "text-muted" : "text-ink"}`}>{value}</b>
      {unit ? <span className="ml-0.5 text-sm">{unit}</span> : null}
    </span>
  );
}

/**
 * 한 연결의 읽기 상태.
 *
 * <b>0은 두 가지다.</b> 읽었고 없었던 0과 읽지 못한 0은 다른 사실이고, 그 구분은 서버의 판정이 가진다
 * (`ChannelDataState`). 읽지 못한 연결의 줄 수 자리에는 숫자가 아니라 「—」가 선다 — 0을 적으면 그것은
 * 「없었다」는 말이 된다.
 */
function ReadRow({ row, name }: { row: OrderReadState; name: string }) {
  const observed = hasObservations(row.state as ChannelDataState);
  return (
    <li className="flex items-center border-t border-line/70 py-2 text-sm">
      <span className={`${READ_COL.channel} font-medium text-ink`}>{name}</span>
      <span className={`${READ_COL.state} ${observed || row.state === "ZERO" ? "text-muted" : "text-warn"}`}>
        {dataStateLabel(row.state as ChannelDataState) ?? row.state}
      </span>
      <span className={`${READ_COL.lines} text-muted`}>{heldLabel(row)}</span>
      <span className={`${READ_COL.seen} text-muted`}>{row.lastSeenAt ? kstDayTime(row.lastSeenAt) : "—"}</span>
    </li>
  );
}

/**
 * 보유한 상품주문 줄 수 — 그리고 <b>0이 두 가지라는 것</b>.
 *
 * <ul>
 *   <li>읽었고 없었다({@code ZERO}) → 「0줄」. 측정된 0이다.</li>
 *   <li>읽긴 했지만 한 건씩 들고 있는 것이 없다 → 「없음」. 카페24가 그렇다 — 일자 집계만 들어와 있어서
 *       이 목록에는 한 줄도 서지 않는다. 여기에 「0줄」을 적으면 그 채널의 <b>주문이 0건</b>이라는 말로
 *       읽히는데, 그것은 이 읽기가 증명한 적 없는 사실이다.</li>
 *   <li>읽지 못했다 → 「—」. 숫자를 적을 자리가 아니다.</li>
 * </ul>
 */
function heldLabel(row: OrderReadState): string {
  if (!wasRead(row.state)) {
    return "—";
  }
  if (row.orderLineCount === 0) {
    return row.state === "ZERO" ? "0줄" : "없음";
  }
  return `${count(row.orderLineCount)}줄`;
}

/** 한 결제 단위 — 주소는 네 조각 전부다(§4d). 번호만으로 가는 길은 이 화면에 없다. */
function RecordRow({ row, name }: { row: OrderRecordRow; name: string }) {
  return (
    <li>
      <Link
        to={`/orders/${encodeURIComponent(row.channelCode)}/${row.accountId}/${encodeURIComponent(row.parentOrderId)}`}
        className="-mx-2 flex items-center rounded border-t border-line/70 px-2 py-2 text-sm transition hover:bg-canvas/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
      >
        <span className={`${COL.id} font-medium text-ink`}>{row.parentOrderId}</span>
        <span className={`${COL.channel} text-muted`}>{name}</span>
        {/* 1도 적는다. 빈 칸은 「읽지 못했다」로 읽히고, 이 열의 1은 측정된 1이다 — 열세 줄짜리를 눈에
            띄게 하는 일은 색이 아니라 숫자 자체가 한다. */}
        <span className={`${COL.lines} text-muted`}>{count(row.lineCount)}줄</span>
        <span className={`${COL.amount} text-ink`}>{won(row.totalAmount)}</span>
        <span className={COL.status}>
          <OrderStatusText raw={row.rawStatusCode} labelKo={row.confirmedStatusLabelKo} varies={row.statusVaries} />
        </span>
        <span className={`${COL.paid} text-muted`}>{row.paidAt ? kstShortDateTime(row.paidAt) : "—"}</span>
        <span className={`${COL.seen} text-muted`}>{row.lastSeenAt ? kstShortDate(row.lastSeenAt) : "—"}</span>
      </Link>
    </li>
  );
}

/**
 * 매출 — 기록 아래, 한 단계 약하게.
 *
 * <b>이 숫자는 위의 목록과 다른 것을 센다.</b> 일자 집계(`/api/orders/summary`)이고 카페24처럼 한 건씩
 * 읽지 않는 채널까지 포함한다. 그래서 상자도 차트도 없이 문장과 한 줄짜리 목록으로 남고, 자기가 무엇을
 * 센 것인지 말한다.
 *
 * <b>고른 기간이 비어 있으면 0을 그리지 않는다.</b> 「최근 7일 0건」은 수집이 멈춘 날 가장 쉽게 쓰이는
 * 거짓말이다. 비어 있을 때는 그렇게 말하고, 읽어 둔 기간 전체로 한 번 더 물어 그 기간의 매출을 적는다.
 */
function Sales({
  range,
  date,
  periodFrom,
  periodTo,
  onRange,
  onClearDate,
}: {
  range: Preset;
  date: string | null;
  periodFrom: string | null;
  periodTo: string | null;
  onRange: (days: Preset) => void;
  onClearDate: () => void;
}) {
  const today = kstToday();
  const from = date ?? shiftDays(today, -(range - 1));
  const to = date ?? today;
  const window = useApiData(() => api.getOrdersSummaryStrict({ from, to }), [from, to]);
  const empty = !!window.data && window.data.totalOrders7d === 0;
  // 고른 기간이 비었을 때만 읽어 둔 기간을 한 번 더 묻는다 — 빈 화면 대신 사실을 적기 위해서다.
  const held = useApiData(
    () =>
      empty && periodFrom && periodTo
        ? api.getOrdersSummaryStrict({ from: periodFrom, to: periodTo })
        : Promise.resolve(null),
    [empty, periodFrom, periodTo],
  );
  const shown = empty ? held.data : window.data;
  const scope = date
    ? kstMonthDay(date)
    : `최근 ${range}일 (${kstMonthDay(from)} – ${kstMonthDay(to)})`;

  return (
    <section aria-label="매출" className="space-y-2 border-t border-line pt-4">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-2">
          {/* 기록보다 한 단계 약하게 — 같은 굵기의 제목이 셋이면 어느 것이 이 화면의 주제인지 사라진다. */}
          <h2 className="break-keep text-base font-semibold text-muted">매출</h2>
          <span className="break-keep text-sm text-muted">
            일자 집계입니다 — 카페24처럼 한 건씩 읽지 않는 채널까지 포함합니다.
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {date ? (
            <button
              type="button"
              onClick={onClearDate}
              className="inline-flex min-h-[32px] items-center gap-1.5 rounded-lg border border-line px-2.5 text-sm text-muted transition hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              data-testid="orders-day-chip"
            >
              {kstMonthDay(date)}
              <span aria-hidden="true">×</span>
            </button>
          ) : null}
          <div className="flex gap-0.5 rounded-lg bg-canvas p-0.5" role="group" aria-label="기간">
            {PRESETS.map((p) => (
              <button
                key={p}
                type="button"
                onClick={() => onRange(p)}
                aria-pressed={range === p && !date}
                className={`min-h-[32px] rounded-md px-2.5 text-sm font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
                  range === p && !date ? "bg-surface text-ink shadow-sm" : "text-muted hover:text-ink"
                }`}
              >
                최근 {p}일
              </button>
            ))}
          </div>
        </div>
      </div>

      {window.loading ? (
        <p className="text-sm text-muted">불러오는 중…</p>
      ) : window.error ? (
        <p className="text-sm text-warn">매출 집계를 불러오지 못했습니다.</p>
      ) : (
        <>
          <p className="break-keep text-sm leading-7 text-muted" data-testid="orders-sales-line">
            {empty ? (
              <>
                {scope}에 집계된 주문이 없습니다.
                {periodTo ? ` 마지막으로 집계된 날은 ${kstMonthDay(periodTo)}입니다.` : ""}
              </>
            ) : (
              <>
                {scope} 주문 <b className="font-semibold tabular-nums text-ink">{count(window.data?.totalOrders7d ?? 0)}</b>건 ·{" "}
                <b className="font-semibold tabular-nums text-ink">{won(window.data?.totalSales7d ?? 0)}</b>
              </>
            )}
          </p>
          {shown && shown.channelShare.length > 0 ? (
            <>
              <div className="border-b border-line/70 pb-1.5 text-xs text-muted">
                채널별 매출 ·{" "}
                {empty && periodFrom && periodTo ? `${kstMonthDay(periodFrom)} – ${kstMonthDay(periodTo)}` : scope} · 합계{" "}
                <b className="font-semibold tabular-nums text-ink">{won(shown.totalSales7d)}</b>
              </div>
              <ul data-testid="orders-channel-share">
                {shown.channelShare.map((share) => (
                  <li key={share.channelNameKo} className="flex items-center gap-4 border-t border-line/70 py-1.5 text-sm first:border-t-0">
                    <span className="min-w-0 flex-1 truncate text-ink">{share.channelNameKo}</span>
                    <span className="w-[140px] shrink-0 text-right tabular-nums text-muted">{won(share.salesAmount)}</span>
                    <span className="hidden h-1.5 w-[260px] shrink-0 overflow-hidden rounded-full bg-line sm:block">
                      <span className="block h-1.5 rounded-full bg-muted/60" style={{ width: `${share.percent}%` }} />
                    </span>
                    <span className="w-[48px] shrink-0 text-right tabular-nums text-muted">{share.percent}%</span>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </>
      )}
    </section>
  );
}

/** `YYYY-MM-DD` 에서 날짜를 민다 — 서울의 달력 위에서, 브라우저의 시간대와 무관하게. */
function shiftDays(day: string, delta: number): string {
  const ms = Date.parse(`${day}T00:00:00Z`) + delta * 86_400_000;
  return new Date(ms).toISOString().slice(0, 10);
}
