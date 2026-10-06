import type { ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { Empty } from "../components/ui/Empty";
import { SectionHeader } from "../components/ui/SectionHeader";
import { BtnLink } from "../components/ui/Btn";
import { AgentLaunch } from "../components/ui/AgentLaunch";
import { OrderAxis, OrderStatusText, RawCode, UNCONFIRMED } from "../components/order/OrderStatusText";
import { useApiData } from "../lib/useApiData";
import { api } from "../lib/apiClient";
import { count, won, kstDate, kstDateTime } from "../lib/format";
import { linesWithInquiry, sharedAcrossLines } from "../lib/orderRecords";
import { useAgentSurface } from "../lib/agentPanel";
import type { ChannelResponse, OrderLinkedInquiry, OrderRecordDetail, OrderStatusEvent } from "../lib/types";

/**
 * 주문 상세 — 결제 단위 하나 (canonical visual reference v1, docs/images/orders).
 *
 * <b>레코드 한 장이지 대시보드가 아니다.</b> 주인공은 주문번호이고, 그 아래로 이 저장소가 실제로 들고
 * 있는 것만 차례로 선다: 묶인 문의 · 상품주문 줄 · 상태 이력. 상자도 카드도 없다 — 구분은 구분선과
 * 글자 크기가 한다.
 *
 * <b>갖고 있지 않은 것을 칸으로 만들지 않는다</b>(§4d). 구매자·배송지·받는 사람·송장·수량·옵션·할인·
 * 세금·배송비는 읽지 않으므로 빈 열이 아니라 맨 아래 한 줄의 사실로 적는다. 빈 열은 「그 값이 없다」로
 * 읽히고, 사실은 「우리가 읽지 않는다」이다.
 *
 * <b>주문 처리(WRITE)는 없다.</b> 상태 변경·취소·발송·송장 입력은 각 판매자센터의 일이고, 이 화면의
 * 액션은 읽기와 질문뿐이다.
 */
export function OrderRecord() {
  const { channelCode = "", accountId = "", parentOrderId = "" } = useParams();
  const { data, loading, error } = useApiData<OrderRecordDetail>(
    () => api.getOrderRecordStrict(channelCode, accountId, parentOrderId),
    [channelCode, accountId, parentOrderId],
  );
  const { data: channels } = useApiData(() => api.getChannelsStrict());
  // 패널이 무엇을 보고 있는지 — 주문번호는 말하지 않는다. 라벨은 화면 이름이면 충분하다.
  useAgentSurface({ surface: "orders", label: "이 주문" });

  if (loading) {
    return <p className="text-muted">불러오는 중…</p>;
  }
  if (error || !data) {
    return (
      <Empty
        title="주문을 찾지 못했습니다"
        body="이 주소가 가리키는 주문이 없거나, 이 계정의 주문이 아닙니다."
        action={<BtnLink to="/orders">주문 기록으로</BtnLink>}
      />
    );
  }

  const channelName =
    (channels ?? []).find((c: ChannelResponse) => c.code === data.channelCode)?.nameKo ?? data.channelCode;
  const shared = sharedAcrossLines(data.lines);
  const marked = linesWithInquiry(data);
  const elapsed = data.lastSeenAt ? daysSince(data.lastSeenAt) : null;

  return (
    <div className="max-w-[930px] space-y-7">
      <div>
        <Link
          to="/orders"
          className="rounded text-sm font-medium text-brand hover:text-brand-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          ← 주문 기록으로
        </Link>
      </div>

      <header className="space-y-2">
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
          <h1 className="break-all text-title font-bold tabular-nums tracking-tight text-ink">{data.parentOrderId}</h1>
          <AgentLaunch
            context={{ surface: "orders" }}
            label="이 주문에 대해 물어보기"
          />
        </div>
        <p className="break-keep text-base tabular-nums text-muted">
          {channelName}
          {data.paidAt ? ` · ${kstDateTime(data.paidAt)} 결제` : ""} · 상품주문 {count(data.lineCount)}줄 ·{" "}
          {won(data.totalAmount)}
        </p>
        {/* 세 축은 따로 선다 — 하나가 다른 하나를 증명하지 않는다. 확인한 코드는 그 축 옆에 함께 적고,
            확인하지 않은 코드는 어느 축도 증명하지 못하므로 아래 상품주문 머리에 raw로 남는다. */}
        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1 text-base" data-testid="order-axes">
          <span className="text-muted">
            결제
            {data.paymentLabelKo ? (
              <span className="ml-1.5 inline-flex items-baseline gap-1.5">
                <b className="font-semibold text-ink">{data.paymentLabelKo}</b>
                {data.rawStatusCode ? <RawCode code={data.rawStatusCode} /> : null}
              </span>
            ) : (
              <span className="ml-1.5 font-medium text-muted">{UNCONFIRMED}</span>
            )}
          </span>
          <OrderAxis name="취소" labelKo={data.cancellationLabelKo} />
          <OrderAxis name="배송" labelKo={data.fulfillmentLabelKo} />
        </div>
        {/* 마지막으로 본 시점은 머리에 선다(§4d). 색은 서버의 판정이 정한다 — 지금 상태로 말해도 되는
            읽기(OBSERVED_FRESH)만 조용하고, 나머지는 눈에 띈다. 경과 일수를 화면이 혼자 판단해 색을
            고르면 「며칠부터 오래된 것인가」가 화면마다 달라진다. */}
        {data.lastSeenAt ? (
          <p
            className={`text-sm font-medium tabular-nums ${data.readState === "OBSERVED_FRESH" ? "text-muted" : "text-warn"}`}
            data-testid="order-last-seen"
          >
            마지막 확인 {kstDate(data.lastSeenAt)}
            {elapsed !== null ? ` · ${elapsed === 0 ? "오늘" : `${elapsed}일 전`}` : ""}
          </p>
        ) : null}
        <p className="break-keep text-sm text-muted">{limitSentence(data, channelName)}</p>
      </header>

      <Block
        title={`문의 ${count(data.inquiries.length)}`}
        right={
          data.inquiries.length === 1 ? (
            <Link
              to={`/inquiries/${data.inquiries[0].inquiryId}`}
              className="rounded text-xs text-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              문의에서 열기 ›
            </Link>
          ) : null
        }
      >
        {data.inquiries.length === 0 ? (
          <p className="text-sm text-muted">이 주문을 가리킨 문의가 없습니다</p>
        ) : (
          <ul className="space-y-4">
            {data.inquiries.map((inquiry) => (
              <InquiryRow key={inquiry.inquiryId} inquiry={inquiry} channelName={channelName} />
            ))}
          </ul>
        )}
      </Block>

      <Block title={`상품주문 ${count(data.lineCount)}`} right="상품주문 번호순">
        {/* 줄마다 같은 값은 줄의 속성이 아니다 — 열에서 빼고 머리로 올린다. */}
        <p className="pb-2 text-sm text-muted">
          {shared.rawStatusCode ? (
            <>
              {data.lines.length > 1 ? `${count(data.lines.length)}줄 모두 ` : "채널 상태 "}
              <OrderStatusText raw={shared.rawStatusCode} labelKo={shared.confirmedStatusLabelKo} />
            </>
          ) : (
            <span className="text-muted">줄마다 채널 상태가 다릅니다</span>
          )}
          {shared.paidAt ? ` · ${kstDateTime(shared.paidAt)} 결제` : null}
        </p>
        <div aria-hidden="true" className="flex items-center pb-1 text-xs text-muted">
          <span className="min-w-0 flex-1">상품주문 번호</span>
          <span className="w-[120px] shrink-0 text-right">결제 금액</span>
        </div>
        <ul data-testid="order-lines">
          {data.lines.map((line) => (
            <li
              key={line.externalOrderId}
              className={`flex items-center border-t border-line/70 py-1.5 text-sm tabular-nums ${
                marked.has(line.externalOrderId) ? "bg-canvas/40" : ""
              }`}
            >
              <span className="flex min-w-0 flex-1 items-center gap-2 truncate text-ink">
                {line.externalOrderId}
                {marked.has(line.externalOrderId) ? (
                  <span className="shrink-0 rounded-md bg-warn/10 px-1.5 py-0.5 text-xs font-semibold text-warn">문의</span>
                ) : null}
                {/* 줄마다 코드가 다를 때만 줄에 상태가 선다 — 같을 때는 머리에 이미 있다. */}
                {shared.rawStatusCode ? null : (
                  <span className="shrink-0">
                    <OrderStatusText raw={line.rawStatusCode} labelKo={line.confirmedStatusLabelKo} />
                  </span>
                )}
              </span>
              <span className="w-[120px] shrink-0 text-right text-ink">{won(line.paymentAmount)}</span>
            </li>
          ))}
        </ul>
        {/* 줄이 하나면 합계는 그 줄을 한 번 더 적는 것이다. */}
        {data.lines.length > 1 ? (
          <div className="flex items-center border-t border-line py-1.5 text-sm" data-testid="order-total">
            <span className="min-w-0 flex-1 text-muted">합계</span>
            <span className="w-[120px] shrink-0 text-right font-semibold tabular-nums text-ink">{won(data.totalAmount)}</span>
          </div>
        ) : null}
      </Block>

      <Block title={`상태 이력 ${count(data.statusHistory.length)}`} right="오래된 순">
        {data.statusHistory.length === 0 ? (
          <p className="text-sm text-muted">기록된 상태 변경이 없습니다.</p>
        ) : (
          <>
            <div aria-hidden="true" className="flex items-center pb-1 text-xs text-muted">
              <span className="w-[230px] shrink-0">상태</span>
              <span className="min-w-0 flex-1">이전</span>
              <span className="w-[170px] shrink-0 text-right">채널이 말한 시각</span>
              <span className="w-[170px] shrink-0 text-right">기록한 시각</span>
            </div>
            <ul data-testid="order-history">
              {data.statusHistory.map((event, index) => (
                <EventRow key={`${event.toStatusCode}:${event.recordedAt}:${index}`} event={event} unitLines={data.lineCount} />
              ))}
            </ul>
          </>
        )}
      </Block>

      <p className="border-t border-line pt-3 text-sm text-muted">
        읽지 않는 것 <span className="text-muted">구매자 · 배송지 · 받는 사람 · 송장 · 수량 · 옵션 · 상품 연결 · 할인 · 세금 · 배송비</span>
      </p>
    </div>
  );
}

/**
 * 한 구역 — 제목과 그 아래 한 줄의 hairline. 카드가 아니다.
 *
 * <p>목록·상품 상세와 <b>같은 렌더러</b>({@link SectionHeader})를 쓴다. 이 화면만 제 섹션 문법을 들고
 * 있으면 같은 제품의 화면 셋이 세 가지 랭크로 읽힌다 — 2026-10-06 visual QA에서 셋을 하나로 모았다.
 */
function Block({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-2">
      <SectionHeader title={title} action={right ? <span className="text-xs text-muted">{right}</span> : undefined} />
      {children}
    </section>
  );
}

function InquiryRow({ inquiry, channelName }: { inquiry: OrderLinkedInquiry; channelName: string }) {
  return (
    <li className="space-y-1.5">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="w-[150px] shrink-0 truncate text-sm text-muted">{inquiry.channelCode ? channelName : "채널을 확인할 수 없음"}</span>
        <Link
          to={`/inquiries/${inquiry.inquiryId}`}
          className="rounded text-base font-semibold text-ink underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          {inquiry.title ?? "제목 없음"}
        </Link>
        {inquiry.status === "UNANSWERED" ? (
          <span className="rounded-md bg-warn/10 px-1.5 py-0.5 text-xs font-semibold text-warn">미답변</span>
        ) : null}
        <span className="ml-auto shrink-0 text-sm tabular-nums text-muted">{inquiry.receivedAt ? kstDate(inquiry.receivedAt) : "—"}</span>
      </div>
      {inquiry.body ? (
        <p className="whitespace-pre-line break-keep pl-0 text-sm leading-relaxed text-muted sm:pl-[162px]">{inquiry.body}</p>
      ) : null}
      <p className="text-sm tabular-nums text-muted sm:pl-[162px]">
        {/* 채널이 적어 보낸 번호 그대로 — 어느 줄을 가리켰는지는 그 번호가 말한다. */}
        상품주문 {inquiry.sourceOrderRef}
        {inquiry.productName ? (
          <>
            {" · 상품 "}
            {inquiry.productId ? (
              <Link
                to={`/products/${inquiry.productId}`}
                className="rounded text-brand underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
              >
                {inquiry.productName}
              </Link>
            ) : (
              inquiry.productName
            )}
          </>
        ) : null}
      </p>
    </li>
  );
}

function EventRow({ event, unitLines }: { event: OrderStatusEvent; unitLines: number }) {
  return (
    <li className="flex items-center border-t border-line/70 py-1.5 text-sm">
      <span className="flex w-[230px] shrink-0 items-baseline gap-2 truncate">
        <OrderStatusText raw={event.toStatusCode} labelKo={event.toLabelKo} />
        {/* 결제 단위의 일부만 움직였을 때만 적는다 — 전부일 때는 말할 것이 없다. */}
        {event.lineCount < unitLines ? (
          <span className="shrink-0 text-xs tabular-nums text-muted">{count(event.lineCount)}줄</span>
        ) : null}
      </span>
      <span className="min-w-0 flex-1 truncate text-muted">
        {event.fromStatusCode ? <OrderStatusText raw={event.fromStatusCode} labelKo={event.fromLabelKo} /> : "없음"}
      </span>
      {/* 채널이 변경 시각을 주지 않으면 「—」다 — 기록한 시각을 그 자리에 적지 않는다. */}
      <span className="w-[170px] shrink-0 text-right tabular-nums text-muted" data-testid="event-observed">
        {event.observedAt ? kstDateTime(event.observedAt) : "—"}
      </span>
      <span className="w-[170px] shrink-0 text-right tabular-nums text-muted">{kstDateTime(event.recordedAt)}</span>
    </li>
  );
}

/**
 * 이 읽기가 증명하지 <b>못한</b> 것을, 한 줄로.
 *
 * 채널마다 손으로 쓴 문장이 아니라 축에서 나온다: 아무 축도 증명되지 않았으면 그 코드의 뜻을 확인한
 * 적이 없는 것이고, 일부만 증명됐으면 나머지 축은 이 읽기에 없는 것이다. 새 채널이 와도 이 줄은 저절로
 * 맞는 말을 한다.
 */
function limitSentence(detail: OrderRecordDetail, channelName: string): string {
  const missing = [
    detail.paymentLabelKo ? null : "결제",
    detail.cancellationLabelKo ? null : "취소",
    detail.fulfillmentLabelKo ? null : "배송",
  ].filter(Boolean) as string[];
  if (missing.length === 0) {
    return "";
  }
  if (detail.statusVaries) {
    return "줄마다 채널 상태가 달라, 이 주문 전체의 상태로 말할 수 있는 것이 없습니다";
  }
  if (missing.length === 3) {
    return `${channelName} 상태값의 뜻을 확인한 적이 없습니다`;
  }
  return `${channelName} 주문 읽기에 ${missing.join("·")} 상태가 없습니다`;
}

/** 마지막으로 본 날로부터 며칠 — 「32일 전」의 그 수. */
function daysSince(iso: string): number | null {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) {
    return null;
  }
  return Math.max(0, Math.floor((Date.now() - ms) / 86_400_000));
}
