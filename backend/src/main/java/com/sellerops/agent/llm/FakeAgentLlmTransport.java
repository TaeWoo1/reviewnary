package com.sellerops.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A model that answers by rule, for the end-to-end check without a model</b> (owner, 2026-10-29: 「가짜 모델 전체
 * 흐름 점검」). Development only — {@link AgentLlmConfiguration} wires it when {@code sellerops.dev.fake-llm=true} and
 * refuses to start a {@code prod} profile with it. Nothing leaves the machine: it never opens a connection.
 *
 * <p>It recognises each capability by its own system prompt and answers in that capability's exact schema, so every
 * prompt builder, parser and gate between a review and a stored draft runs for real. The answers are deterministic
 * from the customer's words, so a scenario can steer a run: a harm word makes the judge say S, a 「?」 makes it a
 * question, 「모호」 makes it UNSURE, 「[근거부족]」 makes the drafter report a point it cannot answer, 「[근거충돌]」 makes it
 * report conflicting facts, 「[정책+조치]」 plans a rule question beside an action on the order (the rule left PARTIAL;
 * under coverage-judge/v6 the seller's 처리 완료 record named as settling it) — with 「배송비」 also a separate shipping-fee
 * rule the judge wrongly names the record for and the check calls not taken up — 「[되묻기]」 makes the drafter ask the customer
 * something once, and anything else is a complaint to answer. It is not a quality model — the drafts it
 * writes are placeholders that only say they are fake.
 */
public final class FakeAgentLlmTransport implements AgentLlmTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FIGURE_LINE = Pattern.compile("(?m)^(\\d+)\\. ");
    /** A numbered item of 「이번 답변이 다룰 내용」 — 「N1. …」, or 「S1. …」 for one a seller's correction added. */
    private static final Pattern SCOPE_ITEM = Pattern.compile("(?m)^\\s*-?\\s*([NS]\\d+)\\. (.*)$");

    @Override
    public Response post(URI uri, Map<String, String> headers, String jsonBody) {
        try {
            JsonNode req = MAPPER.readTree(jsonBody);
            String system = systemOf(req);
            String user = userOf(req);
            // A scenario steers a failure into the one call it names: the inquiry planner, or the inquiry drafter.
            if ((user.contains("[판단실패]") && system.contains(PLAN_MARK))
                    || (user.contains("[생성실패]") && system.contains(DRAFT_MARK))) {
                return new Response(500, "{}", 5);
            }
            String answer = answer(system, user);
            boolean anthropic = uri.getHost() != null && uri.getHost().contains("anthropic");
            return new Response(200, envelope(answer, anthropic), 5);
        } catch (Exception e) {
            return new Response(500, "{}", 5);
        }
    }

    /** The inquiry decision's planner and judge, and the inquiry drafter — recognised by their system turns. */
    static final String PLAN_MARK = "독립적인 정보·행동 단위(need)로 나눕니다";
    static final String JUDGE_MARK = "근거 후보";
    /** A scenario: a rule question and an action on the order in one message (2026-11-06). */
    static final String POLICY_AND_ACTION = "[정책+조치]";
    /** A scenario: the drafter asks the customer something nobody judged necessary — once; a rewrite does not. */
    static final String EXTRA_ASK = "[되묻기]";
    static final String JUDGE_MARK_V1 = "심사자입니다";
    static final String DRAFT_MARK = "고객 문의 하나를 읽고";
    private static final String SELLER_CHECK_LINE = "판매자가 판매자센터에서 직접 확인한 이 문의의 결과(이 문의에만 해당, 채널이 확인한 사실 아님): ";

    /**
     * The planner: one need, its type read off the customer's words — 「[조치]」 or a refund/cancel/exchange asked for is
     * an action on this order, 「[상태]」 or a when/where about delivery is this order's state, anything else policy.
     */
    static String plan(String user) {
        String customer;
        try {
            customer = MAPPER.readTree(user).path("customer").asText("");
        } catch (Exception e) {
            customer = user;
        }
        if (customer.contains(POLICY_AND_ACTION)) {
            // Whether it can be done (a rule) and doing it (an action) — the R1 shape (2026-11-06 thirteenth review).
            ObjectNode root = MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode needs = root.putArray("needs");
            needs.addObject().put("id", "N1").put("ask", "이 상품을 교환할 수 있는지").put("type", "POLICY")
                    .put("search", "교환 기준");
            needs.addObject().put("id", "N2").put("ask", "이 주문에 요청한 조치").put("type", "ORDER_ACTION")
                    .put("search", "이 주문에 요청한 조치");
            if (customer.contains("배송비")) {
                // A rule the customer asked separately — what a completed exchange does not answer (fourteenth review).
                needs.addObject().put("id", "N3").put("ask", "교환 배송비는 누가 내는지").put("type", "POLICY")
                        .put("search", "교환 배송비");
            }
            return root.toString();
        }
        String type;
        String ask;
        if (customer.contains("[조치]") || customer.matches("(?s).*(환불|취소|교환|반품).*(해 ?주세요|해줘|원합니다|부탁).*")) {
            type = "ORDER_ACTION";
            ask = "이 주문에 요청한 조치";
        } else if (customer.contains("[상태]") || customer.matches("(?s).*(언제|어디쯤|도착|배송 ?(됐|되)).*")) {
            type = "ORDER_STATE";
            ask = "이 주문이 지금 어떤 상태인지";
        } else {
            type = "POLICY";
            ask = "운영 기준 안내";
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("needs").addObject().put("id", "N1").put("ask", ask).put("type", type).put("search", ask);
        return root.toString();
    }

    /**
     * The judge: an order need is answered only by the seller's own record; any other need by the store's policy or
     * product information. Keyed by need id when the system turn asks for that ({@code coverage-judge/v3}+).
     */
    static String judge(String system, String user) {
        JsonNode in;
        try {
            in = MAPPER.readTree(user);
        } catch (Exception e) {
            return "{}";
        }
        boolean keyed = system.contains("need id를 키로");
        // coverage-judge/v6 asks for settled_by; the fake names the seller's 처리 완료 record for the scenario's rule.
        boolean settles = system.contains("settled_by");
        boolean scenario = in.path("customer").asText("").contains(POLICY_AND_ACTION);
        String doneRecord = null;
        for (JsonNode e : in.path("evidence")) {
            if ("판매자 확인".equals(e.path("source").asText()) && e.path("text").asText("").contains("처리 완료")) {
                doneRecord = e.path("id").asText();
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode byId = keyed ? root.putObject("verdicts") : null;
        com.fasterxml.jackson.databind.node.ArrayNode list = keyed ? null : root.putArray("verdicts");
        for (JsonNode need : in.path("needs")) {
            String type = need.path("type").asText();
            boolean order = type.startsWith("ORDER_");
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (JsonNode e : in.path("evidence")) {
                String source = e.path("source").asText();
                if (order ? "판매자 확인".equals(source) : "운영 정책".equals(source) || "상품 정보".equals(source)) {
                    ids.add(e.path("id").asText());
                }
            }
            ObjectNode v = keyed ? byId.putObject(need.path("id").asText()) : list.addObject();
            if (!keyed) {
                v.put("need", need.path("id").asText());
            }
            // The scenario's rule never settles by itself: what would decide it is not in the store's material.
            boolean undecided = scenario && "POLICY".equals(type);
            v.put("status", ids.isEmpty() ? "NONE" : undecided ? "PARTIAL" : "FULL");
            com.fasterxml.jackson.databind.node.ArrayNode ev = v.putArray("evidence");
            ids.forEach(ev::add);
            com.fasterxml.jackson.databind.node.ArrayNode missing = v.putArray("missing");
            if (ids.isEmpty() || undecided) {
                missing.add(undecided ? (need.path("ask").asText("").contains("배송비") ? "배송비 부담 기준" : "판단 기준")
                        : order ? "판매자센터에서 확인할 이 주문의 결과" : "운영 기준");
            }
            v.putArray("customer_input");
            v.putArray("assumptions");
            v.putNull("ask_customer");
            v.putArray("precedents");
            if (settles) {
                // Deliberately for EVERY rule of the scenario — the shipping fee too, which is wrong: the screen check
                // shows what keeps that one open when the judge errs.
                if (undecided && doneRecord != null) {
                    v.put("settled_by", doneRecord);
                } else {
                    v.putNull("settled_by");
                }
            }
        }
        return root.toString();
    }

    static String answer(String system, String user) {
        if (system.contains(PLAN_MARK)) {
            return plan(user);
        }
        if (system.contains(JUDGE_MARK) || system.contains(JUDGE_MARK_V1)) {
            return judge(system, user);
        }
        String review = between(user, "고객 리뷰:\n", "\n\n");
        if (system.contains("고객 응대 분류 담당")) {
            if (review.matches("(?s).*(베였|다쳤|화상|데었|피났).*")) {
                return "{\"category\":\"S\",\"also\":[],\"points\":[\"부상\"],\"actions\":[\"PERSON_REVIEW\"],\"unsure\":null}";
            }
            if (review.contains("모호")) {
                return "{\"category\":\"UNSURE\",\"also\":[],\"points\":[],\"actions\":[],\"unsure\":\"AMBIGUOUS_TEXT\"}";
            }
            if (review.contains("?")) {
                return "{\"category\":\"Q\",\"also\":[],\"points\":[\"질문\"],\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}";
            }
            if (review.matches("(?s).*(누락|빠져|불량|떨어|깨져).*")) {
                return "{\"category\":\"R\",\"also\":[],\"points\":[\"상품 문제\"],"
                        + "\"actions\":[\"CHECK_ORDER\",\"PUBLIC_REPLY\"],\"unsure\":null}";
            }
            return "{\"category\":\"P\",\"also\":[],\"points\":[\"칭찬\"],\"actions\":[\"NO_RESPONSE\"],\"unsure\":null}";
        }
        if (system.contains("공개 답글 초안을 씁니다")) {
            if (user.contains("[근거부족]")) {
                return "{\"comments\":\"\",\"unanswered\":[\"질문\"],\"conflict\":false}";
            }
            if (user.contains("[근거충돌]")) {
                return "{\"comments\":\"\",\"unanswered\":[],\"conflict\":true}";
            }
            return "{\"comments\":\"[가짜 모델] 부품이 빠져 있어 불편을 드려 죄송합니다.\","
                    + "\"unanswered\":[],\"conflict\":false}";
        }
        if (system.contains("답글 초안을 검토합니다")) {
            return "{\"failed\":[]}";
        }
        if (system.contains("답변 초안을 근거와 대조합니다")) {
            StringBuilder figures = new StringBuilder();
            Matcher m = FIGURE_LINE.matcher(between(user, "확인할 숫자:\n", "\u0000"));
            while (m.find()) {
                figures.append(figures.isEmpty() ? "" : ",").append("{\"n\":").append(m.group(1))
                        .append(",\"verdict\":\"SUPPORTED\"}");
            }
            // Every numbered item the draft was told to cover is covered: the fake is not a quality model (v11 schema,
            // 2026-11-02 — 「answered」 and 「covered」 are required beside the figures and claims).
            StringBuilder covered = new StringBuilder();
            String draft = between(user, "\n답변 초안:\n", "\n\n확인할 숫자:");
            Matcher item = SCOPE_ITEM.matcher(user);
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            while (item.find()) {
                if (!item.group(2).contains("다루지 않습니다") && seen.add(item.group(1))) {
                    // An item about the shipping fee the draft never mentions is not taken up (the scenario's one rule).
                    boolean silent = item.group(2).contains("배송비") && !draft.contains("배송비");
                    covered.append(covered.isEmpty() ? "" : ",").append("\"").append(item.group(1))
                            .append(silent ? "\":\"NOT_COVERED\"" : "\":\"ANSWERED\"");
                }
            }
            return "{\"figures\":[" + figures + "],\"claims\":[],\"answered\":\"ANSWERED\",\"covered\":{"
                    + covered + "}}";
        }
        if (system.contains(DRAFT_MARK)) {
            // With the seller's record in front of it, the placeholder says the record back — and nothing else.
            String record = between(user, SELLER_CHECK_LINE, "\n").strip();
            String comments = record.isEmpty() ? "[가짜 모델] 문의 주셔서 감사합니다."
                    : "[가짜 모델] 확인한 결과를 안내드립니다. " + record;
            if (user.contains(EXTRA_ASK) && !user.contains("- 다시 쓰기:")) {
                comments += " 원하시는 색상을 알려주시겠어요?";
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.put("category", AgentDraftPrompt.CATEGORIES[AgentDraftPrompt.CATEGORIES.length - 1]);
            root.put("title", "[가짜 모델] 답변");
            root.put("comments", comments);
            return root.toString();
        }
        // The legacy review drafter and anything else: a one-field reply.
        return "{\"comments\":\"[가짜 모델] 소중한 후기 감사합니다.\"}";
    }

    private static String systemOf(JsonNode req) {
        if (req.path("system").isTextual()) {
            return req.path("system").asText();
        }
        for (JsonNode m : req.path("messages")) {
            if ("system".equals(m.path("role").asText())) {
                return m.path("content").asText();
            }
        }
        return "";
    }

    private static String userOf(JsonNode req) {
        for (JsonNode m : req.path("messages")) {
            if ("user".equals(m.path("role").asText())) {
                return m.path("content").asText();
            }
        }
        return "";
    }

    private static String between(String text, String start, String end) {
        int a = text.indexOf(start);
        if (a < 0) {
            return "";
        }
        int from = a + start.length();
        int b = text.indexOf(end, from);
        return b < 0 ? text.substring(from) : text.substring(from, b);
    }

    private static String envelope(String content, boolean anthropic) {
        ObjectNode root = MAPPER.createObjectNode();
        if (anthropic) {
            root.putArray("content").addObject().put("type", "text").put("text", content);
            root.put("stop_reason", "end_turn");
            root.putObject("usage").put("input_tokens", 0).put("output_tokens", 0);
        } else {
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", content);
            root.putObject("usage").put("prompt_tokens", 0).put("completion_tokens", 0);
        }
        return root.toString();
    }
}
