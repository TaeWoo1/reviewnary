-- 2단계 재검사: 바뀐 문서가 초안이 실제로 쓴 구절을 바꿨는가 — docs/vendor_server_contract_v1.md §4 (2026-10-23).
-- passage_fingerprint: 초안을 쓸 때 본 구절(공백을 접은 본문)의 SHA-256. 구절 자체는 복사하지 않는다 — 원문은 판매자의
-- 자료 화면 한 곳에만 있다. 문서가 고쳐진 뒤 지금 구절 중 같은 지문이 있으면 그 초안이 쓴 부분은 그대로다.
-- 이 열이 비어 있는 옛 인용은 지금처럼 문서가 고쳐지면 다시 확인으로 본다(닫힌 쪽).
alter table review_draft_evidence add column passage_fingerprint varchar(64);
