-- 답변 본문을 마지막으로 확인한 시각 — 문의를 마지막으로 본 시각과 따로 (2026-11-06 열일곱째 리뷰).
--
-- `last_seen_at`은 수집이 이 문의를 보여 준 때다. 그 수집에 답변 본문이 없으면 `answer_body`는 지우지 않고
-- 그대로 둔다(본문을 주지 않는 수집은 판매자가 답변을 지운 것이 아니다 — IngestionService.applyInquirySource).
-- 그래서 월요일에 본문 A를 읽고 금요일에 등록 상태만 읽으면, 화면이 본문 A 옆에 「금요일 확인」을 붙였다.
-- 금요일에 본 것은 상태지 본문이 아니다.
--
-- 이 컬럼은 수집이 본문을 실제로 실어 온 때만 찍힌다. 기존 행은 null로 둔다 — 그 본문을 언제 읽었는지 우리는
-- 모르고, 모르는 것을 `last_seen_at`이나 `answered_at`으로 채워 넣으면 이 컬럼이 고치려는 바로 그 거짓이 된다.
-- 화면은 null을 「언제 확인했는지 기록이 없어요」로 읽는다.
alter table inquiries add column answer_body_seen_at timestamptz;

comment on column inquiries.answer_body_seen_at is
    '수집이 answer_body를 실제로 실어 온 마지막 시각. last_seen_at(문의·상태를 본 시각)과 다르다. V183 이전 행은 null = 모름.';
