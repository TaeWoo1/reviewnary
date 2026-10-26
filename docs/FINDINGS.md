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

### B19. 변형 증명 뒤 원본을 이름 바꾸기로 되돌리면, Gradle이 변형을 계속 컴파일된 채로 둔다

- **했던 것** — 2026-10-22 공급자 서버 수정의 변형 증명(고친 줄을 되돌려 테스트가 실패하는지)을 스크립트로 돌렸다.
  원본을 `.bak`으로 복사해 두고, 변형을 쓰고, 테스트를 돌린 뒤 `shutil.move(.bak → 원본)`으로 되돌렸다.
- **사실** — 되돌린 소스는 `git diff`상 정확했는데, 다음 전체 실행에서 방금 고친 테스트가 실패했다.
  `build/classes/.../ReplyTasks.class`의 시각이 되돌린 소스보다 늦었다 — **변형이 컴파일된 그대로** 남아 있었다.
  이름 바꾸기로 들어온 파일을 Gradle의 파일 감시가 바뀐 것으로 보지 않았다(되돌린 파일의 수정 시각은 복사 시점이라
  컴파일 결과보다 오래됐다). `./gradlew clean test`로 바로 통과했다. 같은 상태로 만든 `bootJar`에도 변형이 들어간다.
- **다음에** — 변형은 **제자리에 다시 써서** 되돌린다(이름 바꾸기 · 이동 금지). 변형 증명을 돌린 뒤 마지막
  통과 실행과 jar 빌드는 `clean`부터 한다. "되돌렸더니 통과"는 `clean` 뒤의 통과만 증거다.
- **출처** — 2026-10-22, `ReplyTasksTest.aRepeatedReportIsAnsweredOnlyToTheClaimerWhileTheStoreIsStillTheirs`가
  되돌린 뒤에도 실패 → `clean` 뒤 112건 통과. `CLAUDE.md` "검사 도구의 실패" 4번(양방향 증명)을 잇는다.

### B20. 통과한 테스트가 구별해야 할 경우를 만들지 않으면, 고친 줄을 지워도 초록이다

- **했던 것** — 2026-10-22 시안 보완을 구현하며 새 테스트를 썼다: "담당자가 활동 중인 `UNKNOWN`은 운영자가 종결할 수
  없다", "「리뷰 받기 시작」은 이름 받은 스토어만 묶는다". 둘 다 통과했다.
- **사실** — 고친 줄을 되돌려도 둘 다 통과했다. 첫째는 활동 중인 담당자의 작업을 `UNKNOWN`이 되기 **전**(`CLAIMED`)에
  시험해, 상태 조건만으로 거절됐다. 둘째는 시작할 때 서버에 스토어가 **하나뿐**이라 "이름 받은 것만"과 "전부"가 같았다.
  같은 날 단위 테스트를 다 통과한 화면이 실제 서버로 돌리자 결과 문구("새 리뷰 6건")와 스토어 목록("아직 받지 않음")이
  어긋나 보였다 — 문구를 먼저 그리고 목록을 나중에 다시 읽었는데, 가짜 API는 즉시 답해 그 틈이 없었다.
- **다음에** — 조건을 하나 더한 코드는 **그 조건만 다른 두 입력**으로 시험한다(같은 상태 · 다른 담당자 상태, 같은 서버 ·
  이름 없는 스토어 하나 더). 두 응답을 이어 그리는 화면은 둘 다 받은 뒤 함께 반영하고, 테스트는 문구가 보이는 그 순간의
  목록을 단언한다. 새 테스트도 변형 증명 전에는 증거가 아니다.
- **출처** — 2026-10-22, `ReplyTasksTest.anAdminSettlesASuspendedDelegatesInDoubtTaskAndNothingElse` ·
  `VendorReviewPullScenarios.onlyTheStoresShownAreStartedAndALaterStoreWaitsToBeShown`(변형 생존 → 입력 보강 → 실패 확인),
  `VendorReviewsSection`(실제 서버 e2e에서 발견, 순서 변형으로 고정).

### B21. 반복문에 「건너뛰기」를 넣으면 그 아래의 일이 전부 같이 건너뛰어진다

- **했던 것** — 2026-10-22 스토어별 「받기 멈추기」를 받기 반복문 맨 앞의 `continue`로 만들었다. 같은 날 PC 리뷰 받기
  화면은 첫 읽기 한 번으로 그리고, 테스트는 정상 응답 · 서버에 닿지 않음 응답만 가짜로 주었다.
- **사실** — 외부 검토가 둘을 찾았다. 그 반복문 아래에는 리뷰 받기뿐 아니라 **이미 맡긴 답글의 상태 동기화**와 「답글
  불필요」 보고가 있어, 멈춘 스토어에서는 휴대폰 승인 · 담당자 등록이 PC에 오지 않았다. 화면은 첫 읽기가 **예외**로
  끝나면 구역 자체가 사라졌고, 서버에 닿지 않는 채 열리면 서버가 돌아와도 다시 읽지 않았다. 자동 받기의 결과도
  새로고침 전에는 보이지 않았다. 테스트는 모두 초록이었다 — 그 경우를 만든 테스트가 없었다.
- **다음에** — 반복문에 건너뛰기를 넣기 전에 **그 아래에서 하는 일을 다 적고**, 건너뛸 것과 계속할 것을 나눈다. 멈춤
  같은 사용자 동작은 "무엇을 멈추는가"를 한 문장으로 계약에 적는다. 한 번 읽어 그리는 화면은 **첫 읽기 실패 · 막힌 채
  열림 · 막혔다 풀림 · 화면이 열린 채 데이터가 바뀜** 네 경우를 테스트로 만든다.
- **출처** — 2026-10-22 외부 검토(커밋 `8cec712e` 기준) →
  `VendorReplyTaskScenarios.stoppingAStoreStopsNewReviewsButNotTheRepliesAlreadyHandedOver`,
  `VendorReviewsSection.test.tsx`의 회복 · 갱신 테스트 5건, 실제 서버 e2e(서버를 끈 채 연 화면이 15초 만에 회복).

### B22. 답변 기억이 저장됐다는 것과 다음 초안이 그것을 찾는다는 것은 다른 주장이다

- **했던 것** — 2026-10-22 담당자 대행 경로의 승인을 답변 기억(`USER_APPROVED`)과 과거 답변 검색에 이었다. 저장 · 검색
  목록 단정은 바로 통과했고, "다음 비슷한 리뷰의 초안 근거에 그 답글이 들어간다"를 같은 테스트에 단정했다.
- **사실** — 원래 리뷰 문장 그대로("배송이 정말 빠르고 포장이 꼼꼼해서 좋았어요")로 물어도 초안 근거는 비어 있었다. 짧은
  낱말("포장 꼼꼼 배송 빠름", "빠른 배송 꼼꼼한 포장")로는 찾았다. 테스트 환경에는 임베딩이 없어 낱말 일치만 돌고, 리뷰
  문장의 조사(배송이 · 포장이)와 답글의 조사(배송과 · 포장을)가 달라 문턱을 못 넘는다. 대행 경로만의 일이 아니라 리뷰 답변
  기억 전체의 성질이다. 기존 테스트는 기억 행이 생기는 것만 단정했다.
- **다음에** — "기억된다"와 "다음에 쓰인다"를 따로 단정한다. 쓰인다는 단정은 실제 리뷰 같은 긴 문장으로 하고, 통과한
  질문과 실패한 질문을 함께 적는다. 실제 사례 확인(판매자가 고친 답글 → 다음 유사 리뷰)은 운영과 같은 검색 설정(임베딩
  켜짐 여부 포함)에서 잰다.
- **출처** — 2026-10-22, `VendorReplyTaskScenarios.aReplyTheSellerRewordedOnThePhoneIsTheirAnswerTheNextSimilarReviewIsDraftedFrom`
  작성 중 질문 4개 시험(긴 문장 2개 0건, 짧은 문장 2개 1건).

### B23. 검색이 못 찾은 이유를 재지 않고 그럴듯한 원인으로 적었다

- **했던 것** — B22를 적으며 긴 리뷰가 자기 답변 기억을 못 찾는 이유를 "조사가 달라서(배송이 · 배송과)"라고
  보고했다.
- **사실** — 조사 보정은 이미 있었다. 원인은 질문 단위 부재 문턱(`MIN_ASKABLE_RATIO` 0.35)이었다 — 긴 리뷰는
  기억에 없는 내용어가 분모를 채워 약 0.21. 그리고 찾았더라도 리뷰 초안은 과거 답변만 있으면 템플릿을 썼다.
  외부 검토가 코드에서 두 겹을 짚었다. 합성 품질 세트로 재 보니 lexical은 자연스러운 문장 9개 중 0개를 찾았다.
- **다음에** — 검색 실패의 원인을 적기 전에 **어느 문턱에서 떨어졌는지** 값을 찍어 본다(부재 비율 · 겹친 글자 ·
  coverage). 찾기와 쓰기는 다른 단계라 따로 확인한다(B22). 짧은 질문으로 바꿔 통과시키지 않는다.
- **출처** — 2026-10-22, B22 → 외부 검토(코드 대조) → `docs/knowledge/retrieval_runtime_closure_v1.md` §10,
  `ReviewPrecedentQualitySetTest` lexical arm(hit 0 / 9).

### B24. 리뷰 초안 주석이 하지 않는 검사를 한다고 적고 있었다

- **했던 것** — `ReviewDraftComposer` 클래스 주석이 "모델과 저장된 답글 사이에 검사 셋 — 프롬프트, 금지 표현
  (`AnswerStyleInstruction#forbiddenPresentIn`), 약속 검사"라고 적었고, 그 뒤 리뷰 초안을 고칠 때마다 이 주석을
  안전 근거로 읽었다.
- **사실** — 리뷰 초안은 금지 표현 검사를 **한 번도 부른 적이 없다**. 도입 커밋(`0b17fbe2`)의 본문은 "검사 둘"이라
  적었는데 주석만 셋이었다. 화면도 말투 설정을 "문의 답변의 말투"라고 안내한다 — 리뷰는 회사의 금지 표현을 거르지
  않는다. 정책 지문을 만들려고 리뷰 초안의 입력을 하나씩 따라가다 발견했다(2026-10-22).
- **다음에** — 안전 장치를 적은 주석은 **호출 지점으로 확인한 뒤에만** 근거로 쓴다(`grep`으로 그 메서드를 부르는 곳이
  있는가). 주석과 커밋 본문의 개수가 다르면 코드가 정답이다. 리뷰에도 금지 표현을 적용할지는 [PO] — 화면 안내를
  같이 바꿔야 한다.
- **출처** — 2026-10-22, `review/draft/ReviewDraftComposer.java` 주석 정정, `docs/vendor_server_contract_v1.md` §4
  (초안 메타의 지문).

### B25. 관련 묶음 시험만 돌리고 커밋해, 결정과 부딪히는 울타리를 커밋 뒤에 만났다

- **했던 것** — 리뷰 금지 표현 적용(`eae5eca8`)을 `review.draft` · `vendorlink` 묶음과 vendor-server · 화면 시험만
  돌리고 커밋했다. 리뷰 초안 세 파일이 답변 스타일 서비스를 직접 읽게 바꾼 채였다.
- **사실** — 다음 전체 실행에서 `AnswerStyleFenceTest` K(「리뷰 답글은 문의 답변 스타일로 쓰지 않는다」 — 리뷰 패키지는
  답변 스타일을 읽지 않는다)가 실패했다. 울타리는 옳게 물었다: 오너 결정(2026-10-23)은 금지 표현 **하나만** 건너게
  했는데, 직접 읽기는 말투 · 길이 · 인사까지 건널 수 있는 문을 세 곳에 열었다. 문을 `ReviewStyleGuard` 한 곳으로
  좁히고, 울타리를 "그 파일만, 그리고 금지 목록만"으로 고쳐 양방향으로 증명했다.
- **다음에** — 다른 패키지의 설정을 새로 읽게 하는 변경은 **커밋 전에 전체 시험을 돌린다** — 그런 경계는 그 패키지가
  아니라 읽히는 쪽의 울타리가 지킨다. 결정이 울타리와 부딪히면 울타리를 지우지 말고 결정이 허락한 만큼만 좁혀 고친다.
- **출처** — 2026-10-23, `knowledge/style/AnswerStyleFenceTest` K, `review/draft/ReviewStyleGuard.java`.

### B26. 검사를 '시도함'과 '서버에 남음'을 같은 칸에 적어, 거절된 검사가 끝난 검사가 됐다

- **했던 것** — PC가 휴대폰 수정본 · 되돌아온 초안 · 승인된 답글을 검사한 뒤, 서버가 그 결과를 거절해도
  (`stale` 등) 로컬 사본에 "이 버전은 확인함"을 적었다. 그리고 서버는 휴대폰 버전을 **PC 검사 전에** 승인받으면
  바로 담당자에게 넘겼다 — 검사 결과가 나중에 와도 이미 등록됐을 수 있었다.
- **사실** — 문장 · 근거가 그대로면 다음 받기는 그 버전을 건너뛰어, 지적이 서버에 영영 닿지 않을 수 있었다. 판매자가
  보지 못한 지적을 "보고 승인한 것"으로 취급할 여지도 있었다. 외부 점검(2026-10-23)이 두 줄을 짚었다.
- **다음에** — 다른 쪽에 반영해야 끝나는 일은 **반영이 성공했을 때만** 끝났다고 적는다. 시도한 기록과 반영된 기록이
  다르면 둘을 따로 둔다. 판정을 기다려야 하는 승인은 "승인 의사는 받되 넘기지 않음"으로 두고, 판정과 상태 이동을 한
  번의 갱신에서 한다(두 번 부르면 그 사이가 또 틈이다).
- **출처** — 2026-10-23, `vendor-server/.../task/ReplyTasks.java` `awaitingPcCheck`,
  `backend/.../vendorlink/VendorReplyTaskService.java` `checkPhoneEdit` · `recheckApproved` · `recheckSentBack`.

### B27. 돌고 있는 서버의 jar를 다시 빌드해 덮어써, 셀러 PC가 리뷰 받기를 말없이 멈췄다

- **했던 것** — `tools/dev/seller-pc-demo.sh up`이 서버를 새로 띄울 때마다 `bootJar`로 jar를 다시 만들었고, 이미 떠 있던
  셀러 PC 백엔드는 **그 같은 파일**에서 돌고 있었다. 실제 리뷰 3,555건을 올렸는데 PC에는 0건이었다.
- **사실** — 30초마다 돌던 받기 작업이 00:58 이후 한 번도 돌지 않았고 오류 줄도 없었다. 스케줄러 스레드는 빈 큐를 기다리고
  있었다(작업이 큐에서 빠졌다). 덮어쓴 jar에서는 아직 안 읽은 클래스를 못 읽는다: 처음으로 스택이 찍히는 순간 logback의
  `ThrowableProxy`가 없어 로그 쓰기마저 실패하고, 그 오류가 예약 작업 밖으로 새어 주기 작업이 조용히 사라진다. 따로 띄운
  서버의 jar를 덮어쓰고 오류를 하나 내 같은 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`를 재현했다.
  PC를 다시 띄우니 바로 3,555건을 받았다(받은 위치가 DB에 있어 잃은 것은 없다).
- **다음에** — 오래 도는 서버는 다음 빌드가 덮어쓸 파일에서 돌리지 않는다. 데모 스크립트는 띄울 때 jar를 실행 폴더에
  복사해 그 사본으로 돌린다. "왜 안 들어오지"면 로그보다 먼저 스케줄러 스레드가 무엇을 기다리는지(`jstack`) 본다.
- **출처** — 2026-10-26, `tools/dev/seller-pc-demo.sh` `own_jar`. "오래 도는 프로세스는 시작한 버전을 붙잡는다"의
  반대편이다 — 여기서는 붙잡은 버전이 발밑에서 바뀌었다.

### B28. 한 묶음을 줄마다 "이미 했나" 물어, 리뷰 받기가 묶음 크기의 제곱으로 느려졌다

- **했던 것** — 셀러 PC가 리뷰 5,000건을 한 번에 받을 때 23~33초가 걸렸다. `jstack`을 0.5초마다 찍어 보니 대부분
  vendor 서버 확인(`ack`) 호출에 서 있었고, 그 쿼리를 찾아 `EXPLAIN`까지 했다 — 16ms였다.
- **사실** — `jstack`이 JVM을 매번 멈춰 세워 받기를 오히려 두 배 느리게 만들었고, 멈추는 순간에 걸린 곳이 많이 보였을 뿐이다.
  JFR(`jfr print`는 스택을 기본 **5단**만 찍는다 — `--stack-depth 300`)로 다시 보니 CPU의 96%가
  `ItemAnalysisService.analyzeForSources`였다. 한 트랜잭션에서 5,000건을 올려 두고 줄마다 `exists` 쿼리를 했고, 쿼리마다
  Hibernate가 먼저 flush하며 쥐고 있는 모든 행을 dirty-check했다. 처리량이 묶음 크기의 제곱으로 떨어진다. 한 번에 묻도록
  고치자 같은 PC에서 5,000건이 약 6초가 됐다(분류 단계 7~15초 → 0.5초). 시험은 쿼리 수를 센다(5건과 60건이 같음).
- **다음에** — 느린 곳은 멈춰 세우는 도구(`jstack` 반복)가 아니라 표본 도구(JFR)로 찾고, 스택 깊이를 먼저 늘린다. 한 트랜잭션
  안의 반복문에서 저장소 쿼리를 부르면 "줄마다 flush"를 의심한다 — 묶음 앞에서 한 번 묻는다. 받기 속도는
  `tools/dev/seller-pc-qa-perf.mjs upload N`으로 잰다.
- **출처** — 2026-10-26, `ItemAnalysisBatchQueryTest`, QA 스택(합성 리뷰 300 → 46,855건).

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
  S3 사본은 버킷 수명주기 정책에 맡긴다(34–37행 주석). 게다가 합성 fixture 자체도 한 행의 `포토/영상`에 주소 텍스트
  (`https://example.invalid/synthetic-media-1`)를 담고 있었다 — 머리글만 읽고 셀 값을 읽지 않아 "여부만"이라고 썼다.
  실제 파일은 그 모양 그대로(주소 1개, 텍스트)였다.
- **다음에** — fixture · 계약 · 설정 기본값은 **실물 구조의 증거가 아니다.** "없다"는 그 출처가 실물을 본 것인지 먼저
  확인하고, 아니면 "합성 계약에는 없다"까지만 쓴다. 보관 기간을 말할 때는 삭제를 실제로 수행하는 주체(스크립트 · 버킷
  정책 · 스냅샷)를 사본마다 찾는다. fixture를 근거로 쓸 때도 머리글이 아니라 **셀 값까지** 읽는다.
- **출처** — 2026-10-17 `docs/vendor_server_contract_v1.md` §3.1 · §6, 오너 검토로 정정.

---

### 깨뜨려 보는 증명이 실제 외부로 요청을 보낼 수 있다 — 거부 테스트의 목적지는 닫힌 루프백으로 (2026-10-17)

- **했던 것** — `LlmSendGate` 전송 테스트를 실제 모델 호스트(`https://api.openai.com/...`) URI로 썼다. 관문이 보류하면
  보내지 않으므로 평소엔 안전하다고 봤고, 양방향 증명으로 관문을 끈 채 실행했다.
- **사실** — 관문을 끄자 테스트 3건이 실제로 `api.openai.com`에 요청했다(합성 주소 문장 · 5바이트 가짜 이미지, 인증 없음 또는
  가짜 키). 빨강은 맞게 나왔지만 그 빨강이 외부 요청으로 만들어졌다. 같은 작업에서 공급자 서버 fence가 맨 단어 `sentry`를
  찾다가 Spring의 `HttpStatusEntryPoint`(statu-**s-entry**)에 걸린 것도 같은 부류다 — 검사 대상이 의도보다 넓었다.
- **다음에** — "보내지 않음"을 단정하는 테스트의 목적지는 **닫힌 루프백 포트**(`http://127.0.0.1:1/…`)로 둔다. 관문이 고장
  나면 연결 실패로 빨개지고 아무것도 기계를 떠나지 않는다. 고정 엔드포인트라 목적지를 바꿀 수 없는 기능은 실제 전송 클래스로
  돌리지 않는다. 금지 문자열은 맨 단어가 아니라 패키지 · 키 · 호스트 모양으로 쓴다.
- **출처** — 2026-10-17 `LlmSendGateTransportTest` 첫 판 → 루프백으로 교체, `VendorServerScopeFenceTest`.

---

### 같은 화면에 보이는 값을 다시 입력하게 하는 확인은 아무것도 증명하지 않는다 (2026-10-17)

- **했던 것** — PC 연결 승인 화면(`vendor-server/.../static/link/`)에서, 코드를 요청한 PC와 승인하는 브라우저의 네트워크가
  다르면 "PC 화면의 코드를 직접 입력"해야 허용되게 만들었다. 그런데 같은 화면의 확인 목록에 그 코드를 그대로 보여 주고 있었다.
- **사실** — 피싱 링크를 누른 사람은 화면에 보이는 코드를 옮겨 적으면 통과한다. 단위 · 통합 테스트는 "재입력 전에는 버튼이
  꺼진다"만 봤고 모두 초록이었다. 실제 브라우저로 화면을 찍어 보고서야 드러났다(커밋 전).
- **다음에** — "다른 곳에서 가져온 값을 입력하라"는 확인은 그 값이 **이 화면 어디에도 없음**을 함께 단정한다(페이지 본문 텍스트
  검사). 브라우저 화면은 기능 테스트가 초록이어도 한 번 실제로 띄워 본다.
- **출처** — 2026-10-17 `link.js`(재입력이 필요하면 코드 대신 "PC 화면에서 확인" 표시), Playwright 확인 스크립트.

---

### 실행 도구 스크립트는 나중에 생긴 시작 검사를 모른다 (2026-10-17)

- **했던 것** — API센터 화면 측정을 위해 `tools/naver-local/run-backend-local.sh`로 walkthrough 백엔드를 띄웠다.
- **사실** — 두 번 막혔다. (1) `PilotConfigValidator`(`eb3b3b9c`, 2026-10-16)가 NAVER 커넥터가 켜져 있으면
  `SELLEROPS_CONNECTOR_NAVER_ADVERTISED_EGRESS_IPS`를 요구해 백엔드가 시작을 거부했다. 스크립트는 그 값을 넣지 않는다.
  (2) 기본으로 켜진 `sellerops.seed.seller-accounts`가 NAVER 판매자 계정 한 줄을 만들어 preflight의 깨끗한 DB 확인
  (`naver_accounts=0`)에 걸렸다. 둘 다 스크립트보다 나중에 생긴 검사 · 시드이고, 스크립트 자체 테스트는 그것을 보지 않는다.
- **다음에** — 라이브 전 실행 도구는 시작부터 한 번 돌려 본다. 고정 호출 IP는 지어내지 않고 이 기기의 실제 공인 IPv4를
  조회해 실행 설정에만 넣는다(출력 · 기록 안 함). 깨끗한 DB가 필요한 런은 `SELLEROPS_SEED_SELLER_ACCOUNTS=false`로 띄운다.
  스크립트에 두 기본값을 넣을지는 따로 정한다.
- **출처** — 2026-10-17 run `wt-cca943ebbc03` / `apr-54d92eeabd12` 준비 과정. `PilotConfigValidator.java`,
  `application.yml` `sellerops.seed.seller-accounts`.

---

### 화면 측정은 운영자가 다른 화면에서 눌러도 그 자리에 찍는다 (2026-10-17)

- **했던 것** — `capture-api-center-visual`로 `app_detail` · `app_edit` 두 화면을 측정했다.
- **사실** — `app_detail` 자리의 확인을 운영자가 앱 **목록** 화면에서 눌렀고, 도구는 가림 검증 후 그대로 찍어
  `app_detail` 이름으로 저장했다. 요약의 분류는 `app_list`로 맞게 나왔지만 체크포인트와 비교하지 않으므로 런 결과는
  「찍음 2 · 정지 0」으로 초록이었다. 상세용 고정 라벨 세 개가 0인 것을 보고서야 드러났다. 수정 화면은 분류 어휘에 없어
  `credential_issuance`로 분류된다.
- **다음에** — 측정 결과는 숫자보다 먼저 **요약의 `pageCategory`가 그 체크포인트의 화면과 같은지** 본다. 안내 문구에
  화면을 알아볼 단서(예: 「[수정] 버튼이 보이는 화면」)를 넣는다. 도구가 분류 불일치를 표시하거나 그 화면을 다시 묻게 할지는
  따로 정한다.
- **출처** — 2026-10-17 recon `vis_87e268bf4894`, `docs/evidence/INDEX.md` §2.

---

### 「값을 읽지 않는다」 전체 검사가 첫 단계에서 멈춰 있었다 (2026-10-17)

- **했던 것** — NAVER 발급 안내에 IP 단계(⑤ [수정] · ⑥ 수정 화면)와 패널의 [복사]를 넣었다. [복사]는 클립보드에 쓰므로
  "모든 평가 스크립트에 `clipboard`가 없어야 한다"는 기존 검사(`naver-issuance-driver.test.ts` SECRET READ = 0)에 걸릴 줄 알았다.
- **사실** — 초록이었다. 그 검사는 런을 시작하고 첫 멈춤(②)에서 끝나서 ③ 이후 단계의 스크립트를 한 번도 보지 않았다. 지금까지는
  뒷단계도 같은 오버레이를 써서 드러나지 않았을 뿐이다.
- **다음에** — "어떤 스크립트도 ~하지 않는다"는 검사는 **런을 끝까지 걸은 뒤** 모은 스크립트로 한다. 새 단계를 넣으면 그 단계의
  스크립트가 검사 대상에 실제로 들어갔는지(예: 감사된 예외 스크립트가 정확히 1개 보였는지) 같이 단정한다. 클립보드 쓰기 예외는
  표식(`aw-panel-copy-rows`)으로 한정하고, 그 스크립트에 읽기 호출이 없음을 따로 단정한다.
- **출처** — 2026-10-17 `naver-issuance-driver.test.ts` "SECRET READ = 0 across the WHOLE walk", `overlay-copy-values.ts`.

---

### 앱을 연 직후의 확인 읽기가 판매자의 이동에 깨지고, 그 멈춤은 맞는 화면에서 풀리지 않았다 (2026-10-17)

- **했던 것** — 9단계 NAVER 발급 안내의 실제 화면 강조 확인(Phase B, `apr-fd5dd2e4cef4`)을 시작했다.
- **사실** — 판매자가 앱을 열자 확인 폴링의 첫 읽기는 `unknown`(화면이 아직 뜨는 중), 다음 읽기는 SPA가 실행 맥락을
  없애며 던졌다. 폴링이 그 오류를 받지 않아 drive error → `UI_DRIFT` 멈춤. 그 멈춤의 다시 확인은 목록부터 다시 읽게 돼 있어서
  판매자가 이미 상세 화면에 있어도 또 멈춘다. 오프라인 테스트는 "`unknown` 몇 번 뒤 상세"만 흉내 냈고 "읽다가 던짐"은 없었다.
- **다음에** — 판매자의 이동과 겹치는 읽기는 이동 중 오류(맥락 소멸 · 프레임 분리)를 "아직 안 뜸"으로 다루고 계속 읽는다(그 밖의
  오류는 그대로 던진다). 멈춤에서 회복은 **판매자가 지금 있는 화면**에서 판단한다 — `open_app` 멈춤은 다시 확인 시 현재 화면을
  확인해 상세면 진행, 목록이면 다시 안내. 가짜 페이지에는 "읽다가 던짐"도 넣는다.
- **출처** — 2026-10-17 run `wt-7c2c8cd2c384`, `naver-issuance-driver.ts` `midNavigationRead`, `issuance-engine.ts` `recheck`.

---

### 강조 확인 하네스는 멈춤을 풀 수 없었다 — 그리고 이 기기엔 이미 도우미가 떠 있었다 (2026-10-17)

- **했던 것** — `run-api-issuance-live-naver`(호스트) + `issuance-live-proof`(START_RUN만 보내는 클라이언트)로 강조 확인을 돌렸다.
- **사실** — 세 가지가 차례로 막았다. (1) 10월 15일에 설치된 reviewnary 도우미가 기본 브리지 포트 47615를 잡고 있어 호스트가
  `already_running`으로 물러났다 — 그대로 클라이언트를 붙였다면 옛 코드의 도우미에 붙었을 것이다. (2) 백그라운드 호스트는 페어링
  확인을 띄울 TTY가 없어 `approval_unavailable`(메모리에 있던 사실). (3) 클라이언트는 설계상 다시 확인을 못 보내므로, 멈춤 하나가
  런의 끝이 된다.
- **다음에** — 강조 확인 전 `lsof -iTCP:47615`로 포트 주인을 먼저 본다. 설치된 도우미는 건드리지 않고 `BRIDGE_PORT`로 비켜 가며,
  프론트엔드를 `SELLEROPS_WALK_ENABLE_AGENT_BRIDGE=true VITE_BRIDGE_URL=…`로 같은 포트에 붙여 판매자가 SellerOps 탭에서 시작 ·
  다시 확인을 누를 수 있게 한다(제품 경로). 클라이언트만으로 하는 런은 멈춤이 없을 때만 끝까지 간다.
- **출처** — 2026-10-17 run `wt-7c2c8cd2c384`, `local-agent.ts` `resolveAgentBridgeConfig`, `issuance-live-proof.ts`.

### 고정 라벨 측정의 「1건」이 화면에 그려지지 않는 요소였다 (2026-10-17)

- **했던 것** — 수정 화면 측정(`vis_87e268bf4894`)에서 `app_edit.call_ip_label`(「API호출 IP」 정확 일치)이 `matchCount 1`로 나와
  채택하고, 강조 확인 3차(`apr-974d094587aa`)에서 ⑥ 링을 기대했다.
- **사실** — 측정 스크립트(`buildFixedLabelProbeScript`)는 개수만 세고 **그려지는지 보지 않는다.** 강조 스크립트는 그려지는 것만
  센다. 판매자가 보는 라벨은 「API호출 IP (n/3)」라 정확 일치에 걸리지 않았고, 걸린 1건은 그려지지 않는 요소였다 — 스크롤이 8초
  「보이지 않음」으로 끝나고 그리는 요소 0. 측정 기록 자체에 「(n/3)」이 적혀 있었는데 라벨과 대조하지 않았다.
- **다음에** — 고정 라벨을 채택하기 전에 그 화면에서 `buildFixedLabelContainmentScript`(exactVisible/exactHidden/deepestContains*)로
  **보이는 1건**인지 잰다. `matchCount 1`만으로는 채택하지 않는다. 측정 요약에 적힌 화면 문구와 `exactText`가 글자 그대로 같은지도 본다.
- **출처** — 2026-10-17 run `wt-d20b19d31058`, `visual-recon-inpage.ts` `buildFixedLabelProbeScript` · `buildFixedLabelLocateScript`,
  `docs/evidence/INDEX.md` §2 수정 화면 측정 행.

### 링이 마켓플레이스의 인증 창 위에 그려졌다 (2026-10-17)

- **했던 것** — 강조 확인 4차 ⑧에서 시크릿 줄에 링(화면 전체 음영 포함)을 그려 두었다.
- **사실** — 판매자가 「보기」를 누르자 네이버가 자동입력 방지 인증을 그 줄 위에 띄웠다. 링과 음영은 `z-index:2147483000`이라
  인증 창보다 위에 그려져 풀어야 할 문제를 가렸다. 패널 배치는 링과 `data-aw-avoid`만 피할 뿐, 페이지가 나중에 띄운 창은 몰랐다.
- **다음에** — 링은 「대상이 지금 보이는가」를 매 재배치마다 hit test로 확인하고, 가려졌으면 비켜난다(`overlay.ts` reposition).
  최상위 레이어에 무언가를 그릴 때는 그 아래에 판매자가 직접 해야 하는 일(인증 · 로그인 · 확인)이 뜰 수 있다고 가정한다.
- **출처** — 2026-10-17 run `wt-cc1bb2fe42c4`, `dbee448e`, `overlay-occlusion-browser.test.ts`.

### 판매자가 네이버 수정 화면을 닫기 전에 다음 단계로 넘어갔다 (2026-10-17)

- **했던 것** — ⑥ 패널의 [저장했어요 · 다음]을 누르면 바로 ⑦ 애플리케이션 ID를 찾게 했다.
- **사실** — 수정 화면은 상세 보기를 `display:none`으로 숨긴 채 같은 자리에 뜬다. 수정 화면을 닫지 않고 누르면 ⑦의 라벨은 숨은
  상세 보기에만 있어 스크롤 8초 시간 초과 후 멈추고, 안내는 "화면이 모두 뜬 뒤 다시 확인"이라고 해 원인과 다른 말을 했다.
  같은 숨은 상세 보기가 3차 ⑥ 멈춤의 원인이기도 했다 — 한 화면이 다른 화면을 숨겨 품는 구조는 앞뒤 단계 모두에 닿는다.
- **다음에** — 멈춤 안내는 확인할 수 있는 원인이면 그 원인을 말한다. 수정 화면 여부는 ⑥과 같은 두 개수(개수 라벨 그려짐 ·
  「수정」 안 그려짐)로 판정한다. 라이브 런 안내에서는 「다음」 전에 네이버 쪽에서 해야 할 동작을 순서대로 먼저 말한다.
- **출처** — 2026-10-17 run `wt-cc1bb2fe42c4`, `dbee448e` `naver-issuance-driver.ts` `showParkNotice`.

### NAVER 안내의 마지막 「돌아가기」가 쿠팡 연결 화면을 열었다 (2026-10-17)

- **했던 것** — 쿠팡 안내에 만든 돌아가기 화면 검사(`screenSellerOpsReturnUrl`)를 NAVER 도우미에도 그대로 붙였다. 강조 확인 하네스는
  돌아가기를 받지 않아 라이브 런은 `return_not_wired`로 끝났다.
- **사실** — 그 검사는 경로를 `/connect/coupang?issuance=resume` 하나로 고정해 두었다. 그래서 제품 NAVER 도우미의 돌아가기는 쿠팡
  화면을 열었고, 하네스가 이 경로를 쓰지 않아 라이브에서는 한 번도 드러나지 않았다. 4차 뒤 하네스에 제품의 돌아가기를 연결하려고
  읽다가 발견했다. 처음엔 grep 결과를 `head`로 잘라 읽어 "어디에도 연결 안 됨"이라고 잘못 적을 뻔했다.
- **다음에** — 한 채널을 위해 만든 함수를 다른 채널이 쓸 때는 그 함수의 고정값(경로 · 문구 · 표시)을 먼저 읽는다. 하네스는 제품과
  같은 함수를 받아 제품과 같이 끝나야 한다 — 하네스만의 빈칸은 제품 결함을 가린다. "어디에도 없다"고 쓰기 전에 잘리지 않은 검색으로
  다시 센다.
- **출처** — 2026-10-17 run `wt-cc1bb2fe42c4`, `224da68f` `sellerops-return-url.ts` · `local-agent.ts` `naverReturnToSellerOps`.

### 버튼 없는 안내 패널이 「다음에 할 일」을 버리고 있었다 (2026-10-18)

- **했던 것** — 멈춤 · 완료 안내를 `detail`과 함께 그렸고, 패널이 그 문구를 보여 준다고 여겼다.
- **사실** — 패널은 버튼이 있을 때만 상세 문구를 그린다(버튼 없는 패널은 클릭을 받지 않게 하려는 설계). 그래서 멈춤 안내는 "다시
  확인을 누르세요"를, 완료 안내는 "reviewnary에 두 값을 입력하세요"를 한 번도 보여 준 적이 없다. 테스트는 마운트 인자에 문구가
  있는지만 봤다. 운영자 탭에 실제로 그려 보고서야 보였다.
- **다음에** — 안내 문구는 인자가 아니라 그려진 화면으로 확인한다. 실제 드라이버가 기록한 스크립트를 운영자 브라우저에 재생하고
  가림 검증 뒤 찍어 보는 방식이 그 확인 수단이다(`dc3d716b`).
- **출처** — 2026-10-18 Aside UX 점검, `overlay.ts` `detailStatic`, `docs/evidence/INDEX.md` §2.

### 「복사해 두세요」를 두 번 시키면 첫 값은 사라진다 (2026-10-18)

- **했던 것** — ⑦ ID, ⑧ 시크릿을 각각 "복사해 두세요"로 안내하고, 붙여넣기는 안내가 끝난 뒤 SellerOps 폼에서 하게 했다.
- **사실** — 클립보드는 한 칸이다. 시크릿을 복사하는 순간 ID가 지워진다. 판매자는 결국 두 창을 오가야 했고, 안내는 그걸 말하지
  않았다. 네이버 화면 안에 입력칸을 넣는 해법은 공지 #1564 · 커머스API 약관 제8조 2.가 문언상 가장 위험한 모양이라 택하지 않았다.
- **다음에** — 값을 옮기는 안내는 "복사 → 바로 붙여넣기"를 한 단계로 묶고, 붙여넣을 곳이 그 순간 화면에 있어야 한다(`60517685`:
  ID 단계부터 SellerOps 폼을 안내 옆에 표시). 마켓플레이스 화면에 자격증명 입력칸을 만들지 않는다.
- **출처** — 2026-10-18 Aside UX 점검, `NaverIssuanceGuidedWalkthrough.tsx` `PASTE_STEPS`, 약관 조사(커머스API센터 이용약관 2026-01-23 시행본 — API센터 공개 번들의 약관 모달 원문 · 공지 #1564).

### 화면 안으로 스크롤해도 고정 상단 바 아래면 안 보인다 (2026-10-18)

- **했던 것** — ⑤의 「저장」이 화면 위로 밀려나 있어, 맨 위(y≈24)로 끌어오는 스크롤을 넣었다.
- **사실** — 네이버 API센터는 상단 바가 고정이라 y≈24는 그 바 밑이다. 링의 가림 규칙이 「저장」 링을 숨겼고, 판매자 눈엔 여전히
  「저장」이 없었다. 실 Chromium 테스트 고정물에 고정 헤더가 없어서 통과했다.
- **다음에** — "보이게 스크롤"은 그 위치에서 hit test가 대상을 돌려주는지로 판정한다. 테스트 고정물에는 실제 화면의 고정 · sticky
  요소를 넣는다(`cd8e937f`).
- **출처** — 2026-10-18 Aside 재생 측정(「저장」 24→80px), `naver-issuance-driver.ts` `BRING_SECONDARY_ABOVE_INTO_VIEW`.

### 브라우저를 못 띄운 실 Chromium 테스트가 「통과」로 보였다 (2026-10-18)

- **했던 것** — `RUN_INTEGRATION=1`로 브라우저 묶음을 한꺼번에 돌리고 합계가 초록이면 통과로 읽었다.
- **사실** — `calibration-initscript-browser.test.ts`는 `chromium.launch`가 실패하면 각 테스트를 `skip`한다. 묶음 실행에서
  launch가 부하로 실패해 7~18건이 skipped로 바뀌었고, 실패는 0이었다. 그 파일만 따로 돌리면 7/7 통과. 같은 날 Playwright 캐시
  폴더가 통째로 사라졌을 때도 같은 모양(통과 0 · 실패 1 · 나머지 skipped)이었다.
- **다음에** — 실 Chromium 묶음은 합계의 skipped 수를 직전 실행과 맞춰 본다. 늘었으면 그 파일을 따로 돌린다. launch 실패를 skip으로
  바꾸는 패턴은 RUN_INTEGRATION 아래에선 실패여야 맞다 — 고칠 대상으로 남긴다.
- **출처** — 2026-10-18, `test/action-window/api-issuance-calibration/calibration-initscript-browser.test.ts:44-49`.

### 걷기의 마지막 「돌아가기」가 걷기 프런트의 환경 확인에 막혔다 (2026-10-19)

- **했던 것** — 발급 안내의 마지막 단계가 SellerOps로 돌려보내면 운영자가 연결 화면에 그대로 닿는다고 보고, 걷기 증명의 끝을
  「돌아감 → 완료 안내」 로그로만 판정했다(7 · 8차 행도 그렇게 적었다).
- **사실** — 돌아가는 주소는 `screenSellerOpsReturnUrl`이 origin + `/connect/naver`로 고정해 만든다(`sellerops-return-url.ts:95`).
  걷기 프런트는 주소의 `walkthroughRun`이 없으면 `MISSING_URL_RUN`으로 연결 화면 전체를 막는다(`walkthrough.ts` `evaluateBinding`).
  9차에서 운영자가 「환경 확인 실패 — 연결을 진행할 수 없습니다」를 봤다. 제품 빌드(걷기 모드 아님)에는 이 검사가 없으니 제품 결함은
  아니지만, 걷기 증명은 마지막 화면을 한 번도 확인하지 않았다.
- **다음에** — 「돌아감」은 호스트 로그(`opened:true`)가 아니라 **도착한 화면**으로 판정한다. 걷기 모드에서 돌아가는 주소는 그 실행의
  `walkthroughRun`을 실어야 한다 — 고칠 대상으로 남긴다.
- **출처** — 2026-10-19 run `wt-2b54572d84c8`(`apr-6faa81cb9e6a`), `collector/src/cli/local-agent.ts` `naverReturnToSellerOps`,
  `frontend/src/components/guidedConnection/WalkthroughMismatch.tsx`.

### 영구 프로필이면 로그인이 남는다고 봤다 (2026-10-19)

- **했던 것** — 걷기 프로필이 `launchPersistentContext`로 디스크에 남으니, 7차에서 한 로그인으로 8차가 로그인된 채 시작할 거라 보고
  8차를 「세션 유지 시작」으로 잡아 승인까지 받았다.
- **사실** — 8차 첫 판독은 `login`이었다. 운영자 확인으로 네이버 로그인은 「로그인 상태 유지」를 체크해도 브라우저가 닫히면 남지 않는다.
  실행 호스트는 멈출 때마다 브라우저를 닫으니, 프로필이 남아도 다음 걷기는 늘 로그아웃 상태로 시작한다. 한 번의 승인을 그 가정에 썼다.
- **다음에** — 세션 유지가 필요한 라이브 런은 브라우저를 호스트와 따로 살려 둔다(`naver-test-browser.ts` + `SELLEROPS_NAVER_TEST_BROWSER_PORT`).
  "프로필 = 세션"으로 보지 않는다. 시작 상태가 목표인 런은 그 상태를 승인 전에 만들 수 있는지 먼저 따진다.
- **출처** — 2026-10-19 run `wt-fa543f4f162c`(`apr-421a0c0aa696`), `tools/naver-local/README.md` 「Keeping the NAVER login between issuance walks」.

### 멈춤 안내가 시키는 대로 하면 같은 안내로 돌아왔다 (2026-10-19)

- **했던 것** — 2026-10-18에 「화면이 예상과 달라요」 안내 문구를 "왼쪽 메뉴의 '내 스토어 애플리케이션'에서 연결할 애플리케이션을 연 뒤
  '다시 확인'"으로 바꿨다. 로그인 뒤 네이버가 「내 솔루션」에 내려 준다는 라이브 관찰 때문이었다. 그 뒤 실제 실행은 7차 하나였고, 로그아웃
  상태로 시작했다.
- **사실** — 시작 직후의 멈춤에서 「다시 확인」은 화면을 처음부터 다시 읽고 **목록만** 받았다. 안내대로 앱을 연 판매자는 상세 화면에서 같은
  안내를 다시 받았고, 누를 때마다 반복이었다. 앱 열기(②) 멈춤 쪽은 상세 화면을 이미 받고 있었는데, 시작 멈춤에는 같은 규칙이 없었다.
  세션이 유지된 채 시작하는 경우는 테스트에도 없었다(테스트 기본 화면이 목록이라 「내 솔루션」 시작이 빠졌다).
- **다음에** — 안내 문구가 판매자에게 어떤 화면으로 가라고 하면, **그 화면에서 그 멈춤의 「다시 확인」을 누르는 테스트**를 같이 둔다. 라이브
  준비 때는 로그아웃 시작과 세션 유지 시작을 서로 다른 경우로 센다(프로필이 영구라 로그인은 실행을 넘어 남는다).
- **출처** — 2026-10-19, `collector/src/action-window/api-issuance/issuance-engine.ts` `enterFromOpenedApp`,
  `collector/test/action-window/api-issuance/issuance-session.test.ts` 「a walk that starts with the NAVER session still logged in」.

### 강조 확인 뒤 운영자가 연결 폼을 제출했다 — 두 번째 (2026-10-19)

- **했던 것** — 강조 확인(READ_ONLY) 승인서에 "reviewnary 입력란은 제출하지 않습니다"를 적고, 실행 중에는 호스트 로그만 봤다.
- **사실** — 마지막 단계가 SellerOps 연결 화면을 열어 주므로, 운영자가 그 폼에 방금 받은 값을 넣고 저장하는 것이 흐름상 자연스럽다.
  4차 뒤에도(5차 preflight에서 발견), 7차에서도 그렇게 일회용 DB에 자격증명이 생겼다. 호스트 로그에는 아무 흔적이 없고 DB를 세어야만 보인다.
- **다음에** — 강조 확인이 끝날 때마다 `connector_credentials` · `channel_connection_status` 수를 센다(값 칸은 읽지 않는다). 생겼으면
  운영자에게 확인하고 승인받아 지운다. 제출 자체를 검증하려면 별도 WRITE 승인으로 따로 잡는다.
- **출처** — 2026-10-19 run `wt-5df0900659e7`(`apr-92f0a1237acb`), 5차 행(`docs/evidence/INDEX.md`).

### 촬영 도구가 거절된 안내 뒤에도 찍어서 로그인 화면이 깜빡였다 (2026-10-18)

- **했던 것** — 강조 확인 6차에서 가린 스크린샷을 켜고 시작했다. 촬영은 "패널을 그리는 메서드가 불린 뒤"마다 따라붙게 감쌌다.
- **사실** — 네이버 로그인 폼에서는 세션이 로그인 안내를 0.5초마다 다시 그리려 하고, 드라이버는 설계대로 매번 거절한다(그리지
  않음). 감싼 쪽은 결과를 보지 않고 호출마다 촬영했다 — 30초에 54장, 매번 가림 막을 입력란 위에 씌웠다 걷었다 해서 운영자가 로그인
  하는 화면이 "박동"했다. 거절이 0.5초마다 일어난다는 사실은 세션 코드 주석에 이미 적혀 있었다.
- **다음에** — 무언가를 "메서드 호출 뒤"에 붙일 때는 **호출이 실제로 한 일**(그렸는가)을 조건으로 쓴다. 폴링 루프 안에서 불리는
  메서드에 부작용을 붙이기 전에 그 호출 빈도를 먼저 센다. 비밀번호 칸이 있는 화면은 어떤 도구도 건드리지 않는다(`ce465a3a`).
- **출처** — 2026-10-18 run `wt-c2ec193626d3`, `ce465a3a`, `collector/instruments/live-runs/masked-capture.ts`.

### 가림 검증이 「감지 못 한 시크릿」을 통과시켰다 (2026-10-18)

- **했던 것** — 강조 확인 5차에서 패널마다 자동 스크린샷을 남겼다. 가림을 적용하고 "감지된 민감 요소가 전부 덮였는가"를 촬영 전후로
  검증해 통과할 때만 저장했다. ⑦에서 운영자가 「보기」를 누른 뒤 완료 안내 촬영본에 **애플리케이션 시크릿이 평문으로** 찍혔고, 그
  이미지를 세션에서 열었다.
- **사실** — 검증은 감지기가 찾은 것만 센다. 감지기는 그 값을 찾지 못했다: 「복사」 버튼은 속성으로만 판정(네이버 버튼엔 글자뿐),
  긴 토큰 패턴은 `$` · `/`로 끊긴 해시 모양을 놓침, 자격증명 영역은 속성만 보고 화면 라벨 「애플리케이션 시크릿」을 보지 않음. 그래서
  "덮은 수 = 감지 수"가 0 = 0으로 초록이었다 — §「검사 도구의 실패는 초록색으로 나타난다」의 다섯째 사례와 같은 모양이다. 앞선
  측정들은 시크릿이 ●●●로 가려진 상태에서만 돌아서 이 구멍이 드러나지 않았다.
- **다음에** — 가림은 "무엇처럼 보이는가"보다 **"어디에 있는가"** 를 먼저 쓴다: 자격증명 라벨의 행은 내용과 무관하게 덮는다
  (`fdfb54f3`). 가림을 바꾸거나 새 화면에 쓰기 전에, 값이 **드러난 상태**(「보기」 누른 뒤)를 재현한 고정물로 실패하는지 먼저 본다.
  운영자가 값을 드러낼 수 있는 단계에서는 자동 촬영을 하지 않는 것도 선택지다. 촬영본은 열기 전에 그 단계가 값을 드러낼 수 있는지 본다.
- **출처** — 2026-10-18 run `wt-ffcdcea76be7`, `fdfb54f3`, `test/instruments/masked-capture-browser.test.ts`.

### 감시 필터의 `cut`이 줄을 모아 두어 라이브 진행을 실시간으로 못 봤다 (2026-10-17)

- **했던 것** — 강조 확인 4차 호스트 로그를 `tail -f | grep -v --line-buffered … | cut -c1-300`으로 감시했다.
- **사실** — `cut`은 파이프에 줄 단위로 내보내지 않는다. ⑥까지 진행이 로그에는 있었는데 감시는 30분 동안 0건이었고, 운영자가
  "다음까지 했어"라고 말한 뒤에야 로그를 직접 읽었다.
- **다음에** — 감시 파이프의 모든 단계는 줄 단위로 내보내야 한다(`grep --line-buffered`, `awk … fflush()`). `cut` · `head` ·
  `sed`(비 `-u`)는 쓰지 않는다. 운영자가 움직였다고 할 때 감시가 조용하면 감시를 먼저 의심한다.
- **출처** — 2026-10-17 run `wt-cc1bb2fe42c4`, 이 세션의 감시 명령.

### 걷기 끝의 「돌아가기」가 연 새 탭이 걷기의 시작 화면을 보였다 — 쿠팡 D2의 반복 (2026-10-20)

- **했던 것** — 강조 확인 10차에서 `fce3e4d3`(돌아가기에 실행 ID를 실음)을 확인했다. 돌아간 탭은 이 실행에 묶인 걷기 화면이었고
  「환경 확인 실패」도 없었다. 그런데 화면은 「화면 안내로 진행하기 (도우미 필요)」 시작 문 그대로였다.
- **사실** — 돌아가기는 걷기를 시작한 탭을 이동시키지 않고 기본 브라우저에 **새 탭**을 연다(`surface:"DEFAULT_BROWSER"`). 새 탭에서
  안내 단계는 복원되지 않고(`application_issuance_guided`는 복원 대상이 아님), 시작 여부는 컴포넌트 안의 상태라 `false`로 시작한다.
  쿠팡은 2026-08-13 같은 증상(D2)을 `?issuance=resume`과 판독기로 고쳤지만 NAVER 화면에는 판독기가 없었고, 반환 URL 주석은
  "읽는 쪽이 없으니 표시를 싣지 않는다"고 적고 있었다. 9차까지는 돌아간 화면이 「환경 확인 실패」에 먼저 막혀 이 문제가 가려져 있었다.
- **다음에** — 돌아가기 같은 **채널 사이 이동 경로는 한 채널에서 고친 것을 다른 채널에도 맞춰 본다.** 앞의 막힘을 고친 직후에는 그 뒤에
  가려져 있던 화면을 끝까지 본다. 표시와 판독기는 두 패키지에 나뉘어 있어 어긋나도 조용하므로, 두 쪽을 한 교차 테스트로 묶는다.
- **출처** — 2026-10-20 run `wt-5bf616a2c9d0`(`apr-9581410a53a5`), `collector/src/cli/sellerops-return-url.ts`,
  `frontend/src/components/guidedConnection/NaverIssuanceGuidedWalkthrough.tsx`, `collector/test/crossstack/naver-issuance-fe-copy-parity.test.ts`.

### 답글 대행 서버가 계약 문서의 세 문장을 코드에서 놓쳤다 (2026-10-21)

- **했던 것** — 답글 대행 상태 기계(`36f7736e`)를 계약 §4대로 만들었다고 보고했다. 테스트 95개와 변형 증명 4건이 통과했다.
- **사실** — 오너 리뷰에서 세 군데가 계약과 달랐다. ① 휴대폰 수정이 `RECHECK_NEEDED`를 `AWAITING_APPROVAL`로 되돌려, 옛 정책 지문
  그대로 승인할 수 있었다(계약: "PC가 지금의 지문으로 다시 내야 승인"). ② 착수한 건의 등록 직전 검사가 본문과 답글 여부만 보고
  전시 상태를 보지 않았다(계약: 숨김 · 삭제는 `전시상태`로만 안다). ③ 판매자용 작업 응답에 리뷰 원문이 없었다(계약 §4.3 표:
  "리뷰 원문 + 초안 + 메타"). 세 테스트 모두 정상 경로만 확인하고, 계약 문장마다 "우회 경로"를 넣어 보지 않았다.
- **다음에** — 상태 기계는 **계약의 금지 문장마다 그 금지를 우회하는 다른 입구**(다른 역할 · 다른 엔드포인트 · 다른 필드)를 테스트로
  하나씩 넣는다. 응답 필드는 계약 표의 열과 한 줄씩 대조한다.
- **출처** — 2026-10-21 오너 리뷰, `vendor-server/src/main/java/com/sellerops/vendor/task/ReplyTasks.java`,
  `V5__reply_task_guards.sql`, `ReplyTasksTest`(`editingADraftOnThePhoneIsNotAPolicyRecheck` · `aClaimedReviewThatIsHiddenIsNotClearToPost`).

### '끝난 작업'을 '답글이 없는 리뷰'로 읽어 PC 승인 길을 다시 열었다 (2026-10-21)

- **했던 것** — PC 쪽 대행 연결(`8e83bdcf`)에서 서버의 종료 상태(`POSTED` · `FAILED` · `CANCELLED` · `INVALIDATED`)를 그대로 써서
  "열린 작업이 없으면 PC의 승인 · 가이드 등록 길을 연다"고 했다. 테스트도 `POSTED ends it`을 단정했다.
- **사실** — `POSTED`는 서버 작업의 끝이지 리뷰의 끝이 아니다. 담당자가 등록했다고 보고한 뒤, 다음 엑셀 반입에서 `답글여부=Y`가
  보이기 전까지 PC의 어느 것도 답글이 나간 줄 모르므로 그 사이 PC에서 승인 · 등록하면 두 번째 답글이 된다. 화면 작업 중 코드를 다시
  읽다 발견했고, 테스트는 잘못된 단정을 초록으로 지키고 있었다.
- **다음에** — 다른 시스템의 상태 집합을 내 쪽 규칙의 조건으로 빌려 쓸 때는 **그 상태가 내 쪽 질문("답글이 나갔나")에 같은 답을
  하는지** 상태마다 따로 적는다. 종료 상태 묶음을 통째로 가져오지 않는다.
- **출처** — `backend/src/main/java/com/sellerops/vendorlink/VendorReplyTaskService.java` `holds`,
  `VendorReplyTaskScenarios.aDelegatesPostIsRecordedOnceAsReportedAndUnverifiedWithThePhoneText`, 2026-10-21.

### 링크의 `#` 뒤만 바뀐 이동은 페이지를 다시 읽지 않는다 (2026-10-21)

- **했던 것** — 담당자 초대 수락 화면(`/delegate/join#<코드>`)이 코드를 페이지를 읽을 때 한 번만 읽게 만들었다.
- **사실** — 이미 `/delegate/join`이 열린 탭에서 링크를 열면 주소의 `#` 뒤만 바뀌는 같은 문서 안 이동이라 스크립트가 다시 돌지 않고,
  화면은 "초대 링크가 없습니다"에 머문다. 단위 테스트로는 보이지 않고, 실제 브라우저에서 빈 화면 → 링크 순서로 열 때 처음 드러났다.
- **다음에** — 비밀을 fragment로 받는 화면은 **로드와 `hashchange` 둘 다에서** 읽고, 브라우저 확인에 "같은 탭에서 다시 연다"를 넣는다.
- **출처** — `vendor-server/src/main/resources/static/delegate/join.js` `start`, 2026-10-21.

### 「비었나」를 기간 숫자로 물으면 지난 철 데이터를 가진 가게가 빈 가게가 된다 (2026-10-24)

- **했던 것** — PC 「오늘」은 채널 표의 7일 숫자(주문 · 문의 · 리뷰)와 문의 미답변 잔고만 보고 "연결됐지만 아무것도 없음"을 골랐다.
- **사실** — 화면만으로 끝까지 돌린 확인(`596b931c`)에서 엑셀로 들인 리뷰 6건이 모두 170일쯤 전 것이라 7일 숫자가 0이었고, 「오늘」은
  "첫 수집이 끝나면…" · 확인할 일 0건이라고 했다. 같은 때 「확인할 일」에는 5건이 있었다. 문의는 같은 구멍을 9월에 잔고로 막았는데 리뷰는
  남아 있었고, 에이전트 런타임(`SellerReadiness`)은 이미 기간 없는 저장 건수를 보고 있어 두 쪽 판단이 달랐다.
- **다음에** — "이 가게가 비었나"는 **기간 없는 보유 건수**로 묻는다. 기간 숫자는 "이번 주에 무엇이 왔나"에만 쓴다. 같은 질문을 하는
  다른 쪽(런타임)과 기준이 같은지 확인한다.
- **출처** — `frontend/src/lib/homeFirstUse.ts` `holdsAnything`, `ChannelMetricRow.reviewsHeld`, 2026-10-24.

### PC 안의 기록만 읽는 할 일 목록은 담당자 길의 상태를 모른다 (2026-10-24)

- **했던 것** — 「확인할 일」의 답글 줄은 PC의 초안 · 승인 기록만으로 「승인 대기」 · 「초안 필요」를 정했다.
- **사실** — 휴대폰 승인이나 「승인하고 담당자에게 맡기기」의 승인은 공급자 서버에 서고 PC 승인 기록에는 남지 않는다. 그래서 담당자가
  등록했다고 보고한 답글도, 보류된 답글도, 등록하지 못한 답글도 모두 「승인 대기」로 보였다. 리뷰 화면의 패널은 같은 때 정확했다 —
  패널만 담당자 길을 읽고 있었다.
- **다음에** — 한 리뷰의 답글이 갈 수 있는 길이 둘 이상이면, 그 상태를 말하는 **모든 화면이 모든 길을 읽는지** 확인한다. 패널 하나가
  맞다고 목록도 맞는 것이 아니다.
- **출처** — `backend/src/main/java/com/sellerops/operations/ReviewWorkController.java` `WITH_THE_DELEGATE`,
  `DelegatedReplyLane.statesOf`, 2026-10-24.

---

## 어디에 무엇이 있나

| 찾는 것 | 가는 곳 |
|---|---|
| 지켜야 하는 규칙 | `CLAUDE.md` |
| 현역 문서 전체 지도 | `docs/INDEX.md` |
| 라이브 실행 이력 (한 런 = 한 행) | `docs/evidence/INDEX.md` |
| 소스가 인용하는 과거 기록 · 없는 문서 이름 | `docs/lineage_index.md` |
| 링크 검사 | `tools/docs-link-check.py` |
