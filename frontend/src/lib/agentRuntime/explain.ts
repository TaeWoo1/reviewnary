import { AgentRuntimeError } from "./agentClient";

/** Coarse, content-free explanation for an agent-runtime failure. */
export function explainAgentError(err: unknown): string {
  if (err instanceof AgentRuntimeError) {
    switch (err.code) {
      case "MISSING_ACCOUNT_SCOPE":
        return "리뷰 답변은 판매 계정을 선택해야 해요.";
      case "UNRECOGNIZED_GOAL":
        return "지원하는 작업을 찾지 못했어요. 문의·리뷰·이슈 중 하나로 다시 말해 주세요.";
      case "EXECUTION_ENABLED":
        return "안전 점검 실패: 외부 발송 경로가 활성화되어 실행을 중단했어요.";
      case "NO_CHECKPOINT":
        return "이 실행에는 확인 단계가 없어요.";
      case "MISSING_TOKEN":
        return "로그인이 필요해요.";
      case "RESUME_IN_PROGRESS":
        return "이미 처리 중인 요청이에요. 잠시 후 다시 시도해 주세요.";
      case "RESUME_CONFLICT":
        return "다른 곳에서 먼저 처리되어 순서가 어긋났어요. 새로고침 후 다시 확인해 주세요.";
      case "HTTP_409":
        return "이미 처리되었거나 다른 곳에서 변경된 항목이에요. 새로고침 후 다시 확인해 주세요.";
      default:
        return `요청이 거부됐어요 (${err.status}).`;
    }
  }
  return "에이전트 서비스에 연결하지 못했어요.";
}
