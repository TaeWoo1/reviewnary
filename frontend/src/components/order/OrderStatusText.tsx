/**
 * 주문 상태를 그리는 <b>유일한</b> 자리 — 목록도 상세도 여기를 지난다.
 *
 * <p><b>규칙은 하나다: 화면은 코드를 우리 말로 바꾸지 않는다.</b> 번역은 서버의
 * {@code ChannelOrderStatusVocabulary} 한 곳에서, 뜻을 확인한 (채널, 코드)에만 일어난다. 서버가 라벨을
 * 주지 않았다면 그것은 「아직 이름이 없다」가 아니라 <b>뜻을 확인한 적이 없다</b>는 뜻이고, 화면이 할
 * 수 있는 가장 정직한 일은 채널이 보낸 값을 그대로 보여 주는 것이다 — {@code DELIVERING}을 「배송
 * 중」으로 적는 순간, 그 추측은 고객에게 하는 말이 된다.
 *
 * <p>두 화면이 각자 이 규칙을 들고 있으면 한쪽만 고쳐지는 날이 온다. 그래서 컴포넌트가 하나다.
 */

/** 증명되지 않은 축의 말. 「취소되지 않음」이 아니다 — 그것은 저장된 행이 증명하지 못한다. */
export const UNCONFIRMED = "확인되지 않음";

/** 채널이 보낸 값 그대로. 글꼴로도 「이건 우리 말이 아니다」라고 말한다. */
export function RawCode({ code }: { code: string }) {
  return <span className="font-mono text-[0.8125rem] tracking-tight text-muted">{code}</span>;
}

/**
 * 한 레코드/한 줄의 상태 — 확인한 뜻이 있으면 우리 말과 raw를 나란히, 없으면 raw만.
 *
 * @param varies 줄마다 코드가 다른 결제 단위. 하나를 골라 적으면 일부만 취소된 주문이 취소되지 않은
 *               주문으로 읽히므로, 고르지 않는다.
 */
export function OrderStatusText({
  raw,
  labelKo,
  varies = false,
}: {
  raw: string | null;
  labelKo: string | null;
  varies?: boolean;
}) {
  if (varies) {
    return <span className="text-muted">줄마다 다름</span>;
  }
  if (!raw) {
    return <span className="text-muted">—</span>;
  }
  if (!labelKo) {
    return <RawCode code={raw} />;
  }
  return (
    <span className="inline-flex items-baseline gap-1.5">
      <span className="text-ink">{labelKo}</span>
      <RawCode code={raw} />
    </span>
  );
}

/** 세 축 가운데 하나 — 증명된 것만 우리 말이고, 아니면 「확인되지 않음」이다. */
export function OrderAxis({ name, labelKo }: { name: string; labelKo: string | null }) {
  return (
    <span className="text-muted">
      {name}
      {labelKo ? (
        <b className="ml-1.5 font-semibold text-ink">{labelKo}</b>
      ) : (
        <span className="ml-1.5 font-medium text-muted">{UNCONFIRMED}</span>
      )}
    </span>
  );
}
