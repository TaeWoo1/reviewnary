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
- `agent-runtime/` — standalone Node/TS **LangGraph** orchestration service (port 8787), and since
  2026-08-21 SellerOps's **AI Operator 실행 구조**: an `OperatorGraph` that interprets a goal **with an
  LLM planner and no deterministic fallback** (v2 — no plan, no run), chooses
  specialists (ProductOps · ReviewOps · InquiryOps · ReportOpsNode) and tools, keeps **evidence as
  first-class state**, judges what may be said, and stops on a bounded budget — over the four existing
  compiled graphs with human `interrupt`/resume, whose contracts are unchanged. Tools adapt onto Spring.
  A docker-compose service with its own CI status check and a live `/agent` route. It **holds no
  credential of any kind**: the draft, planner and judge models are all BACKEND capabilities reached
  with the operator's forwarded bearer, so "the backend is the only LLM egress" is still the property to
  check here. **The Operator's tool catalogue is 100% READ — there is no WRITE tool and a structural
  test refuses one** (`OperatorToolRegistry`, `operatorToolRegistry.test.ts`); credential handoff, Action
  Window commands and guided-submission mints are deliberately absent from it (`privilegedPlaneFence`).
  **Two lanes, and they are not the same product:** a button sends a closed `intent` and runs
  deterministically; a typed sentence is planned by the LLM or the run **fails**. Canonical:
  **`docs/sellerops_operator_graph_v2.md`** (제품 행동 계약 — Agent chat의 계획은 **반드시 LLM Planner**가
  세우고, 세울 수 없으면 run은 실패한다; 결정론 goal/keyword planner는 test·fallback 용도로도 존재하지
  않는다) · `docs/sellerops_operator_graph_v1.md`(구현·라이브 증명 기록, runtime semantics는 v2가 대체) ·
  `docs/decisions/agent-runtime-langgraph-llm-split.md`.
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
  `docs/sellerops_live_approval_contract.md`** — the single source for the Standing Safety Contract,
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
| 4 | **decisions** | ADRs and standing contracts | `docs/decisions/` · the contracts in the **Document index** below — the *Standing contracts and ADRs* group, plus the lane documents under *Inquiry* / *Review* / *Knowledge and retrieval*, which are contracts rather than history |
| 5 | **evidence** | every live run: date, channel, capability, commit, approval id, outcome | `docs/evidence/INDEX.md` |

**Demo 제품 정의:** `docs/demo_core_experience_v1.md` owns **데모로 보여줄 SellerOps** — 화면과 경험의 순서를
정하고 기존 canonical technical 문서를 덮어쓰지 않는다. 로컬 데모 절차는 `docs/demo_runbook_v1.md`.
**FE/IA는 A7(2026-08-18) 기준으로 frozen** — `docs/product_assembly_ia_v1.md` §8. `frontend/` 디자인 계약은
`docs/reviewnary_design.md`이고, 거기 없는 색·서체·컴포넌트 라이브러리는 허가되지 않는다.

**Evidence rule.** Every live run gets a row in `docs/evidence/INDEX.md` in the same PR that lands its
proof. **Landing a proof document without a row there is a defect** — an unlinked proof is how Coupang
`ORDER_SUMMARY` stayed recorded as "인증 골격만" for two weeks after it was live-proven
(`docs/channel_integration_completeness_audit_v1.md` §5). A proof file may only be retired once its row
carries its whole unique claim.

**Status lives in workstream homes, not here:** Action Window runtime → `docs/action-window-runtime/`
(`HANDOFF.md`); Action Window frontend → `docs/workstreams/action-window-frontend/` (`progress.md`);
ESM live capture → `docs/esm/` (`live-capture-checklist.md`); review operations MVP →
`docs/workstreams/review_operations_mvp.md`; **review AI triage demo ("리뷰 AI 데모 준비" and the like) →
`docs/workstreams/review_ai_triage_demo.md`** (canonical entry point). A router carries paths, not state.

### Conflict priority

1. explicit product-owner decisions from the current task
2. `docs/product-scope-v1.md`
3. `docs/product_assembly_ia_v1.md` (IA / screens / visible channels), then `docs/sellerops_frontend_spec.md`
   (frontend principles)
4. `docs/sellerops_local_agent_runtime_adr.md`
5. `docs/multi-channel-connector-roadmap.md` §4.1 (living capability table)
6. the active slice document (`docs/slices/*` — index: `docs/slices/README.md`)
7. current implementation evidence
8. historical records under `docs/archive/` and the r4 evidence in `docs/action-window-runtime/`

Implementation evidence may reveal docs are stale, but must not silently redefine product intent —
**report the conflict** instead. That is exactly how the Coupang `ORDER_SUMMARY` correction happened.
For Action Window *status*, `docs/action-window-runtime/HANDOFF.md` wins.

### Assumption rule

Do not invent product, UX, channel-support, API, or security decisions. Verify repository facts
before relying on them. Surface product-owner decisions rather than resolving them. Classify every
unresolved point as: repository-verifiable, external-research required, or product-owner decision.
When uncertain, stop and report rather than guess.

## Document index

Every document the reading path and the rules above depend on, with its own title. **This is a map, not a
summary** — a package's reasoning, measurements and decisions live in its document, and this file does not
restate them. Anything under `docs/` that is not listed here is lineage or evidence: reachable from these,
but not carrying current truth on its own (`docs/archive/` especially).

### Orientation, scope, architecture

- `docs/architecture.md` — SellerOps architecture — the map
- `docs/demo_core_experience_v1.md` — Demo Core Experience v1 — SellerOps 데모 제품 정의
- `docs/demo_runbook_v1.md` — SellerOps Demo Runbook v1 (product assembly A7 — 2026-08-18)
- `docs/multi-channel-connector-roadmap.md` — Multi-Channel Connector Roadmap
- `docs/product-scope-v1.md` — Product Scope v1.13 — Drift Guard
- `docs/product_assembly_ia_v1.md` — Product Assembly v1 — 목표 IA와 화면 책임 (정본)
- `docs/product_operating_model.md` — SellerOps — Product Operating Model
- `docs/sellerops_canonical_reference.md` — SellerOps — Canonical Reference: Product, Strategy, State
- `docs/sellerops_frontend_spec.md` — SellerOps Frontend Spec — 통합 셀러센터 리디자인 정본

### Standing contracts and ADRs

- `docs/auth_growth_instrumentation_v1.md` — Auth + Growth Instrumentation v1 — contract
- `docs/coupang_review_policy_gate_v1.md` — Coupang WING Review — Policy Gate v1
- `docs/decisions/agent-runtime-langgraph-llm-split.md` — ADR — the LangGraph runtime and the LLM are two disjoint subsystems
- `docs/learning_outcome_loop_v1.md` — Learning & Outcome Loop v1 — 한 일과 그 결과가 다음 판단에 들어간다
- `docs/operational_knowledge_direction_v1.md` — Operational Knowledge Direction v1 — 방향만, 구현 없음
- `docs/order_context_foundation_v1.md` — Order Context Foundation v1 — 주문이 자기 상품과 도착을 알게 된다
- `docs/proactive_operations_agent_v1.md` — Proactive Operations Agent v1
- `docs/responsibility_runtime_v1.md` — Responsibility Runtime v1
- `docs/self_pilot_runtime_v1.md` — Self-Pilot Runtime v1 — audit, design, implementation (2026-08-18)
- `docs/sellerops_live_approval_contract.md` — SellerOps Live-Run Approval Contract (canonical) — v1
- `docs/sellerops_local_agent_runtime_adr.md` — ADR — SellerOps Local Agent Runtime & Guided-Connection Architecture
- `docs/sellerops_local_to_pilot_connectivity_decision.md` — Decision — SellerOps Local-to-Pilot Connectivity (NAVER egress IP · Cafe24 callback)
- `docs/sellerops_operator_graph_v1.md` — SellerOps Operator Graph v1 — 구현 계약 (정본)
- `docs/sellerops_operator_graph_v2.md` — SellerOps Operator Graph v2 — 제품 행동 계약 · 구현 계약 (정본)
- `docs/seller_declared_operations_policy_v1.md` — Seller-declared Operations Policy v1 — 판매자가 정한 처리 기준이 다음 판단에 들어간다
- `docs/service_readiness_v1.md` — Service Readiness v1 — contract

### Inquiry lane

- `docs/inquiry_action_flow_v1.md` — Inquiry Action Flow v1 — 초안 → 승인 → 실행
- `docs/inquiry_answer_execution_v1.md` — Inquiry Answer Execution v1 — 1단계: 계약 재조정과 READ proof manifest
- `docs/inquiry_operational_truth_v1.md` — Inquiry Operational Truth & Sync Lifecycle Hardening — v1
- `docs/inquiry_operations_workspace_v1.md` — Data Origin Integrity + Inquiry Operations Workspace v1
- `docs/inquiry_thread_semantics_v1.md` — Cafe24 Thread Semantics Recovery v1 — 답글은 문의가 아니다
- `docs/inquiry_workflow_completion_v2.md` — Inquiry Workflow Completion v2 — 근거가 먼저 옳아야 한다

### Review lane

- `docs/agentic_report_v1.md` — Issue Evidence Trust Closure + Agentic Report v1 (2026-09-04)
- `docs/cafe24_comment_answer_observation_v1.md` — Cafe24 Comment Answer Observation v1
- `docs/cafe24_review_comment_execution_v1.md` — Cafe24 Review Seller Comment Execution v1
- `docs/naver_review_date_picker_capability_v1.md` — NAVER 리뷰 기간 선택 capability v1 — 측정, 규칙, 그리고 탐색 방식의 결정
- `docs/opportunity_engine_v1.md` — Auth Entry Regression Closure + Opportunity Engine v1
- `docs/repeated_issue_v1.md` — Repeated Issue v1 — 반복되는 문제 하나를 판단하고 추적하는 화면
- `docs/review_approval_path_v1.md` — Review Approval Path v1 — 특정 리뷰 답변을 검토하고 승인하는 길
- `docs/review_auto_check_v1.md` — Review Auto-Check v1 — 자동 리뷰 확인 (제품 계약 · 구현 계약)
- `docs/review_auto_check_live_proof_v1.md` — Review Auto-Check — Live Proof v1 (2026-10-09 → 10-10)
- `docs/review_decision_workspace_v1.md` — Review Decision Workspace v1 — 한 리뷰를 한 화면에서 판단하고 기록한다
- `docs/review_delivery_truth_spine_v1.md` — Review Delivery Truth Spine v1 — 리뷰 답변이 어디까지 갔는지, 한 곳에서 말한다
- `docs/review_reply_template_settings_v1.md` — Review Reply Template Settings v1
- `docs/review_triage_contract_v1.md` — Review Triage Contract v1 — 두 축을 분리한다
- `docs/workstreams/review_ai_triage_demo.md` — Review AI Triage — Demo Home (canonical entry point)
- `docs/workstreams/review_operations_mvp.md` — Review Operations MVP — Workstream Home

### Knowledge and retrieval

- `docs/agent_product_self_knowledge_v1.md` — Agent Product Self-Knowledge v1
- `docs/answer_applicability_v1.md` — Answer Applicability v1 — 근거가 검색됐다는 것과, 그 근거를 이 질문에 쓸 수 있다는 것
- `docs/demo_org_and_channel_knowledge_v1.md` — Production-like Demo Org + Channel Knowledge v1
- `docs/exact_operational_context_v1.md` — Exact Operational Context v1 — 문의가 지목한 주문 하나를, 그때 읽는다
- `docs/image_product_knowledge_v1.md` — Image Product Knowledge v1 — 설계, 그리고 그 아래에 깔린 것
- `docs/knowledge_capture_learning_loop_v1.md` — Knowledge Capture / Learning Loop v1 (2026-08-30)
- `docs/knowledge_gap_resolution_v1.md` — Knowledge Gap Resolution v1 — 모르는 것을 판매자에게 묻고, 다시 판단한다
- `docs/knowledge_retrieval_quality_v1.md` — Knowledge Retrieval Quality v1
- `docs/knowledge_retrieval_quality_v2.md` — Knowledge Retrieval Quality v2
- `docs/knowledge_setup_inbox_ux_v1.md` — Knowledge Setup & Inbox UX v1
- `docs/operational_fact_binding_v1.md` — Operational Fact Binding v1 — 주문을 문의에 붙인다
- `docs/organization_answer_style_v1.md` — Organization Answer Style v1
- `docs/retrieval_grounding_correctness_v1.md` — Retrieval & Grounding Correctness v1 (2026-08-30)
- `docs/retrieval_runtime_closure_v1.md` — Retrieval Runtime Closure v1
- `docs/seller_operations_knowledge_and_answer_memory_v1.md` — Seller Operations Knowledge & Answer Memory v1

### Agent runtime and conversation

- `docs/agent_command_center_v1.md` — Agent Command Center v1
- `docs/agent_interaction_model_v2.md` — Agent Interaction Model v2 — Conversation as an Operating Workspace (2026-08-31)
- `docs/agent_object_first_use_v1.md` — Agent Object + First-use Closure v1
- `docs/agent_procedure_layer_v1.md` — Agent Procedure Layer v1
- `docs/agent_responsiveness_v1.md` — Agent Responsiveness v1
- `docs/agent_runtime_architecture_audit_v1.md` — Agent Runtime Architecture Audit v1
- `docs/agent_runtime_production_closure_v1.md` — Agent Runtime Production Closure v1
- `docs/agent_semantic_ownership_v1.md` — Agent Semantic Ownership Closure v1
- `docs/agentic_experience_ux_v2.md` — Agentic Experience + UI/UX v2
- `docs/agentic_operating_workspace_v2.md` — Reviewnary Agentic Operating Workspace v2
- `docs/aop_execution_closure_v1.md` — AOP Execution Closure v1
- `docs/chat_first_agent_shell_v1.md` — Chat-first Agent Shell Completion v1
- `docs/contextual_agent_contract_completion_v1.md` — Contextual Agent Contract Completion v1
- `docs/contextual_agent_workspace_v1.md` — Contextual Agent Workspace & Interactive UX QA v1
- `docs/conversation_contract_correctness_v2.md` — Conversation Contract Correctness v2
- `docs/conversation_core_chat_ux_v1.md` — Reviewnary Conversation Core + Chat UX v1 (2026-08-31)
- `docs/conversation_object_integrity_v1.md` — Conversation Object Integrity v1 (2026-08-30)
- `docs/conversation_ux_v2.md` — Conversation UX v2 — 업무 봇에서 운영 비서로
- `docs/frontend_agent_workspace_v1.md` — Frontend-first Agent Workspace Redesign v1
- `docs/grounded_conversation_lane_v1.md` — Grounded Conversation Lane v1 + Cross-Lane Context Continuity
- `docs/langgraph_orchestration_aop_v1.md` — LangGraph Orchestration Migration + AOP Runtime Core v1
- `docs/planner_model_benchmark_v1.md` — Planner Model Benchmark v1
- `docs/planner_model_prompt_benchmark_v1.md` — Planner Model & Prompt Benchmark v1
- `docs/seller_facing_response_hygiene_v1.md` — Seller-facing Response Hygiene v1 (2026-08-30)
- `docs/working_context_v1.md` — Working Context v1 — 대화가 지금 무엇을 붙잡고 있는지 보이게 한다

### Frontend and UX

- `docs/core_daily_loop_ux_v1.md` — Core Daily Loop UX Integration v1
- `docs/demo_ux_polish_v1.md` — Demo UX Polish v1 — 화면 감사와 정리
- `docs/disconnected_channel_onboarding_v1.md` — Disconnected Channel Onboarding Live Walkthrough v1
- `docs/executive_readiness_fix_v1.md` — Executive Readiness Fix v1
- `docs/executive_ux_redesign_v1.md` — Executive-friendly UX Redesign v1
- `docs/frontend_ux_audit_v1.md` — Frontend UX Audit v1 — 현재 IA · 재사용 컴포넌트 · 재설계 원칙
- `docs/operational_workspace_ux_v1.md` — Operational Workspace UX System v1
- `docs/operations_home_v1.md` — Operations Home v1 — 로그인하면 「지금 확인할 것」이 화면에 있다
- `docs/outcome_artifact_visual_closure_v1.md` — Outcome Artifact + Visual Final Closure v1
- `docs/product_operations_continuity_v1.md` — Product Operations Continuity v1
- `docs/reviewnary_design.md` — reviewnary Design Contract v3
- `docs/reviewnary_visual_system_v1.md` — Reviewnary Visual System + Conversation Shell v1
- `docs/secondary_workspaces_ux_closure_v1.md` — Secondary Workspaces UX Closure v1
- `docs/visual_qa_product_polish_v1.md` — Visual QA & Product Polish Closure v1

### Pilot and deployment

- `docs/demo_seller_validation_v1.md` — Demo Seller Validation v1 — 외부 판매자 검증 준비 (2026-09-22)
- `docs/design_skills_bootstrap.md` — Design skills — bootstrap
- `docs/full_pilot_walkthrough_v1.md` — Full Pilot Walkthrough v1 — 처음 쓰는 판매자 한 명으로 제품을 끝까지 통과하기
- `docs/helper_device_authentication_v1.md` — Helper Device Authentication v1 — 결정과 구현 (2026-09-05)
- `docs/local_helper_pilot_packaging_v1.md` — Local Helper Pilot Packaging v1 (2026-09-05)
- `docs/naver_guided_acquisition_live_findings_closure_v1.md` — NAVER Guided Acquisition — Live Findings Closure v1
- `docs/pilot_connection_external_proof_gate_v1.md` — Pilot Connection & External Proof Gate v1
- `docs/pilot_host_provisioning_v1.md` — Pilot Host Provisioning v1 — PREPARE
- `docs/pilot_readiness_closure_v1.md` — Pilot Readiness Closure v1
- `docs/pilot_readiness_gate_v1.md` — Pilot Readiness Gate v1
- `docs/pilot_readiness_v2.md` — Pilot Readiness v2 — 첫 외부 판매자를 받을 수 있는가
- `docs/pilot_release_closure_v1.md` — Pilot Release Closure v1
- `docs/pilot_runtime_foundation_v1.md` — Pilot Runtime Foundation v1
- `docs/pilot_usage_loop_v1.md` — Pilot Usage Loop v1 — 계측 감사와 측정 계약

### Capability views (never promote a status)

- `docs/channel-capability-registration-matrix.md` — Channel Capability & Registration Matrix
- `docs/channel_capability_ledger.md` — SellerOps — Channel Capability Ledger
- `docs/channel_integration_completeness_audit_v1.md` — Channel integration completeness audit v1 (2026-08-19)
- `docs/post_purchase_timeline_audit_v1.md` — Post-purchase Timeline + Review Opportunity v1 — 감사 (2026-10-10)

### Evidence and workstream homes

- `docs/action-window-runtime/HANDOFF.md` — HANDOFF — Action Window Runtime (NAVER / Coupang)
- `docs/evidence/INDEX.md` — Live-proof evidence index
- `docs/slices/README.md` — Slice index
