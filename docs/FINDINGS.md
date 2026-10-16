# FINDINGS — 같은 시행착오를 두 번 하지 않기 위한 장부

**이 문서의 용도는 하나다.** 과거에 시간을 썼거나 틀렸던 것을, 다음 사람(과 다음 세션)이
**5분 안에** 읽고 피하게 한다. 설계 문서가 아니고 상태 보고도 아니다.

**읽는 때.** 라이브 런 전 · 리팩터 전 · "왜 안 바뀌지" 싶을 때. `CLAUDE.md`가 규칙을 싣고,
이 문서는 **그 규칙이 왜 있는지**를 싣는다.

**한 행의 조건 — 넷 다 있어야 적는다.**

1. **무엇을 했나** — 실제로 시도한 것.
2. **무엇이 사실이었나** — 측정된 것. 추측은 적지 않는다.
3. **다음에 지킬 것** — 행동으로 번역된 규칙.
4. **출처** — 커밋 · 문서 · 날짜. 출처 없는 행은 적지 않는다.

**append만 한다.** 틀린 행은 지우지 말고 그 아래에 교정을 적는다 — 틀렸다는 사실 자체가
다음 사람에게 정보다. 행이 늘어 길어지면 `CLAUDE.md`의 규칙으로 **승격**시키고 여기엔
그 포인터를 남긴다.

---

## A. 실행 · 라이브 런

### A1. 오래 떠 있는 dev 프로세스는 버전 핀이다

- **했던 것** — 코드를 고치고, 이미 떠 있는 backend/frontend에 대고 라이브 증명을 했다.
- **사실** — `bootRun`은 devtools가 없고, **시작할 때의 클래스를 계속 서비스한다.** 고친
  코드는 그 프로세스에 들어가 있지 않았다.
- **다음에** — 라이브 증명 전에 backend·frontend를 **재시작한다.** 증명 기록에 그 프로세스의
  시작 시각을 남긴다.
- **출처** — 반복 관측, 운영 메모. 관련: A2.

### A2. Gradle 결과를 파이프로 읽지 않는다

- **했던 것** — `./gradlew test ... | tail`로 결과를 읽었다. **2026-10-12에 또 했다.**
- **사실** — 파이프의 종료 코드는 `tail`의 것이다. 그날은 더 나빴다 — `gradlew`가 루트에
  없어서 쉘이 `exit 127`(명령 없음)을 돌려줬는데, 그 자리에 **전날 19:00에 생성된 XML**이
  남아 있어서 `failures="0"`으로 읽혔다. **테스트는 돌지 않았고 통과로 보였다.**
- **다음에** — ① `gradlew`는 루트가 아니라 **`backend/gradlew`**다. ② 결과는
  `backend/build/test-results/test/TEST-*.xml`에서 읽는다. ③ 읽기 전에 그 XML의
  **mtime을 확인**하거나 미리 지운다. ④ 전체 실행 전엔 `cleanTest`.
- **출처** — 2026-10-12 이 저장소 세션에서 재현. 운영 메모.

### A3. Playwright 브라우저를 `open -a`로 띄우지 않는다

- **했던 것** — 브라우저를 `open -a`로 올렸다.
- **사실** — 런 자신의 컨텍스트 안에 **빈 창 하나가 따로 생긴다.** 그 창은 런의 제어를
  받지 않는다.
- **다음에** — 브라우저는 **프로세스 안에서** 띄운다.
- **출처** — 운영 메모.

### A4. detached 상태의 resident helper는 페어링을 거부한다

- **했던 것** — 백그라운드로 상주시킨 helper로 브라우저 페어링을 시도했다.
- **사실** — `approval_unavailable`로 거부된다. 페어링에는 TTY가 필요하다.
- **다음에** — attach 증명이 필요하면 helper를 **foreground로** 올린다.
- **출처** — 운영 메모.

### A5. 라이브 체크포인트를 채팅 문구로 전진시키지 않는다

- **했던 것** — 조작자가 "N번 됐어"라고 해서 `.ready` sentinel을 건드려 다음 단계로 갔다.
- **사실** — 그 문장은 화면 상태의 근거가 아니었다.
- **다음에** — sentinel을 건드리기 전에 **화면에 무엇이 보이는지 묻는다.** 문구 하나로
  체크포인트를 전진시키지 않는다.
- **출처** — 운영 메모. 규칙 본문: `docs/standing/sellerops_live_approval_contract.md`.

### A6. 토큰 하나 만료로 "불가능"이라 보고하지 않는다

- **했던 것** — 만료된 로컬 토큰을 보고 그 경로가 막혔다고 보고했다.
- **사실** — seed 계정 경로가 살아 있었다. 로컬 데모 로그인
  (`demo@sellerops.ai`)은 NAVER 수집·catch-up을 가진 바로 그 org다.
- **다음에** — "막혔다"고 적기 전에 **seed 계정 경로를 먼저 확인**한다.
- **출처** — 운영 메모.

---

## B. 저장소 · 문서

### B1. 규칙과 지도를 같은 파일에 두면 반드시 다시 커진다

- **했던 것** — 2026-09-28 `cfc2ef42`에서 `CLAUDE.md`를 라우터로 되돌렸다.
  **395,395 → 24,160 bytes (-94%).**
- **사실** — 2주 뒤(2026-10-11) 25,432 bytes였고, 그중 **159줄(44%)이 123항목 문서
  인덱스**였다. 그 사이 `CLAUDE.md`는 12번 수정됐다. 크기는 다시 폭발하지 않았지만
  **지도가 다시 절반을 차지했다** — 한 번 줄여도 지도를 같은 파일에 남겨두면 그 자리가
  다시 찬다.
- **다음에** — 지도는 `docs/INDEX.md`, 규칙은 `CLAUDE.md`. 라우터에 문서 목록을 다시
  쓰지 않는다. 문서를 늘릴 때는 `CLAUDE.md` § Document index의 세 단계 점검을 지킨다.
- **출처** — `cfc2ef42`(2026-09-28) · `e5651272`(2026-10-12).

### B2. 1회성 기록이 새 파일이 되면 한 달에 75개가 생긴다

- **했던 것** — 라이브 런 · proof · calibration · 감사 · closure를 그때마다 새 문서로 적었다.
- **사실** — 2026-09-12 → 10-11 사이 **새 문서 75개(하루 2.5개)**. 그 결과 `docs/`
  최상위에 .md 231개가 평면으로 쌓였고, 그중 **113개는 `CLAUDE.md`의 어느 경로에서도
  도달할 수 없었다.** 즉 쓰는 데 시간을 썼지만 아무도 다시 읽지 않는 상태였다.
- **다음에** — 1회성 기록은 **`docs/evidence/INDEX.md`의 한 행**이다. 새 파일은
  ① evidence 한 행으로 안 되고 ② 레인 현역 문서를 고쳐도 안 담길 때만 만들고, 만들면
  **같은 PR에서 `docs/INDEX.md`에 등재**한다.
- **출처** — `e5651272` · `e52b40fd`(2026-10-12).

### B3. 인덱스가 구판만 가리키면 최신 판단이 보이지 않는다

- **했던 것** — `pilot_readiness_v2`를 인덱스에 두고 `v3`를 만들었다.
  `review_triage_contract_v1`을 인덱스에 두고 `v2`로 개정했다.
- **사실** — 인덱스를 따라 읽는 사람은 **v2와 v1만 읽었다.** `v3`의 판정은
  `NOT_DEPLOYABLE`이었고 인덱스 어디에도 없었다. 2026-10-12 정리에서야 발견됐다.
- **다음에** — `_v2`를 만들면 **v1 항목에 그 사실을 적는다.** v1이 틀렸으면 v2를 만들지
  말고 v1을 고친다.
- **출처** — `e5651272` · `docs/review/review_triage_contract_v2.md` §0.

### B4. 경로 일괄 치환에서 `&`는 접두까지 포함한다

- **했던 것** — `sed 's|docs/NAME|docs/archive/2026-q3/&|g'`로 경로를 옮겼다.
- **사실** — `&`는 **매치 전체**(`docs/NAME`)다. 결과는
  `docs/archive/2026-q3/docs/NAME` — 한 단 겹쳤다. **같은 실수를 같은 날 두 번** 했다
  (두 번째는 `collector/collector/docs/`).
- **다음에** — 치환 후 **중복 접두를 반드시 재점검**한다
  (`grep -r '<새접두>/<옛접두>'`). 가능하면 `&` 대신 전체 경로를 명시한다.
- **출처** — 2026-10-12 세션.

### B5. `collector/` 안의 `docs/<이름>.md`는 깨진 링크가 아니다

- **했던 것** — 루트에서 링크 검사를 돌려 `collector/docs/*.md` 안의 `docs/<이름>.md`를
  "깨짐"으로 보고, `collector/docs/<이름>.md`로 고쳤다.
- **사실** — 그것들은 **그 서브트리 기준 상대경로**로 이미 맞았다. 고친 쪽이 틀렸고
  되돌렸다. `aiagent/docs/...`도 깨진 게 아니라 **공유 git host의 부모 저장소**를 가리키는
  의도된 교차 참조다.
- **다음에** — 링크 검사는 **서브트리 기준**으로 해석한다. `tools/docs-link-check.py`가
  이 두 예외를 알고 있다.
- **출처** — 2026-10-12 세션. 검사기: `tools/docs-link-check.py`.

### B6. 코드가 주석으로 인용하는 문서는 load-bearing이다

- **했던 것** — 도달 불가 문서를 아카이브로 옮기려 했다.
- **사실** — 그중 **43개를 소스·테스트가 주석으로 인용**하고 있었다
  (`agent_real_validation_v1.md` 하나만 24곳). 옮기면 문서 정리가 소스 수백 곳의 변경이 된다.
  2026-10-12 레인 재배치에서는 실제로 **코드 318개 파일**이 함께 바뀌었다.
- **다음에** — 문서를 옮기기 전에 코드 인용을 센다. 옮긴다면 **역변환이 원본과 바이트
  단위로 같은지** 증명한다 — 그러면 주석만 바뀌었음이 증명된다. 파일시스템으로 문서를
  **여는** 코드(예: `ProductTruthReportTest`)는 따로 찾아 테스트까지 돌린다.
- **출처** — `e5651272` · 2026-10-12 레인 재배치.

### B7. 적용된 Flyway 마이그레이션은 주석조차 고치면 안 된다

- **했던 것** — 2026-10-12 문서 레인 재배치에서 경로를 일괄 치환했다. 그 치환이
  `backend/src/main/resources/db/migration/` 안의 **13개 마이그레이션 파일**의 주석에 있는
  문서 경로까지 바꿨다.
- **사실** — Flyway는 적용된 마이그레이션의 **체크섬을 검증**한다. 주석 한 글자만 달라져도
  이미 그 마이그레이션이 적용된 모든 DB에서 `validate`가 깨진다 — 데모 org와 라이브 증명
  org를 포함한 **모든 기존 환경**이다. 커밋 전에 발견해 13개 전부 원본으로 되돌렸다.
- **다음에** — `db/migration/` 안의 파일은 **어떤 이유로도 일괄 치환 대상에 넣지 않는다.**
  그 안의 낡은 문서 경로는 낡은 채로 둔다 — 그것이 올바른 상태다.
  `tools/docs-link-check.py`가 이 경로를 건너뛴다. 저장소 전체 치환을 돌렸다면
  `git diff --name-only -- '*.sql'`로 반드시 확인한다.
- **출처** — 2026-10-12 세션. 관련: B4 · B6.

### B8. 확장자 allowlist만 보면 `.gitignore`와 `Dockerfile`을 놓친다

- **했던 것** — 문서 레인 재배치 후 링크 검사기로 "끊김 0건"을 확인하고 커밋했다.
- **사실** — 검사기와 치환 스크립트 둘 다 **확장자 allowlist**로 파일을 골랐다. 그래서
  확장자가 없거나 점으로 시작하는 파일을 보지 않았고, 그 안의 경로 4건이 낡은 채
  남았다 — `.gitignore`(`design_skills_bootstrap.md` 2곳 + 지워진
  `agent_orchestration_playbook.md`를 지키려는 규칙), `frontend/Dockerfile`,
  `frontend/nginx.conf`(둘 다 `service_readiness_v1.md`). `.gitignore`의 경우는 더
  나빴다 — 방금 삭제한 `.claude/agents/*`와 `orchestrate.md`를 **추적하라는 예외**가
  그대로 남아 있었다.
- **다음에** — 저장소 전체 경로 작업은 **확장자 없는 파일을 명시적으로 포함**한다
  (`.gitignore` · `.dockerignore` · `Dockerfile*` · `Makefile` · `*.conf` · `*.env.example`).
  파일을 지웠으면 **`.gitignore`의 그 파일 예외도 같이 지운다.**
  `tools/docs-link-check.py`가 이제 이 파일들을 본다.
- **같은 날 두 번째로 물렸다 (분석 쪽에서).** "쓸모없는 과거 기록이 남았는지" 감사하면서
  현역 문서 119개의 인용을 셀 때 `grep --include='*.yml'`을 썼다. 실제 파일은 **`.yaml`**이고,
  그 안은 `backend/src/main/resources/product-truth/` — `ProductTruthReportTest`가 검증하는
  **정본 제품 원장**이다. 결과: 문서 33개가 "아무도 인용하지 않는 1회성 기록"으로 잘못
  판정됐고, 7,232줄을 정리 후보로 보고하려던 직전에 숫자가 이상해서 다시 확인했다.
  바로 세어 보니 **고립 0개 / 119** — 42개는 원장과 코드가, 77개는 코드나 문서가 인용한다.
  **확장자 allowlist는 파일을 놓치는 데 그치지 않고 결론을 뒤집는다.** 지우자는 판단의
  근거가 될 때는 더 그렇다.
- **다음에, 하나 더** — 인용을 세어 무언가를 지울지 판단할 때는 `--include`를 쓰지 않는다.
  `grep -rl <이름> . | grep -v node_modules | grep -v '/\.git/'`로 **전부** 세고 나서
  출처를 분류한다. 그리고 `grep -c`가 0을 세면 **exit 1**이므로 `&&` 연쇄 안에서 뒤가
  조용히 실행되지 않는다 — 같은 세션에서 두 번 겪었다(`.gitignore`의 `.DS_Store` 줄을
  "없다"고 읽은 것도 이것이다).
- **출처** — 2026-10-12 세션, `8d9a2310` 직후 감사에서 발견. 관련: B4 · B6 · B7.

### B9. 선언된 게이트가 red인 채로 커밋이 쌓일 수 있다

- **했던 것** — `collector/`에 문서 변경을 커밋하기 전에, `collector/CLAUDE.md` §2가
  요구하는 대로 `npm run typecheck`를 돌렸다.
- **사실** — **HEAD부터 이미 red였다.** 오류 9개 — `src/`는 0개, 전부 테스트 파일이다:
  `test/aside/naver-review-selector-grammar.test.ts` 5개(TS2339 — 테스트가 기대하는
  `queryLabelled` 필드가 구현 반환 타입에 없다) · `test/naver/review-list-attachments.test.ts`
  3개 + `test/action-window/reply-submission/reply-session.test.ts` 1개
  (TS2532/TS18048 — `noUncheckedIndexedAccess` 인덱스 접근). `collector/CLAUDE.md` §2는
  "`typecheck`와 `test`가 커밋 전 green이어야 한다"고 적고 있고
  `.github/workflows/collector-ci.yml`도 `npm run typecheck`를 돌린다. 규칙과 CI가 둘 다
  있는데도 red인 채로 지나갔다.
- **다음에** — ① 패키지에 커밋하기 전 그 패키지의 게이트를 **먼저** 돌린다. 내 변경이
  깨뜨린 것인지 기존 상태인지는 `git stash` 후 HEAD에서 같은 명령을 돌려 가른다.
  ② 부분 수정을 문서 정리 커밋에 섞지 않는다 — 고칠 수 없는 것이 남으면 별도 커밋으로
  닫거나 보고한다.
- **근본 원인, 그리고 왜 또 생길 수 있었나** — TS2339 5개는 제품 판단이 필요한 드리프트가
  아니었다. 구현
  (`src/naver/review-list-observe-inpage.ts`)은 `dateFormFound`와 `queryLabelled`를
  **실제로 반환**하고 있었고(§435–452), 문서 주석도 그 둘을 열거하고 있었다. 틀린 쪽은
  테스트였다 — `census` 헬퍼가 결과 모양을 **인라인 `as {…}` 캐스트로 자기 파일 안에서
  다시 선언**하고 있었고, 2026-10-08/09에 스크립트가 두 필드를 얻는 동안 그 캐스트가
  따라오지 않았다. 캐스트는 `openers`도 빠뜨리고 있었다.
  이 모양이 손으로 유지돼야 하는 이유는 하나다 — 그 스크립트는 **문자열로 만들어져
  페이지에서 평가**되므로 TypeScript가 모양을 도출할 수 없다.
- **고친 방식 (2026-10-12)** — 캐스트에 필드를 더하지 않고, 모양의 **소유자를 하나로** 만들었다.
  구현 모듈이 `NaverReviewControlsCensus` 타입을 내보내고 테스트는 그것을 쓴다. 이제
  스크립트에 필드를 더하면 그 타입에도 더해야 하고, 두 번째 선언이 없으므로 다시 어긋날
  자리가 없다. 나머지 7개는 `collector/CLAUDE.md` §5의 문서화된 관용구(`ranked[0]!`)로 닫았다.
  **결과: `npm run typecheck` 0 오류 · `npm test` 10,106개 중 9,946 통과 · 0 실패.**
  contracts · frontend · agent-runtime typecheck도 함께 확인했고 전부 green이었다 —
  red였던 것은 `collector` 하나였다.
- **남는 교훈** — 한 모양을 두 곳에서 선언하면 한 곳은 반드시 낡는다. 생성된 코드(문자열
  스크립트, 템플릿, 외부 경계)의 모양은 **생성하는 쪽이 타입으로 내보내고** 소비하는 쪽이
  그것을 import한다. 테스트 안의 인라인 구조 캐스트는 그 자체로 드리프트 예고다.
- **출처** — 2026-10-12 세션. `collector/CLAUDE.md` §2·§5 ·
  `.github/workflows/collector-ci.yml` · `src/naver/review-list-observe-inpage.ts`.

### B10. fence가 디렉터리를 평면으로 읽으면 하위 폴더는 무방비다

- **했던 것** — `ConversationService.ts`(4,710줄)를 쪼개기 전에, 그 파일의 **소스를 읽는**
  테스트가 있는지 확인했다. 다섯 개가 있었고 그중 `conversationWriteFence`가 레인 전체를
  검사한다고 적혀 있었다 — "No file under `src/conversation/` references a backend method that
  writes toward a channel or the privileged plane."
- **사실** — 그 테스트의 파일 수집은 `readdirSync(DIR)` 한 줄, **재귀가 아니었다.**
  그래서 `src/conversation/graph/`의 두 파일(`turnGraph.ts` · `TurnState.ts`, 12KB)은
  **한 번도 검사된 적이 없다.** 실제로 `turnGraph.ts:84`의 `phases.resume(` 는 FORBIDDEN
  패턴 `/\.resume\s*\(/`와 일치하는데 아무 일도 일어나지 않았다.
  확인 방법: `graph/TurnState.ts`에 `spring.confirmPublish(1)`을 한 줄 넣고 fence를 돌렸다 —
  **green이었다.** 재귀로 고친 뒤 같은 줄을 넣으니
  `graph/TurnState.ts matches /\bconfirmPublish\b/`로 실패했다.
- **왜 이게 가장 나쁜 종류인가** — fence가 없는 것보다 나쁘다. 없으면 아무도 보호받는다고
  믿지 않는다. 있고 green이면 **그 초록 표시가 증거로 읽힌다.** 이 fence는 문서 주석에
  "레인 전체"라고 적어 두기까지 했다.
- **다음에** — ① 소스를 스캔하는 fence는 **반드시 재귀**로 수집한다. 같은 저장소의
  `plannerFence`/`privilegedPlaneFence`는 `statSync`로 재귀 순회하는 관용구를 이미 쓰고
  있었다 — 셋이 같은 일을 하면서 하나만 평면이었다. ② fence를 고치면 **일부러 위반을
  주입해 실패하는 것을 확인한다.** 통과는 fence가 일한다는 증거가 아니다.
  ③ 코드를 옮기기 전에 그 파일을 **소스로 읽는** 테스트를 먼저 찾는다 — 옮기면 fence가
  green인 채로 아무것도 검사하지 않게 될 수 있다.
- **남은 예외 하나** — `graph/turnGraph.ts`의 `/\.resume\s*\(/`는 명시적으로 허용했다.
  `phases.resume(...)`는 그래프가 **자기 phase**를 부르는 것이고(노드 표 → 서비스),
  백엔드의 publish resume과 방향이 반대다. 패턴은 모양으로 둘을 구분할 수 없으므로 그
  **한 경로 · 한 패턴**만 좁게 허용했다 — `graph/` 안 다른 곳의 진짜 `spring.resume(`는
  여전히 실패한다.
- **출처** — 2026-10-12 세션. `test/conversation/conversationWriteFence.test.ts` ·
  `test/operator/plannerFence.test.ts`.

### B11. 큰 파일이 "그냥 큰" 것인지 **계약으로 고정된** 것인지 먼저 본다

- **했던 것** — `ConversationService.ts`(4,710줄)를 쪼개려 했다. 가장 큰 메서드
  `routeSend()`가 혼자 1,321줄(파일의 28%)이니 거기부터가 당연해 보였다.
- **사실** — 그 파일은 **네 개의 fence가 심볼 단위로 고정**하고 있었다.
  `semanticOwnership`은 `countIn("conversation/ConversationService.ts", "answer.axis") === 1`,
  `procedureLayer`는 `honestZero(` 1회 · `inquiryDraftPrecondition(` 3회 ·
  `reviewDraftPrecondition(` 2회, `turnGraphOwnership`은 phase별 `this.<phase>(` 호출 수와
  `selectProcedure(` 3회를 센다. 즉 **"이 개념은 이 파일이 소유한다"가 테스트로 선언돼
  있다.** 파일이 큰 것은 사고가 아니라 부분적으로 설계다.
  그리고 `reviewApprovalDeepLink`는 `routeSend`의 **본문을 잘라내어**
  (`SOURCE.slice(start, indexOf("\n  private ", …))`) 승인·전송 경로가 없음을 검사한다 —
  쪼개면 slice가 첫 조각에서 끝나므로 **fence는 green인 채로 일부만 읽는다**(§B10과 같은
  실패 모드).
- **다음에** — ① 큰 파일을 쪼개기 전에 그 파일을 **소스로 읽는 테스트를 모두 찾고**, 무엇을
  몇 번 세는지 적어 둔다. ② 고정된 심볼을 옮기는 것은 **소유권 재선언**이므로 제품·구조
  판단이다 — 조용히 하지 않는다(`CLAUDE.md` § Assumption rule). ③ 어느 fence도 이름을
  대지 않는 **순수 심볼부터** 옮긴다. ④ 본문을 잘라 읽는 fence는 옮기기 전에
  **파일 전체 스캔으로 보강**한다 — 그것이 분해 후에도 살아남는 쪽이다.
- **이번에 한 것** — ④를 먼저 했다(`reviewApprovalDeepLink`에 승인·전송 토큰의 파일 전체
  단정을 추가; slice 단정은 의도를 적어 두므로 남겼다). 그다음 ③으로 **셀러가 읽는 문구
  전체**를 `src/conversation/sellerSentences.ts`로 옮겼다 — 144줄, 상수와 순수 함수만,
  어느 fence도 이름을 대지 않는 것들이다. 문구 하나를 고치려고 4,580줄 파일을 열 필요가
  없어졌다. `routeSend` 분해는 **열린 결정**으로 남겼다.
- **출처** — 2026-10-12 세션. `test/conversation/reviewApprovalDeepLink.test.ts` ·
  `test/operator/semanticOwnership.test.ts` · `test/operator/procedureLayer.test.ts` ·
  `test/conversation/turnGraphOwnership.test.ts`.

### B12. `slice(start, indexOf(...))`는 종료 표시가 없으면 조용히 넓어진다

- **했던 것** — `routeSend()`를 쪼개려고 그 메서드를 지키는 fence
  (`reviewApprovalDeepLink`)가 무엇을 읽는지 확인했다. 코드는
  `SOURCE.slice(start, SOURCE.indexOf("\n  private ", start + 10))` — "routeSend의 몸통"이라고
  적혀 있었다.
- **사실** — `routeSend`는 클래스의 **마지막** `private` 멤버다. 그래서 그 `indexOf`는
  **`-1`**을 돌려주고, JS의 `slice(start, -1)`은 "끝에서 한 글자 앞까지"라서 슬라이스가
  **파일 끝까지** 달렸다. fence는 한 메서드의 **120줄**을 읽는다고 적어 두고 실제로
  **1,187줄**(파일의 26%)을 읽고 있었다. `expect(block).toContain("replyApprovalStateOf")`가
  통과한 이유도 routeSend와 무관할 수 있었다.
- **왜 §B10과 다른가** — §B10은 **덜 읽는** 실패였다(하위 폴더를 안 봄). 이것은 **더 읽는**
  실패다. 방향은 반대지만 증상은 같다 — **fence가 자기가 읽는다고 적은 것을 읽지 않고,
  green은 그대로다.** 세 번째로 같은 자리에서 나왔다.
- **다음에** — ① 경계를 찾는 코드는 **못 찾았을 때 실패**해야 한다. `indexOf` 결과를
  `slice`에 바로 넣지 않고 `> -1`을 확인한다. ② 슬라이스를 쓰는 fence에는 **크기 상한**을
  둔다 — 한 메서드를 읽는다면 400줄 미만 같은 느슨한 bound만으로도 26%로 번진 것이 잡힌다.
  ③ 고친 뒤 **좁아진 것을 숫자로 확인**한다(1,187 → 120).
- **고친 방식 (2026-10-12)** — `methodBody(signature)` 헬퍼를 두고, 시작을 못 찾으면 실패,
  종료 후보(다음 멤버 선언 네 가지와 클래스 닫는 중괄호) 중 **가장 앞**을 쓰고, 후보가
  하나도 없으면 실패한다. 금지 토큰을 routeSend 안에 주입해 **잡히는 것을 확인**했다
  (파일 전체 단정과 슬라이스 단정 둘 다 실패했고, 주입은 되돌렸다).
- **출처** — 2026-10-12 세션. `test/conversation/reviewApprovalDeepLink.test.ts`. 관련: B10 · B11.

### B13. 메서드 크기를 재는 방법을 두 번 틀렸다

- **했던 것** — "`ConversationService.ts`에서 가장 큰 메서드"를 재어 리팩터 대상을 골랐다.
  두 번 재고 두 번 틀렸으며, 그 숫자로 작업 계획을 세워 보고까지 했다.
- **사실** — ① 첫 측정은 `awk`로 "들여쓰기 2칸 + 괄호"를 메서드 선언으로 봤는데
  `log("conversation_turn", {` 같은 **호출**까지 선언으로 잡혀, 잘못된 두 경계 사이의 모든
  줄이 한 메서드에 몰렸다 → `routeSend` **1,321줄**이라는 허수. ② 두 번째는 중괄호
  매칭으로 고쳤는데, 반환 타입 `Promise<{ artifact: Artifact; … }>`의 중괄호를 본문의
  끝으로 봐서 → `routeSend` **4줄**이라는 반대쪽 허수. **실제는 120줄**이고 가장 큰 것은
  `compose` 559줄이다.
- **다음에** — 구조를 세는 스크립트는 **합계로 교차 검증**한다. 세 번째 측정은 메서드
  선언 간격의 합(3,185줄)이 클래스 본문 크기(3,197줄)와 맞는 것을 확인한 뒤에야 믿었다.
  합이 맞지 않으면 경계 검출이 틀린 것이다. 그리고 **측정값으로 계획을 말하기 전에**
  한 건을 눈으로 확인한다 — `routeSend`를 한 번 열어 봤다면 1,321도 4도 바로 틀렸다.
- **출처** — 2026-10-12 세션. 관련: B8(확장자 allowlist가 결론을 뒤집은 것)과 같은 종류 —
  **측정 도구의 결함이 측정값이 아니라 결론으로 나타난다.**

### B14. 큰 파일을 쪼갤 때 `tsc`가 의존성 목록을 대신 만들어 준다

- **했던 것** — `ConversationService.ts`(4,710줄)에서 모듈 레벨 헬퍼를 세 묶음으로 나눠
  옮겼다. 묶음마다 "이 함수가 무엇에 의존하는지"를 먼저 읽어 import 목록을 손으로 만들려
  했다.
- **사실** — 손으로 만든 목록은 매번 틀렸고, 매번 `tsc --noEmit`가 **정확히 무엇이 빠졌는지
  이름으로** 알려줬다. 세 묶음에서 각각 1 · 2 · 3개가 틀렸다 — `ResolvedTarget`은
  `./contract`가 아니라 서비스 안의 비공개 타입이었고, `DraftTarget`/`ReviewDraftTarget`은
  `./DraftPreparer`에 있었고, `InquiryDetailArtifact`는 `./contract`에 있었다. 읽어서
  맞히려는 쪽이 느리고 덜 정확했다.
- **다음에** — 모듈을 분리할 때는 **먼저 옮기고 `tsc`에게 묻는다.** 블록을 그대로 옮기고
  빈 import로 시작해, 컴파일러가 대는 이름을 하나씩 채운다. 타입이 강한 저장소에서
  컴파일러는 의존성 분석기이고, 그 일을 사람이 대신하면 오류가 는다. 단 **옮기는 블록의
  경계는 여전히 사람이 확인**해야 한다 — 그건 `tsc`가 모른다(§B13).
- **곁따라 나온 규칙** — 옮긴 쪽이 **타입의 소유자**가 되는 것이 맞다.
  `ResolvedTarget`과 `SelectedInquiryRow`는 그것을 만드는 함수들과 함께 `historyReaders.ts`로
  갔고 서비스가 되가져온다. 서비스에 남겨 두고 새 모듈이 import하면 순환이 된다.
- **검증 방식** — 리팩터마다 ① `tsc --noEmit` 0 ② 전체 테스트 수가 기준선과 같음
  ③ **fence가 세는 심볼 카운트가 그대로**(`answer.axis` 1 · `honestZero(` 1 ·
  `inquiryDraftPrecondition(` 3 · `reviewDraftPrecondition(` 2 · `selectProcedure(` 3) —
  ③이 같은 파일 안 분해와 모듈 분리를 가르는 선이다(§B11).
- **공유 상수는 양쪽이 아니라 **제3의 파일**이 가진다** — `WORKSPACE_OF`(작업 집합별 화면)는
  남아 있는 `suggestionsFor`와 옮긴 `workspaceFor`가 **같이** 썼다. 서비스에 남겨 두고 새
  모듈이 import하면 순환이 되므로, 둘 다 쓰는 `turnArtifacts.ts`로 옮기고 양쪽이 가져간다.
  "누가 쓰는가"가 아니라 **"무엇에 대한 것인가"**로 집을 정한다.
- **출처** — 2026-10-12 세션.
  `src/conversation/{sellerSentences,historyReaders,turnArtifacts,workingSet,turnGrounding}.ts`.

### B15. `this`를 쓰지 않는 메서드는 이미 함수다 — 그리고 가장 큰 메서드가 가장 쪼갤 만한 것은 아니다

- **했던 것** — `ConversationService.ts`를 줄이려고 **가장 큰 메서드**부터 찾았다
  (`compose` 559줄 · `routeSend` 295줄 · `directLane` 248줄 · `applyVisibleFilter` 148줄).
- **사실** — 크기는 쪼갤 만한지와 거의 무관했다. 가른 것은 **`this` 사용 횟수**다.
  `compose`는 `this`를 10번 쓰고 더 중요하게는 `headline` · `selected` · `pendingCapture` ·
  `trailingQuestion` · `extraChips`를 **여러 분기가 함께 고치는** 선형 파이프라인이다.
  바로 `return`하는 lane은 세 개(41줄)뿐이고 나머지는 전부 공유 지역변수를 건드린다.
  반면 `resolveTargets`(87줄)는 **`this`를 한 번도 쓰지 않았다** — 이미 함수였고 메서드처럼
  보였을 뿐이다. `applyVisibleFilter`(148줄)는 `this.now()` 한 번뿐이다.
- **다음에** — 큰 클래스를 줄일 때 정렬 기준은 줄 수가 아니라 **`this` 사용 횟수(오름차순)**다.
  `grep -c 'this\.'`로 메서드별로 세면 "이미 함수인 것"이 먼저 나온다. 그것들을 먼저 빼면
  남은 클래스가 작아지고, 진짜로 상태를 들고 있는 메서드만 남는다.
- **`compose`를 쪼개지 않은 이유** — 쪼개려면 공유 지역변수를 담는 상태 객체를 만들어 각
  lane이 그것을 변형하게 해야 한다. 그것은 정리가 아니라 **대화 조립 모델의 재설계**이고,
  순서·변형에 숨은 버그가 들어갈 자리가 넓다. 1,054개 테스트가 좋은 안전망이지만 compose의
  동작 공간은 그보다 넓고, 무엇보다 **조용히 할 결정이 아니다**
  (`CLAUDE.md` § Assumption rule · § 리팩터 게이트 5).
- **곁따라 나온 측정 함정** — 선언 간격으로 메서드 크기를 재면 **클래스의 마지막 메서드만
  부풀려진다**(그 뒤의 모듈 레벨 코드까지 더해진다). `routeSend`가 120줄로도, 295줄로도
  나온 이유다. 마지막 메서드는 **클래스 닫는 중괄호**를 끝으로 써야 한다. §B13의 세 번째 변종.
- **출처** — 2026-10-12 세션. `src/conversation/targetResolution.ts` · 관련 B11 · B13 · B14.

### B16. fence의 상한을 절대 줄 수로 적으면, 지키던 파일이 작아질 때 조용히 무력해진다

- **했던 것** — §B12에서 `reviewApprovalDeepLink`의 슬라이스가 EOF까지 흐르던 결함을 고치면서
  두 겹으로 막았다. ① 종료 표식이 하나도 없으면 **실패**시킨다 ② 슬라이스가
  **400줄 미만**인지 본다. 그때 폭주 값이 1,186줄이었으니 400은 "충분히 여유롭고 폭주와는
  멀다"고 적어 뒀다.
- **사실** — 같은 세션에서 `ConversationService.ts`를 4,710 → 3,439줄로 쪼갰다. 그러자
  `routeSend` 시작점에서 EOF까지가 **295줄**이 되었다. 즉 **고치려던 바로 그 폭주가 이제
  상한 400을 통과한다.** ②는 보호처럼 읽히지만 아무것도 막지 않는 상태로 남아 있었다.
  막아 주던 것은 ① 하나뿐이었다. 마지막 검토에서 종료 표식을 세어 보고
  (`ends.length === 1`) 발견했다.
- **다음에** — fence의 경계는 **절대 수가 아니라 성질로** 적는다. "400줄 미만"이 아니라
  "**그 파일의 나머지보다 짧다**" — `slice 길이 < start부터 EOF까지의 길이`. 그러면 파일이
  커지든 작아지든 뜻이 유지된다. 증명은 양쪽으로 한다: 경계를 고의로 없애
  (`slice(start)`) **실패**하고(`295 < 295` 불성립), 본문에 금지 심볼을 넣어 **실패**하고,
  그때 `tsc`가 0이어서 구문 오류가 아니라 **텍스트 위반**을 잡았음을 같이 보인다.
- **일반화** — 리팩터로 **지키는 쪽이 아니라 지켜지는 쪽이 변하면** fence의 숫자가 상한으로서의
  뜻을 잃는다. 숫자를 쓴 fence는 그 숫자가 어디서 나왔는지 주석에 적고, 파일 크기가 크게
  바뀐 뒤에는 그 숫자를 다시 본다.
- **출처** — 2026-10-12 세션 마지막 검토.
  `agent-runtime/test/conversation/reviewApprovalDeepLink.test.ts` · §B12를 잇는다.

### B17. 링크 검사기가 `docs/` 없는 경로를 못 봐서, 레인 정리로 끊긴 36건이 green으로 통과했다

- **했던 것** — 2026-10-12에 문서를 레인 폴더(`docs/standing/` · `docs/lineage/` ·
  `docs/channel/` …)로 옮기고 `tools/docs-link-check.py`를 돌려 **끊김 0건**을 확인했다.
  그 숫자를 보고 "링크는 다 살아 있다"고 보고했다.
- **사실** — 검사기의 참조 정규식은 `[A-Za-z0-9_./-]*docs/[A-Za-z0-9_./-]+\.md`다. **경로에
  `docs/` 가 들어가야 매칭한다.** 그래서 마크다운 링크의 목적지가
  `./x.md` 나 `../x.md` 처럼 **`docs/` 없이 적힌 상대 경로면 아예 스캔 대상이
  아니었다.** 마지막 검토에서 따로 세어 보니
  **36건이 끊겨 있었다** — 전부 내가 옮긴 파일들이다
  (`docs/lineage/…` 안에서 `./sellerops_live_approval_contract.md`를 가리키는 식).
  검사기는 끝까지 0을 보고했다. **0은 "없다"가 아니라 "내가 보는 범위에 없다"였다.**
- **같이 드러난 것** — 이름으로 지목만 하는 `` `r4-preparation.md` `` 형태도 검사되지
  않았다. `39186802`(2026-08-19, 49개 폐기)이 **링크 목적지는 후임 문서로 바꾸고 라벨은
  폐기된 이름 그대로** 둬서, 라벨 ≠ 목적지 링크가 **61건** 남아 있었다. 그리고
  `docs/` 문서 349개 중 **4개는 저장소 어디에서도 가리켜지지 않았다** — 그중
  `action-window-runtime/account-scoped-persistent-session-runtime.md`는 **지금 돌아가는
  코드**(마이그레이션 `V30 account_session_slot`,
  `ChannelReviewAcquisitionService`가 쓰는 `AccountSessionSlotService.resolveSlot`)의
  유일한 설계 기록이었다. 쓰이는 기능의 설계가 도달 불가 상태였다.
- **다음에** — 검사기를 믿기 전에 **검사기가 무엇을 안 보는지 먼저 센다.** 통과 숫자만
  보지 말고, 같은 대상을 **다른 방법으로 한 번 더 세어** 두 숫자를 맞춰 본다. 숫자가
  다르면 틀린 쪽은 보통 검사기다. §B10(fence가 자기가 읽는다고 말한 것보다 덜 읽음) ·
  §B12(더 읽음) · §B16(상한이 뜻을 잃음)과 **같은 병**이다 — 검사 도구의 실패는 빨간색이
  아니라 **초록색으로** 나타난다.
- **고친 것** — 검사기에 패스 둘을 더했다. ② 목적지가 `./…` · `../…` 인 마크다운
  링크를 그 파일 위치 기준으로 해석한다 ③ 백틱 안의 맨 이름이 저장소에 없고 폐기 등재도 없으면
  잡는다. 둘 다 **양방향으로 증명**했다 — 고친 링크를 되깨뜨려 `exit 1`, 없는 이름을 넣어
  `exit 1`, 그 이름을 `lineage_index.md`에 등재하면 `exit 0`. 36건은 역변환으로 **링크
  경로 문자열만 바뀌었음을 20개 파일 바이트 동일**로 증명하고 고쳤다. 폐기된 이름 12개와
  내 삭제 6개를 `lineage_index.md`에 승계처까지 적어 등재했다. 라벨 61건은 **고치지
  않았다** — 대부분 라이브 런 증거 기록이고, 그때 그 문서를 인용했다는 사실이 기록의
  일부다. 승계 표를 `lineage_index.md`에 두는 쪽을 택했다.
- **출처** — 2026-10-12 세션 마지막 검토. `tools/docs-link-check.py` ·
  `docs/lineage_index.md` · `docs/action-window-runtime/HANDOFF.md` ·
  `docs/workstreams/action-window-frontend/README.md`.

### B18. 울타리의 경계를 주석에 두면, 그 주석을 옮기는 리팩터가 울타리를 14.7배 넓힌다 — 초록색으로

- **했던 것** — `ae0205ef`(2026-10-12)에서 `resolveTargets`를 `targetResolution.ts`로 옮겼다.
  딸린 docblock `/** Which objects a draft/send sentence points at …`도 같이 갔다.
  `tsc` 0 · 1,054 테스트 통과 · 고정 심볼 5/5를 확인하고 보고했다.
- **사실** — `conversationWriteFence`의 `decideCapture` 슬라이스가 **그 주석 문장을 종료
  표식으로** 쓰고 있었다. 주석이 사라지자 `indexOf`가 `-1`을 돌려주고
  `slice(start, -1)`이 EOF까지 흘렀다. 울타리가 읽는 범위가 **102줄 → 1,501줄, 14.7배**로
  넓어졌다. 그런데 그 블록의 양성 단정(`toMatch`)은 **넓어질수록 쉽게 통과**하고 금지
  목록은 마침 그 범위에 없어서, **테스트는 끝까지 초록이었다.** 고정 심볼 카운트도
  못 잡는다 — 그건 심볼을 세고, 경계는 **주석**이었다.
- **다음에** — **울타리의 경계는 읽는 대상의 구조여야 한다.** 클래스 들여쓰기의 다음 멤버,
  또는 클래스 닫는 중괄호. 주석 문장 · 이웃 선언의 이름 · 고정 글자 수는 경계가 아니다.
  경계를 못 찾으면 **넓어지지 말고 던진다.** 그래서
  `agent-runtime/test/support/sourceSlice.ts`를 만들어 세 울타리가 같이 쓰게 했다 —
  `memberBody()`(한 멤버만) · `between()`(두 표식, 둘 다 있고 순서까지 맞아야) ·
  `memberEnd()`.
- **같이 고친 같은 병 셋**
  - `turnGraphOwnership`의 `slice(from, from + 2600)` — **고정 2,600자 창.** 세 메서드를
    각각 **719 · 1,676 · 1,125자**씩 이웃까지 침범했다. `phaseProcedure`와
    `selectPostPlanProcedure`는 붙어 있어서, 호출이 한쪽에서 다른 쪽으로 옮겨가는 날
    창은 여전히 찾아내고 울타리는 초록으로 남는다. 지금은 거짓이 아니었지만 **잠복**이었다.
  - `.not.toContain`을 **빈 슬라이스**에 걸면 공허하게 통과한다.
    `slice(indexOf(a), indexOf(b))`는 `b`가 `a` 위로 올라가는 순간 `""`가 된다 —
    `between()`이 순서까지 거부한다.
  - `reviewApprovalDeepLink`의 `indexOf("};", start)`에 표식 존재 단정이 없었다.
- **전수 주입 테스트** — "읽는다고 적힌 것을 실제로 읽는가"는 통과 여부로 알 수 없어서,
  울타리마다 금지된 것을 **직접 넣어 보고** 되돌렸다. 7개 전부 물었다 —
  `conversationWriteFence`(하위 폴더 포함) · `reviewApprovalDeepLink`(`tsc` 0인 상태로) ·
  `plannerFence`(키워드 표 · 문장 매칭) · `semanticOwnership`(금지 단어표 · `answer.axis`
  두 번째 사용 · `senseOf(` 소유 파일 추가) · `procedureLayer`(`honestZero(` 두 번째) ·
  `privilegedPlaneFence`(도구 이름에 `sync`) · `turnGraphOwnership`(phase 호출 2곳).
  디렉터리 순회 6개는 전부 재귀임을 확인했다(§B10 형태 없음).
- **주입이 안 물었는데 울타리가 옳았던 경우 3번** — 내 주입이 틀렸다. ① `semanticOwnership`의
  `CODE`는 **주석을 제거**한 소스다(이웃의 설명 때문에 실패하는 울타리는 고치는 게 아니라
  지운다는 설계). 주석으로 주입하면 안 걸리는 게 맞다. ② `privilegedPlaneFence`는 소스
  텍스트가 아니라 **등록된 도구 이름**을 읽는다. ③ `turnGraphOwnership`은 `this.phaseX(`
  형태를 센다. **"울타리가 안 물었다"를 결함으로 보고하기 전에 그 울타리가 무엇을 읽는지
  먼저 읽는다.**
- **아직 감사하지 않은 범위 (2026-10-13 측정, 열림)** — 위 작업은 `agent-runtime`의 울타리
  7개만 다뤘다. 같은 형태가 다른 패키지에 남아 있고, 숫자는 이렇다.

  | 패키지 | 소스를 읽는 테스트 | 양성 단정 슬라이스 | 고정 크기 창 | 경계가 주석 |
  |---|---|---|---|---|
  | `agent-runtime` | 19 | **0** (고침) | **0** (고침) | **0** (고침) |
  | `collector` | 33 | **57** | 2 | 6 |
  | `backend` | 76 | 1 | 1 | 0 |
  | `frontend` | 0 | — | — | — |

  **양성 단정(`toContain`/`toMatch`/`contains`)을 거는 슬라이스만 위험하다** — 넓어질수록
  쉽게 통과한다. 부정 단정만 거는 슬라이스는 넓어지면 **실패**하므로 보수적이다. 그래서
  `collector`의 57건이 실제 잔여 표면이고, 대부분
  `test/action-window/coupang-issuance/`와 `test/action-window/initial-import/`에 몰려 있다.
  `backend`는 울타리 대부분이 **파일 전체**에 `.contains(`를 걸어(1,355회) 이 병에 걸리지
  않는다 — 전체 스캔이 슬라이스보다 안전하다는 증거다.

  다시 세는 방법: 슬라이스 선언을 찾고, 그 변수에 걸린 단정의 **극성**으로 분류한다.
  **분류기를 언어별로 맞춰야 한다** — 처음에 `const|let`만 보는 정규식을 Java에 돌려
  "backend 0건"이 나왔다. 0은 또 "내가 보는 범위에 없다"였다(§B17).
- **출처** — 2026-10-13. `agent-runtime/test/support/sourceSlice.ts` ·
  `test/conversation/{conversationWriteFence,turnGraphOwnership,reviewApprovalDeepLink}.test.ts` ·
  §B10 · §B12 · §B16 · §B17을 잇는다. 넷 다 같은 병이다 — **검사 도구의 실패는 빨간색이
  아니라 초록색으로 나타난다.**

---

## C. 증거 · 계약

### C1. 연결되지 않은 proof는 상태를 거짓으로 만든다

- **했던 것** — Coupang `ORDER_SUMMARY`를 라이브로 증명하고 proof 문서를 랜딩했지만
  `docs/evidence/INDEX.md`에 행을 넣지 않았다.
- **사실** — 그 capability는 **2주 동안 "인증 골격만"으로 기록된 채** 있었다. 증명은
  존재했고 아무도 찾지 못했다.
- **다음에** — 라이브 런은 **그 proof를 랜딩하는 같은 PR에서** `docs/evidence/INDEX.md`에
  행을 갖는다. 행 없는 proof는 결함이다.
- **출처** — `docs/channel/channel_integration_completeness_audit_v1.md` §5 · `CLAUDE.md`
  Evidence rule.

### C2. 계약이 존재하지 않는 문서를 자기 근거로 인용할 수 있다

- **했던 것** — `contracts/review-id-fingerprint/v1/SPEC.md`가 식별자의 근거로
  `docs/review_acquisition.md` §S와 `docs/action-window-runtime/r4-review-id-trace.md`를 인용했다.
- **사실** — 둘 다 저장소에 없다. §S는 `e1550892`(2026-07-22, legacy VOC 분리)에서
  삭제됐고 **git에서 복구 가능**하다 — `git show e1550892^:docs/review_acquisition.md`
  (372줄, §S는 126행부터). trace 문서는 **git의 어느 ref에도 존재한 적이 없다.** 기존
  드리프트 노트는 §S가 "보존된 runtime worktree에만 남아 있다"고 적었는데 **틀렸다.**
- **다음에** — 계약이 외부 문서를 provenance로 삼으면, 그 문서는 **같은 저장소에**
  있어야 한다. 없는 문서 이름은 `docs/lineage_index.md`에 삭제 커밋과 함께 등재한다.
  §S 재수입 여부는 **아직 열린 제품 오너 결정**이다.
- **출처** — `e52b40fd`(2026-10-12) · `docs/lineage_index.md`.

### C3. 정본 문서가 사실이 아닌 것을 적고 있을 수 있다

- **했던 것** — reading path stop #1(`sellerops_canonical_reference.md`) §9.7을 믿었다.
- **사실** — 그 절은 VOC 계열이 "several retain live code at repo root
  (`app_industrial_review_ops.py`, `src/voc/`, `cardnews/`)"라고 적고 있었다. **세 경로
  모두 존재하지 않는다** — `e1550892`에서 분리됐다. capability 표(stop #3)의 MANUAL 행도
  증거로 삭제된 `sellerops_phase2.md`를 대고 있었다.
- **다음에** — 정본 문서가 **파일·경로의 존재를 주장**하면 인용하기 전에 확인한다.
  어긋나면 `CLAUDE.md` § Conflict priority대로 **보고한다** — 조용히 고쳐 제품 의도를
  재정의하지 않는다. capability 표의 **status는 건드리지 않는다**(증거 칸만 고친다).
- **출처** — `e52b40fd`(2026-10-12).

---

## D. 다른 곳에 있는 finding

같은 성격의 장부가 저장소 안 다른 곳에도 있다. 여기에 베껴 적지 않고 가리킨다.

### D1. NAVER Review Cloud Export POC — provider capability survey

- **무엇** — Steel(`steel-sdk 0.19.0`) + `browser-use 0.13.10`로 "클라우드 브라우저에서
  NAVER 셀러센터 로그인을 세션 간 유지하고 **공식** 리뷰 Excel export를 셀렉터 없이
  수행할 수 있는가"를 묻는 격리된 실험. **offline 검증만 끝났고 NAVER 접촉·라이브 런은
  0이다.**
- **왜 중요** — SDK를 코드로 실측해 가설 셋을 부분적으로 답했다: `region`에 **한국이 없다**
  (`nrt`/`ap-northeast`만) · Steel은 **인증 상태 신호를 노출하지 않아** `AUTH_REQUIRED`와
  `UNSUPPORTED_STATE`를 provider API로 구분할 수 없다(H-2 열림) · 기본 action 24개 중
  `evaluate`/`search`/`read_file` 등 7개를 빼 17개로 좁혔다 · PostHog 텔레메트리가
  **기본 ON**이어서 모듈 로드 전에 끈다.
- **라이브 런만 측정할 수 있는 것 5가지**를 문서가 명시한다 — 로그인이 Steel 프로필에
  세션 간 남는지, 도쿄 egress IP가 추가 인증을 유발하는지, export 컨트롤이 셀렉터 없이
  닿는지, 다운로드가 Files API에 떨어지는지, 비용.
- **출처** — `tools/naver-cloud-export-poc/FINDINGS.md` · `README.md`.
  `tools/`는 모든 build·test 그래프 밖이고, 이 POC는 저장소 코드를 import하지 않는다.
  2026-10-12에 작업 브랜치로 들였다.

> **교정 (2026-10-12).** 위 행과 커밋 `eaccdcee`는 이 디렉터리가 "저장소 밖 백업 tarball
> 안에만 있었고 git에 없었다"고 적었다. **틀렸다.** 같은 12개 파일이 git 브랜치
> `preserve/repo-wip-2026-10-10`(`6b96862c`)에 들어 있었다 — 내용은 백업본과 동일하고,
> 백업이 이름만으로 withheld했던 `.env.example`은 거기에만 있었다(값 없는 이름 선언이라
> 함께 들였다). 작업이 사라질 위험은 없었다. 다음 행이 그 교훈이다.

### D2. 저장소 밖 백업보다 git ref를 먼저 본다

- **했던 것** — 상위 폴더 `sellerops-backup/`을 감사하면서 tarball 목록을 현재 작업 트리와
  대조해 "이 11개는 백업에만 있다"고 판정했다.
- **사실** — `git ls-files`와 작업 트리만 봤고 **브랜치·태그를 보지 않았다.** 그 파일들은
  `preserve/*` 브랜치 7개 중 하나에 들어 있었다. `sellerops/_preserved/2026-10-10/README.md`가
  그 ref 목록을 표로 싣고 있었는데, 그것도 나중에 읽었다.
- **다음에** — 저장소 밖 백업을 지울지 판단할 때는 순서가 있다. ① 보존 아카이브의
  `README`를 먼저 읽는다 — 무엇이 git에 있고 무엇이 git에 담을 수 없는지 적혀 있다.
  ② `git for-each-ref 'refs/heads/preserve/*' 'refs/tags/preserve/*'`로 ref를 센다.
  ③ `git ls-tree -r --name-only <ref>`로 대조한다. 작업 트리만 보고 "git에 없다"고
  말하지 않는다.
- **출처** — 2026-10-12 세션. `sellerops/_preserved/2026-10-10/README.md`.

---

### 운영 시간 인터뷰에서 수집 실행 환경을 먼저 구분한다 (2026-10-12)

- **했던 것** — 구현 목표 인터뷰에서 API·로컬 브라우저 수집을 구분하지 않고 모든 채널의 24시간 확인·준비와 업무 시간 알림을 추천했다.
- **사실** — 현재 Aside 연결은 `collector/src/aside/aside-cli.ts`에서 로컬 CLI 프로세스를 실행한다. PC·헬퍼·Aside·세션 가용성이 새 자료 확인의 전제다. 기기 연결 권한과 실제 실행 가능 상태, 수집 가용 시간과 알림 업무 시간도 서로 다르다. 실제 수집은 실행하지 않았다.
- **다음에** — 운영 시간·준비 지연 목표를 합의하기 전에 수집 방식과 실행 위치를 나눈다. 상시 서버의 API 수집과 로컬 수집의 가용성을 별도로 표시하고, 확인하지 못한 구간을 신규 업무 없음으로 표현하지 않는다. 로컬 제약을 이유로 API 수집도 업무 시간에 제한한다고 임의 결정하지 않는다.
- **출처** — 2026-10-12 제품 오너 인터뷰 · `docs/reviewnary_implementation_goals_draft_v1.md` 질문 36 · `collector/src/aside/aside-cli.ts` · `backend/src/main/java/com/sellerops/responsibility/aside/AsideHelperDevices.java`.

### 벽에 막힌 lane이 설정 행에서는 한 주기 늦게 보인다 (2026-10-13)

- **했던 것** — 자동 확인 lane을 라이브로 다시 띄운 뒤 `review_auto_check.paused_reason`을 보고 「벽이
  섰는지」를 판정하려 했다. settle이 `AUTH_REQUIRED`로 끝났는데 행은 `paused_reason=null` ·
  `next_check_at`은 평소 주기(+60분)였고, 8분 동안 그 행만 보면 lane은 정상으로 보였다.
- **사실** — `PAUSED_AUTH`를 쓰는 것은 settle이 아니라 **다음 tick의 판정**이다
  (`ReviewAutoCheckReconciler.heldByWalk` → `Outcome.PAUSED_AUTH`). claim이 먼저 pause를 지우고 주기를
  세운 뒤에 읽기가 벽을 만나므로, 그 사이 **최대 한 주기(60분)** 동안 행은 벽을 말하지 않는다. 2026-10-11도
  같은 모양이었다(settle 14:58:53 → 표기 15:18:47). 그때는 20분 차이라 눈에 띄지 않았다.
- **다음에** — 「이 lane이 지금 벽에 막혔는가」는 `review_catch_up_run.state`(+`stop_reason`)로 읽는다.
  설정 행의 `paused_reason`은 **표시용 요약이고 한 주기 늦는다.** 그 행을 읽는 다른 lane
  (`OperationsCaseProcessor`의 관측 공백, `CollectNowService.autoCheckParkedOnSignIn`)도 같은 지연을
  물려받는다는 사실까지 같이 본다.
- **출처** — 2026-10-13 라이브 런 `apr-4b88269414ba` · `docs/review/review_auto_check_live_proof_v1.md` §7-1.

---

### 도우미 재설치는 기기 연결이 아니라 브라우저 페어링을 끊는다 (2026-10-13)

- **했던 것** — 도우미를 HEAD에서 재빌드·재설치한 뒤 연결 화면이 「도우미 연결 안 됨」이라 기기 연결이
  날아간 줄 알고 `helper_devices`부터 뒤졌다.
- **사실** — 기기 연결은 멀쩡했다(`helper_devices` 행 그대로, 재설치 뒤에도 `last_used_at` 갱신). 끊긴 것은
  **브라우저 탭 ↔ bridge 페어링**이고 그것은 **브라우저 프로필마다** 따로다. 판매자가 「이 기기 연결」을 한 번
  누르자 `bridge_pair_presented approved` → `ticket_minted` → `ws_accepted`로 복구됐고 **새 device link는
  생기지 않았다**. 그 다음이 더 헷갈린다 — 페어링이 화면이 뜬 뒤에 맺히면 그 탭은 복귀 확인을 **한 번도**
  돌리지 않는다. 복귀 확인은 **탭 활성화당 1회**다(`CollectNowAction.returnCheck`).
- **다음에** — 재설치 뒤 「연결 안 됨」을 보면 ① `helper_devices`의 `last_used_at`으로 기기 연결 생사를 먼저
  가르고, ② 끊긴 쪽이 페어링이면 화면에서 한 번 눌러 복구하고, ③ **탭을 떠났다 돌아오는 한 번**을 반드시
  넣는다. 그 한 번이 없으면 「고쳤는데 아무 일도 안 일어난다」가 된다.
- **출처** — 2026-10-13 라이브 런 · `docs/review/review_auto_check_live_proof_v1.md` §7-2.

---

### 월 단위 분할은 마켓 제약이 아니었다 — 측정 대신 추정이 안전장치 노릇을 했다 (2026-10-13)

- **했던 것** — 과거 리뷰 2026-04~06을 가져오려다 「구간이 달력 월로 쪼개지니 다운로드 3번」을 그대로 받아들였고,
  사용자가 「NAVER는 1년도 한 번에 되잖아?」라고 묻자 「행 상한이 미확인이라 보수적으로 월」이라고 설명했다.
  그 설명도 틀렸다.
- **사실** — `ReviewImportSegmentPlanner`는 2026-07-24에 **가독성**(「2026-03」으로 읽힌다)을 이유로 월을 골랐고,
  NAVER 상한은 **가정하지 않는다**고 스스로 적고 있었다. 그런데 2026-09-02 `mintNextForAccount` 주석은 같은 규칙을
  「one export per month is a bound the marketplace screen imposes」라고 적었다 — **출처 없는 마켓 제약 주장**이다.
  collector·docs 어디에도 월 상한 관측은 없고, NAVER 리뷰 검색은 「1년」 프리셋을 스스로 준다. 그리고 월 분할이 사주던
  안전은 **없었다**: `rows_reconciled`는 V27 이래 한 번도 true가 된 적이 없어서 잘린 한 달과 잘린 1년이 똑같이
  안 보였다. 비용만 진짜였다 — 1년치 = 판매자 착석 12번. 이 최적화는 §4g(`docs/knowledge/demo_org_and_channel_knowledge_v1.md`)에서
  이미 backlog로 이름이 붙어 있었다.
- **다음에** — 사람 손이 드는 횟수를 정하는 규칙을 만나면 「이 수치는 누가 측정했나」를 먼저 묻는다. 코드 주석이
  마켓 제약이라고 말해도 collector 관측이나 evidence 행이 없으면 그건 추정이다. 완결성은 **작은 창으로 추정하지 말고
  대조로 측정한다** — 2026-10-13부터 구간은 기간당 1개(최대 1년)이고, guided run이 읽은 화면 총 건수와 파일 행 수가
  다르면 그 시도는 실패하고 구간은 커버되지 않는다.
- **출처** — 2026-10-13 세션 · `ReviewImportSegmentPlanner` · `ReviewImportRunService` · V133 · `docs/review/review_acquisition_baseline_v1.md` §2.4.

---

### migration 계약 테스트는 파일을 글자 순으로 정렬한다 — V134가 V99보다 앞에 온다 (2026-10-13)

- **했던 것** — V134(`review_history_from`)를 추가하며 `SchemaVocabularyTest.allowedBy`와 `MigrationContractTest`가
  새 제약을 「최신 정의」로 읽는지 보았다.
- **사실** — 두 테스트 모두 migration 파일을 **문자열로 정렬**한다. `V134__…`는 `V99__…`보다 앞에 온다. 그래서 어느
  날 V1xx가 V9x에서 처음 정의된 제약을 다시 정의하면, 「마지막 정의」로 V9x의 옛 정의를 집고도 **초록색으로
  통과**한다. 이번 변경은 제약 이름이 전부 새것이라 영향이 없었다 — 그래서 아무도 몰랐다.
- **다음에** — migration 순서를 읽는 검사는 버전 번호를 **정수로** 파싱해 정렬한다. 고치기 전에 V1xx가 V9x 제약을
  재정의하는 픽스처를 넣어 지금 검사가 틀린 정의를 고르는 것(=결함)을 먼저 보이고, 고친 뒤 같은 픽스처로 맞는
  정의를 고르는 것을 보인다(CLAUDE.md 「검사 도구의 실패는 초록색」 4).
- **출처** — 2026-10-13 세션, V134 작업 중 발견 · 미수정.

---

### 403이 났다고 그 403이 내 필터의 것은 아니다 — 거부 필터는 실제 서버와 응답 본문으로 증명한다 (2026-10-16)

- **했던 것** — local-seller 모드의 `LoopbackRequestGuard`(Host/Origin 거부)를 MockMvc에 `addFilters`로 직접 끼워
  "evil Host → 403"을 단정했고 통과했다. 거부는 `response.sendError(403)`이었다.
- **사실** — 실제 내장 서버(RANDOM_PORT + 소켓으로 쓴 원시 HTTP)에서는 **401**이었다. `sendError`는 컨테이너가
  `/error`로 재전송하게 만들고, 그 경로를 Spring Security가 인증 필요로 막아 401로 바꾼다. MockMvc는 error
  dispatch를 하지 않아 이 결함을 볼 수 없었다. 고친 뒤에는 가드를 꺼도 외부 `Origin` 케이스가 **여전히 403으로
  통과**했다 — Spring CORS가 같은 요청을 "Invalid CORS request" 403으로 막기 때문이다.
- **다음에** — 서블릿 필터의 거부는 `setStatus` + 빈 본문으로 끝내고, 실제 서버에 원시 HTTP를 써서 검증한다
  (HTTP 클라이언트는 `Host`를 못 바꾼다). 단정은 상태 코드만이 아니라 **그 필터의 응답 모양**(여기선 `body=0`)까지
  한다. 가드를 꺼서 두 거부 케이스가 모두 빨개지는지 본다.
- **출처** — 2026-10-16 L1 · `LoopbackRequestGuard` · `LocalSellerGuardWiringTest`.

---

### 자기 속성을 가진 SpringBootTest가 기본 H2 이름을 쓰면 무관한 428개가 깨진다 (2026-10-16)

- **했던 것** — `LocalSellerGuardWiringTest`를 `@SpringBootTest(properties=…)`로 추가하고 datasource는 기본값
  (`jdbc:h2:mem:sellerops_test`)을 그대로 두었다. 단독 실행은 초록색이었다.
- **사실** — 전체 실행에서 ingest · connector 계열 **428개**가 `channels(code)` 유일 제약 위반 등으로 실패했다. 속성이
  다른 컨텍스트는 새로 만들어지고, 같은 이름의 인메모리 DB(`DB_CLOSE_DELAY=-1`)에 create-drop과 seed를 한 번 더
  실행해 캐시된 기본 컨텍스트의 상태와 부딪힌다. 저장소의 다른 23개 클래스는 이미 자기 DB 이름을 쓴다.
- **다음에** — 속성을 바꾸는 SpringBootTest는 `spring.datasource.url=jdbc:h2:mem:<자기이름>;…`을 함께 준다. 새 테스트
  클래스는 단독 통과가 아니라 **전체 실행**의 결과 XML로 판정한다(§A2). 컨텍스트 하나가 늘면 힙 한도도 다시 본다
  (`build.gradle` test 블록 주석).
- **출처** — 2026-10-16 L1 · 첫 전체 실행 5389건 중 428 실패 → DB 이름 분리 후 0 실패.

---

### 합성 계약 fixture에 없다고 실제 파일에도 없는 것은 아니다 (2026-10-17)

- **했던 것** — 공급자 서버 계약 초안에서 `contracts/review-export/naver/v1`(25열)을 근거로 "NAVER 리뷰 엑셀에는 사진
  주소와 기존 답글 본문이 없다"고 단정하고, 그 위에 "파일럿은 사진 여부 표시만"이라는 권고를 얹었다.
- **사실** — 그 계약은 실제 열 이름을 본떠 만든 **합성** 파일이다(SPEC: "Everything in the cells is synthetic").
  네이버 판매자센터 FAQ는 엑셀로 내려받으면 리뷰 첨부 1개가 조회된다고 안내한다. 첨부의 형태와 기존 답글 본문의 유무는
  실제 파일로만 확인된다. 같은 초안에서 "백업에 최대 14일 더 남는다"도 틀렸다 — `backup.sh`는 호스트 덤프만 지우고
  S3 사본은 버킷 수명주기 정책에 맡긴다(34–37행 주석).
- **다음에** — fixture · 계약 · 설정 기본값은 **실물 구조의 증거가 아니다.** "없다"는 그 출처가 실물을 본 것인지 먼저
  확인하고, 아니면 "합성 계약에는 없다"까지만 쓴다. 보관 기간을 말할 때는 삭제를 실제로 수행하는 주체(스크립트 · 버킷
  정책 · 스냅샷)를 사본마다 찾는다.
- **출처** — 2026-10-17 `docs/vendor_server_contract_v1.md` §3.1 · §6, 오너 검토로 정정.

---

## 어디에 무엇이 있나

| 찾는 것 | 가는 곳 |
|---|---|
| 지켜야 하는 규칙 | `CLAUDE.md` |
| 현역 문서 전체 지도 | `docs/INDEX.md` |
| 라이브 실행 이력 (한 런 = 한 행) | `docs/evidence/INDEX.md` |
| 소스가 인용하는 과거 기록 · 없는 문서 이름 | `docs/lineage_index.md` |
| 링크 검사 | `tools/docs-link-check.py` |
