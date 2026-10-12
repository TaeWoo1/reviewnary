-- 이 export가 몇 행이어야 했는가 — 마켓 화면이 같은 기간에 대해 말한 총 건수.
--
-- **왜 필요한가.** `review_import_segment.rows_reconciled`는 V27부터 있었지만 한 번도 true가 된 적이
-- 없다. 기대 행 수의 출처가 없었기 때문이다. 그래서 「범위를 맞게 내보냈다」와 「그 범위를 다
-- 받았다」를 구별할 방법이 없었고, 그 공백을 달력 월 단위 분할(작은 창이면 안 잘렸겠지)로 메워
-- 왔다 — 측정이 아니라 추정이다. 2026-10-13부터 한 export는 기간 하나(최대 1년)이고, 완결은
-- 이 값과 파일 행 수의 대조로 판정한다.
--
-- **세 상태가 구별된다.**
--   null           = 화면 총 건수를 읽지 못했다(수동 업로드 · 이전 기록 · 읽기 실패) — 대조 없음, rows_reconciled=false
--   값 = 파일 행 수  = 대조 성공 — rows_reconciled=true
--   값 ≠ 파일 행 수  = 이 export는 완결되지 않았다 — 시도 FAILED, 구간은 COVERED로 올라가지 않는다
-- 이전 시도에 값을 채워 넣지 않는다: 읽지 않은 숫자를 지금 적으면 사실을 지어내는 것이다.
alter table review_import_segment_attempt
    add column expected_rows integer;
