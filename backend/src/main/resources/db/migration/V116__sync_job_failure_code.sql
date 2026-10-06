-- Persist the run's failure classification.
--
-- `SyncRunExecutor` has always produced a closed code for a run that did not finish clean
-- (AUTH_REQUIRED / TIMEOUT / RATE_LIMITED / PAGE_LIMIT_REACHED / CONNECTOR_UNAVAILABLE /
-- CONFIGURATION_REQUIRED / EXECUTION_FAILED), but it lived on a @Transient field: it reached the
-- caller holding the returned instance and died there. Every reader that loads a run back from the
-- database saw `FAILED` and nothing about WHY, so it could not tell a run that asked the channel and
-- was refused from a run that stopped at its own config gate and never opened a socket.
--
-- That distinction is load-bearing for the freshness surfaces. A run which ended because no pull
-- connector is configured observed nothing about the channel; letting it stand next to a real attempt
-- is how "마지막 성공 수집" was erased by a run that never left the process.
--
-- Additive and nullable: existing rows keep a null code, which every reader already treats as "says
-- nothing special" — the behaviour they have today.
alter table sync_jobs
    add column if not exists failure_code varchar(40);

-- Backfill the classification onto the rows that already carry it in words.
--
-- These three messages have exactly one writer — SyncRunExecutor.recordConfigFailure — so matching on
-- them names the config-stage failures precisely, with no heuristic. Nothing is invented here: the run
-- was classified CONNECTOR_UNAVAILABLE when it happened; the column it belonged in did not exist yet.
--
-- Leaving history uncoded would have made the fix true only for runs recorded after this migration,
-- while the runs that exposed the defect went on erasing the collection times they erased.
update sync_jobs
   set failure_code = 'CONNECTOR_UNAVAILABLE'
 where status = 'FAILED'
   and failure_code is null
   and (error_message = '채널에 자동 수집 커넥터가 없습니다.'
     or error_message like '% 데이터 유형은 이 채널에서 지원되지 않습니다.'
     or error_message like '% 데이터 유형은 이 채널에서 기간 지정 백필을 지원하지 않습니다.');
