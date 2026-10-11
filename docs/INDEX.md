# Document index — 현역 문서 전체 목록

`CLAUDE.md`가 가리키는 지도다. **요약이 아니라 지도** — 패키지의 추론·측정·결정은 그 패키지의
문서에 있고, 이 파일은 그것을 옮겨 적지 않는다. 규칙은 전부 `CLAUDE.md`에 있고 여기엔 없다.

## 폴더 지도

| 폴더 | 무엇이 있나 |
|---|---|
| `docs/` (루트 12개) | `CLAUDE.md`가 이름으로 부르는 문서 — reading path 6정점 · 이 색인 · `FINDINGS.md` · `lineage_index.md` |
| `docs/standing/` | standing contracts · ADR (`docs/decisions/`를 2026-10-12에 흡수) |
| `docs/inquiry/` `docs/review/` | 문의 · 리뷰 레인 |
| `docs/agent/` | agent runtime · conversation |
| `docs/knowledge/` | 지식 · retrieval |
| `docs/frontend/` | UI · UX |
| `docs/pilot/` | 파일럿 · 배포 |
| `docs/channel/` | capability view (**status를 승격하지 않는다**) |
| `docs/lineage/` | 소스가 주석으로 인용해 제자리에 남은 과거 기록 — 현재 진실이 아니다 |
| `docs/archive/` | 그 이전 lineage |
| `docs/evidence/` | 라이브 실행 이력 (한 런 = 한 행) |
| `docs/slices/` `docs/workstreams/` `docs/action-window-runtime/` `docs/esm/` `docs/vendor/` | 기존 구조 그대로 |

여기 적힌 문서만 **현재 진실을 싣는다.** `docs/` 아래 나머지는 lineage 아니면 evidence다:

- `docs/lineage_index.md` — 소스가 주석으로 인용해서 제자리에 남은 과거 기록 43개
- `docs/archive/2026-q3/MANIFEST.md` — 2026 Q3 1회성 기록 63개
- `docs/archive/` — 그 이전 lineage
- `docs/evidence/INDEX.md` — 라이브 실행 이력 (**모든 라이브 런은 여기 한 행을 갖는다**)
- `docs/vendor/` — 채널 API 원문 사본 (NAVER / Coupang / Cafe24)
- `docs/slices/README.md` — slice 색인

파일을 여기 추가하는 것은 **그 문서가 현재 진실을 싣는다는 선언**이다. 1회성 기록 ·
live proof · selector calibration · audit · closure record는 여기 올라오지 않는다 —
`docs/evidence/INDEX.md`의 행이 그 자리다.

### Orientation, scope, architecture

- `docs/architecture.md` — SellerOps architecture — the map
- `docs/demo_core_experience_v1.md` — Demo Core Experience v1 — SellerOps 데모 제품 정의
- `docs/demo_runbook_v1.md` — SellerOps Demo Runbook v1 (product assembly A7 — 2026-08-18)
- `docs/multi-channel-connector-roadmap.md` — Multi-Channel Connector Roadmap
- `docs/product-scope-v1.md` — Product Scope v1.13 — Drift Guard
- `docs/product_assembly_ia_v1.md` — Product Assembly v1 — 목표 IA와 화면 책임 (정본)
- `docs/product_operating_model.md` — SellerOps — Product Operating Model
- `docs/sellerops_canonical_reference.md` — SellerOps — Canonical Reference: Product, Strategy, State
- `docs/frontend/sellerops_frontend_spec.md` — SellerOps Frontend Spec — 통합 셀러센터 리디자인 정본
- `docs/sellerops_local_env_setup.md` — SellerOps — Local Environment Setup (Live-Smoke Preparation)

### Standing contracts and ADRs

- `docs/standing/auth_growth_instrumentation_v1.md` — Auth + Growth Instrumentation v1 — contract
- `docs/standing/coupang_review_policy_gate_v1.md` — Coupang WING Review — Policy Gate v1
- `docs/standing/coupang_guided_issuance_credential_lifecycle_scope_v1.md` — Coupang Guided Issuance + Credential Lifecycle — Canonical Scope v1
- `docs/standing/agent-runtime-langgraph-llm-split.md` — ADR — the LangGraph runtime and the LLM are two disjoint subsystems
- `docs/standing/pairing-native-approval.md` — ADR — pairing native approval
- `docs/standing/learning_outcome_loop_v1.md` — Learning & Outcome Loop v1 — 한 일과 그 결과가 다음 판단에 들어간다
- `docs/standing/operational_knowledge_direction_v1.md` — Operational Knowledge Direction v1 — 방향만, 구현 없음
- `docs/standing/operations_daily_loop_v1.md` — Operations Daily Loop v1 — 감사, 그리고 이은 세 자리
- `docs/standing/pilot_reliability_v1.md` — Pilot Reliability v1 — 72시간 무인 read-only 운전을 위한 감사
- `docs/standing/order_context_foundation_v1.md` — Order Context Foundation v1 — 주문이 자기 상품과 도착을 알게 된다
- `docs/standing/proactive_operations_agent_v1.md` — Proactive Operations Agent v1
- `docs/standing/responsibility_runtime_v1.md` — Responsibility Runtime v1
- `docs/standing/self_pilot_runtime_v1.md` — Self-Pilot Runtime v1 — audit, design, implementation (2026-08-18)
- `docs/standing/sellerops_live_approval_contract.md` — SellerOps Live-Run Approval Contract (canonical) — v1
- `docs/standing/sellerops_local_agent_runtime_adr.md` — ADR — SellerOps Local Agent Runtime & Guided-Connection Architecture
- `docs/standing/sellerops_local_to_pilot_connectivity_decision.md` — Decision — SellerOps Local-to-Pilot Connectivity (NAVER egress IP · Cafe24 callback)
- `docs/standing/sellerops_operator_graph_v1.md` — SellerOps Operator Graph v1 — 구현 계약 (정본)
- `docs/standing/sellerops_operator_graph_v2.md` — SellerOps Operator Graph v2 — 제품 행동 계약 · 구현 계약 (정본)
- `docs/standing/seller_declared_operations_policy_v1.md` — Seller-declared Operations Policy v1 — 판매자가 정한 처리 기준이 다음 판단에 들어간다
- `docs/standing/service_readiness_v1.md` — Service Readiness v1 — contract

### Inquiry lane

- `docs/inquiry/inquiry_action_flow_v1.md` — Inquiry Action Flow v1 — 초안 → 승인 → 실행
- `docs/inquiry/inquiry_answer_execution_v1.md` — Inquiry Answer Execution v1 — 1단계: 계약 재조정과 READ proof manifest
- `docs/inquiry/inquiry_operational_truth_v1.md` — Inquiry Operational Truth & Sync Lifecycle Hardening — v1
- `docs/inquiry/inquiry_operations_workspace_v1.md` — Data Origin Integrity + Inquiry Operations Workspace v1
- `docs/inquiry/inquiry_thread_semantics_v1.md` — Cafe24 Thread Semantics Recovery v1 — 답글은 문의가 아니다
- `docs/inquiry/inquiry_workflow_completion_v2.md` — Inquiry Workflow Completion v2 — 근거가 먼저 옳아야 한다

### Review lane

- `docs/review/agentic_report_v1.md` — Issue Evidence Trust Closure + Agentic Report v1 (2026-09-04)
- `docs/review/cafe24_comment_answer_observation_v1.md` — Cafe24 Comment Answer Observation v1
- `docs/review/cafe24_review_comment_execution_v1.md` — Cafe24 Review Seller Comment Execution v1
- `docs/review/naver_review_date_picker_capability_v1.md` — NAVER 리뷰 기간 선택 capability v1 — 측정, 규칙, 그리고 탐색 방식의 결정
- `docs/review/opportunity_engine_v1.md` — Auth Entry Regression Closure + Opportunity Engine v1
- `docs/review/repeated_issue_v1.md` — Repeated Issue v1 — 반복되는 문제 하나를 판단하고 추적하는 화면
- `docs/review/review_approval_path_v1.md` — Review Approval Path v1 — 특정 리뷰 답변을 검토하고 승인하는 길
- `docs/review/review_auto_check_v1.md` — Review Auto-Check v1 — 자동 리뷰 확인 (제품 계약 · 구현 계약)
- `docs/review/review_auto_check_live_proof_v1.md` — Review Auto-Check — Live Proof v1 (2026-10-09 → 10-10)
- `docs/review/review-auto-check-next-check-at-early-due.md` — Review Auto-Check — `nextCheckAt` early-due 조사
- `docs/review/review_decision_workspace_v1.md` — Review Decision Workspace v1 — 한 리뷰를 한 화면에서 판단하고 기록한다
- `docs/review/review_acquisition_baseline_v1.md` — Review Acquisition Baseline v1 — CURRENT FACT (현행 취득 구현)
- `docs/review/review_delivery_truth_spine_v1.md` — Review Delivery Truth Spine v1 — 리뷰 답변이 어디까지 갔는지, 한 곳에서 말한다
- `docs/review/review_reply_template_settings_v1.md` — Review Reply Template Settings v1
- `docs/review/review_triage_contract_v2.md` — Review Triage Contract v2 — severity와 closure를 분리한다 (v1 §0 승계·개정)
- `docs/review/review_triage_contract_v1.md` — Review Triage Contract v1 — 두 축을 분리한다
- `docs/workstreams/review_ai_triage_demo.md` — Review AI Triage — Demo Home (canonical entry point)
- `docs/workstreams/review_operations_mvp.md` — Review Operations MVP — Workstream Home

### Knowledge and retrieval

- `docs/knowledge/agent_product_self_knowledge_v1.md` — Agent Product Self-Knowledge v1
- `docs/knowledge/answer_applicability_v1.md` — Answer Applicability v1 — 근거가 검색됐다는 것과, 그 근거를 이 질문에 쓸 수 있다는 것
- `docs/knowledge/demo_org_and_channel_knowledge_v1.md` — Production-like Demo Org + Channel Knowledge v1
- `docs/knowledge/exact_operational_context_v1.md` — Exact Operational Context v1 — 문의가 지목한 주문 하나를, 그때 읽는다
- `docs/knowledge/image_product_knowledge_v1.md` — Image Product Knowledge v1 — 설계, 그리고 그 아래에 깔린 것
- `docs/knowledge/knowledge_capture_learning_loop_v1.md` — Knowledge Capture / Learning Loop v1 (2026-08-30)
- `docs/knowledge/knowledge_gap_resolution_v1.md` — Knowledge Gap Resolution v1 — 모르는 것을 판매자에게 묻고, 다시 판단한다
- `docs/knowledge/knowledge_retrieval_quality_v1.md` — Knowledge Retrieval Quality v1
- `docs/knowledge/knowledge_retrieval_quality_v2.md` — Knowledge Retrieval Quality v2
- `docs/knowledge/knowledge_setup_inbox_ux_v1.md` — Knowledge Setup & Inbox UX v1
- `docs/knowledge/knowledge_spine_v1.md` — Knowledge Spine v1 — Customer Operations Manager Demo v1 · Q1
- `docs/knowledge/operational_fact_binding_v1.md` — Operational Fact Binding v1 — 주문을 문의에 붙인다
- `docs/knowledge/organization_answer_style_v1.md` — Organization Answer Style v1
- `docs/knowledge/retrieval_grounding_correctness_v1.md` — Retrieval & Grounding Correctness v1 (2026-08-30)
- `docs/knowledge/retrieval_runtime_closure_v1.md` — Retrieval Runtime Closure v1
- `docs/knowledge/seller_operations_knowledge_and_answer_memory_v1.md` — Seller Operations Knowledge & Answer Memory v1

### Agent runtime and conversation

- `docs/agent/agent_command_center_v1.md` — Agent Command Center v1
- `docs/agent/agent_interaction_model_v2.md` — Agent Interaction Model v2 — Conversation as an Operating Workspace (2026-08-31)
- `docs/agent/agent_object_first_use_v1.md` — Agent Object + First-use Closure v1
- `docs/agent/agent_procedure_layer_v1.md` — Agent Procedure Layer v1
- `docs/agent/agent_responsiveness_v1.md` — Agent Responsiveness v1
- `docs/agent/agent_runtime_architecture_audit_v1.md` — Agent Runtime Architecture Audit v1
- `docs/agent/agent_runtime_production_closure_v1.md` — Agent Runtime Production Closure v1
- `docs/agent/agent_semantic_ownership_v1.md` — Agent Semantic Ownership Closure v1
- `docs/agent/agentic_experience_ux_v2.md` — Agentic Experience + UI/UX v2
- `docs/agent/agentic_operating_workspace_v2.md` — Reviewnary Agentic Operating Workspace v2
- `docs/agent/aop_execution_closure_v1.md` — AOP Execution Closure v1
- `docs/agent/chat_first_agent_shell_v1.md` — Chat-first Agent Shell Completion v1
- `docs/agent/contextual_agent_contract_completion_v1.md` — Contextual Agent Contract Completion v1
- `docs/agent/contextual_agent_workspace_v1.md` — Contextual Agent Workspace & Interactive UX QA v1
- `docs/agent/conversation_contract_correctness_v2.md` — Conversation Contract Correctness v2
- `docs/agent/conversation_core_chat_ux_v1.md` — Reviewnary Conversation Core + Chat UX v1 (2026-08-31)
- `docs/agent/conversation_object_integrity_v1.md` — Conversation Object Integrity v1 (2026-08-30)
- `docs/agent/conversation_ux_v2.md` — Conversation UX v2 — 업무 봇에서 운영 비서로
- `docs/agent/frontend_agent_workspace_v1.md` — Frontend-first Agent Workspace Redesign v1
- `docs/agent/grounded_conversation_lane_v1.md` — Grounded Conversation Lane v1 + Cross-Lane Context Continuity
- `docs/agent/langgraph_orchestration_aop_v1.md` — LangGraph Orchestration Migration + AOP Runtime Core v1
- `docs/agent/planner_model_benchmark_v1.md` — Planner Model Benchmark v1
- `docs/agent/planner_model_prompt_benchmark_v1.md` — Planner Model & Prompt Benchmark v1
- `docs/agent/seller_facing_response_hygiene_v1.md` — Seller-facing Response Hygiene v1 (2026-08-30)
- `docs/agent/working_context_v1.md` — Working Context v1 — 대화가 지금 무엇을 붙잡고 있는지 보이게 한다

### Frontend and UX

- `docs/frontend/core_daily_loop_ux_v1.md` — Core Daily Loop UX Integration v1
- `docs/frontend/demo_ux_polish_v1.md` — Demo UX Polish v1 — 화면 감사와 정리
- `docs/frontend/disconnected_channel_onboarding_v1.md` — Disconnected Channel Onboarding Live Walkthrough v1
- `docs/frontend/executive_readiness_fix_v1.md` — Executive Readiness Fix v1
- `docs/frontend/executive_ux_redesign_v1.md` — Executive-friendly UX Redesign v1
- `docs/frontend/frontend_ux_audit_v1.md` — Frontend UX Audit v1 — 현재 IA · 재사용 컴포넌트 · 재설계 원칙
- `docs/frontend/operational_workspace_ux_v1.md` — Operational Workspace UX System v1
- `docs/frontend/operations_home_v1.md` — Operations Home v1 — 로그인하면 「지금 확인할 것」이 화면에 있다
- `docs/frontend/outcome_artifact_visual_closure_v1.md` — Outcome Artifact + Visual Final Closure v1
- `docs/frontend/product_operations_continuity_v1.md` — Product Operations Continuity v1
- `docs/frontend/reviewnary_design.md` — reviewnary Design Contract v3
- `docs/frontend/reviewnary_ui_system_audit_v1.md` — Reviewnary UI System Audit v1 (`tailwind.config.ts` 등 FE 6곳이 인용)
- `docs/frontend/reviewnary_visual_system_v1.md` — Reviewnary Visual System + Conversation Shell v1
- `docs/frontend/secondary_workspaces_ux_closure_v1.md` — Secondary Workspaces UX Closure v1
- `docs/frontend/visual_qa_product_polish_v1.md` — Visual QA & Product Polish Closure v1

### Pilot and deployment

- `docs/pilot/demo_seller_validation_v1.md` — Demo Seller Validation v1 — 외부 판매자 검증 준비 (2026-09-22)
- `docs/pilot/design_skills_bootstrap.md` — Design skills — bootstrap
- `docs/pilot/full_pilot_walkthrough_v1.md` — Full Pilot Walkthrough v1 — 처음 쓰는 판매자 한 명으로 제품을 끝까지 통과하기
- `docs/pilot/helper_device_authentication_v1.md` — Helper Device Authentication v1 — 결정과 구현 (2026-09-05)
- `docs/pilot/local_helper_pilot_packaging_v1.md` — Local Helper Pilot Packaging v1 (2026-09-05)
- `docs/pilot/naver_guided_acquisition_live_findings_closure_v1.md` — NAVER Guided Acquisition — Live Findings Closure v1
- `docs/pilot/pilot_connection_external_proof_gate_v1.md` — Pilot Connection & External Proof Gate v1
- `docs/pilot/pilot_host_provisioning_v1.md` — Pilot Host Provisioning v1 — PREPARE
- `docs/pilot/pilot_readiness_closure_v1.md` — Pilot Readiness Closure v1
- `docs/pilot/pilot_readiness_gate_v1.md` — Pilot Readiness Gate v1
- `docs/pilot/pilot_readiness_v2.md` — Pilot Readiness v2 — 첫 외부 판매자를 받을 수 있는가
- `docs/pilot/pilot_readiness_v3.md` — Pilot Readiness v3 — Cafe24 Inquiry 파일럿 (**v2보다 최신**)
- `docs/pilot/pilot_release_closure_v1.md` — Pilot Release Closure v1
- `docs/pilot/pilot_runtime_foundation_v1.md` — Pilot Runtime Foundation v1
- `docs/pilot/pilot_usage_loop_v1.md` — Pilot Usage Loop v1 — 계측 감사와 측정 계약

### Capability views (never promote a status)

- `docs/channel/channel-capability-registration-matrix.md` — Channel Capability & Registration Matrix
- `docs/channel/channel_capability_ledger.md` — SellerOps — Channel Capability Ledger
- `docs/channel/channel_integration_completeness_audit_v1.md` — Channel integration completeness audit v1 (2026-08-19)
- `docs/channel/post_purchase_timeline_audit_v1.md` — Post-purchase Timeline + Review Opportunity v1 — 감사 (2026-10-10)
- `docs/channel/review_acquisition_capability_matrix_v1.md` — Review Acquisition Capability Matrix v1

### Evidence and workstream homes

- `docs/action-window-runtime/HANDOFF.md` — HANDOFF — Action Window Runtime (NAVER / Coupang)
- `docs/evidence/INDEX.md` — Live-proof evidence index
- `docs/slices/README.md` — Slice index
