-- 2026-11-01, tenth review: a question the store's material could not answer is the seller's to handle, one by one, with a
-- reason and a history — 「미해결 질문 「처리함」: 질문별로 처리하고, 해결 · 대응 불필요 등의 이유와 이력을 남긴다」. It
-- belongs to the work (a review, an inquiry's work item), not to one draft version: editing a sentence does not settle it.
-- An inquiry draft that answers only part of what was asked is held from sending while a required question is open.
CREATE TABLE open_question (
    id          uuid PRIMARY KEY,
    org_id      uuid         NOT NULL,
    lane        varchar(16)  NOT NULL,
    subject_id  uuid         NOT NULL,
    question    varchar(500) NOT NULL,
    required    boolean      NOT NULL,
    state       varchar(16)  NOT NULL,
    resolution  varchar(24),
    note        varchar(300),
    origin_ref  varchar(120) NOT NULL,
    created_at  timestamptz  NOT NULL,
    closed_at   timestamptz,
    closed_by   varchar(120)
);
CREATE INDEX ix_open_question_subject ON open_question (org_id, lane, subject_id, state);

-- Append-only: every opening, handling and re-judging of a question, with who and why.
CREATE TABLE open_question_event (
    id           uuid PRIMARY KEY,
    org_id       uuid         NOT NULL,
    question_id  uuid         NOT NULL REFERENCES open_question (id),
    action       varchar(16)  NOT NULL,
    resolution   varchar(24),
    note         varchar(300),
    actor        varchar(120) NOT NULL,
    at           timestamptz  NOT NULL
);
CREATE INDEX ix_open_question_event_question ON open_question_event (question_id, at);
