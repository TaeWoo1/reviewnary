# 정리 후보 목록 (초안, 2026-10-28)

> 초안이다. 아무것도 지우지 않았다. CLAUDE.md 「기능은 사라지지 않는다」 게이트에 따라 기준선을 먼저 재고, 사장님이 확인한
> 목록만 지운다. 읽기 전용 조사(에이전트) 결과를 옮겨 적은 것이다.

## 1. 기능 기준선 (작업 트리 / HEAD 99b37d0f)

| 측정 | 작업 트리 | HEAD | 측정 명령 (저장소 루트) |
|---|---|---|---|
| backend 엔드포인트(메서드 단위) | 319 (GET 150 · POST 144 · PUT 15 · DELETE 10) | 317 | `grep -rhE '^ +@(Get\|Post\|Put\|Patch\|Delete\|Request)Mapping([( ]\|$)' backend/src/main/java \| wc -l` |
| Flyway 마이그레이션 | 150 (V1–V152, V151·V152 미추적) | 148 | `ls backend/src/main/resources/db/migration \| wc -l` |
| frontend 라우트 | 58 (`<Route … path=` 줄) + `LEGACY_REDIRECTS` 12 | 57 | `grep -cE '<Route\b[^>]*path=' frontend/src/App.tsx` |
| collector CLI | 28 파일, 실제 진입점 12 | 27 / 11 | `ls collector/src/cli/*.ts \| wc -l`; `grep -lE 'invokedDirectly\|import\.meta\.url ===' collector/src/cli/*.ts` − `invoked-directly.ts` |
| backend main `.java` | 1645 | 1636 | `find backend/src/main/java -name '*.java' \| wc -l` |
| vendor-server main | 54 | – | 같은 명령 |
| frontend 비테스트 | 483 | 480 | `find frontend/src \( -name '*.ts' -o -name '*.tsx' \) ! -name '*.test.*'` |
| collector src 비테스트 | 415 | 412 | `find collector/src -name '*.ts' ! -name '*.test.ts'` |
| agent-runtime src 비테스트 | 155 | 155 | 같은 명령 |

재측정 주의: `frontend/src/test/`를 빼면 478이 나온다. 위 명령 그대로 쓴다.

## 2. 규칙 (유지 · 합침 · 폐기)

1. **고칠 결함** — `AgentDraftPrompt.java:82` `[판매자 지침]` 줄이 `\` 연결을 잃어 문장 한가운데 공백 16칸이 두 번 들어간다(c56fe584부터).
2. **문의 지시문 `system()`** — 규칙 24개. 주문 상태 7개(:85–100)를 한 묶음으로 합칠 수 있다. :65 · :69 · :83 「지어내지 말 것」이 겹치고 :120은 :83의 부분집합. 테스트가 일부 문자열을 고정한다(`SpecApplicabilityTest`, `AgentReviewDraftPayloadFloorTest`).
3. **리뷰 지시문 `reviewSystem()`** — 성격 2 + 규칙 20. :320(후기 문장이 결을 정한다)과 :322(「후기의 결」 줄이 우선)가 부딪친다(둘 다 테스트 고정). 「불만엔 차분한 마무리」가 :335 · :343 · `MOOD_*`에 세 번. :346(`【확인`) · :332(`후보`)는 과거 한 사례 패치이며 코드 가드도 따로 있다(`KnowledgeBlanks`, `CANDIDATE_LABEL`). v16에서 더한 문의 :116 · :118은 리뷰 :328 · :330과 같은 말.
4. **코드와 어긋난 주석** — `ReviewTriageRules:97`(본문은 공백 여부만 읽는다 → 3★ 유보 검사가 읽음), `ReviewKnowledgeNeed:44`(극성 판단 안 함 → ★3 칭찬 검사가 함), `ReviewDraftComposer:60-63`(금지 표현 여기서 안 봄 → styleGuard가 봄), `ReviewStyleGuard:16-18`, `ReviewResponseMode.ISSUES_ONLY`·`ReviewResponseRules.DEFAULT`(「문제를 적거나 물으면」 → 지금은 AI 판정), V152 주석(「셋 다 AHEAD」).
5. **`ReviewDraftComposer.praiseOnly`** — 미리 쓰기 순서가 AI 판정으로 바뀌어 외부 호출자가 없다. 공개 범위와 주석을 되돌린다.
6. **「이 리뷰에 무언가 필요한가」를 다섯 곳이 따로 읽는다 (사장님 결정)** — `ReviewTriageRules.tier`(화면 단계, SQL `TRIAGE_TIER_RANK`와 짝) · `OperationsCaseRules.forReview/assertsProblem`(사건 큐) · `AiTriagePilotService#needsResponse`(미리 쓰기) · `ReviewKnowledgeNeed`(판매자에게 기준 묻기) · `praiseOnly`/`moodOf`(검색에서 사실 빼기, 말투). 미리 쓰기만 AI로 옮겼다.
7. **유지** — `ReviewClaimGuard` · `InquiryClaimGuard` · `refusalOf` · `ReviewStyleGuard` 금지 표현 게이트(안전·승인 울타리), 페이로드 하한, `RESERVATION_MARKERS`(SQL 짝 · 테스트 고정), `ReviewComplaintClauses`(검색 질의).

## 3. 문서

1. **보관 이동** — `pilot_readiness_v2.md`(13.7KB, 2026-10-28 `docs/archive/2026-q3/`로 옮김). v3 §24가 대체한다고 적음. 코드 인용 없음, INDEX:181 · evidence INDEX:365에서 링크.
2. **건드리지 않고 보고** — `docs/reviewnary_implementation_goals_draft_v1.md`(132KB, 미추적, 커밋 금지). gitignore에 없고, **커밋된** `docs/FINDINGS.md:938`이 이 파일을 인용해 공개 저장소에 끊긴 링크가 있다.
3. `docs/workstreams/reviewnary_demo_and_ai_readability_goals_v1.md`(23.5KB, 미추적) — 수정된 INDEX.md · product-scope-v1.md가 이미 가리킨다. 함께 커밋하거나 함께 빼야 한다.
4. **유지** — review_triage_contract_v1, operator_graph_v1, knowledge_retrieval_quality_v1(코드·테스트 인용). 보관 후보(불확실): `action-window-runtime/live-import-run-preflight-gotchas.md`, `naver-import-cta-live-runbook.md`, `workstreams/action-window-frontend/live-verification-readiness.md`(문서 링크 1, 코드 0).
5. `docs/archive` 73개 · 1.2MB. 코드가 인용하는 3개(`phase3b`, `phase3c_live_smoke`, `phase0_esm_discovery` — V3 마이그레이션이 인용, 변경 불가). lineage 43개는 모두 코드 인용. `docs/.DS_Store`는 미추적 찌꺼기.

## 4. 코드

1. **폐기 (확신 높음)** — 테스트 밖 사용처가 없는 frontend 모듈: `components/DataBadge.tsx`, `ui/Card.tsx`, `ui/Toolbar.tsx`, `ui/InsightList.tsx`, `lib/homeActions.ts`, `lib/insights.ts`, `hooks/useReviewAttention.ts`, `hooks/useAgentAvailability.ts`, `inbox/InboxFilterRail.tsx`, 테스트만 쓰는 `inbox/InboxList.tsx` · `StatusPill.tsx`, `reviews/ReviewReadDetail.tsx`(24.8KB, 856c8ef4 이후 고아, `visualSystemFence.test.ts`에 등록), `reviews/decision/DecisionLog.tsx`.
2. **폐기 (사장님 결정)** — `backend/.../agent/llm/report/`(클래스 4). 2026-10-06 결정 이후 호출자 없는 모델 출구(`AgentReportService:30-35`). 테스트 · `application.yml` · `pilot.env.example`도 같이 고쳐야 한다.
3. **보류 (죽은 코드 아님)** — 네이버 답글 읽기 실행기(collector 미추적 3개 + 테스트, `aside-guard.test.ts` 수정, backend `targets` from/to) 그리고 런타임이 import하지 않는 옛 Playwright `naver/review-reply-enrichment.ts`. 지우면 CLI 12→11 — 브랜치로 옮겨 보관을 권함.
4. **불확실** — 테스트·문서만 닿는 collector 모듈 약 25개(`local-agent-real-chrome.ts`, `cafe24-api-port.ts`, `coupang-inquiry-reply-engine.ts`, `journey-live.ts`, `adversarial-loop.ts`, `attention-view`, `aside-execution-provider`, `ingestion/consumer`, `esm-trading-cs-ingest`, `esm-producer`, `approval-coordinator`, `sync-outcome-bridge`, `available-range-discovery`, `browser-driver`, `local-agent-catch-up`, `esmplus/capabilities`, `cloud-api-connector-startup`, fixture 드라이버 등). 대부분 06-19~08-14가 마지막 수정. 하나씩 확인 필요. `LEGACY_REDIRECTS`(08-03 「한 릴리스만 유지」)는 IA 동결 결정 사항.
5. **유지** — V151 · V152(V152 주석만 고친다). backend 미참조 클래스 스캔은 Spring 외 고아 0. agent-runtime 깨끗함.
