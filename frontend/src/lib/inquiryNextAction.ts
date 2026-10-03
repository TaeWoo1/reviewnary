import { WORK_STATE, type WorkStateWord } from "./workState";
import type { FeedItem } from "./types";

/**
 * <b>「이건 어떤 상태고, 그래서 내가 뭘 하면 되는가」 — 한 문의에 대해 한 번만 답한다.</b>
 *
 * <p><b>왜 이 파일이 생겼나.</b> 상세 화면은 상태를 말했고, 다음 행동은 말하지 않았다. 실제 live
 * 데이터에서 그 차이가 드러난 문장이 하나 있다 —
 * 「이 문의에는 reviewnary가 답변 방향을 제안할 수 없습니다.」 맞는 말이지만 <b>이유가 없고</b>, 이유가
 * 없으면 판매자가 읽는 뜻은 「제품이 이 문의를 못 다룬다」가 된다. 실제 이유는 그게 아니다: 초안은
 * <b>답변을 기다리는 문의</b>에만 쓰이고, 이미 답변된 문의에는 쓰지 않는다. 그건 결함이 아니라 규칙이고,
 * 규칙이면 화면이 말할 수 있다.
 *
 * <p><b>근거는 이미 행에 있다.</b> 아무것도 추론하지 않는다:
 *
 * <ul>
 *   <li>{@code status === "ANSWERED"} — 채널 자신이 답변됐다고 말한 상태. {@code answeredAt}이 그 시각.</li>
 *   <li>{@code workItemId} — {@code InquiryRowsService.WORKABLE} = {OPEN, PROPOSED}인 work item이
 *       있을 때만 행에 실린다. 즉 이 값이 있다는 것은 「판매자의 다음 행동을 기다리는 중」과 같은 말이고,
 *       없다는 것은 「이 문의는 그 목록에 없다」는 <b>사실</b>이다 — 왜 없는지에 대한 짐작이 아니다.</li>
 * </ul>
 *
 * <p><b>말하지 않는 것.</b> work item이 없는 이유를 열거하지 않는다(「스팸으로 정리됐거나, 실행 중이거나,
 * …일 수 있습니다」). 행이 담고 있지 않은 사실이고, 가능성 목록은 정보가 아니라 사용자에게 넘긴 숙제다.
 * 대신 규칙과 판매자가 실제로 할 수 있는 일을 말한다.
 *
 * <p>단어는 {@link WORK_STATE}에서 온다 — 제품의 업무 어휘는 화면마다 다시 만들지 않는다.
 */

/** 답변된 문의인가. 채널이 말한 상태이지, 우리가 센 것이 아니다. */
export function isAnswered(item: Pick<FeedItem, "status">): boolean {
  return item.status === "ANSWERED";
}

export interface InquiryNextAction {
  /** 제품의 업무 어휘 한 단어. */
  readonly state: WorkStateWord;
  /** 판매자가 지금 할 일 한 문장. 할 일이 없으면 그렇다고 말한다. */
  readonly sentence: string;
}

/**
 * 이 문의의 상태와 다음 행동.
 *
 * @param workItemId 답변 대기 중인 work item의 id, 없으면 null
 */
export function inquiryNextAction(
  item: Pick<FeedItem, "status">,
  workItemId: string | null,
): InquiryNextAction {
  if (isAnswered(item)) {
    return {
      state: WORK_STATE.ANSWERED,
      // 「완료」가 아니라 「답변함」이다. 보낸 것을 확인한 것과 고객이 만족한 것은 다른 사실이고,
      // 이 화면이 아는 것은 앞쪽뿐이다.
      sentence: "답변이 등록된 문의입니다. 따로 하실 일은 없습니다.",
    };
  }
  if (workItemId !== null) {
    return {
      state: WORK_STATE.REPLY_NEEDED,
      sentence: "고객이 답변을 기다리고 있습니다. 아래에서 답변을 준비하세요.",
    };
  }
  return {
    state: WORK_STATE.REPLY_NEEDED,
    // 고객이 어디 서 있는지만 말한다. <b>어디서 답하면 되는지는 여기서 말하지 않는다</b> —
    // {@link draftUnavailableReason}이 바로 아래에서 그 말을 하고, 둘이 같이 나오면 한 화면에
    // 「판매자센터에서 직접 작성」이 두 번 적힌다. 이 패키지가 없애려던 바로 그 모양이다.
    sentence: "고객이 아직 답변을 받지 못했습니다.",
  };
}

/**
 * <b>왜 초안이 없는지</b> — 「제안할 수 없습니다」를 대체하는 문장.
 *
 * <p>초안을 쓸 수 있을 때는 {@code null}. 쓸 수 없을 때는 <b>규칙</b>을 말한다: 이유를 모르는 것이 아니라,
 * 이유가 규칙이기 때문에 말할 수 있다.
 *
 * @param channelNameKo 판매자가 아는 채널 이름. 없으면 문장에서 빠진다 — 모르는 채널 이름을 지어내지 않는다.
 */
export function draftUnavailableReason(
  item: Pick<FeedItem, "status">,
  workItemId: string | null,
  channelNameKo?: string | null,
): string | null {
  if (workItemId !== null) {
    return null;
  }
  if (isAnswered(item)) {
    return `이미 답변이 등록되어 있어 새 초안을 만들지 않습니다. 답변 내용은 ${sellerCenter(channelNameKo)}에서 확인하실 수 있습니다.`;
  }
  return draftRuleNotice(channelNameKo);
}

/**
 * 답변 상태를 <b>모르는</b> 화면이 쓰는 같은 문장.
 *
 * <p>확인할 일의 pane이 그렇다 — 그 행은 {@code status}를 들고 다니지 않는다. 그래서 「이미 답변됨」
 * 분기는 쓸 수 없고, 쓸 수 있는 것은 규칙 그 자체다. 문장이 한 곳에 있는 이유는 두 화면이 같은 규칙을
 * 다른 말로 설명하기 시작하면 판매자가 두 제품을 쓰는 셈이 되기 때문이다.
 */
export function draftRuleNotice(channelNameKo?: string | null): string {
  return `reviewnary는 답변을 기다리는 문의에만 초안을 씁니다. 이 문의는 그 목록에 없어서 초안을 만들지 않았습니다. 답변은 ${sellerCenter(channelNameKo)}에서 직접 작성해 주세요.`;
}

/** 모르는 채널 이름을 지어내지 않는다. */
function sellerCenter(channelNameKo?: string | null): string {
  return channelNameKo ? `${channelNameKo} 판매자센터` : "해당 채널의 판매자센터";
}

/**
 * <b>제목과 본문을 각각 무엇으로 그릴지</b> — 한 번 정해서 목록과 상세가 같은 답을 쓰게 한다.
 *
 * <p>이게 없어서 같은 문장이 한 화면에 세 번 나왔다. 상세 pane은 {@code snippet}의 앞부분을 제목으로
 * 그리고, 그 아래 {@code InboxDetail}이 같은 문장을 다시 제목으로 그리고, 그 아래 「문의 발췌」가 같은
 * 본문을 또 그렸다 — 실제 live NAVER 문의(답변 대기 목록에 없는 문의)에서 그대로 관측된다.
 *
 * <p>규칙 하나: <b>제목이 있으면 제목이 제목이고 본문은 본문이다. 제목이 없으면 본문의 앞부분이 제목
 * 자리를 대신하고, 그때 본문은 다시 그리지 않는다.</b> 두 번째 절이 중복을 끝낸다.
 */
export interface InquiryHeadline {
  /** 가장 큰 글자에 들어갈 것. 절대 비지 않는다. */
  readonly title: string;
  /** 제목 아래 조용히 놓일 본문. 제목이 본문에서 왔다면 null — 같은 문장을 두 번 그리지 않는다. */
  readonly body: string | null;
}

export function inquiryHeadline(
  fields: { title?: string | null; snippet?: string | null },
  preview: (text: string | null | undefined) => string,
): InquiryHeadline {
  const title = (fields.title ?? "").trim();
  const snippet = (fields.snippet ?? "").trim();
  if (title) {
    // 제목과 본문이 실제로 같은 문장인 채널도 있다(제목 칸에 본문을 그대로 넣는 게시판). 그때도 두 번
    // 그리지 않는다 — 두 칸이 채워져 있다는 것과 두 가지 사실이라는 것은 다르다.
    const body = snippet && preview(snippet) !== preview(title) ? snippet : null;
    return { title: preview(title), body };
  }
  if (snippet) {
    return { title: preview(snippet), body: null };
  }
  return { title: "문의", body: null };
}

/**
 * <b>고객이 쓴 말이 먼저 오고, 제목은 뭔가를 더해 줄 때만 옆에 선다</b> (문의 canonical redesign,
 * 2026-10-03, product-owner decision).
 *
 * <p><b>왜 {@link inquiryHeadline}과 다른가.</b> 그 규칙은 「제목이 있으면 제목이 제목이다」였고, 제목이
 * 목록 어디에도 안 나오던 결함을 고치려고 그렇게 정했다. 실제 데이터에서 그 규칙의 대가가 드러난다 —
 * 데모 org의 최신 문의 11건 중 5건은 제목이 비었거나 「문의 드립니다」다. 게시판이 자동으로 채운 칸이
 * 가장 큰 글씨를 가져가고, 고객이 실제로 물어본 문장은 그 아래 회색으로 내려앉았다.
 *
 * <p>그래서 두 칸의 <b>역할</b>을 바꾼다. 첫 시선은 언제나 고객이 쓴 본문이고, 제목은 <b>본문이 말하지 않는
 * 것을 말할 때만</b> 남는다. 제목이 화면에서 사라지지는 않는다 — 자리가 바뀔 뿐이다.
 *
 * <p><b>제목을 숨기는 조건은 네 가지뿐이고, 전부 확인 가능한 사실이다:</b>
 * <ol>
 *   <li>비어 있다</li>
 *   <li>본문이 없다 — 그러면 제목이 곧 질문이고, 같은 문장을 두 번 그리지 않는다</li>
 *   <li>본문이 제목으로 시작한다 — 본문이 이미 그 말을 하고 있다</li>
 *   <li>{@link GENERIC_TITLES}에 있다</li>
 * </ol>
 *
 * <p>네 번째만이 판단이고, 그래서 <b>닫힌 목록</b>이다. 목록에 없는 제목은 언제나 남는다 — 판매자의 자료를
 * 숨기는 쪽이 아니라 보여 주는 쪽으로 틀린다. 「배송 후 분실」이나 「선바로 길이 문의」처럼 본문에 없는
 * 사실을 담은 제목은 그대로 남는다.
 */
export interface InquiryReading {
  /** 첫 시선. 고객이 쓴 본문, 없으면 제목. 절대 비지 않는다. */
  readonly question: string;
  /** 질문 옆에 조용히 붙는 제목. 더해 주는 것이 없으면 null. */
  readonly titleContext: string | null;
}

/**
 * 게시판이 채웠거나 사람이 습관으로 적는, 문의 자체에 대해 아무것도 말하지 않는 제목들.
 *
 * <p>관측에서 나왔다(데모 org: 「문의 드립니다」 × 2, 빈 제목 × 3 / 상위 11건). 공백과 문장부호를 지운
 * 형태로 비교하므로 「문의드립니다.」와 「문의 드립니다」는 같은 항목이다. <b>여기 없는 제목은 생략하지
 * 않는다</b> — 목록을 넓히는 것은 제품 결정이지 이 함수의 추측이 아니다.
 */
const GENERIC_TITLES: ReadonlySet<string> = new Set([
  "문의",
  "문의드립니다",
  "문의드려요",
  "문의드림",
  "문의합니다",
  "문의요",
  "질문",
  "질문드립니다",
  "질문있습니다",
  "안녕하세요",
]);

/** 공백과 문장부호를 지운 비교형. 「문의 드립니다.」 → 「문의드립니다」 */
function bare(text: string): string {
  return text.replace(/[\s~!@#$%^&*()_+\-=[\]{};':"\\|,.<>/?·ㆍ…]/g, "");
}

export function inquiryReading(
  fields: { title?: string | null; snippet?: string | null },
  preview: (text: string | null | undefined) => string,
): InquiryReading {
  const title = preview(fields.title ?? null).trim();
  const body = preview(fields.snippet ?? null).trim();
  if (!body) {
    // 본문이 없다. 제목이 곧 질문이고, 그마저 없으면 이 화면이 아는 말이 없다.
    return { question: title || "문의", titleContext: null };
  }
  if (!title) return { question: body, titleContext: null };
  const bareTitle = bare(title);
  const bareBody = bare(body);
  const adds =
    bareTitle.length > 0
    && !bareBody.startsWith(bareTitle)
    && !GENERIC_TITLES.has(bareTitle);
  return { question: body, titleContext: adds ? title : null };
}
