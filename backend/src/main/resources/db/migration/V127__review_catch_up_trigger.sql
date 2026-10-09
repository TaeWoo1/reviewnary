-- 같은 walk를 누가 시작했는지 행이 들고 있어야 한다.
--
-- catch-up은 누가 시작하든 같은 프로그램이다. 그래서 다음 창이 「사람이 눌러서」 읽히는지 「자동 확인이
-- 빈 구간을 찾아서」 읽히는지는, 시작할 때 적어 두지 않으면 알 길이 없다 — 그리고 자식 job이 자기
-- trigger_source를 그 값에서 가져가므로, 몇 시간에 걸친 walk와 한 번의 재개를 지나서도 감사 기록이
-- 어긋나지 않는다. 재개가 바꾸지 않는 것도 이것이다: 로그인 벽에서 멈췄다 이어진 run은 여전히 그 run이다.
--
-- client_request_id는 이제 null일 수 있다. 자동 확인에는 누름이 없고, 없는 누름의 id를 합성하면
-- 「같은 누름이 다시 찾아온 의도」가 서로 무관한 두 tick에 대해 참이 된다.

alter table review_catch_up_run
    add column trigger_source varchar(16) not null default 'OPERATOR';

alter table review_catch_up_run
    add constraint chk_review_catch_up_trigger
        check (trigger_source in ('OPERATOR', 'SCHEDULED'));

alter table review_catch_up_run
    alter column client_request_id drop not null;

-- 누름이 자기 의도를 다시 찾는 길은 그대로다. 누름 id가 없는 행만 이 unique에서 빠진다.
drop index if exists uq_review_catch_up_request;
create unique index uq_review_catch_up_request on review_catch_up_run (org_id, client_request_id)
    where client_request_id is not null;

-- 누름으로 시작한 walk는 누름 id를 들고 있어야 한다. 그 둘이 어긋나면 더블클릭이 두 번째 walk를 만든다.
alter table review_catch_up_run
    add constraint chk_review_catch_up_operator_request
        check (trigger_source <> 'OPERATOR' or client_request_id is not null);

comment on column review_catch_up_run.trigger_source is
    '이 walk를 시작한 쪽. OPERATOR(판매자가 지금 확인을 눌렀다) · SCHEDULED(판매자의 자동 확인이 빈 구간을 찾았다). 자식 job이 이 값을 그대로 가져가고, 재개는 이 값을 바꾸지 않는다.';
comment on column review_catch_up_run.client_request_id is
    '누름의 id. 자동 확인이 시작한 walk에서는 null — 없는 요청의 id를 만들지 않는다.';
