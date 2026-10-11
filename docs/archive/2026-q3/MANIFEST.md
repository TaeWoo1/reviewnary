# Archive — 2026 Q3 lineage (2026-10-12 정리)

이 폴더의 문서는 **현재 진실을 싣지 않는다.** 모두 2026-06-25 ~ 2026-09-30 사이의
1회성 기록 — live proof, selector calibration, audit, closure record, 단계별 work package —
이고, 정리 시점에 `CLAUDE.md`의 reading path와 Document index 어느 쪽에서도 도달할 수
없었다. 파일은 지워지지 않았고 `git mv`로 옮겼을 뿐이므로 blame과 history는 그대로다.

**읽는 규칙.** 여기 있는 문장을 현재 동작의 근거로 인용하지 않는다. 인용해야 한다면
먼저 `docs/INDEX.md`에서 그 레인의 현역 문서를 찾고, 거기에 없는 사실이면 코드에서
다시 도출한다. 라이브 실행 이력은 이 폴더가 아니라 `docs/evidence/INDEX.md`가 소유한다.

**여기 없는 것.** 소스 코드가 주석으로 인용하는 과거 기록 43개는 옮기지 않았다 —
경로를 바꾸면 소스 120여 곳을 같이 고쳐야 하고, 그 인용이 바로 그 문서들을 아직
load-bearing하게 만들고 있다. 목록은 `docs/lineage_index.md`에 있다.

| 문서 | 마지막 커밋 | 줄 | 제목 |
|---|---|---|---|
| `naver-connection-strategy-v1.md` | 2026-07-29 | 134 | NAVER API Connection Strategy v1 |
| `sellerops_cafe24_c1_boards_live_verification.md` | 2026-07-29 | 74 | Cafe24 Phase C1 — board-discovery live verification (refresh + rotation + /boards) |
| `sellerops_cafe24_c2_order_summary_live_verification.md` | 2026-07-29 | 84 | Cafe24 Phase C2 — ORDER_SUMMARY live verification + order-read hardening |
| `sellerops_cafe24_review_read_live_verification.md` | 2026-07-30 | 126 | Cafe24 REVIEW_READ live verification + 비밀글(secret) fail-closed exclusion |
| `sellerops_agent_runtime_operator_pilot_v1.md` | 2026-07-30 | 158 | SellerOps Agent Runtime — Operator Pilot v1 |
| `sellerops_cafe24_first_connection_tutorial_live_proof.md` | 2026-07-31 | 73 | Cafe24 First Connection Tutorial v1 — live onboarding proof (sanitized) |
| `sellerops_cafe24_review_issue_memory_bridge_downstream_proof.md` | 2026-07-31 | 76 | Cafe24 Review → Issue-Memory Bridge — real-source downstream processing proof (sanitized) |
| `naver_live_first_connection_proof_v2.md` | 2026-08-05 | 52 | NAVER Main Live First-Connection Proof v2 |
| `naver_onboarding_ux_live_regression_proof_v1.md` | 2026-08-05 | 70 | NAVER Onboarding UX Live Regression Proof v1 |
| `coupang_already_issued_guided_walkthrough_live_regression_v1.md` | 2026-08-07 | 69 | Coupang Already-Issued Guided Walkthrough — Live Regression v1 (BLOCKED) |
| `coupang_credential_expiry_guided_renewal_v1.md` | 2026-08-07 | 87 | Coupang Credential Expiry + Guided Renewal v1 |
| `coupang_no_key_form_classifier_selector_recon_v1.md` | 2026-08-08 | 247 | Coupang No-Key Form — Classifier Correction + Selector Recon v1 |
| `coupang_wing_live_calibration_v1.md` | 2026-08-08 | 83 | Coupang WING Live Calibration + Walkthrough Binding v1 |
| `coupang_wing_reveal_live_v3_evidence_landing.md` | 2026-08-09 | 150 | Coupang WING Reveal Live v3 — Evidence Landing |
| `coupang_wing_stage2_recon_evidence_landing_v1.md` | 2026-08-09 | 172 | Coupang WING Stage-2 Recon Evidence Landing v1 |
| `coupang_wing_stage2_label_calibration_evidence_landing_v1.md` | 2026-08-10 | 191 | Coupang WING Stage-2 Label Calibration Evidence Landing v1 |
| `coupang_wing_key_issuance_live_e2e_v1.md` | 2026-08-12 | 100 | Coupang WING key issuance — live E2E, 2026-08-12 |
| `coupang_wing_vendor_method_epistemic_audit_v1.md` | 2026-08-12 | 100 | Vendor-Method Epistemic Audit — 2026-08-12, before the WRITE run |
| `coupang_wing_guided_control_highlight_calibration_v1.md` | 2026-08-12 | 152 | Coupang WING Guided Control Highlight Calibration v1 — session handoff |
| `coupang_credential_handoff_live_proof_v1.md` | 2026-08-14 | 416 | Coupang Credential Handoff v1 — live proof record |
| `coupang_wing_issuance_form_reveal_v1.md` | 2026-08-19 | 159 | Coupang WING Issuance Form Reveal Driver v1 |
| `coupang_wing_delete_selector_calibration_v1.md` | 2026-08-19 | 178 | Coupang WING Delete Selector — Live Calibration Landing v1 |
| `sellerops_completion_checkpoint_v1.md` | 2026-08-19 | 296 | SellerOps — Completion Checkpoint v1 (recovery baseline) |
| `coupang_connection_failure_diagnostic_hardening_v1.md` | 2026-08-19 | 89 | Coupang Connection-Failure Diagnostic Hardening v1 |
| `operator_graph_v1_local_integration_proof.md` | 2026-08-21 | 209 | Operator Graph v1 — local integration proof (2026-08-21) |
| `operator_graph_v2_local_integration_proof.md` | 2026-08-21 | 232 | Operator Graph v2 — 로컬 통합 라이브 증명 (2026-08-21) |
| `sellerops_agent_runtime_migration.md` | 2026-08-21 | 784 | SellerOps Agent Runtime Migration v1 |
| `cafe24_inquiry_source_reconciliation_audit_2026-08-22.md` | 2026-08-22 | 153 | Cafe24 Inquiry Source Reconciliation — Audit (2026-08-22) |
| `cross_channel_operational_reasoning_v1.md` | 2026-08-24 | 176 | Cross-Channel Operational Reasoning v1 |
| `inquiry_product_attribution_action_coverage_v1.md` | 2026-08-24 | 289 | Inquiry Product Attribution & Action Coverage v1 |
| `naver_guided_acquisition_e2e_v1.md` | 2026-09-02 | 102 | NAVER Guided Acquisition — end-to-end, live |
| `chat_first_operating_experience_v3.md` | 2026-09-02 | 111 | Chat-first Operating Experience v3 |
| `chat_first_semantic_surface_finalization_v1.md` | 2026-09-02 | 113 | Chat-first Semantic & Surface Finalization v1 |
| `chat_first_outcome_visual_closure_v1.md` | 2026-09-02 | 138 | Chat-first Outcome & Visual Closure v1 |
| `chat_first_completion_continuity_v1.md` | 2026-09-02 | 186 | Chat-first Completion & Continuity v1 |
| `naver_guided_acquisition_runtime_closure_v2.md` | 2026-09-02 | 199 | NAVER Guided Acquisition — Runtime Closure v2 |
| `coupang_aside_poc_hardening_v1.md` | 2026-09-12 | 341 | Coupang Aside PoC Hardening (M4) v1 |
| `review_acquisition_comparison.md` | 2026-09-12 | 36 | Review Acquisition — Legacy vs Aside — 사실 중심 비교 |
| `core_channel_boundary_v1.md` | 2026-09-13 | 105 | Core Channel Boundary v1 |
| `product_truth_v1.md` | 2026-09-13 | 257 | Canonical Product Knowledge v1 · v2 — 사람이 검토하는 제품 원장 |
| `first_external_seller_gate_v1.md` | 2026-09-14 | 157 | First External Seller Gate v1 |
| `agent_native_text_first_pilot_scope_v1.md` | 2026-09-14 | 166 | Agent-native Text-first Pilot — scope, as of 2026-09-13 |
| `pilot_first_external_seller_v1.md` | 2026-09-14 | 187 | Pilot Runtime / First External Seller v1 — Coupang browser review lane |
| `coupang_connection_ux_v2.md` | 2026-09-14 | 190 | Coupang Connection UX v2 — 두 가지 방법, 그뿐 |
| `catalogue_independent_review_ingest_v1.md` | 2026-09-14 | 196 | Catalogue-independent Review Ingest + Bootstrap Closeout v1 |
| `pilot_distribution_first_seller_onboarding_v1.md` | 2026-09-14 | 226 | Pilot Distribution & First Seller Onboarding v1 |
| `issue_action_candidate_v0.md` | 2026-09-14 | 327 | Issue → ActionCandidate v0 |
| `aside_acquisition_productization_v1.md` | 2026-09-14 | 336 | Aside Acquisition Productization v1 |
| `pilot_host_handoff_v1.md` | 2026-09-14 | 91 | Pilot Host Handoff v1 |
| `customer_ops_product_quality_closure_v1.md` | 2026-09-18 | 149 | Customer Ops Product Quality Closure v1 |
| `coupang_aside_operator_run_lane_v1.md` | 2026-09-18 | 203 | Coupang ASIDE — Operator-Run Incremental Lane (M5) v1 |
| `knowledge_intelligence_closure_v1.md` | 2026-09-19 | 346 | Knowledge & Intelligence Closure v1 — Customer Operations Manager Demo v1 · Q2 |
| `customer_ops_demo_closure_v1.md` | 2026-09-19 | 395 | Customer Ops Demo Closure v1 |
| `inquiry_architecture_v3_wp3.md` | 2026-09-20 | 393 | Inquiry Architecture v3 — WP-3: Resolution Planner Contract Hardening & Split-Tolerant Eval |
| `inquiry_architecture_v3_wp2.md` | 2026-09-20 | 421 | Inquiry v3 WP-2 — Resolution Planner: Contract to Shadow |
| `inquiry_architecture_v3_wp4.md` | 2026-09-20 | 437 | Inquiry Architecture v3 — WP-4: Planner Final Validation & Freeze Gate |
| `customer_operations_channel_reach_v1.md` | 2026-09-22 | 181 | Customer Operations — Channel Reach v1 (G1 closure) |
| `ui_ux_audit_v2.md` | 2026-09-22 | 725 | UI/UX Audit v2 + v2 IA 제안 (2026-09-22) |
| `full_mvp_real_api_preflight_v1.md` | 2026-09-22 | 86 | Full MVP · REAL API read preflight v1 (2026-09-22) |
| `full_mvp_production_e2e_v1.md` | 2026-09-23 | 889 | Full MVP Production E2E v1 |
| `pilot_provisioning_plan_v1.md` | 2026-09-26 | 395 | Pilot Provisioning Plan v1 — B4 public host · B5 off-host backup |
| `cafe24_first_live_baseline_v1.md` | 2026-09-30 | 242 | Cafe24 First Live Baseline v1 — 2026-09-30 |
| `naver_inquiry_first_live_baseline_v1.md` | 2026-09-30 | 299 | NAVER Inquiry — First Live Baseline v1 (2026-09-30) |
