# Lineage index — 소스가 인용하는 과거 기록 (2026-10-12)

이 문서들은 **현역 계약이 아니다.** 전부 2026-06 ~ 2026-09의 1회성 기록이고,
`CLAUDE.md`의 reading path와 `docs/INDEX.md`에서 도달하지 않는다. 그런데
`docs/archive/2026-q3/`로 옮기지 않았다 — **소스와 테스트가 주석으로 경로를 인용하기
때문**이다. 경로를 바꾸면 문서 정리가 소스 120여 곳의 변경으로 번진다.

**읽는 규칙 — 여기 있는 문장은 현재 동작의 근거가 아니다.**

- 현재 동작을 알고 싶으면 인용한 **코드 자체**를 읽는다. 그 코드가 진실이고, 이 문서는
  그 코드가 왜 그렇게 됐는지에 대한 당시 기록이다.
- 제품 계약은 `docs/INDEX.md`의 *Standing contracts and ADRs*가 소유한다.
- 라이브 실행 이력은 `docs/evidence/INDEX.md`가 소유한다.
- 이 문서와 현역 문서가 어긋나면 **현역 문서가 이긴다.** 어긋남 자체는 보고 대상이다
  (`CLAUDE.md` § Conflict priority).

인용이 사라지면 그 문서는 `docs/archive/2026-q3/`로 간다.

| 문서 | 소스 인용 | 마지막 커밋 | 줄 | 제목 |
|---|---|---|---|---|
| `docs/agent_real_validation_v1.md` | 24 | 2026-08-24 | 1872 | 3-Channel REAL Agent Validation v1 — baseline (2026-08-23) |
| `docs/naver_inquiry_api_audit_v1.md` | 14 | 2026-08-24 | 947 | NAVER 문의 API 감사 v1 — 공식 계약과 저장소 사실 |
| `docs/inquiry_architecture_v35.md` | 8 | 2026-09-21 | 2349 | Inquiry Architecture v3.5 — Customer Goal Interpreter + Deterministic Resolution Loop |
| `docs/coupang_credential_handoff_v1.md` | 8 | 2026-08-13 | 220 | Coupang Credential Handoff v1 — the contract |
| `docs/review_acquisition_aside_v2.md` | 7 | 2026-09-12 | 376 | Review Acquisition — Aside v2 — TARGET DESIGN |
| `docs/coupang_review_acquisition_v1.md` | 7 | 2026-08-15 | 388 | Coupang Review Acquisition + Locate v1 |
| `docs/inquiry_decision_v2_1.md` | 5 | 2026-09-20 | 323 | Inquiry Decision v2.1 — Judge Calibration & Memory Scope Hardening |
| `docs/sellerops_cafe24_review_acquisition_completion_live_proof.md` | 4 | 2026-07-31 | 107 | Cafe24 REVIEW Acquisition Completion v1 — live proof (sanitized) |
| `docs/demo_baseline_recovery_audit_2026-08-21.md` | 4 | 2026-08-21 | 380 | Demo Baseline Recovery Audit — `main @ 2491f1ab` (2026-08-21) |
| `docs/coupang_inquiry_live_proof_v1.md` | 4 | 2026-08-14 | 161 | Coupang 고객문의 — Live Proof v1 |
| `docs/sellerops_cafe24_live_verification.md` | 3 | 2026-06-25 | 70 | Cafe24 ORDER_SUMMARY — Gated live verification record |
| `docs/inquiry_need_eval_v1.md` | 3 | 2026-09-19 | 214 | Inquiry Need Eval v1 — Knowledge Coverage → Retrieval → Sufficiency → Decision, need 단위로 |
| `docs/coupang_review_feasibility_v1.md` | 3 | 2026-08-14 | 459 | Coupang Review Integration — Feasibility v1 |
| `docs/coupang_final_main_first_connection_order_routine_proof_v1.md` | 3 | 2026-08-06 | 122 | Coupang Final Main First-Connection + Idempotent Order Routine Proof v1 |
| `docs/sellerops_cafe24_inquiry_read_live_proof.md` | 2 | 2026-08-19 | 159 | Cafe24 INQUIRY_READ Live Proof v1 — SUCCESS (exact-window contract) |
| `docs/sellerops_cafe24_community_board_discovery.md` | 2 | 2026-06-25 | 106 | Cafe24 Community Board Discovery — design + verification plan |
| `docs/review_media_presence_audit_v1.md` | 2 | 2026-09-13 | 369 | Review Media Presence Audit v1 |
| `docs/product_truth_matrix.md` | 2 | 2026-09-22 | 672 | Product Truth v2 — 렌더된 요약 |
| `docs/inquiry_decision_v2.md` | 2 | 2026-09-19 | 177 | Inquiry Decision v2 — 「관련 근거가 있다」와 「고객 요청 전체를 답할 수 있다」를 가른다 |
| `docs/inquiry_architecture_v3_wp31.md` | 2 | 2026-09-20 | 890 | Inquiry Architecture v3 — WP-3.1: Closing Authority Semantics & Replay Diagnostics |
| `docs/inquiry_architecture_v3_wp1.md` | 2 | 2026-09-20 | 399 | Inquiry v3 WP-1 — Eval Asset Recovery + Deterministic Authority Layer |
| `docs/coupang_review_locate_ux_v1.md` | 2 | 2026-08-16 | 343 | Coupang Review Locate UX v1 — `[쿠팡에서 보기]` |
| `docs/ci-coverage.md` | 2 | 2026-07-27 | 103 | CI coverage — what is verified automatically, and what deliberately is not |
| `docs/trusted_operator_confirmation_proof_v1.md` | 1 | 2026-08-13 | 113 | Trusted Operator Confirmation v1 — live proof record |
| `docs/sellerops_cafe24_review_inquiry_capture.md` | 1 | 2026-08-22 | 506 | Cafe24 Review/Inquiry Article Capture — storage foundation (PR A) |
| `docs/resident_helper_on_demand_carrier_v1.md` | 1 | 2026-08-28 | 264 | Resident helper — the guided walks on demand (2026-08-19, v1.1; extended 2026-08-20, v1.2) |
| `docs/pilot_launch_readiness_v1.md` | 1 | 2026-09-26 | 437 | Pilot Launch Readiness v1 |
| `docs/naver_cafe24_shadow_run_v1.md` | 1 | 2026-09-28 | 395 | NAVER + Cafe24 Cloud Shadow Run v1 — procedure |
| `docs/inquiry_decision_v2_2.md` | 1 | 2026-09-20 | 142 | Inquiry Decision v2.2 — Evidence Integrity & Aggregation Calibration |
| `docs/inquiry_architecture_v3.md` | 1 | 2026-09-20 | 965 | Inquiry Architecture v3 — Capability-Oriented Resolution (설계 · 구현 0) |
| `docs/inquiry_architecture_v3_wp32.md` | 1 | 2026-09-20 | 357 | Inquiry Architecture v3 — WP-3.2: Planner Boundary & Scope Hardening |
| `docs/full_mvp_channel_capability_audit_v1.md` | 1 | 2026-09-22 | 219 | Full MVP — 3채널 Production Capability 감사 v1 |
| `docs/execution_strategy_v1.md` | 1 | 2026-09-12 | 207 | Reviewnary Execution Strategy v1 |
| `docs/coupang_wing_openapi_issuance_flow_discovery_v1.md` | 1 | 2026-08-10 | 163 | Coupang WING OPEN-API Issuance Flow Discovery v1 — evidence landing |
| `docs/coupang_wing_key_deletion_live_v1.md` | 1 | 2026-08-08 | 217 | Coupang WING Key Deletion — Live v1 |
| `docs/coupang_wing_issue_selector_calibration_landing_v2.md` | 1 | 2026-08-19 | 210 | Coupang WING Issue Selector Calibration Landing v2 |
| `docs/coupang_routine_operations_v1.md` | 1 | 2026-08-14 | 335 | Coupang Routine Operations v1 — 고객문의 |
| `docs/coupang_ordersheets_response_contract_hardening_v1.md` | 1 | 2026-08-06 | 109 | Coupang Ordersheets Response Contract Hardening v1 |
| `docs/coupang_credential_expiry_audit_v1.md` | 1 | 2026-08-07 | 74 | Coupang Credential Expiry + Guided Renewal — Audit & Policy Basis v1 |
| `docs/coupang_aside_review_acquisition_poc_v1.md` | 1 | 2026-09-12 | 341 | Coupang WING Review Acquisition over Aside — PoC v1 |
| `docs/cafe24_live_e2e_runbook.md` | 1 | 2026-09-22 | 290 | Cafe24 Live E2E Runbook (2026-09-21) |
| `docs/aside_execution_provider_v1.md` | 1 | 2026-09-12 | 243 | Aside Execution Provider v1 — M1 (Execution Provider Foundation) + M2 (Aside Execution Adapter) |
| `docs/aside_capability_discovery_v1.md` | 1 | 2026-09-12 | 477 | Aside Capability Discovery v1 — read-only |

## 저장소에 없는 문서 이름 (2026-10-12)

`docs/` 아래 여러 문서가 **이 저장소에 더는 존재하지 않는 파일**을 이름으로 언급한다. 대부분
의도된 언급이다 — "이 틀은 폐기됐다"고 적으면서 폐기된 문서를 지목하는 것이고, 지목 자체가
뜻을 싣는다. 문제는 읽는 사람이 그 파일을 찾으러 나선다는 것뿐이다. 그래서 지우지 않고
여기에 어디로 갔는지만 적는다.

**이 표에 있는 이름이 본문에 보이면, 파일이 없는 것이 정상이다.** 원문이 필요하면
`git show <커밋>^:<경로>`로 꺼낸다.

| 삭제 커밋 | 날짜 | 없어진 문서 |
|---|---|---|
| `e1550892` — chore(repo): separate legacy VOC stack from SellerOps | 2026-07-22 | `docs/PROJECT.md` · `docs/review_acquisition.md` · `docs/review_ops_architecture.md` · `docs/review_ops_brand20_pipeline.md` · `docs/review_ops_brand20_manual_mapping_guide.md` · `docs/detail_page_snapshot_design.md` · `docs/agent_harness_design.md` · `docs/agent_orchestration_playbook.md` · `docs/instagram_voc_brand_strategy.md` · `docs/instagram_voc_dm_response_script.md` · `docs/instagram_voc_dm_conversion_ledger.md` · `docs/instagram_voc_publishing_checklist.md` · `docs/public_instagram_cardnews_spec.md` · `docs/sellerops_product_roadmap.md` · `docs/sellerops_ceo_connector_status_onepager.md` · `docs/sellerops_ui_reference.md` · `docs/sellerops_phase2.md` · `docs/sellerops_phase3a.md` · `docs/sellerops_phase3b_completion.md` · `docs/sellerops_phase3c.md` |
| `39186802` — docs: retire 49 superseded documents | 2026-08-19 | `docs/sellerops_current_state.md` |
| — | — | `docs/action-window-runtime/r4-review-id-trace.md` · `docs/phase_b_public_education_planner_plan.md` — **git의 어느 ref에도 존재한 적이 없다.** 쓰겠다고 적히고 쓰이지 않았다. |

**예외 두 가지 — 이것들은 깨진 게 아니다.**

- `collector/` 안의 문서가 적는 `docs/<이름>.md`는 **그 서브트리 기준 상대경로**다
  (`collector/docs/<이름>.md`). 저장소 루트에서 읽으면 없는 것처럼 보이지만 정상이다.
- `aiagent/docs/phase2e_detector_design.md`는 이 저장소가 아니라 **공유 git host의 부모
  저장소**를 가리키는 의도된 교차 참조다.

### 하나는 아직 열려 있다 — PO 결정 필요

`contracts/review-id-fingerprint/v1/SPEC.md`는 식별자의 근거로 `docs/review_acquisition.md`
§S(= "Sample-file schema analysis", 2026-06-16)와 `docs/action-window-runtime/r4-review-id-trace.md`를
인용한다. **계약이 자기 provenance로 삼는 문서 둘이 저장소에 없다.**

- §S는 복구 가능하다 — `git show e1550892^:docs/review_acquisition.md` (372줄, §S는 126행부터).
  보존된 runtime worktree에만 남아 있다는 기존 설명은 부정확하다.
- trace 문서는 복구할 것이 없다. 한 번도 쓰이지 않았다. 실제로 존재하는 가장 가까운 기록은
  `docs/action-window-runtime/r4-review-id-reconciliation-run-record.md`와
  `…-reconciliation-scope.md`이고, 둘 다 같은 주장을 덮지 않는다.

§S의 schema·민감도 분석을 저장소로 다시 들여올지는 **제품 오너 결정**이며 이 정리에서
해결하지 않았다. `contracts/review-export/naver/v1/SPEC.md`와
`docs/slices/review-acquisition-spine-v1.md`가 이미 같은 드리프트를 보고하고 있다.
