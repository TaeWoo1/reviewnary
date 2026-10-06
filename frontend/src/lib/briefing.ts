/**
 * <b>The disconnected morning — the one case where 「확인할 일은 없습니다」 is a lie.</b>
 *
 * <p>Disconnected Channel Onboarding Live Walkthrough v1 §17. A seller who signed up two minutes ago
 * has nothing waiting because nothing has been read yet. There IS something to do, it is the only
 * thing, and these two lines replace the greeting until the org has one connected channel — with no
 * flag and nothing to turn off.
 *
 * <p><b>이 모듈이 한때 말하던 나머지는 전부 사라졌다.</b> 인사말과 「무엇이 기다리는가」 문장은
 * `AgentHome`의 opener turn이 가져갔다가(Agent Interaction Model v2 §11), 2026-10-06에 Home이 하나가
 * 되면서 그 turn 자체와 함께 없어졌다 — 연결을 끝낸 org의 Home은 대화가 아니라 할 일 목록이고, 기다리는
 * 것이 무엇인지는 그 목록이 말한다(`docs/reviewnary_design.md` §8-A v3.3). 여기 남은 두 줄은 <b>연결이
 * 하나도 없는 아침</b>의 것이고, 그 아침에는 목록이 할 말이 없다.
 */
export const DISCONNECTED_HEADLINE = "판매 채널을 연결하면 시작할 수 있습니다.";
export const DISCONNECTED_SUBLINE =
    "채널을 연결하면 주문·문의·리뷰를 대신 확인하고, 먼저 봐야 할 일을 여기에 정리해 두겠습니다.";
