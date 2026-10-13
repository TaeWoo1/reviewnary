-- 리뷰 이력을 어디까지 거슬러 읽는가 — 판매자 설정 한 칸, 기본값 1년.
--
-- **왜 필요한가.** catch-up walk는 지금까지 「빠짐없이 확인한 경계」 뒤쪽(앞으로)만 걸었다. 경계보다
-- 오래된 날은 계획할 근거가 없었고, 근거 없는 시작일은 측정된 시작일과 구별되지 않는다는 이유로 일부러
-- 비워 두었다(ReviewCoverageCursor). 그 이유는 옳았다 — 그래서 시작일을 추측하는 대신 **기록한다**.
-- 이 칸은 「판매자(또는 판매자를 대신한 제품 기본값)가 여기까지 읽으라고 정했다」는 사실이고, walk의
-- requested_from은 이 값에서 나온다.
--
-- **기본값은 제품 책임자 결정이다 (2026-10-13).** NAVER 리뷰 이력은 Aside 화면 읽기 lane이 판매자 손을
-- 거의 빌리지 않고 무인으로 채운다. 기본 깊이는 1년이고, 판매자는 아무것도 하지 않는다. 새 행은 생성
-- 시점의 KST 오늘에서 1년 전으로 채워지고(ReviewAutoCheckService#ensure), 이미 있는 행은 아래 update가
-- 같은 규칙으로 채운다.
--
-- null은 「이력을 거슬러 읽지 않는다」이다. 판매자가 그렇게 고르는 길은 아직 없지만, 칸의 뜻은 열어 둔다.
alter table review_auto_check
    add column history_from date;

-- 2010-01-01은 날짜 오타(0202년, 1970년)를 막는 바닥일 뿐, 마켓이 거기까지 보여준다는 주장이 아니다.
-- 어디까지 닿는지는 마켓 달력이 정하고, 비활성 날짜에서는 런타임이 닫힌 채로 실패한다.
alter table review_auto_check
    add constraint chk_review_auto_check_history_from
        check (history_from is null or history_from >= date '2010-01-01');

-- 이미 있는 행: 제품 책임자의 기본값(1년)을 그대로 적용한다. 판매자의 KST 하루로 센다 —
-- 서버 시간대의 current_date는 자정 무렵 하루가 어긋난다.
update review_auto_check
   set history_from = ((now() at time zone 'Asia/Seoul')::date - interval '1 year')::date
 where history_from is null;

comment on column review_auto_check.history_from is
    '리뷰 이력을 거슬러 읽을 첫날(KST). 기본값은 생성 시점 기준 1년 전(제품 책임자 결정 2026-10-13). null = 이력을 거슬러 읽지 않음.';

-- walk 하나가 두 구간을 걷는다: 경계 뒤의 빈 날(앞으로, 오래된 것부터)과, 경계 앞의 이력(뒤로, 최신부터).
--
-- 이력을 최신부터 걷는 이유: 행이 있는 기간이 먼저 읽혀야 판매자 센터 화면 지문이 기록되고
-- (NaverReviewObservationService#rememberScreenStore), 그래야 리뷰가 한 건도 없는 오래된 기간이
-- 「이 가게의 빈 기간」으로 귀속될 수 있다. 또 최신부터 이어 붙이면 걷다 멈춰도 구멍이 생기지 않는다.
--
--   phase            = 지금 걷는 구간. FORWARD는 cursor_day가 다음 창의 첫날, HISTORY는 다음 창의 마지막 날이다.
--   history_from     = 이력 구간의 첫날(설정의 history_from). null이면 이 walk에는 이력 구간이 없다.
--   history_through  = 이력 구간의 마지막 날 — walk가 시작될 때 경계 바로 앞날(경계가 없으면 어제).
alter table review_catch_up_run
    add column phase varchar(8) not null default 'FORWARD',
    add column history_from date,
    add column history_through date;

alter table review_catch_up_run
    add constraint review_catch_up_phase_known check (phase in ('FORWARD', 'HISTORY'));

alter table review_catch_up_run
    add constraint review_catch_up_history_span check (
        (history_from is null and history_through is null)
        or (history_from is not null and history_through is not null and history_through >= history_from));

-- 이력 구간 없이 HISTORY를 걷는 run은 읽을 수 없는 상태다.
alter table review_catch_up_run
    add constraint review_catch_up_history_phase_has_span check (phase = 'FORWARD' or history_from is not null);
