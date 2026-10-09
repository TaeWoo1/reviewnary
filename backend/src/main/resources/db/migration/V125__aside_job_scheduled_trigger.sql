-- 자동 확인이 세 번째 trigger가 된다 — 사람도, Responsibility도 아닌 「판매자의 설정」.
--
-- 이 lane이 OPERATOR를 흉내 내지 않는 이유는 감사 기록 하나다. 「이 읽기를 누가 요청했는가」에 대해
-- scheduled_aside_job.trigger_source가 답하고, 눌리지 않은 읽기를 눌렸다고 적으면 그 칸이 존재하는
-- 유일한 이유가 사라진다. RESPONSIBILITY를 쓰지 않는 이유도 같다: 그 lane의 조건(배포가 조직과 계정을
-- 미리 지명)은 다른 질문에 대한 답이고, 여기서 재사용되거나 넓혀지지 않는다.
--
-- SCHEDULED의 승인 근거는 review_auto_check — 판매자가 소유한 제품 설정이다(V126).

alter table scheduled_aside_job
    drop constraint chk_scheduled_aside_job_trigger;

alter table scheduled_aside_job
    add constraint chk_scheduled_aside_job_trigger
        check (trigger_source in ('OPERATOR', 'SCHEDULED', 'RESPONSIBILITY'));

-- 「계정을 지명하고 run에 묶이지 않는다」는 OPERATOR만의 성질이 아니었다. RESPONSIBILITY만이 계정을
-- 배포 목록에서 해결하고, 나머지는 모두 자기 계정을 들고 온다.
alter table scheduled_aside_job
    drop constraint chk_scheduled_aside_job_operator;

alter table scheduled_aside_job
    add constraint chk_scheduled_aside_job_caller
        check (trigger_source = 'RESPONSIBILITY'
               or (seller_account_id is not null and run_id is null));

-- <b>아무도 보지 않는 읽기는 기간을 지명한다.</b> 화면이 그때 보여주던 기간을 읽는 것은, 나중에
-- 「어젯밤 자동 확인은 무엇을 덮었나」에 답할 수 없다는 뜻이다 — 그 기간은 마켓플레이스의 설정이고
-- 이 제품이 고른 적이 없다. 사람이 누른 읽기는 화면 그대로 읽어도 된다: 그 사람이 화면을 보고 있다.
alter table scheduled_aside_job
    add constraint chk_scheduled_aside_job_scheduled_window
        check (trigger_source <> 'SCHEDULED'
               or (requested_window_start is not null and requested_window_end is not null));

comment on column scheduled_aside_job.trigger_source is
    '누가 요청했는가: OPERATOR(판매자가 자기 계정에서 지금 확인을 눌렀다 — 누름이 승인) · SCHEDULED(판매자의 자동 확인 설정이 주기로 요청했다 — review_auto_check가 승인, 기간 지정 필수) · RESPONSIBILITY(무인 실행; 배포가 조직과 계정을 지명해야 한다).';
