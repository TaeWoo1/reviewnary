-- The live-approval interlock's refusals, named in history.
--
-- V116 added failure_code and backfilled the one pre-attempt class it then knew: the configuration gate
-- (CONNECTOR_UNAVAILABLE). The rule is wider than that one gate — a run that ended before any request left the
-- process cannot date a collection and cannot erase one — and the Coupang live-approval interlock is the other
-- member of that class. Its refusals already sat in history carrying no code, so the freshness reads treated
-- them as the channel's latest word: Demo Org's Coupang 문의 collected successfully on 2026-09-23 and coverage
-- still read 「확인된 적 없음」 because of two runs that opened no socket.
--
-- The match is exact, not heuristic. `CoupangLiveCallGuard` is the ONLY writer of this sentence (both the READ
-- and the WRITE gate phrase it), and it throws as the first statement of the signed-GET choke points — before
-- the signature and before the socket. `SyncRunExecutor` prefixes '수집 실패: ' when it records the run.
--
-- Scoped to FAILED on purpose: a run that had already landed a page would be PARTIAL
-- (SyncRunExecutor.resolveStatus), and a PARTIAL keeps its ordinary meaning everywhere.
--
-- The audit rows themselves are never deleted — only classified.
update sync_jobs
   set failure_code = 'CONFIGURATION_REQUIRED'
 where status = 'FAILED'
   and failure_code is null
   and error_message like '%라이브 API%승인 없이 시도되었습니다.%';
