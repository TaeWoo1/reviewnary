-- 리뷰 응대 기준에 「문제 있을 때만」(ISSUES_ONLY)을 더한다 (product-owner, 2026-10-28: 「별점 4~5점 칭찬 리뷰는 초안 잡지 말자.
-- 디폴트로는 4~5점 중에 특정 이슈에 관해 설명했다던가 해서 응대해야 하는 경우만」). 그 별점대의 리뷰 가운데 문제를 적었거나 무언가를
-- 물은 리뷰만 미리 쓴다. 줄이 없는 org의 기본값은 코드가 정한다(★1~2 · ★3은 AHEAD, ★4~5는 ISSUES_ONLY). V151은 이미 적용된
-- 마이그레이션이라 고치지 않고 제약만 바꾼다.
alter table review_response_rule drop constraint ck_review_response_rule_low;
alter table review_response_rule drop constraint ck_review_response_rule_mid;
alter table review_response_rule drop constraint ck_review_response_rule_high;
alter table review_response_rule add constraint ck_review_response_rule_low
    check (low_mode in ('AHEAD', 'ISSUES_ONLY', 'ON_REQUEST'));
alter table review_response_rule add constraint ck_review_response_rule_mid
    check (mid_mode in ('AHEAD', 'ISSUES_ONLY', 'ON_REQUEST'));
alter table review_response_rule add constraint ck_review_response_rule_high
    check (high_mode in ('AHEAD', 'ISSUES_ONLY', 'ON_REQUEST'));
