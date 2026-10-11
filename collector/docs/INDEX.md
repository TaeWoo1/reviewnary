# collector/docs — 색인

`collector/`의 설계 문서 29개를 관심사별로 묶은 지도다. 규칙은 `collector/CLAUDE.md`와
저장소 루트의 `CLAUDE.md`에 있고 여기엔 없다. 레인별 폴더로 옮기지 않은 이유는 하나다 —
`collector/src/`와 테스트가 이 경로들을 주석으로 인용하고 있어서, 옮기면 문서 정리가 소스
변경으로 번진다(`docs/FINDINGS.md` §B6).

**경로 주의.** 이 서브트리의 문서가 적는 `docs/<이름>.md`는 **`collector/docs/<이름>.md`**를
뜻한다 — 저장소 루트 기준이 아니다. 루트에서 링크 검사를 돌리면 깨진 것처럼 보이지만
정상이다(`docs/FINDINGS.md` §B5). 저장소 루트의 문서를 가리킬 때는 `docs/standing/…`처럼
루트 기준 전체 경로를 쓴다.

## 채널 접속 · 연결

| 문서 | 무엇을 소유하나 |
|---|---|
| `connection-onboarding.md` | 연결 온보딩 불변식 — 계정 바인딩과 auth identity |
| `connector-orchestrator-model.md` | 커넥터 오케스트레이션 모델 |
| `connector-sync-state-model.md` | 동기화 상태 모델 (§1–§11) |
| `session-classifier-model.md` | 세션 분류 |
| `local-agent-startup.md` | 로컬 에이전트 기동 |

## ESM+ (리뷰 · 문의)

| 문서 | 무엇을 소유하나 |
|---|---|
| `esmplus-access-model.md` | 접근 모델 |
| `esmplus-feasibility.md` | 타당성 |
| `esmplus-review-export-discovery.md` | Gate 0–4 사다리와 라이브 발견 |
| `esmplus-review-data-policy.md` | **Policy A — STRICT NO-LEAK** (`esm-review-row-shape.ts`가 인용) |
| `esmplus-review-row-shape-design.md` | 행 모양 |
| `esmplus-review-header-capture-design.md` | 헤더 캡처 |
| `esmplus-review-dedup-strategy-design.md` | 중복 제거 전략 |
| `esmplus-review-db-ingest-design.md` | DB 적재 |
| `esm-trading-cs-inquiry-api-model.md` | ESM 거래/CS 문의 API 모델 |

## 문의 (inquiry)

| 문서 | 무엇을 소유하나 |
|---|---|
| `inquiry-intake-slice-model.md` | 수집 슬라이스 |
| `inquiry-ingestion-bridge-model.md` | 적재 브리지 |
| `inquiry-approval-execution-slice-model.md` | 승인 → 실행 슬라이스 |

## 주의 신호 · 우선순위 (attention)

| 문서 | 무엇을 소유하나 |
|---|---|
| `attention-signal-model.md` | 신호 모델 |
| `attention-digest-model.md` | 다이제스트 |
| `attention-view-model.md` | 화면 모델 |
| `priority-score-model.md` | 우선순위 점수 |

## 시간 · 최신성 (recency)

| 문서 | 무엇을 소유하나 |
|---|---|
| `recency-bucket-model.md` | `recencyBucket` — **셀러에게 보이는 유일한 시간 표현** |
| `recency-scoring-policy.md` | 점수 정책 |
| `recency-timestamp-source-audit.md` | 타임스탬프 출처 감사 |
| `recency-timezone-policy.md` | 시간대 정책 |

> `eventTimeMs` 같은 내부 타이밍은 표면에 나오지 않는다 — 루트 `CLAUDE.md` § Safety fences.

## 제품 구조 · 도메인

| 문서 | 무엇을 소유하나 |
|---|---|
| `two-track-product-architecture.md` | Seller Track / Manufacturer Track 2트랙 |
| `commerce-work-domain-model.md` | 커머스 업무 도메인 모델 |
| `sellerops-event-model.md` | 이벤트 모델 |
| `review-collection-strategy.md` | 리뷰 수집 전략 |

## 현재 진실이 아닌 것

- `archive/local-uncommitted-work-inventory.md` — 2026-06-30 당시 로컬 작업 트리의 스냅샷.
  그 트리는 더 존재하지 않는다. 1회성 기록이므로 2026-10-12에 `archive/`로 옮겼다.
- `../findings/*.local.md` — gitignore 대상인 로컬 probe 기록(2026-08-08 WING no-key 3건).
  저장소 내용이 아니다.
- `../findings/milestone1.md` — 추적되는 마일스톤 기록.

라이브 실행 이력은 이 폴더가 아니라 저장소 루트의 `docs/evidence/INDEX.md`가 소유한다.
반복하지 말아야 할 시행착오는 `docs/FINDINGS.md`에 있다.
