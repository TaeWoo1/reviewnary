-- 카페24 댓글 답변 확인의 마지막 결과 — 문의마다 (2026-11-08, inquiry_operational_truth_v1 §10.4).
--
-- 정기 수집은 「판매자 답변 전」으로 저장할 글마다 판매자가 댓글로 이미 답했는지 본다(Cafe24InquiryAnswerObserver).
-- 지금까지는 「답변 확인됨」만 문의에 반영되고, 「확인했는데 판매자 댓글 없음」과 「확인하지 못함」은 같은 침묵이었다.
-- 이 세 칸이 그 둘을 나눈다.
--
--   comment_check              마지막 결과: SHOP_ANSWERED · NO_SHOP_COMMENT · NOT_CHECKED
--   comment_check_attempted_at 그 결과가 나온 때 — 결과와 무관
--   comment_checked_at         확인에 성공한 마지막 때(SHOP_ANSWERED · NO_SHOP_COMMENT). NOT_CHECKED는 움직이지 않는다
--
-- NOT_CHECKED는 확인 실패의 기록이다. 답변이 없다는 뜻이 아니고, 그 시각은 확인한 시각이 아니다 — 그래서 성공 시각을
-- 따로 둔다. 댓글 확인이 돌지 않은 수집(다른 채널 · 스레드 답글 · 이미 답변 상태인 글 · 댓글 확인이 없는 배포)은 세 칸을
-- 건드리지 않는다.
--
-- 기존 문의는 세 칸 모두 null로 둔다 — 확인 이력이 없고, 없는 이력을 채워 넣지 않는다. 작성자 식별자 · 댓글 본문은
-- 저장하지 않는다.
alter table inquiries
    add column comment_check varchar(16),
    add column comment_check_attempted_at timestamptz,
    add column comment_checked_at timestamptz;

alter table inquiries
    add constraint ck_inquiries_comment_check_value
        check (comment_check is null
               or comment_check in ('SHOP_ANSWERED', 'NO_SHOP_COMMENT', 'NOT_CHECKED')),
    add constraint ck_inquiries_comment_check_attempt_pair
        check ((comment_check is null) = (comment_check_attempted_at is null)),
    add constraint ck_inquiries_comment_checked_needs_result
        check (comment_checked_at is null or comment_check is not null);

comment on column inquiries.comment_check is
    '카페24 댓글 답변 확인의 마지막 결과. null = 확인한 적 없음(V184 이전 행 · 다른 채널). NOT_CHECKED = 확인 실패, 미답변 확정이 아님.';
comment on column inquiries.comment_check_attempted_at is
    'comment_check가 나온 시각. NOT_CHECKED일 때도 찍히므로 확인 성공 시각으로 읽지 않는다.';
comment on column inquiries.comment_checked_at is
    '댓글 확인에 성공한(SHOP_ANSWERED · NO_SHOP_COMMENT) 마지막 시각. NOT_CHECKED는 이 값을 바꾸지 않는다.';
