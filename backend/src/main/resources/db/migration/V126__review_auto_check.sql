-- 「새 리뷰를 자동으로 확인합니다」 — 판매자가 소유한 제품 설정 한 줄.
--
-- grant가 아니다. grant는 요청받고, 만료되고, 기기가 들고 있고, 다시 요청해야 한다. 이것은 판매자가
-- 채널을 연결했기 때문에 켜져 있는 자기 계정의 스위치이고, 판매자가 끌 때만 꺼진다.
--
-- device_id가 없는 것이 이 표의 핵심이다. 도우미는 읽기가 「어떻게」 일어나는지이고, dispatch 시점에
-- 해결된다(그 계정을 마지막으로 성공적으로 읽은 데스크를 우선, 없으면 현재 살아 있는 grant). 설정을
-- 기기에 묶으면 토큰 180일 만료·재설치·새 맥마다 판매자가 동의를 다시 해야 했고, 그것은 기기 수명을
-- 판매자 의사로 착각하는 일이다. channel_id도 없다 — seller_accounts.channel_id에서 유도된다.
--
-- mode는 기능 불변조건이다. 모든 공개 recipe는 열고 읽고 닫으며, WRITE recipe는 존재하지 않고 구조
-- 테스트가 그것을 거절한다. 칸으로 적어 두는 이유는 이 lane이 의존하는 사실을 행이 말하게 하려는 것이고,
-- 언젠가 쓰기 recipe가 생겨도 enum에 값을 더하는 것만으로 이 lane에 닿지 못하게 하려는 것이다.

create table review_auto_check (
    id                uuid primary key,
    org_id            uuid        not null references organizations (id) on delete cascade,
    seller_account_id uuid        not null references seller_accounts (id) on delete cascade,
    data_type         varchar(16) not null,
    mode              varchar(16) not null default 'READ_ONLY',
    enabled           boolean     not null default true,
    -- 켠 사람과 켠 때. 기본 ON으로 생긴 행에서는 연결한 판매자이고, 화면이 그것을 묻지는 않는다.
    consented_by      uuid,
    consented_at      timestamptz,
    -- 판매자가 끈 시각. 묘비이기도 하다: 「없으면 만든다」 규칙이 이 행을 다시 켜지 않는다.
    revoked_at        timestamptz,
    interval_minutes  integer     not null default 60,
    next_check_at     timestamptz,
    last_check_at     timestamptz,
    -- 지금 진척이 없는 이유. 허용 여부가 아니다. PAUSED_DEVICE는 도우미가 돌아오면 스스로 풀리고,
    -- PAUSED_AUTH는 판매자가 로그인할 때 풀린다 — 둘 다 enabled를 건드리지 않는다.
    paused_reason     varchar(24),
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    constraint uq_review_auto_check_account_data_type unique (seller_account_id, data_type),
    constraint chk_review_auto_check_data_type check (data_type in ('REVIEW')),
    constraint chk_review_auto_check_mode check (mode = 'READ_ONLY'),
    constraint chk_review_auto_check_interval check (interval_minutes >= 15),
    constraint chk_review_auto_check_pause check (paused_reason is null
        or paused_reason in ('PAUSED_DEVICE', 'PAUSED_AUTH')),
    -- 꺼진 행은 예정이 없다. 「껐는데 다음 확인이 예약되어 있다」는 읽을 수 없는 상태다.
    constraint chk_review_auto_check_off_has_no_schedule
        check (enabled or (next_check_at is null and paused_reason is null))
);

-- 주기 선택에 쓰는 인덱스. due 판정은 next_check_at is null도 포함하므로 부분 인덱스로 좁히지 않는다.
create index idx_review_auto_check_due on review_auto_check (next_check_at)
    where enabled and revoked_at is null;

comment on table review_auto_check is
    '판매자의 자동 리뷰 확인 설정(org × sellerAccount × REVIEW × READ_ONLY). 기기에 묶이지 않고, 판매자가 끌 때만 꺼진다.';
comment on column review_auto_check.mode is
    'READ_ONLY — 기능 불변조건. 쓰기 recipe는 존재하지 않으며, 이 칸은 그 사실을 행이 말하게 한다.';
comment on column review_auto_check.revoked_at is
    '판매자가 끈 시각이자 묘비. 자동 생성 규칙이 이 행을 다시 켜지 않게 한다.';
