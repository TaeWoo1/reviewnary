# 사례별 평가 (docs/standing/reply_agent_v1.md §8 ③)

리뷰와 문의를 **제품이 실제로 처리하는 흐름 그대로** 실제 모델로 돌리고, 사례마다 `RUBRIC.md`(평가표 v1)로 매긴다.
`../reply-agent/`는 모든 리뷰를 먼저 「대응 필요」로 둔 답글 문장 A/B 비교다. 이 도구는 다르다. 답할지 · 넘길지의 판단,
근거 검색, 초안, 남은 상태까지 본다. 실패 · 보류 · 초안 없음도 결과로 남는다.

고객 글이 들어간 산출물은 저장소에 두지 않는다. 모든 출력은 `~/workspace/sellerops/holds/` 아래로만 간다(스크립트가 거부한다).

1. `pick_cases.py --out <dir> --exclude <이전 사례 목록> …` — 글을 읽지 않고 구조로만 고른다(별점 · 길이 · 상품 연결 ·
   상품 지식 유무 · 단계). 이전 회차에 쓴 사례는 고르지 않는다. 별점 1~2처럼 수가 적어 다시 쓸 수밖에 없으면 `dev`로 표시한다.
2. `start_eval_backend.sh <dir>` — 복사본 DB(`sellerops_eval`) 위의 평가용 백엔드와 runtime. 금고 키가 없어 마켓 호출이
   불가능하다. 모델 단계는 모두 Claude이고, 쓴 설정은 `<dir>/config.txt`에 남는다.
3. `run_cases.sh <dir>/cases.txt <dir>` — 리뷰는 `/api/agent/reply/start`(리뷰 화면 버튼과 같은 길, 대응 여부를 미리
   정하지 않음), 문의는 `/api/inquiries/{id}/draft/generate`. 실행 전후 지식 지문을 남긴다.
4. `build_case_sheet.py --run <dir>` — 이번 실행의 버전만 읽어 `sheet.html`(사장님 채점, 「JSON 저장」),
   `auto.json`(코드가 말할 수 있는 것 + 그동안의 모델 호출 기록), `judge-input.jsonl`(백엔드 정제기를 거친 채점자 입력)을 만든다.
5. 별도 채점자는 `judge_prompt.md`로, 초안을 쓴 모델과 다른 모델이 매긴다. 사장님 정답을 대신하지 않는다.
6. 추천 판정: `<dir>/recommend.json`(사례마다 항목별 추천 · 이유 · 사장님 확인 질문)이 있으면 `build_case_sheet.py`가 평가표에 미리
   선택하고 이유를 붙인다. 사장님 확인 질문은 표 맨 위에 모은다. 판매자 정책 · 새 화면 결정은 추천하지 않고 질문으로 남긴다.
7. 모델 없는 재현: 리뷰 검색이 어디서 자료를 잃는지는 `backend/src/test/.../review/draft/ReviewRetrievalTraceIT`로 본다
   (`RUN_REVIEW_RETRIEVAL_TRACE=true`, 복사본 DB, 텍스트 없이 단계별 숫자만).
8. 자료가 있는지와 찾았는지 나누기: 같은 테스트에 `TRACE_MATERIAL_OUT=<holds 아래 파일>`을 주면 리뷰마다 가게 자료 전부와 모델에
   간 자료 id를 쓴다. `material_need.py --run <dir>`이 별도 모델에게 검색 결과를 알리지 않고 필요한 자료를 고르게 해, 「필요 없음 ·
   가게에 없음 · 있는데 놓침 · 찾음 · 쓰면 안 되는 것을 씀」으로 나눈다. 낱말 검색만 켠 회차의 「못 찾음」을 「없음」으로 읽지 않기 위해서다.
9. 막힌 문의 초안 보기: 저장되지 않은 초안은 어디에도 남지 않는다. `InquiryDraftReplayIT`(`RUN_INQUIRY_DRAFT_REPLAY=true`,
   `REPLAY_WORK_ITEMS`, `REPLAY_OUT`은 holds 아래)가 실제 「초안 생성」을 한 번 돌리며 모델이 쓴 초안과 막힌 이유를 남긴다.
10. 리뷰 사례는 선택 도구로만 고른다 — 이미 답한 리뷰 · 승인된 답글이 있는 리뷰는 제품이 거절한다(FINDINGS B55).
11. 정책을 고친 뒤 대기 초안 다시 보기: `PolicyChangeRecheckIT`(`RUN_POLICY_RECHECK=true`, `REPLAY_ORG`, `RECHECK_OUT`은 holds
   아래)가 모델 없이, 승인 전 리뷰 초안은 보내기 전 재검사(`ReviewDraftRecheck`)로, 대기 중인 문의 초안은 인용한 문서의 지금
   본문과 약속 검사로 다시 본다. 정책을 고치기 전과 뒤에 한 번씩 돌려 두 파일을 비교한다. id · 코드 · 걸린 낱말만 쓴다.
12. 문의 회차(2026-11-01 여덟 번째 검토): `pick_cases.py --inquiries 15 …`가 쓰지 않은 실제 문의를 제안 범주별로 고른다(아직 열지 않은
   문의는 `|propose`가 붙어 `run_cases.sh`가 제안부터 받는다). 문의 결과는 리뷰의 SAVED가 아니다 — `build_case_sheet.py`가 초안 · 되묻는
   초안 · 판매자 기본 문구 · 근거 없음 · 검사에서 막힘 · 검토 대기 · 실패로 적고, `summarize_round.py`가 채널 · 업무별로 알려진 글자 수
   제한만 적용한다. `inquiry_material.py` → `material_need.py --lane inquiry`가 원인(자료 없음 · 못 찾음 · 막힘 · 잘못 씀)을 나눈다.
   합성 가게(`synthetic_store.py`)에는 실제 문의에 없는 유형의 합성 문의 8건이 있다(Q8: 모든 옵션에 같다고 밝힌 근거 — 아홉 번째
   검토). 합성 가게 회차는 `--org`(시트 · 자료)와 `EVAL_ORG`(지식 지문)를 함께 준다.
13. 사장님 표본: `owner_sample.py --run <dir>`가 자동 채점이 「승인 가능」 · 「문체만 고침」 · 「넘김」으로 본 리뷰를 2건씩, 그룹 이름을 가린
   `owner-sample.html`로 만든다.
14. 같은 문의 반복(2026-11-01 아홉 번째 검토): 같은 cases.txt를 회차마다 **평가 백엔드를 다시 켠 뒤** 돌린다 — 필요 판단은 같은 요청을
   5분 동안 메모리에 기억하므로, 다시 켜지 않으면 2 · 3회차가 1회차 판단을 그대로 받아 흔들림이 0으로 보인다.
   `repeat_rounds.sh <cases.txt> <holds 접두> 3`이 회차마다 서버를 다시 켜고 돌린 뒤 `stability.py`가 사례마다 최종 처리 · 근거 상태 · 필요 판단(유형 · 상태 · 고객에게 물을 것 여부) · 인용 자료 · 되묻기 여부만 비교한다(문구는
   비교하지 않는다).
15. 질문별 누락(2026-11-02): 채점 입력은 초안과 함께 판매자에게 보여 준 「보내기 전에 직접 답할 질문」(`left`)을 싣고, 채점자는 초안이
   다루지 않은 질문을 하나씩 `toSeller`(그 목록에 있음)와 `missed`(아무도 모르게 빠짐)로 나눈다. `summarize_round.py`의 `questions`가
   질문 수로 센다. 그 전 채점 답(`toSeller` 없음)은 판매자 질문 개수만 앞에서 빼던 옛 방식으로 세고 `byCount`에 따로 적는다.
   `missed` · `toSeller`는 `asked` 항목을 글자 그대로 옮겨야 한다(세부로 쪼개면 채점 실패로 다시 묻는다). 문의마다 시스템이 기록한
   받은 때(`received`)가 붙는다 — 초안을 쓴 쪽이 받은 것과 같은 줄이다.
16. 단계별 흔들림(2026-11-02): 같은 문의가 회차마다 다르게 끝나면 전체 흐름을 더 돌리지 않고 `InquiryDecisionStagesIT`로 한 단계씩
   고정한다 — 그대로(초안 작성 없이) · 질문 분리 고정(수집 · 판정만) · 판정까지 고정(초안 · 사실 검사만). 판정 서비스의 5분 메모는 회마다
   비운다. 결과는 해시 · 유형 · 판정 · 개수뿐이고 `~/workspace/sellerops/holds/` 아래에만 쓴다. 실행 · 요약 스크립트는
   `holds/eval/decision-stages/`(`run.sh`, `summarize.py`).
17. 다시 쓰기(2026-11-02): 문장을 고쳐 풀 수 있는 이유(근거 없는 후속 약속 · 요청 · 보상, 빠진 조건, 근거로 확인되지 않은 문장, 답이 없는 초안,
   쓰지 않기로 한 표현, 【확인】 표시)로 막힌 문의 초안은 같은 근거 · 같은 답변 범위로 **한 번** 다시 쓰고 전부 다시 검사한다. 숫자 · 법 · 근거
   충돌 · 끝나지 않은 검사 · 한도는 다시 쓰지 않는다. 결과의 `rewrite`(`PASSED` · `REFUSED` · `PENDING`(다시 쓴 초안이 검사 한도로 검토 대기 — 거절 아님) · `NOT_WRITTEN`, 없으면 첫 시도)로 요약의
   `rewrite` 칸이 첫 시도 성공과 다시 쓴 뒤 성공을 따로 센다. 추가 비용은 사용량 표의 `REDRAFT` · `REDRAFT_CHECK` 행이다.
   Q1 · Q5 · Q11은 이미 여러 번 고치는 데 썼으므로 그 결과는 회귀 확인으로만 읽고, 새 사례 평가와 섞지 않는다.
