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

## 어디에 무엇이 있나

| 찾는 것 | 가는 곳 |
|---|---|
| 지켜야 하는 규칙 | `CLAUDE.md` |
| 현역 문서 전체 지도 | `docs/INDEX.md` |
| 라이브 실행 이력 (한 런 = 한 행) | `docs/evidence/INDEX.md` |
| 소스가 인용하는 과거 기록 · 없는 문서 이름 | `docs/lineage_index.md` |
| 링크 검사 | `tools/docs-link-check.py` |
