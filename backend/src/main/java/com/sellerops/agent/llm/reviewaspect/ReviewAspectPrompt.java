package com.sellerops.agent.llm.reviewaspect;

import java.util.List;

/**
 * The two instructions of the review-aspect capability. The vocabularies are closed: an aspect, a polarity or a
 * problem outside them is dropped by the parser, never mapped to a nearest neighbour.
 */
public final class ReviewAspectPrompt {

    public static final String READ_VERSION = "review-aspect-read/v1";
    public static final String OUTLOOK_VERSION = "review-outlook-write/v1";

    /** The marker {@code FakeAgentLlmTransport} recognises. */
    public static final String READ_MARK = "리뷰 문장을 측면별로 읽습니다";
    public static final String OUTLOOK_MARK = "관찰된 강점·약점 근거로 기회와 위협을 씁니다";

    /** The aspects, in the order the screen lists them; the first nine are the issue memory's own. */
    public static final List<String> ASPECTS = List.of("배송", "포장", "접착", "표면", "색상", "크기", "설치", "설명", "가격",
            "품질", "사용감", "기능", "디자인", "수량", "응대");

    /** An opinion about the product as a whole, with no aspect. Kept for praise, never a weakness. */
    public static final String OVERALL = "전반";

    /** What went wrong, for a negative opinion — the issue memory's problems, so severity reads the same. */
    public static final List<String> PROBLEMS = List.of("파손", "결함", "누락", "균열", "탈락", "불일치", "지연", "부족",
            "오염", "난이도");

    private ReviewAspectPrompt() {
    }

    public static String readSystem() {
        return READ_MARK + ".\n"
                + "판매자의 상품 리뷰 여러 개가 번호와 함께 주어집니다. 리뷰마다 고객이 평가한 측면을 찾아 JSON으로만 답하세요.\n\n"
                + "측면(이 목록의 이름만 씁니다):\n"
                + "- 배송: 배송 속도, 택배, 도착\n- 포장: 포장 상태, 박스, 완충\n- 접착: 테이프·접착력\n"
                + "- 표면: 마감, 재질 표면, 코팅\n- 색상: 색\n- 크기: 사이즈, 길이, 두께, 규격\n"
                + "- 설치: 설치·조립·부착의 쉬움\n- 설명: 설명서, 안내문\n- 가격: 가격, 가성비\n"
                + "- 품질: 튼튼함, 내구성, 만듦새\n- 사용감: 쓰기 편함, 손에 쥐는 느낌, 사용하며 느낀 점\n"
                + "- 기능: 제 기능을 하는지(잘 나온다, 잘 걸린다, 새지 않는다 등)\n- 디자인: 모양, 보기 좋음\n"
                + "- 수량: 개수, 구성, 양\n- 응대: 판매자 응대, 문의 답변, 교환 처리\n"
                + "- 전반: 측면 없이 상품 전체를 말한 경우(예: \"좋아요\", \"만족합니다\", \"별로예요\")\n\n"
                + "평가: POSITIVE(좋게 말함) 또는 NEGATIVE(문제·불만을 말함). 질문, 바람(\"~면 좋겠어요\"), 다른 상품 이야기, "
                + "아직 안 써 봐서 모른다는 말은 평가가 아닙니다 — 넣지 마세요.\n"
                + "문제(NEGATIVE일 때만, 이 목록에서 하나 또는 null): " + String.join(", ", PROBLEMS) + ".\n"
                + "근거: 리뷰 원문에서 그 평가를 말한 부분을 글자 그대로 잘라 옮깁니다. 고치거나 요약하지 마세요.\n"
                + "한 리뷰에서 같은 측면·같은 평가는 한 번만 씁니다. 평가가 없는 리뷰는 opinions를 빈 배열로 둡니다.\n\n"
                + "답 형식: {\"reviews\":[{\"i\":번호,\"opinions\":[{\"aspect\":\"측면\",\"polarity\":\"POSITIVE\","
                + "\"problem\":null,\"evidence\":\"원문 일부\"}]}]}";
    }

    public static String readUser(List<String> numberedLines) {
        return "리뷰:\n" + String.join("\n", numberedLines);
    }

    public static String outlookSystem() {
        return OUTLOOK_MARK + ".\n"
                + "한 상품의 리뷰·문의에서 관찰된 강점(S)과 약점(W)이 근거 문장과 함께 주어집니다. "
                + "이 근거만으로 판매자가 해 볼 만한 기회(O)와 조심할 위협(T)을 씁니다.\n"
                + "규칙:\n"
                + "- 각 항목은 근거가 된 S·W의 key를 derivedFrom에 하나 이상 적습니다. 주어지지 않은 key는 쓰지 않습니다.\n"
                + "- 주어진 근거에 없는 사실(매출, 경쟁사, 시장 규모, 수치)을 지어내지 않습니다.\n"
                + "- 단정하지 말고 확인할 가설로 씁니다. check에는 판매자가 맞는지 확인하는 방법을 한 문장으로 씁니다.\n"
                + "- title은 30자 안, basis는 120자 안, check는 100자 안. 기회와 위협은 각각 최대 4개.\n"
                + "- 쓸 근거가 없으면 빈 배열로 둡니다.\n\n"
                + "답 형식: {\"opportunities\":[{\"title\":\"\",\"basis\":\"\",\"check\":\"\",\"derivedFrom\":[\"S:품질\"]}],"
                + "\"threats\":[{\"title\":\"\",\"basis\":\"\",\"check\":\"\",\"derivedFrom\":[\"W:배송\"]}]}";
    }

    public static String outlookUser(String evidenceBlock) {
        return "관찰된 근거:\n" + evidenceBlock;
    }
}
