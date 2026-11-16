# SellerOps — Canonical Reference: Product, Strategy, State

## 현재 아이템 기준 — 여기만 갱신 (2026-11-16)

**이 절이 구상 중인 아이템 설명의 단일 원본이다.** 다음 논의에서 고객·문제·가치·확장 방향이 바뀌면 이 절을 고친다.
회차별 피치·브리프는 여기서 만든 평가용 입력이며, 실행한 입력과 결과는 당시 상태로 보존한다.
아래 과거 전략·구현 목표 인터뷰·기술 방향 문서는 근거와 이력이다. 최신 아이템 정의를 따로 만들지 않는다.
현재 구현·운영 범위는 scope v1.15와 레인별 증거가 소유하며, 이 절의 목표 설명으로 바뀌지 않는다.

### 1. 지금 정리하는 목적

**현재 목적(2026-11-16): 데모를 마친 뒤, 도입 예정 기업의 실제 업무에 맞춰 요구를 정리하고 큰 기능 단위로 개발·검수한다.**
오늘 인터뷰의 확인 사항과 기능 후보는 아래 §7에서 관리한다. 우선 인터뷰의 모호한 부분과 현 구현 차이를 확인하며,
후보 기능을 모두 개발 확정으로 간주하지 않는다. 합성 시연 완료는 실제 기업 계정·자료·채널 운영 준비 완료와 구분한다.

이전 과제(2026-11-12)는 목표 제품의 매력과 사업 맹점을 시뮬레이션으로 평가하는 것이었다.
평가 입력·결과는 당시 상태로 보존한다. 가상 반응은 실제 구매·매출·효율의 증거가 아니다.

### 2. 누구의 어떤 일을 줄이는가

- **중심 고객:** 여러 판매 채널을 운영하는 중소 판매자·제조사·브랜드의 대표와 실무 담당자. CS 전담자뿐 아니라 사무 업무 전반을 함께 처리하는 직원도 포함한다.
- **중심 문제:** 판매자센터 순회, 자료·과거 대응 찾기, 답 작성·승인·등록, 교환·환불 등 남은 일 확인이 끊어져 반복 노동과 누락이 생긴다.
- **확장 고객 가설:** 주문은 있지만 리뷰가 적거나 고객 관리 체계가 약한 판매자도 도울 수 있는지 인터뷰한다. 주문 자체가 적은 판매자의 유입 문제까지 해결한다고 확정하지 않는다.

### 3. 목표 제품 — 초안 작성에서 끝나지 않는 고객 운영 AI

**Reviewnary는 여러 판매 채널의 고객 업무를 모으고, 가게의 기준과 처리 기록을 이어 써서 필요한 응대를 준비·처리하며, 답변 뒤 남은 일까지 챙기는 AI 서비스다.**

1. 채널에 흩어진 문의·리뷰를 모아 급한 일과 담당자가 결정할 일을 보여 준다.
2. 상품·정책, 확인 가능한 주문·배송 사실, 이전 안내와 판매자의 처리 기록을 연결해 답을 준비한다. 모르는 사실은 확인할 일로 남긴다.
3. 승인·등록과 실제 교환·환불 등의 완료를 구분하고, 진행 중이면 다음 확인할 일로 이어 준다.
4. 검증된 반복 업무는 판매자가 맡긴 유형부터 매번 승인하지 않아도 처리하는 방향이다. 유형의 품질과 개별 건의 근거·최신성·권한을 함께 보고, 오류 시 해당 범위를 멈추고 판매자가 재개한다.

**카카오톡 알림도 제품 방향에 포함한다 (2026-11-12 오너 재확인).** 새 문의나 확인할 일의 발견, 결정·승인이 필요한 일, 등록 결과 등을 앱 밖에서 알 수 있게 하는 방향이다.
예전 피치에 승인·등록 알림만 적혔다고 해서 다른 업무 알림을 제품에서 제외한 결정으로 읽지 않는다.
2026-11-16 접수 알림 1단계를 구현하고 모의 발송으로 검증했다([데모 §7.63 · §7.64](pilot/demo_seller_validation_v1.md)). 새 리뷰·문의 1건당 1회가 기본이고, 묶음·수신 시간은 고르는 설정이다. 알림에는 인증된 서비스 상세 링크만 담고, 승인은 서비스에서만 한다. 빠진 접수는 48시간 범위 안에서만 다시 찾는다 — 「무누락」 보장이 아니다. 실제 카카오톡 발송은 아직 없다: 카카오 채널·알림톡 템플릿 심사·SOLAPI 키·HTTPS 운영 주소를 준비한 뒤 별도 승인으로 켠다. 승인·등록 결과 등 다른 사건의 알림은 구체화할 항목으로 남는다.

예: 「교환 상품 오늘 보내주시나요?」에 출고 사실을 모르면 확인을 요청하고, 발송 기록이 생기면 그 근거로 답한다.
아직 진행 중이면 완료라고 답하지 않고 다음 확인일을 남긴다. 과거 출고 약속과 실제 이행의 자동 대조는 별도 고도화 목표다.

**차별화 가설:** 가게별 운영 맥락을 이어 써서 자료 찾기·반복 확인·예외 처리·후속 관리까지 줄이는 것.
운영 기록을 연결하는 구조는 그 수단이다. 다른 AI가 맥락을 못 본다거나, 기록이 쌓이기만 하면 해자가 된다고 단정하지 않는다.
현재 눈에 보이는 채널 범위는 NAVER·Coupang·Cafe24이며, 모든 자료·동작이 세 채널에서 같은 수준으로 지원되는 것은 아니다.

### 4. 앞단·뒷단 확장 — 향후 2~3단계 방향, 지금은 인터뷰

멘토링 뒤 오너가 추가한 방향은 **리뷰·문의가 많은 고객에만 갇히지 않도록 구매 전후로 가치를 넓히는 것**이다.
아래 기능은 구체화를 위한 후보이며, 출시 범위·개발 순서·자동 실행 권한을 정한 것이 아니다.
여기서 2~3단계는 향후 제품 발전 단계이며 시뮬레이션 2·3차를 뜻하지 않는다.

| 구간 | 인터뷰할 기능 후보 | 확인할 가치와 남은 질문 |
|---|---|---|
| 구매 전 | 반복 질문을 상품 FAQ·설명 개선으로 연결; SEO·AEO·GEO 활용 검토 | 고객의 구매 판단·발견을 돕는가? 유입 개선이 필요한 고객과 응대 개선 고객이 같은가? 노출·매출 효과는 미검증 |
| 구매 후 | CRM을 통한 사용 안내·리뷰 요청·재구매 안내 | 리뷰가 적은 판매자에게도 유료 가치가 있는가? 고객 식별·연락 권한·채널 제약·기존 도구와 중복은? |
| 운영 개선 | 반복 불만·문의의 원인을 찾아 상품 설명·운영 기준 개선과 후속 확인으로 연결 | 보고서 열람을 넘어 실제 개선 행동과 반복 문제 감소로 이어지는가? |

SEO·AEO·GEO·CRM은 오너가 제시한 탐색 축이다. 표의 구체적인 기능 묶음은 검토 후보이며 채택 확정이 아니다.
경쟁사 기능을 더하는 것만으로 차별화라고 하지 않는다. 현재는 인터뷰로 문제·기존 지출·우선순위를 확인한다.
이번 목표 제품 평가에 어디까지 넣을지는 평가 입력에서 명시하며, 미래 후보를 모두 기본 제공하는 패키지로 섞지 않는다.

### 5. 기대 성과와 시뮬레이션에서 드러낼 맹점

기대 성과는 **업무시간 감소 + 응대 속도·품질 개선 + 실제 반복 사용**이다.
측정 후보는 직접 작업·자료 찾기·재작업 시간, 문의 접수부터 첫 실질 답변 등록까지의 중앙값/P90,
오래 남은 미답변, 사실·정책 오류, 실제 업무 처리 비중, DAU/WAU/MAU와 유지다.
로그인만 한 사용과 업무를 처리한 사용을 나누고, 업무가 없거나 자동화된 날의 낮은 방문을 곧 실패로 보지 않는다.
매출·리뷰 확보·재구매 성과는 확장 기능 검증 항목이며 현재 효과로 주장하지 않는다. 수치 목표는 아직 정하지 않았다.

시뮬레이션에서는 다음을 숨기지 않고 질문한다. 과거 결과는 맹점 후보를 찾는 데 쓰되 새 평가자에게 정답으로 주입하지 않는다.

- **구매 이유:** 초안·통합 목록만으로 기존 도구를 바꿀 만큼 절실한가? 어느 업무량·직원 구조에서 필요한가?
- **승인 부담:** AI가 시간을 줄이는가, 확인할 화면과 승인만 늘리는가?
- **도입·신뢰:** 연결 권한·자료 준비·가게 기준 설정의 부담, 오답·최신성·등록 오류에 대한 우려는?
- **원가·확장성:** 판매자 시간 절감이 우리 대행 노동으로 옮겨진 것은 아닌가? 초기 설정·등록·복구·모델 비용을 포함해 수익이 나는가?
- **차별화·집중:** 기록 재사용이 반복 노동을 줄이는가? CRM·마케팅 확장이 가치를 강화하는가, 제품을 흐리는가?
- **사업 조건:** 무료 기간·가격·연락 수단 등은 평가용 제공 가정이며 수치는 저장소 밖에 둔다. 실제 가격·구현 완료·수요로 읽지 않는다.

**시뮬레이션 해석:** 5차의 가상 buy 2/pass 18은 실제 고객 수가 아니며, 「승인 11」은 주요 거절자 수가 아닌 단어 언급 집계다.
같은 페르소나·seed는 사람 카드만 맞춘다. 모델 판단이나 인과 비교를 보장하지 않는다. 긍정 평가와 치명적인 미해결 조건을 함께 보고한다.

### 6. 이 문서와 연결된 자료의 역할

- **실사용 계획 (2026-11-16 오너 갱신):** 기업 대상 데모 시연은 완료했다. 해당 기업의 실제 도입을 목표로 후속 요구를 정리한다. 실제 운영 시작·유료 전환·성과 확보는 아직 확인되지 않았다.
- **아이템 정의·미래 방향:** 이 절에서 계속 갱신한다. 큰 기능마다 검수·보고 후 멈추며, 현재는 아래 인터뷰 요구 정리가 먼저다. 구체적인 개발 범위와 외부 실행 권한은 별도로 구분한다.
- **오너 결정 원문:** [구현 목표 인터뷰](reviewnary_implementation_goals_draft_v1.md), 특히 질문 76-82. 기술 설계는 [운영 지식 방향](standing/operational_knowledge_direction_v1.md).
- **현재 제공 범위·증거:** [문서 지도](INDEX.md)에서 scope·채널·레인별 문서를 찾는다. 이 절에서 완료 현황을 복제하지 않는다.
- **6차 평가 조건·피치:** 저장소 밖 `../founder/round6/concept.md`와 `../founder/round6/pitch-draft.md`. 확정한 입력은 회차 안에 고정하고, 차기 구상은 이 절로 돌려쓴다.
- **변경 근거 (2026-11-12):** 오너가 시뮬레이션의 목적을 아이템 평가·맹점 발견으로 재확인하고, 문서 한 곳에서 구상을 갱신하도록 요청했다. 멘토링 확장은 인터뷰 단계다.

### 7. 기업 데모 후 인터뷰와 개발 운영 (2026-11-16)

**상태: 오너 설명 반영, 구현 범위·수단 확인 중.** 아래는 오너가 전달한 한 기업의 인터뷰와 후속 답변이다. 전체 판매자 시장의 비율로 일반화하지 않는다.

| 전달받은 내용 | 현재 해석과 확인할 점 |
|---|---|
| 광고는 지마켓·네이버에 가장 크게 집행. 지마켓·옥션 유입이 많아 문의·리뷰도 늘고 있음 | 오너가 채널 추가를 검토 중이다. 지마켓·옥션을 **확장 후보**로 기록하되 둘 중 먼저 다룰 채널, 자료 종류와 조회·응대 범위는 아직 정하지 않았다. 현재 화면의 NAVER·Coupang·Cafe24 지원 범위를 자동으로 변경하지 않는다. |
| 문의보다 톡톡을 쓰며 문의:톡톡 = 1:9 | 오너 목표는 **서비스 안에서 톡톡 답 전송까지**다. 비율의 기간·건수는 미확인. 현재 톡톡 미지원의 정본은 [채널 현행표 §4.1](multi-channel-connector-roadmap.md#41-채널--datatype-현행표-living--capability-진실의-정본). 커머스 API의 한계를 모든 공식 연동 수단의 부재로 일반화하지 않고 별도 조사한다. 목표 합의는 실제 고객에게 메시지를 보내는 실행 승인이 아니다. |
| 온·오프라인을 병행해 정확한 수량 파악이 어려움 | 정확한 재고·판매 가능 수량을 제품이 이미 알고 있다고 가정하지 않는다. 이번 답변은 실시간 재고 통합 기능의 개발 요청이 아니다. |
| 가끔 금요일 휴무, 당일 12시 기준 발송 결정. 구매 화면에 배송 일정이 있어도 배송 문의가 많음 | 휴무 중 문의가 쌓여 월요일에 사과부터 하고 응대를 시작한다. 오너는 확정된 기준의 자동 안내를 선택했다. 휴무와 출고 마감 기준 안내는 자동화하되 주문별 실제 출고·도착 사실은 담당자 확인으로 넘긴다. |
| 마케팅 대행사 언급 | 오너가 이번 범위에서 제외했다. 대행사 기능·여러 고객사 관리로 확장하지 않는다. |
| 유사 상품의 배송비·옵션을 한 번에 점검 | 상품 간 설정 비교·누락/불일치 탐지 후보. 비슷한 상품에 같은 값이 정답이라고 추정하지 않으며, 비교 대상·정답 기준·예외가 필요하다. |
| 상세페이지에 휴무·중요공지를 일괄 추가·제거 | 현재 상품마다 순회해 고치는 반복 작업이 문제다. 오너가 **안내 이미지·배너** 형태로 이 방향을 확인했다. 대상 채널·적용 기간·미리보기·승인·부분 실패·원복 범위는 정해야 한다. 옵션 설정도 가끔 놓치므로 점검 요구는 유지한다. 현재 실제 상품 수정 기능이 있다는 의미가 아니다. |

**이번 개발 순서(2026-11-16 오너 확정):**
1. **지금 구현: 톡톡 상담.** 서비스 안에서 수신·근거 기반 답변·전송·담당자 인계를 잇는다. 오너가 켠 유형의 확정된 휴무·출고 기준만 자동 안내하고, 주문별 실제 사실과 불확실한 건은 담당자 확인으로 넘긴다. 기존 개별 승인 계약에 대한 이 좁은 예외는 별도 톡톡 계약에서 구현 조건을 명시한다. 다른 문의·리뷰 자동 전송으로 넓히지 않는다.
2. **그다음 채널 확장: 지마켓.** 오너가 다음 대상으로 정했다. 옥션은 별도 후속 후보이며 지마켓과 동시에 지원한다고 약속하지 않는다. 공식 취득·답 전송 경로와 자격을 확인한 뒤 별도 큰 단위로 진행한다.
3. 이미지 배너 일괄 관리·배송비/옵션 점검은 후속 요구로 유지한다. 지금 톡톡 단위에 넣지 않는다.

**각 기능의 검수 범위(현재 단위와 후속 후보 구분):**
1. 실제 기업 도입: 계정·조직 격리, 기업 자료·상품 연결, 실제 모델 답변 품질, 필요한 채널의 실제 사용, 접속·복구·측정까지 한 흐름으로 검수한다. 데모의 가짜 모델·채널 성공을 완료 증거로 쓰지 않는다.
2. 톡톡 상담: 공식 연동 자격·수신/발신·과거 대화·주문/상품 연결·상담원 인계 가능 범위 조사 후 한 기능으로 범위를 정한다. 공개 문서 조사와 실제 계정 시험은 구분한다.
3. 상품 운영 점검: 대상 상품과 승인된 기준을 비교해 누락·불일치·예외를 근거와 함께 보여 준다. 실제 수정은 다음 단위다.
4. 공지·설정 일괄 변경: 차이 미리보기→승인→적용 결과→복구를 한 기능으로 설계한다. 채널별 공식 수정 가능 범위 확인 전 구현·제공을 약속하지 않는다.
5. 지마켓·옥션 확장 후보: 실제 유입·문의·리뷰 증가를 근거로 우선순위를 검토한다. 채널 하나 추가를 곧 두 채널의 모든 자료·응대 동작 지원으로 해석하지 않는다. 공식 취득·답 전송 경로, 판매자 자격, 기존 코드·증거를 먼저 확인한다.

**오늘 미팅 요약:** 온·오프라인 판매로 정확한 수량 파악이 어렵고, 배송 기준이 구매 화면에 있어도 문의가 반복된다.
특히 금요일 휴무에는 문의가 쌓여 월요일에 사과부터 하게 된다. 네이버 상담은 톡톡 비중이 높아 서비스 안에서 응대를 끝내야 한다.
휴무·중요공지 이미지 배너를 상품마다 넣고 빼는 반복 작업과 배송비·옵션 설정 누락도 줄이고 싶어 한다.
광고는 지마켓·네이버에 집중하며 지마켓·옥션의 유입과 문의·리뷰가 늘고 있어 채널 확대를 검토한다.
이는 인터뷰에서 확인한 문제와 오너의 제품 목표이며, 현재 제공 기능이나 효과 실측 결과가 아니다.

**이번 기업에서 검수할 핵심 장면(제안):** 담당자가 실제 휴무일과 12시 출고 마감 기준을 등록한다.
선택한 상품에 공지를 미리보기·승인 후 추가하고 기간이 끝나면 해당 공지를 제거한다. 휴무 중 톡톡 배송 질문에는
그 기준을 이용해 안내하고, 주문별 실제 출고·도착 정보가 없으면 확정하지 않는다. 확인이 필요한 건은 담당자에게 남긴다.
오너는 확정된 휴무·출고 기준의 자동 안내와 이미지·배너 공지를 선택했다. 고객에게 보내는 개별 메시지의 실제 시험·운영 권한과는 구분한다.
이는 시나리오 제안이며 자동 전송·예약 제거 기능의 현재 구현이나 실채널 실행 승인으로 간주하지 않는다.

기존 지식·근거 기반 응대, 답변 후 남은 일, 리뷰/문의 분석, 카톡 알림 방향은 유지한다. 위 확장 후보로 대체하지 않는다.
카톡 실수신은 [데모 §7.64-c](pilot/demo_seller_validation_v1.md#764-c-카톡-알림-발송-경로-결정과-남은-외부-조건-2026-11-16-오후--결정-기록만-코드--발송-없음)의 외부 준비 조건이 남는다.

**진행 방식(이번 오너 지시):** Codex가 요구·범위·검수 결과를 관리하고 Claude Code가 구현한다. 긴 대화 전체 대신
`목표 / 읽을 정본 / 포함·제외 / 완료 조건 / 실행 권한 / 보고 위치`만 전달한다. 하나의 큰 기능 안에서는 검토에서 발견한 결함을 계속 고치고,
기능 완료 시 검증·잔여 문제·다음 결정만 짧게 보고한 뒤 멈춘다. 다음 큰 기능은 오너 확인 후 진행한다.
인계 때 다음 작업에 필요한 사실·경로·열린 결정만 남겨 문맥을 줄이며, 예전 세션의 문맥이나 메모리만으로 현재 상태를 정하지 않는다.

**열린 항목 분류:** 현재 구현·권한·데이터 구조는 저장소에서 확인한다. 톡톡과 상품 수정의 공식 지원·자격은 외부 조사다.
톡톡 답 전송 목표·수량 파악 한계·대행사 제외·확정 기준의 자동 안내·이미지 배너 공지는 오너가 확인했으므로 되묻지 않는다.
개발 순서는 톡톡 다음 지마켓으로 오너가 확정했다. 지마켓의 자료·동작 범위와 배너 대상 채널·상품은 각 후속 단위에서 정한다.
현재는 톡톡 구현·합성 시험을 진행한다. 실제 채널 접속·고객 메시지 전송·유료 제품 모델 평가·배포는 이 구현 지시로 실행하지 않는다.

**착수 전 저장소 조사(2026-11-16, Claude Code `8412adc2`, 읽기 전용):** 톡톡은 현재 수집·읽기·초안·답 등록 모두 미지원이다
(`backend/src/main/resources/product-truth/capabilities.yaml`). 기존 미지원 감사는 커머스 API 범위이며 다른 공식 연동 가능 여부는 새로 조사해야 한다.
상품 읽기 기반은 있으나 상품 간 설정 점검과 공지 일괄 변경 기능은 확인되지 않았다(`ProductController.java` 및 상품/커넥터 소스 확인).
실제 기업 자료를 이용한 품질 검수는 아직 없고, 가입·역할·운영 복구의 남은 항목은 [가입 조사 기록](reviewnary_implementation_goals_draft_v1.md#가입-조사-기록과-후속-우선순위-2026-11-16-야간--조사만-코드-변경-없음)을 따른다.
외부 연동 자격에 관한 과거 설계 문구는 최신 공식 조건을 확인하기 전 확정 사실로 사용하지 않는다. 이 조사는 제품 변경·실채널 시험이 아니다.

**공식 문서 조사(인터뷰와 구분, 2026-11-16, Aside `d4akm2HsQPBrriPA`):**
- [톡톡 챗봇 API](https://github.com/navertalk/chatbot-api)는 Webhook 수신·메시지 전송을, [Handover](https://github.com/navertalk/chatbot-api/blob/master/handover_v1.md)는 상담원 인계를 제공한다. 현재 제품에 구현돼 있다는 의미가 아니다. 실제 판매자 계정의 이용 자격·설정·기존 기능과의 호환은 착수 조건으로 확인한다. 과거 상담 조회·주문 식별 연결은 이번 공식 문서에서 확인하지 못했으며, 불가능으로 단정하지 않는다.
- [판매자 공지 등록](https://apicenter.commerce.naver.com/docs/commerce-api/current/create-post-contents)과 [상품 일괄 적용](https://apicenter.commerce.naver.com/docs/commerce-api/current/apply-notice-product)은 존재한다. 이미지 배너 지원, 표시 위치, 기존 상품 공지 보존·해제 동작은 문서만으로 확인되지 않아 오너 요구를 충족한다고 판정하지 않는다.
- 상세 본문을 수정하는 대안은 [필드 생략 시 삭제](https://github.com/commerce-api-naver/commerce-api/discussions/25)와 [스마트에디터 ONE 형식 변경](https://github.com/commerce-api-naver/commerce-api/discussions/26) 주의가 있다. 현재 판매 중인 상품으로 시험하지 않았다.

---

**아래는 기존 제품 원칙·과거 전략과 근거다. 구상 중인 아이템은 위 「현재 아이템 기준」부터 읽는다.**

> **현재 읽기 기준 (2026-11-12).** 이 파일은 제품 정체성과 정본 경로를 안내한다. 아래 `ca470e2`와 §2-§5의 단계·상태는 **과거 스냅샷**이다. 현재 범위는 `docs/product-scope-v1.md` v1.15, 서버 중심 구조는 `docs/reviewnary_product_architecture_proposal_v1.md` §19, 최신 작업별 입구는 `docs/INDEX.md`의 「먼저 찾을 곳」을 따른다. 코드 존재·과거 통과·현재 배포를 서로 대신하지 않는다.
>
> **중기 목표와 현재 권한을 분리한다.** 오너 인터뷰 `docs/reviewnary_implementation_goals_draft_v1.md` 질문 76-80에는 판매자별 기록 재사용과 업무 유형별 자동 발송 위임의 방향이 이미 있다. 현재 개별 승인 계약을 이유로 그 목표를 취소하거나 새 결정으로 되묻지 않는다. 반대로 목표를 현재 자동 발송·외부 AI 쓰기 권한으로 읽지도 않는다.

> **This document is the canonical Product / Strategy / Current State reference for SellerOps.**
> It carries **identity, direction, honest state, authority, and standing fences**. It does not
> carry runtime status, capability tables, slice contracts, or execution plans — those live in
> their own homes and are linked, never mirrored.

---

## 0. Anchor & authority

**Anchored at `main` = `ca470e2056bf69a6fe899cca87fbdd408689fff1`**
(PR #317 NAVER SmartStore v1 · PR #318 ESM marketplace attribution · PR #319 Cafe24 callback dev receiver, all merged).

> **Forward pointer (2026-08-17).** This anchor is stale: `main` has since carried the v2 frontend
> reconstruction (`fc5f86ed`), Coupang review acquisition/locate live proofs, the review AI triage pilot
> (PR #458/#459), and the **product-assembly decision** — channel expansion paused, seller-visible
> channels = NAVER / Coupang / Cafe24, workflow-centric IA (홈 / 리뷰 / 문의 / 주문 / 채널 연결):
> `docs/product_assembly_ia_v1.md`, `docs/product-scope-v1.md` v1.8. §3–§5 state claims must be
> re-derived at the current commit; §5's Coupang "skeleton only" line in particular is superseded by
> `docs/multi-channel-connector-roadmap.md` §4.1.

Every state claim in §3–§5 is true **as of that commit** and must be re-derived, never assumed, at a
later commit. A recorded SHA is a snapshot, not a fact — including this one.

### What this document is responsible for

1. **Product identity** — what SellerOps is and is not, stable across channels and phases (§1).
2. **Strategic direction** — the arc from v1 to long-term AX, as direction, not schedule (§2).
3. **Honest state ledger** — live-verified vs implemented vs documented vs ruled (§3–§5).
4. **Document authority** — which doc wins on what, and which are demoted (§9).
5. **Standing scope fences** — the fences that survive v1 completion (§6–§7).

### What this document explicitly does NOT try to solve

- **Not a runtime status board.** Action Window / R4 runtime status stays in
  `docs/action-window-runtime/`, which is status-of-record for runtime detail and **outranks any
  summary here**. This document links; it does not restate.
- **Not a capability table.** `docs/multi-channel-connector-roadmap.md` §4.1 remains the single
  capability truth. §5 below carries **pointers and posture**, never a duplicate table.
- **Not a slice contract or execution plan.** No completion criteria, no gate mechanics.
- **Not a doc-cleanup pass.** It does not edit, fix, or reconcile the stale documents it names.
  It **classifies** them (§9) and **registers** their drift (§10).
- **Not a rewrite of `CLAUDE.md`.** Conflicts in `CLAUDE.md` are recorded in §10 as findings for a
  later slice, deliberately not resolved here.
- **Not a decision-maker.** Unresolved points are classified `[REPO]` / `[EXT]` / `[PO]` and
  surfaced (§11), never resolved unilaterally.
- **Not a history.** Executed records stay frozen; later truth arrives as a forward-pointer,
  never as an edit.

### Relationship to the historical predecessor

**The former `sellerops_current_state.md` is a historical handoff, not current product authority.**
This file routes product identity and strategy; current state must be derived from the latest lane evidence.

`sellerops_current_state.md` is left **untouched and unrebased by deliberate decision**. Its §1
baseline is 2026-07-08 with two stacked partial-update banners; its §10 "Active slice" names
*Review Response Completion v1* (2026-07-18) and predates PRs #316–#319; its §9 "Truth snapshot"
still reads that the Action Window is not implemented. Rebasing it would mean rewriting a living
handoff around a state it never observed.

**Its retirement or formal subordination is a separate, later doc-reconciliation slice** (§10).
Until that slice runs, treat `sellerops_current_state.md` as a **historical handoff record**:
readable for lineage, not citable for current state.

---

## 1. Product identity

> **SellerOps is an SME multi-channel commerce operations agent.**

It is **not**:

- a browser click-bot,
- a scraper,
- a connector console,
- a plain sales tool,
- an ERP / settlement product,
- an ad or marketing automation product,
- a general BI or analytics playground,
- a chatbot.

The **"unified seller center" is the surface. The operating loop is the engine.**

### 1.1 The operating loop

```
OBSERVE → ACQUIRE → NORMALIZE → UNDERSTAND → PRIORITIZE → ACT → ESCALATE → RESUME
```

(Canonical definition: `docs/product-scope-v1.md` §1.6.)

A **human checkpoint hands back a decision, never the whole workflow.** `ACT` is bounded in v1 —
preparation and explicitly approved execution through the permitted lane, no autonomous outbound write.
The older guided-only description does not cancel the API/delegate paths in scope v1.15 and proposal §19.

### 1.2 How agentic value is measured

> Agentic value is **not** measured by whether data acquisition is click-free.
> It is measured by the **total end-to-end operational work removed around human checkpoints.**

This is the single most load-bearing sentence in the product definition. A human clicking export on
the marketplace is **not** a failure of the agent — it is the correct, policy-safe shape of the
Action Window pattern. Removing the click is not the goal; removing the *operational burden around*
the click is.

### 1.3 Three identity corrections (live contradictions, corrected here)

| # | Correction | Superseded framing |
|---|---|---|
| 1 | Audience is **SME 제조사 / 브랜드 seller operations** (owner + online sales operator). | `docs/sellerops_product_roadmap.md` §1 "industrial / manufacturing e-commerce sellers, 40–50대 CEO" — retired as lineage. |
| 2 | **Settlement (정산) is out of scope.** Five data types only: 주문 · 문의 · 리뷰 · 상품 · 운영 리포트. | `docs/sellerops_ceo_connector_status_onepager.md` promises 정산 data. |
| 3 | The **Manufacturer Track** (VOC / market intelligence) is a long-term track, deliberately fenced from the Seller Track. | This is where the pre-SellerOps VOC lineage legitimately lands. Mixing it into Seller Track is the root cause of most identity drift. |

Canonical source for all three: `docs/product-scope-v1.md` §1.2 (identity), §1 (scope exclusions),
§3 (Manufacturer Track), §7-5 (no mixing with the Instagram/cardnews publishing track).

### 1.4 Autonomy modes

Assigned **per (channel × DataType × operation)** — never per marketplace, never per channel as a
whole (`docs/product-scope-v1.md` §1.4):

`AUTOMATIC_OPERATION` · `ACTION_WINDOW` · `FILE_IMPORT` · `INTEGRATION_PENDING`

**Historical default (at the anchor): `ACTION_WINDOW`.** Do not use it as the current default
for every review channel. Current NAVER review handling and server/API/delegate boundaries follow
`docs/product-scope-v1.md` v1.15, proposal §19 and the capability table (§4.1).

### 1.5 Product-canon update (2026-07-26) — pull-first, Agent-first

Identity-level decisions carried here; scope detail lives in `docs/product-scope-v1.md` **§1.7 / §1.8 /
§5.2** (scope lock **v1.7**), and the FE boundary in
`docs/action-window-runtime/agent-first-ui-light-adr.md`. This section states the identity/strategy
facets and points; it does not restate scope.

- **Multi-channel from the start.** SellerOps is a multi-channel customer-operations agent across
  NAVER · Coupang · Cafe24 · ESM+ · 11번가 · 오늘의집 and beyond — never a NAVER-only review tool. The
  channel set is open (§1). NAVER is the wedge, not the boundary. *(2026-08-17: open as **strategy**;
  the **product surface** shows NAVER / Coupang / Cafe24 only until further channels are proven —
  `docs/product_assembly_ia_v1.md` §2.)*
- **Default experience is pull-first / exception-push.** The seller re-checks when needed; routine
  results surface on return or as a daily summary; only defined exceptions push immediately (new
  inquiry, severe negative review, spike, safety/refund/legal risk, reply delay, important collection
  interruption). This supersedes any "a dashboard they open every day" framing.
- **Session Readiness.** Per-channel session liveness (login / 2FA / expiry) so that, when healthy, the
  seller checks login status roughly **once a day** and otherwise does not intervene.
- **Reply drafting in Company Voice (AI); send after approval, capability-gated.** Drafts are generated
  in the company's voice; MVP default is send-only-after-approval; a real send happens **only where a
  per-channel Capability (official API) supports it**, with the approval serving as the human
  checkpoint; unsupported channels get a draft or an operator-performed Action Window. Unapproved
  auto-send is later scope. This **supersedes** the earlier "rule-based only, no AI" reply stance
  (product-scope v1.4) — it changes the drafting mechanism only; the send fences (§6), the
  never-submit-without-capability boundary, `UNVERIFIED` for unverifiable channels, and the gated live
  submission (§6.2, §8-6) all stand, and no R4 reply-submission evidence is promoted.
- **Product Knowledge + disclosure Issue Operations (direction).** A Product Knowledge Pack (catalog,
  policies, detail-page text/images, FAQ, CS policy, operator-confirmed knowledge) feeds drafting;
  **detail-image-extracted facts record source/confidence and are not treated as confirmed before
  verification.** Complaint-vs-product comparison yields `NOT_DISCLOSED` / `DISCLOSED_BUT_WEAK` /
  `DISCLOSED_AND_CLEAR` / `FAILURE_DESPITE_CORRECT_USE` / `UNKNOWN` candidates — **never used to assign
  customer fault or auto-dismiss a complaint.**
- **FE is a Control Plane projection + command adapter.** It owns no journey state or execution order;
  new FE features are frozen and the existing FE is kept only as a temporary compatibility adapter. The
  direction is a minimal Control Center (things to check · in-progress · completed results · connection
  status) converging on a common OperationView + HumanCheckpoint
  (`docs/action-window-runtime/agent-first-ui-light-adr.md`, Accepted 2026-07-26).

> **Non-promotion.** This update advertises no channel capability and promotes no live claim. Capability
> truth remains §4.1 (production-supported = file upload only); live-verification status is unchanged.

---

## 2. Strategy

**Seller operations → multi-channel operations → SME commerce / manufacturing operations AX.**

### Stage 1 — Seller operations (current)

Make one seller's day-to-day operations calm on the channels they actually sell on. Prove the
Action Window pattern end-to-end on a single channel. **This stage is complete for NAVER.**

### Stage 2 — Multi-channel operations (next)

Prove that the pattern **generalizes**: a second channel, then a third, each with its own
acquisition method (API > export > manual), all converging on one canonical model and one
observability model. The thesis under test is not "can we add channels" but "does the
Action Window pattern survive contact with a channel it was not designed against."

Direction detail: `docs/multi-channel-connector-roadmap.md` §2 (end goal), §5.2 (per-channel
connection decisions), §11 (connection modes AUTOMATED / GUIDED / ASSISTED / MANUAL, orthogonal to
acquisition method).

### Stage 3 — SME commerce / manufacturing operations AX

The long arc. The operating loop, once stable across channels, becomes the substrate for
operational transformation at SME manufacturers and brands — not a dashboard they check, but an
agent that carries operational work between human decisions.

Recorded direction, **implementation forbidden until execution modes and checkpoints are stable**:

- **`OperationRun` domain** — `OperationRun` · `OperationTask` · `HumanCheckpoint` ·
  `ExecutionMode` · `CapabilityPolicy` · `ResumeState` (`docs/product-scope-v1.md` §1.7; direction
  only, §7-18 forbids implementation).
- **Operation Run Engine** — step 8 of the development sequence
  (`docs/multi-channel-connector-roadmap.md` §6).
- **Manufacturer Track** — "how does my product appear in the market", same canonical model,
  view-layer divergence only (`docs/product-scope-v1.md` §3).
- **Runtime expansion** — Windows / company-PC deployment, cloud-managed runtime, Device Vault +
  auto re-login, Projected Direct Action (`docs/product-scope-v1.md` §6.1 future-scope column;
  `docs/standing/sellerops_local_agent_runtime_adr.md` §2.2 / §3.5 / §6).
- **Registration paths** — sole-proprietor registration done; official seller-tool / API-partner
  registration runs in parallel. **NAVER 커머스 솔루션 마켓 is long-term and is NOT a prerequisite**
  for the first paid pilot (`docs/product-scope-v1.md` §1.3).

Each of these is an **independent axis**. None of them is advertised, and none of them gates
Stage 2.

---

## 3. Verified-state ledger (as of `ca470e2`)

### 3.1 The honesty ladder

The four-stage model is defined in `docs/multi-channel-connector-roadmap.md` 부록 A and is binding:

| Stage | Meaning | Evidence bar |
|---|---|---|
| **연결 가능** (connectable) | Seller can start the connection in-product | Credential template / connect flow exists |
| **구현됨** (implemented) | Collection code path exists, passes offline tests | No live run required |
| **라이브 검증** (live-verified) | ≥1 **supervised** real run succeeded, sanitized record | **Evidence doc link mandatory** |
| **운영 지원** (production-supported) | Product promises routine use: default-on, ops procedure, recovery path | — |

> **`live-verified` ≠ `production-supported`.**
> **Only `운영 지원` may be shown to a seller as "지원".**
> `라이브 검증` means a supervised pilot happened. `구현됨` / skeleton is not displayed at all.

**Production-supported today is file upload (all channels) and nothing else.**
(`docs/multi-channel-connector-roadmap.md` §4.1, L189–191.)

### 3.2 What merged in #317 / #318 / #319

**PR #317 — `feat/naver-smartstore-v1`** (merge `bc7d5d8`, 33 files, +4965/−22)

- *Product path*: NAVER guided API-onboarding wizard — `frontend/src/pages/ConnectNaver.tsx`,
  `frontend/src/components/guidedConnection/{GuidedConnectionWizard,SecureCredentialForm}.tsx`,
  `frontend/src/lib/guidedConnection/*` (state machine, bridge session readiness). Seller pastes
  Client ID / Secret manually.
- *Dev / diagnostic only*: `collector/src/cli/observe-api-center.ts` — read-only NAVER API-center
  **page-category** observer. Never logs in, issues, links, clicks, types, or submits, and
  **never reads Client ID or Secret**.
- *Product-adjacent runtime*: bounded row-census settle + same-session sentinel in
  `collector/instruments/calibration/discover-reply-target.ts`; byte-derived file-family classification in
  `collector/src/naver/review-download-save.ts`.
- *Governance*: the `CLAUDE.md` phase anchor and the mandatory product-boundary check;
  `docs/action-window-runtime/HANDOFF.md`; `naver-v1-integration-manifest.md`.

**PR #318 — `feat/esm-review-marketplace-attribution`** (merge `de91bb9`, 8 files, +826/−10)

- *Product path, fail-closed gate*: `collector/src/esm/esm-marketplace-verify.ts` —
  `classifySelectedMarketplace()` → `GMARKET | AUCTION | UNKNOWN | AMBIGUOUS`. Reads only
  `aria-selected/pressed/current/checked` plus a fixed label vocabulary; never arbitrary page text,
  never `loginMode`, host, channel code, or index.
- Four new gate stop codes in `esm-capture-gate.ts`; a distinct `esm-marketplace-ready.ready`
  sentinel.
- `--marketplace GMARKET|AUCTION` is now **required** (exit 7) before any export scan or click.
- *Read-only discovery*: `--observe-marketplace` mode, and `esm-marketplace-observe.ts`.

  > **Net effect: ESM review capture is now correctly BLOCKED rather than silently
  > mis-attributing.** That is the honest outcome and the intended one. The checklist records the
  > path as "not currently runnable, and must not be scheduled."

**PR #319 — `tools/cafe24-callback`** (merge `ca470e2`, 4 files, +193)

- *Dev-only tooling*: dependency-free Node HTTP receiver + Vercel handler. Reports `code` / `state`
  **presence booleans only** — no token exchange, no credential writes, no DB, no logging of values.
  Its own README declares it outside the product runtime.

### 3.3 State summary at `ca470e2`

| | |
|---|---|
| **Live-verified** | NAVER review export end-to-end (Run 4); NAVER `ORDER_SUMMARY` API (once, 2026-06-14); Cafe24 `ORDER_SUMMARY` E2E; Cafe24 board article backfill; file upload (all channels, E2E smoke); ESM session/reconnect (G0) |
| **Implemented, not live-verified** | NAVER guided onboarding wizard (offline-green on synthetic fixtures); NAVER reply preparation + guided reply session; ESM marketplace verification gate; Browser Projection V0 (unwired, State B) |
| **Documented / ruled only** | v1 completion rulings; onboarding "real backend boundary" clause; `OperationRun` direction |
| **Production-supported** | **File upload only.** |

> **Forward-pointer (2026-07-26).** This ledger is anchored at `ca470e2` and is not rebased. Later
> supervised live runs — the guided-import CTA E2E and the reshaped in-page NAVER review-import journey
> (2026-07-25/26, single account, disposable dev backend) and the retirement of the range-discovery run
> — are recorded in the workstream home `docs/workstreams/review_operations_mvp.md`, which outranks this
> summary for runtime state. **None of them changed production-support** (still file upload only) or
> promoted any §4.1 row.
>
> **Forward-pointer (2026-07-31, `026c113`).** Since this ledger was anchored, more supervised live
> proofs merged — **Cafe24 `ORDER_SUMMARY`** (#374), **Cafe24 REVIEW read board 4** (#375), and
> **Cafe24 INQUIRY read board 6** (#382, exact-window contract) — and a **common LangGraph agent
> platform** (`agent-runtime/`, #376–#381; read-only draft #384) was added. §4.1 was updated for the
> Cafe24 read rows. **Production-support is still file-upload-only** and none of these is a seller-facing
> feature. The consolidated snapshot is `docs/archive/2026-q3/sellerops_completion_checkpoint_v1.md`.
>
> **Forward-pointer (2026-07-31, `97ad192`) — Cafe24 pilot channel v1 declared complete.** Later PRs
> merged: Cafe24 REVIEW acquisition completion (#386, sanitized accounting + idempotent replay) and the
> REVIEW → Issue-Memory bridge + bounded historical reconciler (#387, reusing the existing `issue`
> graph, no new pipeline/LangGraph/migration). Cafe24 v1 = connect + orders/inquiries/reviews +
> exact-window/dedup + privacy + Attention/Issue-Memory + agent draft/brief; reply/comment **write**
> stays deferred (shown honestly). **Still pilot-only — production-support remains file-upload-only.**
> Baseline: `docs/evidence/INDEX.md`.

---

## 4. What NAVER v1 proved — and did not prove

NAVER SmartStore v1 = one channel, four surfaces: (1) guided API onboarding (orders),
(2) review export (Action Window), (3) session readiness, (4) guided review reply.
Phase source of truth: `docs/action-window-runtime/HANDOFF.md`.

### 4.1 Proved

All of the following are **human-driven / operator-supervised**, on the **dev seller
`NAVER_DEV_SELLER_SELF_01`**, against a **local dev backend (`localhost:8080`, never production)**.

- **Review export, end-to-end — Run 4 (2026-07-15).** Real human click → download →
  quarantine validation (OOXML sniff) → real `/api/uploads` ingest. Backend `SUCCESS 55/55/0/0`.
  Evidence: `docs/action-window-runtime/r4/r4-evidence-pack.md` §8-17.
- **`USER_ACTION_OBSERVED` fires on a real human click** — Run 5 (2026-07-16), non-mutating.
- **CLI session-recovery loop, live-proven** — Run 6 (2026-07-17), zero clicks.
- **Overlay operator label, live-verified** — §8-24 (2026-07-18).
- **Review identity reconciliation** — the row carries `리뷰글번호`; identity keyed
  `(channel, sellerAccountId, channelReviewId)` (D-036).
- **Composer-open / abort-safe hand-off evidence** — D-033, D-034, D-035.
- **NAVER `ORDER_SUMMARY` API collection**, once, 2026-06-14.

> **The real proof is architectural: the Action Window pattern works.** A human clicks the
> platform; the agent detects, validates, and ingests the result. That generalizes as a **pattern**.
> It does **not** generalize as a selector, a timing, or a DOM contract.

### 4.2 Did NOT prove

- **No live reply was ever submitted.** §9(c) explicitly rules live submission **not required** for
  v1. The terminal state is `OPERATOR_REPORTED_SUBMITTED` / `SUBMISSION_ABORTED`, always paired with
  `UNVERIFIED`. **`COMPLETED` is not a valid value** (D-032). The runtime never fabricates a
  completion.
- **No production backend was ever touched.** Every run hit `localhost:8080`.
- **No first-time API issuance.** The 2026-06-14 order pull was on an **already-configured**
  account. The wizard is offline-green against **synthetic fixtures** only. An assisted end-to-end
  walk against a freshly issued app **must not be claimed as v1-verified**.
- **B2 — row selectors are unratified.** The live calibration pass returned a **negative result**
  (`calibrationState: UNCALIBRATED`); the candidate ladder was de-wired and dead-lettered.
- **B3 — the `ARTIFACT_INVALID` cause is unexplained.** Accepted 2026-07-21 as a *known, unexplained
  caveat* by product-owner ruling, **not by proof**. Run B was a **one-off supervised diagnostic
  exception and is explicitly not precedent for product behavior**; no further B3 live download
  probes.
- **B1 — cross-source fingerprint.** Java ≡ TS parity is proven on the *same text*; a live NAVER DOM
  row's rendered text is **not** proven to normalize to the backend's *stored* body. Explicit
  non-goal.
- **B4** — cold-restart session inheritance stays open by design; a clean cold restart still
  requires re-login.
- **B5** — auto-relogin / Device Vault / credential autofill are **missing**. Do not advertise
  autofill.
- **B7** — bridge pairing is production-ready on **macOS only**; Windows / Linux fail closed.
- **Unattended browser collection is not approved by default. No seller-facing release.**
  *Wording narrowed 2026-09-15 (Responsibility Runtime v1). Original line: 「No unattended or scheduled
  collection.」 In this section it recorded what the NAVER v1 **Action Window (browser) runtime** did not
  prove; it never described official-API collection, which Self-Pilot Runtime v1 made routine on
  2026-08-18 and Responsibility Runtime v1 schedules as fixed windows. Record of the correction:
  `docs/standing/responsibility_runtime_v1.md` §20.*

### 4.3 Epistemics worth preserving

Three findings from the v1 record that outlive the phase and should govern how future proof is
claimed:

> *"Milestone A shipped a capability and proved it against fake drivers — it did not prove anything
> about NAVER."*

> *"Green tests certified an impossible ordering."*

> *"A vacuous guard against a footgun is the footgun."*

A run that observes a human action proves **a human acted** — never that the platform accepted the
request. A `DOWNLOAD_TIMEOUT` is equally consistent with consent-declined, range-refused, and
click-no-op.

---

## 5. Channel pointers

**Capability truth is `docs/multi-channel-connector-roadmap.md` §4.1.** The table below carries
posture and pointers only, and is deliberately not a duplicate of it.

### NAVER SmartStore

- **Status:** v1 merged (#317). `ORDER_SUMMARY` live-verified once; `REVIEW` export live-verified
  end-to-end (Run 4); guided reply implemented, **never live-submitted**; guided onboarding wizard
  offline-proven only.
- **Production-supported:** none. Every capability sits behind supervised, gated use.
- **Read:** `docs/action-window-runtime/` (status-of-record; entry point `HANDOFF.md`),
  `naver-smartstore-v1-plan.md` (phase definition, §8 blocker ledger B1–B9),
  `decisions.md` (D-001…D-036, append-only).

### ESM+ (Gmarket / Auction)

- **Status:** marketplace attribution gate merged (#318) and **fail-closed**. Session / reconnect
  (G0) live-verified 2026-07-07, re-proven 2026-07-08.
- **Blocked:** the GMARKET REVIEW **selected-state contract is unknown**. A live run detected
  `UNKNOWN`; observation found **no `role=tablist`, no visible AUCTION tab, no ARIA/native selected
  state** — REVIEW uses a **dropdown**, not a tablist. Capture is **not currently runnable and must
  not be scheduled** until the contract is resolved *and* a fresh single-use in-turn approval is
  granted.
- **Read:** `docs/esm/live-capture-checklist.md` (binding bounded-capture protocol),
  `docs/esm/decisions.md` (D1–D10), `docs/esm/live-capture-plan.md`.

### Cafe24

- **Status:** `ORDER_SUMMARY` live-verified E2E (token rotation, amount reconciliation).
  `REVIEW` / `INQUIRY` are `CONFIRMED` in code (`backend/.../cafe24/Cafe24ApiConnector.java:133-135`),
  with board-article shape, bounded persistence, and production-runtime backfill each verified.
- **Dev-only:** all of `tools/cafe24-callback/` (#319) — outside the product runtime.
- **Excluded:** board 9 (1:1 맞춤상담, highest-PII — never requested). No comments, no urgent-inquiry,
  no community write, no automatic AI reply posting. `reply_status` maps only `N → PENDING`; the
  answered token stays `UNKNOWN` and is never guessed.
- **Scope note:** Cafe24 covers **the seller's own mall only** — never a proxy or hub for other
  markets (`multi-channel-connector-roadmap.md` §5.2).

### Coupang

- **Status:** authentication skeleton only. Nothing implemented, nothing live-verified.
- **Direction:** seller self-developed key issuance is possible; the dual seller-tool conflict risk
  is recorded. **No official review API exists** (confirmed). `INQUIRY` is a CS-API candidate.
- **Read:** `multi-channel-connector-roadmap.md` §5.2.

### Others

11번가 (holds the only official review API in the set — do not claim seller-issued keys work),
SSG (no review surface on the channel), 오늘의집 (MANUAL only). All `INTEGRATION_PENDING` until
partner access is verified. See §4.1 and §5.2.

---

## 6. Standing scope fences

### 6.1 Permanent — no gate opens these

- **No auto app-issuance click**, no account/store-selection automation, no auth bypass.
- **SellerOps never reads Client ID or Secret from the API-center page.** The observe tool reads a
  sanitized page category only.
- **No automatic export / consent / download as product behavior.** Default production NAVER review
  export stays **human-driven Action Window**: the seller clicks export / consent / download **on
  the marketplace**; SellerOps only detects, validates, and processes the resulting download
  (D-004, D-005).
- **No hidden chained platform clicks** (D-003). Manual progress always remains available (D-007).
- **Ambiguous, missing, or changed targets fail closed** (D-006).
- **SellerOps never types and never clicks submit on a reply.** Outcome and verification are always
  reported as a **pair**; `UNVERIFIED` alone is forbidden and **`COMPLETED` is not a valid value**
  (D-032).
- **Sanitized output only.** Never expose credentials, tokens, cookies, seller IDs, Master ID, API
  keys, JWTs, raw page content, screenshots, exported files, or personal data — in logs, reports,
  events, or frames. `eventTimeMs` is internal; only `recencyBucket` may surface. No raw timestamps
  or elapsed durations.
- **Honest capability display.** Only `운영 지원` may be shown to a seller as "지원".
  `라이브 검증` is pilot-only and must not be presented as a supported feature.

### 6.2 Gated deferrals — open only with an explicit, fresh grant

- **Every live marketplace run** requires a **fresh, single-use, in-turn approval**. The mechanism,
  the Standing Safety Contract, the Approval Manifest, and the lifecycle are defined once in the
  **canonical Live-Run Approval Contract — [`docs/standing/sellerops_live_approval_contract.md`](sellerops_live_approval_contract.md)**;
  this section does not restate them. In short: the operator approves a prepared, displayed manifest
  (channel / account / surface / operation / mode / allowed actions), so the default grant is the one
  line **"Seated and ready."**
  > *A plan, a prior approval, a restored environment, or goal pressure is never authorization.*
  A generic/READ grant never covers a state-mutating (WRITE) action — a WRITE always needs its own
  explicit mode-`WRITE` approval. Before any live dispatch, the **product-boundary check** must be
  answered: is this product-path behavior or a labeled diagnostic exception, and if the latter, what
  is the human-driven product alternative?
- **Live guided reply submission** — requires the 6th G3 scope (`reply submission`) plus a one-shot
  G6. Neither is granted.
- **Browser Projection against a real marketplace** — §20 gate: marketplace terms-permissibility
  clarification **and** customer-PC security review. Projection stays a **non-default renderer**,
  production-runtime **unwired (State B)**, and is **never a v1 dependency**.
- **Unattended browser collection** — not approved by default; supervised only (a person starts every
  browser run). Opening it on a marketplace is its own capability gate.
  *Narrowed 2026-09-15 from 「Unattended / scheduled collection — supervised only」. The fence sits among
  browser-runtime deferrals (Projection, auto-relogin, Device Vault, managed runtime) and held browser runs;
  scheduled official-API collection (Self-Pilot routine READ, Responsibility Runtime runs) is not a browser
  run and was never what it held. Record: `docs/standing/responsibility_runtime_v1.md` §20.*
- **Auto-relogin, Device Vault, credential autofill** — deferred.
- **Windows / cloud managed runtime** — macOS pilot only.

### 6.3 Fences rewritten by v1 completion (not deleted)

Two fences were written for the v1 phase and are **superseded in wording, retained in substance**:

1. **"Coupang and Cafe24 — do not start."**
   → **Coupang and Cafe24 are the post-v1 channel targets.** Existing Cafe24
   `ORDER_SUMMARY` / `REVIEW` / `INQUIRY` code and the dev-only `tools/cafe24-callback` are
   **carve-outs**, and **none of them is production-supported**.
2. **"`sellerops-esm-live` is parked and frozen — do not merge or continue" (D-012).**
   → The **worktree protection stands permanently** (§7 — it is a runtime holder). The **work is
   merged** (#318), so D-012's status line is retired by forward-pointer, not by edit.

---

## 7. Runtime-holder rule

**Never develop in, clean, remove, or `git worktree remove` a runtime holder.**

These worktrees hold live browser profiles, `.env` files, connections, and run state that exist in
exactly one place on disk and are **not recoverable from git**:

- `BE/worktrees/sellerops-r4-runtime` (NAVER)
- `BE/worktrees/sellerops-naver-live-review-match` (NAVER)
- `BE/worktrees/sellerops-action-window-runtime-verification`
- `sellerops-esm-live` (ESM)

`git worktree remove` **silently discards ignored files**. Removing one destroys its runtime assets
with no warning and no recovery path. This rule survives v1, survives #318 having merged the ESM
work, and has no gate that opens it.

**Related standing rules:** stage exact files, never `git add .`. Never stage or delete `.env`,
`.profile/`, `.status/`, `.connections/`, `downloads/`, screenshots, raw HTML, exported marketplace
files, real NAVER/ESM/review data, or credentials. No force-push.

---

## 8. Post-v1 priorities

Direction and sequence. **No dates.** Each step requires its own approval.

> **Operational-capability roadmap (2026-07-26, v1.7).** Alongside the channel-capability sequence
> below, the operational build order is: **Session Readiness (per-channel) → Acquisition Supervisor
> (stable multi-channel pipeline) → common Tutorial / HumanCheckpoint → FE-independent headless
> acquisition → Notification Intent (the exception-push triggers) → Product Knowledge → Response
> draft / approval / (capability-gated) send → Issue detection → minimal Control Center.** Two
> repo-reality adjustments: FE-independent headless acquisition is already proven as a seam
> (`collector/test/crossstack/journey-live-headless.test.ts`) so it may move earlier; and real *send*
> stays gated and late because most channels have no official reply API. Session Readiness is the
> explicit first step — it is the precondition for repeatable acquisition and has the most existing
> primitives (pairing, profile persistence, login-org guard).

1. **Declare v1 closed.** No document currently records the transition; the phase fences still read
   as if v1 is running. This is the precondition for everything below.
2. **Honest capability refresh** — §4.1 rows for NAVER reply-submission, ESM attribution, and
   Cafe24 REVIEW/INQUIRY. **`운영 지원` stays file-upload-only**; that must not quietly change as a
   side effect.
3. **Unblock ESM+ GMARKET REVIEW** — resolve the selected-state contract (dropdown, not tablist),
   *then* seek a fresh single-use grant. **PAUSED 2026-08-17** (channel expansion paused —
   `docs/product_assembly_ia_v1.md` §2).
4. **Second-channel Action Window calibration** — the real post-v1 thesis test: does the pattern
   survive a channel it was not designed against. **Answered in practice by Coupang REVIEW
   (LIVE_PROVEN, §4.1); further channels PAUSED 2026-08-17.**
5. **Cafe24 as a first-class channel** — the code already carries `CONFIRMED` capabilities; the
   fence, not the implementation, is what lags.
6. **Guided reply live submission** — deliberately deferred out of v1. Requires the 6th G3 scope +
   one-shot G6, and must remain `OPERATOR_REPORTED` / `UNVERIFIED`.
7. **Coupang guided key issuance**, with provider / seller-tool registration inquiries in parallel.
8. **`OperationRun` engine** — only after execution modes and checkpoints are stable. Currently
   direction-only; implementation is forbidden.
9. **Long arc** — Windows / company-PC deployment, cloud-managed runtime, Device Vault and
   auto-relogin. Each an independent axis; none advertised; none gating Stage 2.

---

## 9. Document authority map

### 9.1 Conflict priority

When sources conflict, this order wins:

1. explicit product-owner decisions from the current task
2. `docs/product-scope-v1.md` — product scope contract, **scope lock v1.7**
3. `docs/frontend/sellerops_frontend_spec.md` — frontend source of truth
4. `docs/standing/sellerops_local_agent_runtime_adr.md` — runtime boundary ADR
5. `docs/multi-channel-connector-roadmap.md` §4.1 — the living capability table
6. the active slice document (`docs/slices/*`)
7. current implementation evidence
8. historical roadmap and phase records

**This document sits alongside ranks 2–5 for identity, strategy, state honesty, and authority.** It
does not outrank `product-scope-v1.md` on scope, `frontend_spec` on frontend, the ADR on runtime
boundaries, or §4.1 on capability. Where it and they disagree on those domains, **they win and the
conflict is reported**, not silently resolved.

**Runtime detail is the one carve-out:** `docs/action-window-runtime/` is status-of-record for
Action Window / R4 runtime status and **wins over any status claim in this document or in any
router.**

### 9.2 Canonical (read these)

| Document | Owns |
|---|---|
| **this document** | Product identity, strategy, honest state, authority map, standing fences |
| `docs/product-scope-v1.md` (lock **v1.11**; Self-Pilot Runtime → `docs/standing/self_pilot_runtime_v1.md`; Auth + Growth Instrumentation → `docs/standing/auth_growth_instrumentation_v1.md`; Service Readiness → `docs/standing/service_readiness_v1.md`) | Product scope contract, operating loop, autonomy modes, pull-first/exception-push, Session Readiness, Company Voice, registration decisions, Manufacturer Track, visible-channel set |
| `docs/product_assembly_ia_v1.md` (2026-08-17) | **Product IA, screen responsibility, seller-visible channel set** — supersedes `frontend_spec` §5–§8·§17-A |
| `docs/frontend/sellerops_frontend_spec.md` | Frontend principles: states, seller language, a11y, capability honesty, guided connection, Action Window screens |
| `docs/multi-channel-connector-roadmap.md` | Collection strategy; **§4.1 = capability truth**; §5.1, §5.2, §11 |
| `docs/standing/sellerops_local_agent_runtime_adr.md` | Local-agent runtime boundary, projection direction |

### 9.3 Status-of-record (workstream homes — status lives here, never in a router)

| Workstream | Home | Entry point |
|---|---|---|
| Action Window / R4 NAVER Runtime | `docs/action-window-runtime/` | `HANDOFF.md` |
| Action Window frontend | `docs/workstreams/action-window-frontend/` | `progress.md` |
| ESM Plus live capture | `docs/esm/` | `live-capture-checklist.md` |

> **Router rule.** A router carries **paths, not state**. If a status change forces an edit to a
> routing table, that surface was carrying state that belongs in a workstream home. Paths rot
> rarely and break loudly; status rots constantly and silently.

### 9.4 Subordinate (own their domain; not identity or strategy authorities)

`docs/action-window-runtime/HANDOFF.md` (self-declares it does not outrank the
canonical product docs) · `docs/channel/channel-capability-registration-matrix.md` (derived view of §4.1) ·
`docs/archive/2026-q3/sellerops_completion_checkpoint_v1.md` (point-in-time recovery baseline at `026c113`; derived
from §4.1 + this document, never redefines them) ·
`docs/evidence/INDEX.md` (Cafe24 pilot-v1 completion baseline at `97ad192`;
derived, pilot-level, not production-supported) · `docs/slices/*` ·
`docs/workstreams/action-window-frontend/*`.

### 9.5 Frozen (append-only — never rewritten)

All `docs/action-window-runtime/r4-*` dispatch and run records ·
`docs/action-window-runtime/decisions.md` (D-001…D-036) · executed entries in `docs/esm/`.

> Later truth arrives as a **forward-pointer, never an edit**. D-031 deliberately preserves an
> incorrect `☑ Bridge paired` on record, because back-dating it would destroy the only trace of the
> mistake. The de-wired candidate ladder is **dead-lettered, not deleted**.

### 9.6 Stale — cite as superseded, do not fix here

`docs/sellerops_canonical_reference.md` (see §0) · `docs/PROJECT.md` (describes a different product) ·
root `README.md` lede · `docs/sellerops_product_roadmap.md` (self-banners 2026-06-15) ·
`docs/sellerops_ceo_connector_status_onepager.md` (self-banners 2026-06-13) ·
`docs/sellerops_ui_reference.md` (already SUPERSEDED) · `docs/sellerops_phase{0,1,2,3a,3b,3c,3d}*.md`
(historical evidence).

> **2026-10-12.** 이 목록의 파일 대부분은 그 뒤 저장소에서 삭제됐다 — `e1550892`(2026-07-22,
> legacy VOC 분리)와 `39186802`(2026-08-19, 49개 폐기). 이름이 여기 남은 것은 "이 틀이 폐기됐다"는
> 뜻을 싣기 때문이고, 파일이 없는 것이 정상이다. 삭제 커밋별 전체 목록과 복구 방법:
> `docs/lineage_index.md` § 저장소에 없는 문서 이름.

### 9.7 Stale *by decision* — must be reported, never silently edited

`docs/slices/action-window-v1.md` (DRAFT; still describes overlay / download-detection seams as
미구현 — a product-owner decision, deliberately not taken) · `docs/multi-channel-connector-roadmap.md`
§4.1's date header · `docs/action-window-runtime/HANDOFF.md`'s own Git-state block (self-flagged:
re-derive from `git log`).

### 9.8 Legacy lineage — not product, do not delete

Pre-SellerOps VOC / OliveYoung / industrial-review-ops era. Retained for lineage; **not citable for
product state**.

> **2026-10-12 교정.** 이 절은 "several retain live code at repo root
> (`app_industrial_review_ops.py`, `src/voc/`, `cardnews/`)"라고 적고 있었다. **그 코드는 이 저장소에
> 없다** — `e1550892`(2026-07-22, legacy VOC 분리)에서 문서와 함께 분리됐고, 세 경로 모두 현재
> 존재하지 않는다. 아래 문서 이름들도 같은 커밋에서 삭제됐다; 복구 방법은
> `docs/lineage_index.md` § 저장소에 없는 문서 이름.

`docs/PROJECT.md` · `docs/instagram_voc_brand_strategy.md` ·
`docs/instagram_public_education_post_{001,002}.md` · `docs/instagram_voc_publishing_checklist.md` ·
`docs/instagram_voc_dm_response_script.md` · `docs/instagram_voc_dm_conversion_ledger.md` ·
`docs/public_instagram_cardnews_spec.md` · `docs/public_instagram_drafts/*` ·
`docs/review_ops_architecture.md` · `docs/review_ops_brand20_pipeline.md` ·
`docs/review_ops_brand20_manual_mapping_guide.md` · `docs/review_acquisition.md` ·
`docs/detail_page_snapshot_design.md` · `docs/agent_harness_design.md` (repo-process meta) ·
`docs/agent_orchestration_playbook.md` (repo-process meta).

---

## 10. Known drift register

**Registered, not resolved.** Each entry is a finding for a later doc-reconciliation slice. Nothing
in this section is fixed by this document.

| # | Location | Drift |
|---|---|---|
| D1 | root `CLAUDE.md`, "Current phase" | Freeze text — "No push / PR / merge / rebase / remote sync … until the single final v1 integration" and "the branch stays local". #317/#318/#319 are merged. |
| D2 | root `CLAUDE.md`, "Working directory" | Names `/Users/taewookang/Downloads/workspace/aiagent-sellerops` — **that directory does not exist**. |
| D3 | root `CLAUDE.md`, "Scope fence" | "Coupang and Cafe24 … Do not start them" — #319 merged Cafe24 tooling; Cafe24 REVIEW/INQUIRY are already `CONFIRMED` in code. Superseded by §6.3 here. |
| D4 | root `CLAUDE.md`, required reading order | Cites `product-scope-v1.md` as **v1.1**; the actual lock is **v1.7** (2026-07-26). The doc title itself is now v1.7; `CLAUDE.md` still lags. |
| D5 | `docs/sellerops_canonical_reference.md` §1, §9, §10 | Baseline 2026-07-08 with two stacked partial-update banners; §10 predates #316–#319; §9 "Truth snapshot" still says the Action Window is not implemented. **Deliberately not rebased** (§0). |
| D6 | `docs/product-scope-v1.md` §1.5, §6.1, §7-15 | ~~Still states Action Window is 미구현~~ — **RESOLVED (v1.7, 2026-07-26):** corrected to "NAVER 리뷰 라이브 검증됨(1계정·disposable·운영 지원 아님), 그 외 미구현", consistent with §4.1. |
| D7 | `docs/frontend/sellerops_frontend_spec.md` §18 | Same 미구현 claim for the Action Window review screen. Also cites product-scope as v1.2 in its appendix. |
| D8 | `docs/multi-channel-connector-roadmap.md` §4.1 | Header reads "그 외 행·열은 2026-07-07 기준"; rows not refreshed for #316–#318. Note: `HANDOFF.md` instructs that this staleness be **reported, not edited** from a runtime branch. |
| D9 | `docs/channel/channel-capability-registration-matrix.md` | Dated 2026-07-08; **no row exists for the reply-submission (write) axis** despite scope lock v1.7. Still open — the matrix is derived from §4.1, and reply submission stays guided/observe-only/`UNVERIFIED`/gate-locked (not a supported capability), so this is a registration-tracking gap, not a promotion. |
| D10 | `docs/esm/decisions.md` D2, `live-capture-plan.md` §1/§3-G2 | Still encode the **tablist** model (index 0 = GMARKET, 1 = AUCTION) that #318 disproved. |
| D11 | `collector/src/esm/esm-marketplace-verify.ts` | Ships `MARKETPLACE_VERIFICATION_METHOD = "selected-tab-label"` — a code-level name for a contract the same PR states is unknown. **Code-level drift, not doc-level.** |
| D12 | `docs/esm/live-capture-checklist.md` | G1/G2 marked `[x]` PASSED on a signal later shown to be non-discriminating; a "Next single action" line contradicts the "not currently runnable" blocker 60 lines below. |
| D13 | `docs/action-window-runtime/HANDOFF.md` §0 | "**PLAN-ONLY. Nothing here is executed.**" — it was executed (#317). Ahead/behind counts stale. |
| D14 | `docs/action-window-runtime/HANDOFF.md` §8, §9, §10 | "No branch integration is performed now"; "single remote git integration happens only at this completion point" — now past. |
| D15 | `docs/action-window-runtime/HANDOFF.md` | Internal contradiction: "✅ No `[PO]` item remains open" vs a trailing "Next — two OPEN product-owner decisions from Run 5". Also: "§8-24 is now the last live contact" (false — 07-20 runs followed). |
| D16 | `docs/action-window-runtime/decisions.md` D-012 | "The parked ESM marketplace-attribution experiment is neither completed nor merged" — merged as #318. Retire by forward-pointer (append-only). |
| D17 | root `README.md` | Lede frames the product for a "manufacturing CEO" and says "Real channel APIs … are not implemented yet". Links `docs/PROJECT.md` as the architecture doc — a different product. |
| D18 | `tools/cafe24-callback/README.md` | Specifies `/cafe24/callback`; the product backend uses `/api/connect/cafe24/callback`. Cites "§P5"/"§P7" of a protocol document that **does not exist in the repo**. — **Reconciled (Cafe24 Routine + Connection Reliability Hardening v1):** the README now documents the two paths as different **by design** (the dev receiver never exchanges the code; the product endpoint does) and the dangling "§P5/§P7" citations were removed. The tool is **git-tracked**, not "미추적" (the connectivity-decision doc was corrected to match). |
| D19 | `docs/lineage/sellerops_cafe24_review_inquiry_capture.md` | Header still says "PR A is the offline storage foundation only, no Cafe24 client, no live call" while its own body documents PR B–E and promotion to `CONFIRMED`. |
| D20 | `docs/standing/sellerops_local_agent_runtime_adr.md` §7(1) vs §3.4 | `LocalAgentState` "12상태" vs the §3.4 correction to "11상태". |
| D21 | — | **~18 of 42 `docs/*.md` files are legacy lineage** with no quarantine marker (§9.8). |

### Sequencing note

Several of these are only *correct to fix* after §8-1 (declare v1 closed): D1, D3, D13, D14, D16.
Fixing them earlier asserts the closure without recording it.

---

## 11. Open decisions

Classified per the standing assumption rule. **None is resolved here.**

### `[REPO]` — repository-verifiable

| # | Question | What closes it |
|---|---|---|
| R1 | Cross-org dashboard aggregation API is absent | Implement or formally defer; blocks dashboard redesign |
| R2 | Non-Cafe24 seller-account creation API is absent | Blocks generalized channel connection |
| R3 | Is `MARKETPLACE_VERIFICATION_METHOD = "selected-tab-label"` accurate post-#318? (D11) | Read the shipped verifier against the observed dropdown contract |
| R4 | Does any live path still reference the de-wired candidate ladder? | Grep `collector/` — confirmed absent at `ca470e2`; re-verify before claiming |
| R5 | `LocalAgentState` — 11 or 12 states? (D20) | Read the enum |

### `[EXT]` — external research required

| # | Question | What closes it |
|---|---|---|
| E1 | **ESM+ GMARKET REVIEW selected-state contract** — the surface is a dropdown with no ARIA selected state | Contract discovery, then a fresh single-use grant. **Blocks all ESM review capture.** |
| E2 | **B1 cross-source fingerprint** — does live DOM row text normalize to the stored body? | Explicit non-goal today; would need live extraction |
| E3 | **B3 `ARTIFACT_INVALID` root cause** | Accepted as unexplained by PO ruling; **closed to further live probing** |
| E4 | Marketplace terms-permissibility for Browser Projection and for Action Window generally | Policy clarification track (runs in parallel; explicitly **not** an indefinite blocker for supervised pilots) |
| E5 | Coupang / 11번가 / ESM+ / SSG / 오늘의집 provider & partner registration paths | Registration inquiries post-사업자등록 |
| E6 | NAVER API coverage for inquiries, products, store metadata | Not established; **must not be promised as v1** |

### `[PO]` — product-owner decision required

| # | Question |
|---|---|
| P1 | **Formally declare NAVER v1 closed** — the precondition for §8 and for D1/D3/D13/D14/D16 |
| P2 | **Retire or subordinate `docs/sellerops_canonical_reference.md`** — decided in principle (§0); the mechanism and slice are open |
| P3 | ~~Which channel is the **second** Action Window calibration target: ESM+ (blocked on E1) or Cafe24 (code ahead of fence)?~~ **Closed 2026-08-17** — Coupang REVIEW became the second live-proven Action Window channel; further channel work is paused (`docs/product_assembly_ia_v1.md` §2). |
| P4 | Should a selected date range **gate** export, or stay observe-only? (D-025 leaves it explicitly non-gating and calls the gating question a PO call) |
| P5 | When does any capability move from **라이브 검증** to **운영 지원**, and what is the bar? Today the answer is "nothing has, except file upload" |
| P6 | ~~Final mobile navigation composition; final naming for the unified customer-response screen~~ **Closed 2026-08-17** — 홈 / 리뷰 / 문의 / 주문 tabs + 더보기; the response surface is 문의 (`/inquiries`) with reviews on 리뷰 (`docs/product_assembly_ia_v1.md` §3). |
| P7 | Legacy-lineage quarantine: move, mark, or leave the ~18 non-product docs (D21) |

---

*End of canonical reference. Anchored at `ca470e2`. Re-derive before citing at any later commit.*
