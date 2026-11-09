# Reply Standard v1 — 좋은 답글 기준표 · 응대 필요 판단 기준

**사장님 결정 2026-10-28 (「기준표 그대로 가고」).** 리뷰·문의 답글을 쓰는 에이전트가 읽는 기준이다. 판단 단계는
`categories`와 `priority`를, 초안 단계는 그 범주의 `skeleton` · `mustNot`과 `rules`를, 검토 단계는 `rules`의 규칙
번호를 읽는다. 평가 채점도 같은 규칙 번호로 한다.

- `standard.json` — 정본. 근거와 출처는 `docs/research/_drafts/reply-standard-v0.md`,
  `docs/research/_drafts/response-judgement-standard-v0.md`, 같은 폴더의 세 조사 초안.
- 범주(`categories[].code`): S 안전 · L 법적 분쟁 · R 해결 요청 · Q 질문 · D 품질 불만 · M 혼합 · V 막연한 불만 ·
  X 신고 대상 · P 순수 칭찬. `needsResponse`가 true인 것이 「응대 필요」(S·L·R·Q·D·M), `handoff`가 true인 것은
  초안을 확정하지 않고 사람에게 넘긴다(S·L·X).
- 규칙(`rules[].id`): N = 쓰지 않는 것(법·플랫폼), G = 반드시 하는 것, F = 형식.

**판매자마다 다른 것은 여기 없다.** 말투·자체 정책·상품 사실·응대 기준(별점대별 미리 쓰기)은 판매자 설정과 등록된
지식에서 온다. 이 파일은 특정 판매자나 상품에 맞춘 문장을 싣지 않는다.

**바꾸는 법.** 규칙을 고치면 `version`을 올리고(`reply-standard/v2`), 새 폴더로 둔다 — 이 버전으로 쓰인 초안과 채점은
이 버전으로 다시 읽을 수 있어야 한다. 백엔드는 `backend/src/main/resources/reply-standard/v1/standard.json`에 같은 바이트의
사본을 들고, `ReplyStandardContractTest`가 둘이 같음을 고정한다.
