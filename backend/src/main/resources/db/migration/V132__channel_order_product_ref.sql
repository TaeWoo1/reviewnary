-- 주문이 어떤 상품이었는지 — 채널이 준 식별자로만, 증명될 때만.
--
-- **왜 자식 표인가 (컬럼 하나가 아니라).** `channel_orders` 한 행의 알맹이가 채널마다 다르다.
-- NAVER의 행은 상품주문 하나(productOrderId)이므로 상품도 하나다. 쿠팡의 행은 묶음배송 하나
-- (shipmentBoxId)이고 `orderItems`가 여럿일 수 있다 — 그 행에 `external_product_id` 컬럼 하나를
-- 놓으면 두 상품이 든 묶음에서 한 상품이 그 주문 전체의 상품이 된다. 같은 이유로
-- `OrderStatusEventView`가 전이를 줄 수마다 복제하지 않고 `lineCount`로 세고, 같은 이유로
-- `OrderRecordDetailResponse.statusVaries`가 존재한다: **줄의 값을 결제 단위로 올려 적지 않는다.**
--
-- **세 상태가 서로 구별된다.** 이것이 이 표의 전부다.
--   (1) 행이 없다            = 채널이 이 주문에 상품 식별자를 주지 않았다
--   (2) 행이 있고 product_id가 null = 식별자는 받았고, 일치하는 canonical 상품이 없다
--   (3) product_id가 있다      = 증명된 연결
-- 하나로 접으면 「우리가 못 읽었다」와 「채널이 안 줬다」와 「연결이 아직 안 됐다」가 같은 빈칸이 된다.
--
-- **이름으로 잇는 길은 없다.** `ChannelProductRef`가 이미 그 규칙을 선언해 두었다(문의 귀속에서) —
-- `channel_products (channel_id, external_product_id)` 정확 일치로만 찾고, 못 찾으면 **귀속하지 않고**,
-- 이름으로 다시 시도하지 않고, placeholder 상품을 만들지 않는다. 동명이인 상품이 canonical Demo Org에
-- 실제로 있고, 이름은 판매자가 언제든 바꾸는 값이다.
--
-- **식별자는 얼고 연결은 한 번만 채워진다.** 아래 trigger 둘. 식별자가 바뀔 수 있으면 어제의 연결이
-- 오늘 다른 상품을 가리키고, 연결이 다시 쓰일 수 있으면 틀린 연결을 틀린 채로 덮어쓸 수 있다 —
-- 그리고 그 둘은 「판매자가 본 적 있는 주문의 상품이 조용히 달라졌다」로 같이 끝난다.

create table channel_order_products (
    id                  uuid primary key,
    org_id              uuid         not null references organizations (id),
    channel_order_id    uuid         not null references channel_orders (id),
    -- 채널이 준 공식 식별자, 그대로. NAVER는 채널상품번호, 쿠팡은 sellerProductId.
    external_product_id varchar(120) not null,
    -- 어느 endpoint의 어느 필드에서 왔나 — ChannelProductRefSource의 이름. 자유 문자열이 아니다.
    ref_source          varchar(60)  not null,
    -- 정확 일치로만 해소된다. null은 「아직/일치 없음」이고 「상품이 없다」가 아니다.
    product_id          uuid references products (id),
    -- 우리가 해소한 시각. 채널이 말한 시각이 아니다 (D3).
    bound_at            timestamptz,
    first_seen_at       timestamptz  not null,
    last_seen_at        timestamptz  not null,
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,

    -- 연결과 그 시각은 떼어 놓을 수 없다. 시각 없는 연결은 언제 믿기 시작했는지 말할 수 없는 연결이다.
    constraint ck_channel_order_products_bound check ((product_id is null) = (bound_at is null))
);

-- 한 주문 안에서 같은 상품은 한 줄이다. 묶음에 같은 상품이 두 줄로 들어와도(수량 분리) 상품 참조는
-- 하나 — 이 표는 수량을 세지 않는다. 수량은 채널이 준 것이 아니면 만들지 않는다.
create unique index uq_channel_order_products_identity
    on channel_order_products (channel_order_id, external_product_id);
-- 「이 상품이 들어간 주문」 역방향. 아직 아무도 묻지 않지만 정방향과 같은 비용이고, 묻는 날
-- full scan이 되지 않도록.
create index ix_channel_order_products_product
    on channel_order_products (org_id, product_id)
    where product_id is not null;
-- 미해소 참조를 다시 돌리기 위한 것 — 상품이 나중에 수집되면 그때 연결된다.
create index ix_channel_order_products_unbound
    on channel_order_products (org_id, external_product_id)
    where product_id is null;

-- 식별자와 그 출처는 얼어 있다.
create or replace function channel_order_products_identity_frozen() returns trigger as $$
begin
    if new.external_product_id is distinct from old.external_product_id then
        raise exception '상품 식별자는 수정할 수 없습니다 (channel_order_products.external_product_id)';
    end if;
    if new.ref_source is distinct from old.ref_source then
        raise exception '식별자의 출처는 수정할 수 없습니다 (channel_order_products.ref_source)';
    end if;
    if new.channel_order_id is distinct from old.channel_order_id then
        raise exception '상품 참조를 다른 주문으로 옮길 수 없습니다 (channel_order_products.channel_order_id)';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_channel_order_products_identity_frozen
    before update on channel_order_products
    for each row execute function channel_order_products_identity_frozen();

-- 연결은 null에서 값으로 한 번만 간다. 다른 상품으로 바꾸는 것도, 지우는 것도 거부한다.
create or replace function channel_order_products_binding_once() returns trigger as $$
begin
    if old.product_id is not null and new.product_id is distinct from old.product_id then
        raise exception '이미 연결된 상품은 바꿀 수 없습니다 (channel_order_products.product_id)';
    end if;
    if old.bound_at is not null and new.bound_at is distinct from old.bound_at then
        raise exception '연결 시각은 수정할 수 없습니다 (channel_order_products.bound_at)';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_channel_order_products_binding_once
    before update on channel_order_products
    for each row execute function channel_order_products_binding_once();

comment on table channel_order_products is
    '주문 한 행이 가리키는 상품 참조 — 채널이 준 식별자(verbatim) + 출처, 그리고 정확 일치로만 해소되는 canonical 연결 (Order Context Foundation v1).';
comment on column channel_order_products.product_id is
    'channel_products (channel_id, external_product_id) 정확 일치로만 채워진다. 이름 매칭·placeholder 생성 없음.';

-- 그리고 기존 컬럼 하나에 경고를 적는다.
--
-- `normalized_status`는 수집 시점에 ChannelOrderStatusVocabulary로 계산해 넣은 **캐시**이고, 지금
-- 이 저장소에서 그것을 읽는 코드는 하나도 없다(SellingStatus의 주석에 언급만 된다). 2026-10-10에
-- 어휘표가 쿠팡 네 코드로 넓어졌으므로, 그 전에 수집된 행의 이 값은 오늘의 어휘와 다르다 —
-- 쿠팡 ACCEPT 29행이 'UNKNOWN'으로 남아 있고 오늘의 표는 PAID라고 말한다.
--
-- 백필하지 않는다. 백필은 어휘표를 SQL에 한 번 더 적는 일이고, 그 사본이 다음에 표가 넓어질 때
-- 따라오지 않는다 — 그러면 한 질문에 두 답이 생기고, 그것이 어휘표가 한 파일인 이유다. 발송·결제
-- 축은 **읽는 시점에** 어휘표를 지나 계산되고(axesFromStored), 화면과 reader는 전부 그 경로를 쓴다.
comment on column channel_orders.normalized_status is
    '수집 시점의 ChannelOrderStatusVocabulary 계산 결과 캐시. 현재 값으로 읽지 말 것 — 축은 읽는 시점에 axesFromStored로 계산한다. 2026-10-10 이전 수집 행은 넓어진 어휘와 다를 수 있다.';
