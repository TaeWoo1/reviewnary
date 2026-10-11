# CLAUDE.md

Instructions for working in the active SellerOps repo.

**What this file is.** A router and a rule sheet. The rules below are binding; the document index at the
end is a map. It carries **paths, not state** — status lives in the documents and workstream homes it
points to, and a package's own reasoning stays in that package's document.

## Product identity

**SellerOps is a multi-channel commerce operations AI agent for SME sellers/manufacturers.**
It carries operational work between human decisions — normalizing reviews, inquiries, orders, and
reports across the seller's channels. It is **not** a scraper dump, a browser click-bot, or a
VOC/cardnews project.

**Product assembly (2026-08-17, product-owner decision):** the product is **workflow-centric**
(홈 / 리뷰 / 문의 / 주문 / 채널 연결), not channel-centric; channel expansion is **paused** and the
seller-visible channel set is exactly **NAVER / Coupang / Cafe24** (a channel on screen is a channel
that is actually usable — `ProductChannels.java`, `lib/productChannels.ts`). Canonical:
`docs/product_assembly_ia_v1.md`.

Canonical product / strategy / state reference: `docs/sellerops_canonical_reference.md` (re-derive
state before citing at any later commit). **FE/IA is frozen as of A7 (2026-08-18)** —
`docs/product_assembly_ia_v1.md` §8; the local demo procedure is `docs/demo_runbook_v1.md`.

## Where development happens

- Normal work: **`sellerops/repo`** (this repo).
- Isolated feature work: **`sellerops/worktrees/<feature-name>/`** (created from this repo).
- **Never develop in `sellerops/runtime-holders/`.**

## Runtime holders

`sellerops/runtime-holders/` contains preserved runtime worktrees linked to the shared `aiagent/.git`
host. They hold live browser profiles, `.env` files, connections, and run state that exist in exactly
one place on disk and are **not** recoverable from git.

- Do not `git clean` there.
- Do not delete ignored files there.
- Do not move a holder with plain `mv` — use `git worktree move`, and only when explicitly approved.
- Do not read, print, or copy `.env` or connection secrets.

## Active source ownership

- `backend/` — Spring Boot service (Java, Gradle, Postgres/Flyway, JWT). **The only LLM egress** — now
  two capabilities, each with its own flag, key, transport, prompt and payload floor, and each provably
  unable to reach the other's (`review/triage/llm/` for review triage; `agent/llm/` for the agent
  runtime's draft seam).
- `frontend/` — React/Vite operations UI.
- `collector/` — TypeScript local agent: channel acquisition + Action Window (NAVER, ESM, Cafe24).
- `agent-runtime/` — standalone Node/TS **LangGraph** orchestration service: the `OperatorGraph`
  over four compiled graphs with human `interrupt`/resume, whose contracts are unchanged. What it is
  and how it connects: `docs/architecture.md`, then **`docs/standing/sellerops_operator_graph_v2.md`** (정본
  제품 행동 계약) · `docs/standing/sellerops_operator_graph_v1.md` (구현·라이브 증명 기록; runtime semantics는
  v2가 대체) · `docs/decisions/agent-runtime-langgraph-llm-split.md`. Four fences live here:
  - It **holds no credential of any kind.** The draft, planner and judge models are all BACKEND
    capabilities reached with the operator's forwarded bearer, so "the backend is the only LLM
    egress" is still the property to check here.
  - **The Operator's tool catalogue is 100% READ — there is no WRITE tool and a structural test
    refuses one** (`OperatorToolRegistry`, `operatorToolRegistry.test.ts`). Credential handoff,
    Action Window commands and guided-submission mints are deliberately absent (`privilegedPlaneFence`).
  - A goal is interpreted **with an LLM planner and no deterministic fallback** — no plan, no run.
    결정론 goal/keyword planner는 test·fallback 용도로도 존재하지 않는다.
  - **Two lanes, and they are not the same product:** a button sends a closed `intent` and runs
    deterministically; a typed sentence is planned by the LLM or the run **fails**.
- `contracts/` — shared contracts (Action Window, review fingerprint).
- `tools/` — dev/support tooling.
- `docs/` — current SellerOps docs; `docs/archive/` holds historical material.

Map of how these connect, and which document owns each part: `docs/architecture.md` (pointer only).

## Safety fences

- **No CAPTCHA / 2FA bypass**, no auth bypass.
- **No hidden or chained platform clicks** — manual progress always remains available.
- **No automatic export / download / submit** as product behavior — only through an explicit,
  approved **human checkpoint**.
- **Official APIs first; the Action Window** pattern for user-confirmed platform actions: the seller
  clicks export/consent/download/submit on the marketplace; SellerOps only detects, validates, and
  processes the result.
- **Fail closed** on ambiguous, missing, or changed platform targets.
- **Sanitized output only** — never expose credentials, tokens, cookies, seller IDs, API keys, JWTs,
  raw page content, screenshots, exported files, or personal data. Internal timing (`eventTimeMs`)
  never surfaces; only `recencyBucket` may.

## Branch / PR rules

- Work from **feature branches** (never commit product changes directly to `main`).
- **No force-push** unless explicitly approved.
- **No live marketplace runs** without a fresh, single-use, in-turn approval. A plan, a prior
  approval, or a restored environment is never authorization. **Canonical contract:
  `docs/standing/sellerops_live_approval_contract.md`** — the single source for the Standing Safety Contract,
  the Approval Manifest, and the approval lifecycle. Do not restate the rule elsewhere; link there.
  - **Default = one line.** When `bootstrap`/`preflight` has prepared and displayed a valid
    **Approval Manifest** (channel / account / surface / operation / mode / allowed actions on the
    record), the operator's entire single-use grant is the one line **"Seated and ready."**, bound
    to that manifest's `approvalId` + `runId` + scope. Ask for more only in the exceptions the
    canonical contract §3 lists (no manifest; account/operator/date unfixed; scope changed; process
    restarted; or a **WRITE/submission**, which always needs its own explicit mode-`WRITE` approval).
  - **Same-session, same-scope retries need no re-approval** (the live debug-loop). A change of
    channel / account / scope, a new session, or any code/branch/run/environment change ⇒ the
    approval is `REVOKED`; re-bootstrap for a new `approvalId` and a fresh grant.
- Never print secrets. Stage exact files — never `git add .`; never stage `.env`, `.profile/`,
  `.status/`, `.connections/`, `downloads/`, credentials, or real seller data.

### Commit messages

**The subject line says what changed.** This repository is public: the subject is the one line a
reader sees in the file list, the blame view and the commit log, and it has to identify the change
without the body.

```
<type>(<scope>): <what changed>
```

`type` — `feat` · `fix` · `docs` · `test` · `refactor` · `chore`.
`scope` — the area touched (`pilot`, `inquiry`, `backup`, `retrieval`, `agent`, `frontend`, …).
Subject in the imperative, around 60 characters, no trailing period.

**This repository is public, so write the subject in English** — an English Conventional Commit
subject is the recommended default for every commit that lands on `main`. Korean stays welcome in
the body, where it usually says more precisely what was measured.

**Never** write a subject as a poetic line, an essay opening, or a sentence describing the situation
that led to the change.

**The body carries the reasoning; the subject never does.** Put the why, the measurements, the
verification results, the rejected alternatives and the residual limits in the body — at whatever
length the change deserves. None of it is lost by keeping it out of the subject; it is one line
down, where a reader who wants it will look and a reader scanning the log will not have to.

Good:

```
fix(pilot): validate dump before destructive restore
feat(backup): add off-host PostgreSQL backup
fix(inquiry): align publish outcome states
docs(pilot): update deployment readiness
```

Bad:

```
스키마를 먼저 지우고 나서...
리허설이 좋은 덤프 하나만...
RC1을 동결이라 적어 두고도...
```

## Canonical reading path

Six stops, in order. Everything else in `docs/` is evidence or lineage reached **from** these — if a
document is not on this path and nothing here links to it, it does not carry current truth.

| # | Stop | Owns | Document |
|---|---|---|---|
| 0 | orientation | what SellerOps is, who for, channel posture, user journey — **points, owns nothing** | `docs/product_operating_model.md` |
| 1 | **product scope / journeys** | identity, strategy, honest state, authority · the scope contract | `docs/sellerops_canonical_reference.md` · `docs/product-scope-v1.md` (**scope lock v1.13**) |
| 2 | **architecture** | the five runtimes, how they connect, the fail-closed gates — **a pointer page** | `docs/architecture.md` |
| 3 | **capability truth** | channel × DataType × method × status — **the single declaration** | `docs/multi-channel-connector-roadmap.md` §4.1 |
| 4 | **decisions** | ADRs and standing contracts | `docs/decisions/` · the contracts in `docs/INDEX.md` — the *Standing contracts and ADRs* group, plus the lane documents under *Inquiry* / *Review* / *Knowledge and retrieval*, which are contracts rather than history |
| 5 | **evidence** | every live run: date, channel, capability, commit, approval id, outcome | `docs/evidence/INDEX.md` |

**Demo 제품 정의:** `docs/demo_core_experience_v1.md` owns **데모로 보여줄 SellerOps** — 화면과 경험의 순서를
정하고 기존 canonical technical 문서를 덮어쓰지 않는다. 로컬 데모 절차는 `docs/demo_runbook_v1.md`.
**FE/IA는 A7(2026-08-18) 기준으로 frozen** — `docs/product_assembly_ia_v1.md` §8. `frontend/` 디자인 계약은
`docs/frontend/reviewnary_design.md`이고, 거기 없는 색·서체·컴포넌트 라이브러리는 허가되지 않는다.

**Evidence rule.** Every live run gets a row in `docs/evidence/INDEX.md` in the same PR that lands its
proof. **Landing a proof document without a row there is a defect** — an unlinked proof is how Coupang
`ORDER_SUMMARY` stayed recorded as "인증 골격만" for two weeks after it was live-proven
(`docs/channel/channel_integration_completeness_audit_v1.md` §5). A proof file may only be retired once its row
carries its whole unique claim.

**Status lives in workstream homes, not here:** Action Window runtime → `docs/action-window-runtime/`
(`HANDOFF.md`); Action Window frontend → `docs/workstreams/action-window-frontend/` (`progress.md`);
ESM live capture → `docs/esm/` (`live-capture-checklist.md`); review operations MVP →
`docs/workstreams/review_operations_mvp.md`; **review AI triage demo ("리뷰 AI 데모 준비" and the like) →
`docs/workstreams/review_ai_triage_demo.md`** (canonical entry point). A router carries paths, not state.

### Conflict priority

1. explicit product-owner decisions from the current task
2. `docs/product-scope-v1.md`
3. `docs/product_assembly_ia_v1.md` (IA / screens / visible channels), then `docs/frontend/sellerops_frontend_spec.md`
   (frontend principles)
4. `docs/standing/sellerops_local_agent_runtime_adr.md`
5. `docs/multi-channel-connector-roadmap.md` §4.1 (living capability table)
6. the active slice document (`docs/slices/*` — index: `docs/slices/README.md`)
7. current implementation evidence
8. historical records under `docs/archive/` and the r4 evidence in `docs/action-window-runtime/`

Implementation evidence may reveal docs are stale, but must not silently redefine product intent —
**report the conflict** instead. That is exactly how the Coupang `ORDER_SUMMARY` correction happened.
For Action Window *status*, `docs/action-window-runtime/HANDOFF.md` wins.

### 기능은 사라지지 않는다 (정리 · 리팩터 게이트)

애써 만든 기능이 정리 과정에서 조용히 없어지는 것이 가장 비싼 사고다. 그래서 문서 정리든
코드 리팩터든 **범위가 저장소 전체에 닿는 작업**은 다음을 지킨다.

1. **시작 전에 기능 기준선을 측정하고 숫자를 적는다** — backend endpoint 수 · Flyway
   migration 수 · frontend route 수 · collector CLI 수 · 소스 파일 수. 숫자 없이 시작하면
   무엇이 사라졌는지 말할 방법이 없다.
2. **끝나고 같은 숫자를 다시 측정해 델타 0을 증명한다.** 보고에 두 숫자를 나란히 적는다.
3. **일괄 치환은 역변환으로 증명한다** — 치환 결과에 역매핑을 적용해 원본과 **바이트 단위로
   같은지** 확인한다. 같다면 그 치환이 경로 문자열만 바꿨다는 증명이다.
4. **`backend/src/main/resources/db/migration/`은 어떤 일괄 작업의 대상도 아니다.** 적용된
   마이그레이션은 주석조차 불변이다 — 체크섬이 달라지면 기존 DB의 `validate`가 깨진다
   (`docs/FINDINGS.md` §B7).
5. 삭제는 **확인된 목록에 한정**한다. 위험한 통합은 분리해 따로 승인받고, 범위가 커지면
   멈추고 보고한다.

### 틀린 것은 기록한다

라이브 런 · 리팩터 · 디버그에서 **시간을 썼거나 틀렸던 것**을 발견하면
**`docs/FINDINGS.md`에 한 행을 추가한다.** 네 가지가 다 있어야 한 행이 된다 — 무엇을 했나 ·
무엇이 사실이었나 · 다음에 지킬 것 · 출처(커밋·문서·날짜). 그 장부는 append만 한다. 행이
규칙으로 굳으면 이 파일로 승격시키고 거기엔 포인터를 남긴다.

**읽는 때는 쓰는 때보다 중요하다** — 라이브 런 전, 리팩터 전, "왜 안 바뀌지" 싶을 때
`docs/FINDINGS.md`를 먼저 읽는다.

### Assumption rule

Do not invent product, UX, channel-support, API, or security decisions. Verify repository facts
before relying on them. Surface product-owner decisions rather than resolving them. Classify every
unresolved point as: repository-verifiable, external-research required, or product-owner decision.
When uncertain, stop and report rather than guess.

## Document index

현역 문서 전체 목록은 **`docs/INDEX.md`**에 있다 — 묶음별 지도다. 이 파일에 그 목록을 다시
적지 않는다. **규칙은 여기, 지도는 거기.**

| 찾는 것 | 가는 곳 |
|---|---|
| 현역 문서 전체 (묶음별) + 폴더 지도 | `docs/INDEX.md` |
| **과거 finding — 같은 시행착오를 두 번 하지 않기 위한 장부** | `docs/FINDINGS.md` |
| 라이브 실행 이력 — 모든 라이브 런의 한 행 | `docs/evidence/INDEX.md` |
| 소스가 주석으로 인용하는 과거 기록 | `docs/lineage_index.md` |
| 2026 Q3 1회성 기록 | `docs/archive/2026-q3/MANIFEST.md` |
| 그 이전 lineage | `docs/archive/` |
| 채널 API 원문 사본 | `docs/vendor/` |
| slice 색인 | `docs/slices/README.md` |
| 문서 링크 검사 | `python3 tools/docs-link-check.py` |

`docs/INDEX.md`에 등록되지 않은 `docs/` 파일은 **lineage 아니면 evidence**다: 위 경로에서
도달할 수는 있어도 그 자체로 현재 진실을 싣지 않는다.

### 새 문서를 만들기 전에

**1회성 기록은 새 파일이 아니다.** 라이브 런 · live proof · selector calibration · 세션 handoff ·
감사 결과 · closure record는 **`docs/evidence/INDEX.md`의 한 행**이 제자리다. 한 행에 날짜 ·
채널 · capability · 커밋 · approval id · 결과를 적고 끝낸다. 그 행이 담지 못할 분량이면,
담지 못하는 부분만 그 레인의 **현역 문서**에 붙인다.

**새 파일은 마지막 수단이다.** 만들기 전에 세 가지를 순서대로 확인한다.

1. `docs/evidence/INDEX.md`의 한 행으로 끝나는가 → 끝난다면 행만 적는다.
2. 그 레인의 현역 문서(`docs/INDEX.md`)를 고쳐서 담기는가 → 담긴다면 고친다.
3. 둘 다 아니라면 — 새 계약이나 새 레인이라면 — 파일을 만들고 **같은 PR에서
   `docs/INDEX.md`에 등재한다.** 등재하지 않은 파일은 정의상 현재 진실을 싣지 않으므로,
   만들자마자 lineage가 된다.

**버전을 올리기 전에.** `_v2`는 `_v1`이 그대로 옳고 그 위에 얹을 때만 만든다. v1이 틀렸으면
v1을 고친다. 새 파일을 만들었으면 v1의 어느 절을 승계하고 어느 절을 개정하는지 그 문서
§0에 줄 단위로 적고, `docs/INDEX.md`의 v1 항목에 그 사실을 적는다 — 인덱스가 구판만
가리키는 상태로 두지 않는다.
