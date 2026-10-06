import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { Btn } from "../../components/ui/Btn";
import { useAuth } from "../../lib/auth";
import { KNOWLEDGE_NOUN } from "../../lib/knowledgeWords";

/**
 * 설정 — 네 구역, 한 줄에 한 항목 (docs/reviewnary_design.md §7).
 *
 * <p><b>전에는 이름 없는 상자 넷이었다.</b> 화면에 `h2`가 하나도 없었고 — 구역의 이름은 전부
 * `aria-label`에만 있었다 — 눈으로 읽는 사람에게 이 화면의 구조는 상자의 간격뿐이었다. 그래서 「연결
 * 알림」과 「연결된 기기」가 다른 구역에 속한다는 사실이 보이지 않았다. 구역의 이름과 그 아래 가는 선
 * 하나는 주문·상품 상세·리포트가 쓰는 그 문법이고, 상자 넷보다 적은 잉크로 더 많이 말한다.
 *
 * <p><b>한 줄은 이름 · 지금 값 또는 이 설정이 정하는 것 · 들어가는 길이다.</b> 전에는 모든 줄이
 * 「어떤 회사인지 — AI가 답변 표현을 고를 때 참고합니다. …」처럼 em dash로 시작하는 설명문을 달고 있었고,
 * 여덟 줄이 같은 모양이면 그 문장들은 함께 읽히지 않는다. 길이를 줄인 것이지 뜻을 줄인 것이 아니다 —
 * 회사 정보가 <b>근거가 아니라는</b> 경계는 그대로 남아 있다. 그것이 사라지면 이 줄은 거짓이 된다.
 *
 * <p><b>지금 값은 세션이 이미 아는 것만 적는다.</b> 네 가지 사실(스토어·계정·이메일·표시 중인 자료)은
 * 세션에 있고, 나머지 설정의 현재 값은 이 화면이 읽지 않는다 — 그래서 그 자리에 기본값을 지어내지
 * 않는다. 아무것도 켜고 끄지 않는다는 규칙도 그대로다: 아무것도 바꾸지 않는 스위치는 제품이 지키지 않는
 * 약속이다.
 *
 * <p><b>강조는 두 곳뿐이다.</b> 이 계정에서 나가는 일과 이 계정에 붙은 기기 — 되돌리는 값이 다른 둘.
 * 나머지 여섯은 다른 화면으로 가는 길이고, 길은 단추가 아니라 글자다. 전에는 여덟 줄 모두 오른쪽 끝에
 * 테두리 단추를 하나씩 달고 있어서 그 가운데 무엇이 계정을 건드리는 일인지 구별되지 않았다.
 *
 * <p><b>이름은 `lib/knowledgeWords.ts`의 것이다.</b> 이 화면이 「운영 정책 / 답변 기준」이라고 부르던
 * 것을, 그 줄이 여는 화면은 이미 「운영 기준」이라고 부르고 있었다.
 */
export function SettingsHome() {
  const { user, logout } = useAuth();
  const onDemoData = import.meta.env.VITE_USE_MOCKS === "true";

  return (
    <div className="space-y-6">
      <PageHead title="설정" />

      <Block title="워크스페이스">
        <Row label="스토어" value={user?.orgName ?? "내 스토어"} />
        <Row label="사용 중인 계정" value={user?.name ?? "운영자"} />
        {user?.email ? <Row label="이메일" value={user.email} /> : null}
        <Row label="표시 중인 자료" value={onDemoData ? "데모 데이터" : "연결된 자료"} />
      </Block>

      <Block title="AI 답변과 운영 기준">
        <Row
          label="회사 정보"
          value="답변 표현을 고를 때 참고합니다. 배송·환불·규격의 근거는 아닙니다"
          action={<Go to="/settings/company">회사 소개 적기</Go>}
        />
        <Row
          label={KNOWLEDGE_NOUN.operatingRules}
          value="배송·취소·교환·증빙처럼 상품과 무관한 답변의 근거"
          action={<Go to="/settings/policies">기준 관리</Go>}
        />
        <Row
          label="AI 답변 스타일"
          value="말투·길이·인사·호칭. 답변 내용은 바꾸지 않습니다"
          action={<Go to="/settings/style">스타일 설정</Go>}
        />
        <Row
          label="리뷰 답변 문구"
          value="리뷰 답변이 처음 채워지는 문구를 유형별로"
          action={<Go to="/settings/review-templates">문구 설정</Go>}
        />
        <Row
          label="연결 알림"
          value="연결이 끊기거나 확인이 필요할 때. 표시가 없다고 모든 연결이 정상은 아닙니다"
          action={<Go to="/settings/alerts">알림 보기</Go>}
        />
      </Block>

      {/* 반복 문제와 리포트는 메뉴가 들기 전까지 여기 「더 보기」로 서 있었다 — 메뉴 항목으로 가는 두 번째
          문은 한 곳에 붙은 두 번째 이름이다. 메뉴가 들고 가지 못하는 것 하나만 남았다: 맡긴 일 자체. */}
      <Block title="자동 운영">
        <Row
          label="고객 운영 관리"
          value="reviewnary가 주기적으로 확인하는 일 · 시작·일시정지·중지"
          action={<Go to="/customer-operations">관리</Go>}
        />
      </Block>

      <Block title="계정">
        <Row
          label="연결된 기기"
          value="비밀번호 대신 쓰는 연결입니다. 여기서 해제할 수 있습니다"
          action={<Go to="/settings/devices" strong>기기 보기</Go>}
        />
        <Row
          label="로그아웃"
          value="이 브라우저에서만 나갑니다. 수집된 자료는 그대로 남습니다"
          action={
            <Btn variant="outline" size="sm" onClick={logout}>
              로그아웃
            </Btn>
          }
        />
      </Block>
    </div>
  );
}

/** 한 구역 — 이름과 그 아래 선 하나. 상자가 아니고, 주문·리포트와 같은 렌더러를 쓴다. */
function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-2">
      <SectionHeader title={title} />
      <div>{children}</div>
    </section>
  );
}

/**
 * 한 줄 — 이름 / 값 / 길.
 *
 * `value`가 세션이 아는 사실일 때는 그것이 지금 값이고, 설정 항목일 때는 그 설정이 정하는 것이다.
 * 둘 다 아닐 때 이 자리에 기본값을 적지 않는다 — 이 화면은 설정값을 읽지 않는다.
 */
function Row({ label, value, action }: { label: string; value: string; action?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 border-t border-line/70 py-2 text-sm">
      <span className="w-[150px] shrink-0 break-keep font-medium text-ink">{label}</span>
      <span className="min-w-0 flex-1 break-keep break-all text-muted">{value}</span>
      {action ? <span className="shrink-0 text-right">{action}</span> : null}
    </div>
  );
}

/** 다른 화면으로 가는 길. 단추가 아니다 — 여덟 줄에 선 단추 여덟 개는 아무것도 가리키지 않는다. */
function Go({ to, children, strong = false }: { to: string; children: ReactNode; strong?: boolean }) {
  return (
    <Link
      to={to}
      className={`rounded text-sm font-semibold underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
        strong ? "text-brand-700" : "text-muted hover:text-ink"
      }`}
    >
      {children}
    </Link>
  );
}
