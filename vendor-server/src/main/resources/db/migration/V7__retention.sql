-- 보유와 삭제 — docs/vendor_server_contract_v1.md §6. Written to run unchanged on PostgreSQL and on H2 in PostgreSQL mode.
--
-- reply_tasks.ended_at   작업이 끝난 상태(POSTED · FAILED · CANCELLED · INVALIDATED)에 처음 들어간 시각. 완료된 작업과
--                        그 리뷰의 90일이 여기서 시작한다. 한 번 정해지면 바뀌지 않는다.
-- purged_reviews         지운 리뷰의 식별자만(내용 없음). 같은 리뷰가 다음 업로드에 다시 실려도 되살리지 않고, PC가 지운
--                        리뷰에 보내는 '답글 필요 없음'은 이미 끝난 일로 받는다. 리뷰글번호는 해시로만 둔다.
alter table reply_tasks add column ended_at timestamp with time zone;

update reply_tasks set ended_at = updated_at where state in ('POSTED', 'FAILED', 'CANCELLED', 'INVALIDATED');

create index reply_tasks_ended_idx on reply_tasks (ended_at);
create index store_reviews_purge_idx on store_reviews (purge_after);

create table purged_reviews (
    store_id       uuid not null references stores (id),
    review_no_hash char(64) not null,
    review_id      uuid not null unique,
    purged_at      timestamp with time zone not null,
    primary key (store_id, review_no_hash)
);
