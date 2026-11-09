package com.sellerops.inquiry.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * The two instructions of Inquiry Decision v2, written once so tests can read them.
 *
 * <p><b>No product vocabulary, no synonym list, no example from a seller's catalogue.</b> Both judgements are about the
 * relation between a customer's message and texts the seller wrote — what the customer needs, and whether a text
 * answers it — and neither is a question about any domain. The only closed words are the eight need types and the
 * four statuses the code aggregates.
 */
public final class InquiryDecisionPrompt {

    public static final String VERSION = "inquiry-decision/v1";
    /** The coverage judge's instruction, selectable so the calibration can compare v1 and v2 on identical inputs. */
    public static final String JUDGE_V1 = "coverage-judge/v1";
    public static final String JUDGE_V2 = "coverage-judge/v2";
    /**
     * <b>v2's instruction, answered keyed by need id</b> (owner, 2026-10-29: 「v3 구조로 만들고 v2와 비교해 줘」). v2 asks for
     * a LIST of exactly one verdict per need and states the count as {@code minItems = maxItems}; Claude's structured
     * outputs do not take array counts, so on Claude nothing enforced it and a missing or doubled verdict failed the call
     * ({@code VERDICT_SET}). v3 makes each need id a REQUIRED property of one object with no other property allowed — a
     * shape both vendors enforce — so one verdict per need, none missing, none invented, is the schema itself. The rules
     * are v2's word for word: only the answer's shape differs, so a comparison measures the shape.
     */
    public static final String JUDGE_V3 = "coverage-judge/v3";
    /**
     * <b>v2's rules, held to what the customer asked</b> (2026-11-02, after case-eval-r11u). v2 reads 「the answer is
     * fixed」 against everything a reply about the need might go on to say: 「20박스 사면 할인되나요」 against a rule that
     * grants it from 20 boxes came back PARTIAL in two rounds of three, missing 「할인율 · 적용 방법」 — details the customer
     * never asked for. And its seller-decision rule kept a decision the seller had already written down, with its
     * condition, from closing a need whose case the customer's own message settles. v4 adds the first rule and narrows
     * the second; every other line is v2's, and so is the answer's shape, so a comparison measures the rules.
     */
    public static final String JUDGE_V4 = "coverage-judge/v4";
    /**
     * <b>v4, and a value the evidence says is not settled yet</b> (2026-11-05, after the eleventh review's re-eval). A seller
     * record that says 「새 상품 도착일은 아직 정할 수 없음」 answers that the day is open — not the day. Read as FULL it closes
     * the customer's question with an explanation; read as NONE the drafter may not say what the seller wrote. v5 adds one
     * rule: when the need asks for the value itself, PARTIAL, the 「not yet」 cited, the value still missing; when it asks
     * whether the value is settled, 「not yet」 is its answer (twelfth review). A value merely absent from the evidence is
     * never read as 「not yet」. Every other line, and the answer's shape, is v4's.
     */
    public static final String JUDGE_V5 = "coverage-judge/v5";
    /**
     * <b>v5, and a need the seller's completed action made moot</b> (2026-11-06 thirteenth review). The seller recorded
     * 「교환 상품 발송함」 and the need 「can this be exchanged」 still came back PARTIAL for want of the rule that would have
     * decided it — so the seller was asked to decide again what they had already done. v6 adds one rule and one field:
     * {@code settled_by}, the seller's record of the action already carried out, for a need that asks whether that action
     * can be done. The status is still judged from the evidence as before; code decides whether the record settles the
     * need ({@link NeedAggregation#settlingRecord}). Every other line is v5's.
     */
    public static final String JUDGE_V6 = "coverage-judge/v6";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InquiryDecisionPrompt() {
    }

    static String planSystem() {
        return """
                당신은 고객이 판매자에게 보낸 문의 하나를 읽고, 답변에 포함되지 않으면 고객의 요청이 해결되지 않는 독립적인 정보·행동 단위(need)로 나눕니다.
                규칙:
                - 고객이 실제로 묻거나 요청한 것만 need입니다. 그 need에 답하는 데 필요한 배경·전제 사실은 need로 만들지 않습니다.
                - 같은 것을 다른 말로 반복한 것은 하나의 need입니다. 인사·감사·감정 표현은 need가 아닙니다.
                - 질문 없이 불만이나 상황만 적혀 있으면, 판매자가 해결해 줘야 하는 것을 need로 씁니다.
                - ask는 판매자가 읽을 짧은 한국어 문장(120자 이하)입니다. 이름·주소·전화번호·주문번호·송장번호 같은 개인정보를 넣지 않습니다.
                - search는 판매자 자료에서 이 need의 답을 찾을 짧은 검색 문구입니다.
                - type은 다음 중 하나입니다: PRODUCT_SPEC(치수·재질·구성 등 상품 사실), PRODUCT_USAGE(설치·사용·분리·관리 방법), PRODUCT_COMPATIBILITY(다른 물건·용도와 맞는지, 어떤 규격을 골라야 하는지), CATALOGUE_AVAILABILITY(판매 여부·옵션·재고·재입고), POLICY(배송·교환·반품·결제·증빙 등 운영 기준), ORDER_STATE(이 고객 주문의 상태), ORDER_ACTION(이 고객 주문에 대한 조치 요청), SELLER_DECISION(예외·보상·할인·추천처럼 판매자가 정해야 하는 것).
                출력은 {"needs":[{"id":"N1","ask":"...","type":"...","search":"..."}]} 형식의 JSON만.""";
    }

    static String planUser(String question) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("customer", question);
        return root.toString();
    }

    static String judgeSystem(String version) {
        if (JUDGE_V1.equals(version)) {
            return judgeSystem();
        }
        if (JUDGE_V4.equals(version)) {
            return judgeSystemV4();
        }
        if (JUDGE_V5.equals(version)) {
            return judgeSystemV5();
        }
        if (JUDGE_V6.equals(version)) {
            return judgeSystemV6();
        }
        return JUDGE_V3.equals(version) ? judgeSystemV3() : judgeSystemV2();
    }

    /** v2 with the asked-scope rule added and the seller-decision rule narrowed (see {@link #JUDGE_V4}). */
    static String judgeSystemV4() {
        String v2 = judgeSystemV2();
        String sellerRule = "- 이 고객의 개별 주문 상태나 판매자의 판단이 있어야 답이 되는 need는, 일반 기준이 있어도 FULL이 아닙니다.\n";
        String asked = "- 확신이 없으면 FULL이 아니라 PARTIAL이나 NONE을 고릅니다.\n";
        if (!v2.contains(sellerRule) || !v2.contains(asked)) {
            throw new IllegalStateException("coverage-judge/v4 is written against v2's rules, and they changed");
        }
        return v2.replace(sellerRule, "- 이 고객의 개별 주문 상태나 판매자의 판단이 있어야 답이 되는 need는, 일반 기준이 있어도 FULL이 아닙니다. "
                        + "다만 판매자가 그 판단을 조건과 함께 기준으로 적어 두었고, 문의에 밝힌 사실만으로 이 고객이 그 조건에 "
                        + "들어맞는지 정해지면, 판매자의 판단이 근거에 있는 것입니다.\n")
                .replace(asked, asked + "- received(문의 받은 때)가 주어지면 그 날짜 · 요일 · 주말 여부는 판매자 쪽이 아는 사실입니다. "
                        + "고객에게서 받을 값(customer_input)이 아니고, 그것만으로 답이 정해지면 그 사실로 판정합니다. 공휴일인지는 "
                        + "주어지지 않으니 평일이 영업일이라고 가정하지 않습니다.\n"
                        + "- need가 묻는 것까지만 판정합니다. 고객이 묻지 않은 세부(얼마인지·언제인지·어떻게 하는지처럼 "
                        + "다음에 물을 법한 것)가 근거에 없다는 이유로 FULL을 낮추지 않고, missing에도 쓰지 않습니다. "
                        + "되는지를 물었으면, 되는지와 그 조건이 답입니다.\n");
    }

    /** v4 with the 「not settled yet」 rule added (see {@link #JUDGE_V5}). */
    static String judgeSystemV5() {
        String v4 = judgeSystemV4();
        String anchor = "- 확신이 없으면 FULL이 아니라 PARTIAL이나 NONE을 고릅니다.\n";
        if (!v4.contains(anchor)) {
            throw new IllegalStateException("coverage-judge/v5 is written against v4's rules, and they changed");
        }
        return v4.replace(anchor, anchor + "- need가 값 자체(언제 · 얼마 · 어느 것)를 묻는데 근거(판매자 확인 기록 포함)가 그 값이 아직 "
                + "정해지지 않았다고 적고 있으면 그 need는 PARTIAL입니다. 정해지지 않았다는 사실은 근거가 답하는 것이니 evidence에 그 "
                + "근거를 넣고, 아직 남은 그 값은 missing에 씁니다. need가 정해졌는지 여부를 묻는다면 「아직 정해지지 않았다」가 그 답이므로 "
                + "이 규칙으로 낮추지 않습니다. 근거에 값이 없다는 것만으로는 정해지지 않았다고 보지 않습니다.\n");
    }

    /** v5 with the settled-by-action rule and its field added (see {@link #JUDGE_V6}). */
    static String judgeSystemV6() {
        String v5 = judgeSystemV5();
        String anchor = "- 확신이 없으면 FULL이 아니라 PARTIAL이나 NONE을 고릅니다.\n";
        String field = "- precedents: need가 FULL이나 CONDITIONAL_ON_CUSTOMER가 아닐 때, 예전 답변 중 이 need에 대한 일반적인 답으로 다시 쓸 수 있는 P id. 특정 주문·특정 시점·그 대화에만 해당하는 답은 넣지 않습니다.\n";
        String output = "\"ask_customer\":\"...\",\"precedents\":[\"P1\"]}]}";
        if (!v5.contains(anchor) || !v5.contains(field) || !v5.contains(output)) {
            throw new IllegalStateException("coverage-judge/v6 is written against v5's rules, and they changed");
        }
        return v5.replace(anchor, anchor + "- 판매자 확인 기록이 고객이 요청한 조치를 이 주문에 이미 했다고(처리 완료) 적고 있고, need가 그 조치를 "
                        + "할 수 있는지를 묻는 것이라면, 그 조치를 할지는 판매자가 이미 정한 것입니다. 그 need의 settled_by에 그 기록의 "
                        + "E id를 씁니다. status · evidence · missing은 이 규칙과 상관없이 위 규칙대로 판정합니다. 그 조치와 다른 것 — 원인 · "
                        + "안전 · 시점 · 금액처럼 고객이 따로 물은 것 — 을 묻는 need이거나, 기록이 접수 · 진행 중 · 처리 불가라고 적고 있으면 "
                        + "settled_by는 null입니다.\n")
                .replace(field, field + "- settled_by: 위 규칙에 해당할 때 그 판매자 확인 기록의 E id. 해당하지 않으면 null.\n")
                .replace(output, "\"ask_customer\":\"...\",\"precedents\":[\"P1\"],\"settled_by\":null}]}");
    }

    /** v2's rules; the answer keyed by need id, one object per need, every need present (see {@link #JUDGE_V3}). */
    static String judgeSystemV3() {
        String v2 = judgeSystemV2();
        String v2Output = v2.substring(v2.indexOf("출력은 "));
        return v2.replace(v2Output, "출력은 need id를 키로 하는 객체 하나입니다. 받은 need마다 정확히 한 번, 받은 id만 씁니다. "
                + "{\"verdicts\":{\"N1\":{\"status\":\"...\",\"evidence\":[\"E1\"],\"missing\":[],"
                + "\"customer_input\":[],\"assumptions\":[],\"ask_customer\":\"...\",\"precedents\":[\"P1\"]}}} "
                + "형식의 JSON만.");
    }

    static String judgeSystem() {
        return """
                당신은 고객 문의, 그 문의의 need 목록, 판매자가 가진 현재 근거 후보(E…), 판매자가 예전에 쓴 답변(P…)을 받습니다. need마다, 현재 근거만으로 그 need를 이 고객에게 실제로 답할 수 있는지 판정합니다.
                status:
                - FULL: 근거만으로 이 need에 대해 고객에게 필요한 답을 끝까지 말할 수 있습니다.
                - CONDITIONAL_ON_CUSTOMER: 근거에 답이 있지만 고객이 아직 밝히지 않은 정보(규격·옵션·사용 환경 등)에 따라 달라지므로, 그 정보를 고객에게 물어야 답이 정해집니다.
                - PARTIAL: 근거가 이 need의 일부만 답합니다.
                - NONE: 근거가 이 need를 답하지 못합니다.
                규칙:
                - 주제가 관련 있다는 것만으로는 FULL이 아닙니다. 이 고객에게 이 need의 답을 말할 수 있어야 FULL입니다.
                - 근거에 없는 내용을 추측하거나 보충하지 않습니다.
                - 일반 기준이 있어도, 이 고객의 개별 주문 상태나 판매자의 판단이 있어야 답이 되는 need는 FULL이 아닙니다.
                - 예전 답변(P…)은 근거가 아닙니다. evidence에 P를 넣지 않습니다.
                - evidence: 판정을 지지하는 E id 목록. FULL·CONDITIONAL_ON_CUSTOMER·PARTIAL이면 하나 이상이어야 합니다.
                - missing: PARTIAL이나 NONE일 때, 무엇이 부족한지 판매자가 읽을 한 문장.
                - ask_customer: CONDITIONAL_ON_CUSTOMER일 때 고객에게 물을 한 문장.
                - precedents: need가 FULL이나 CONDITIONAL_ON_CUSTOMER가 아닐 때, 예전 답변 중 이 need에 다시 써도 되는 일반적인 답의 P id. 특정 주문·특정 시점·그 대화에만 해당하는 답(그 주문을 발송했다, 그 주소로 바꿨다, 그때 재고가 없었다 등)은 넣지 않습니다.
                출력은 {"verdicts":[{"need":"N1","status":"...","evidence":["E1"],"missing":"...","ask_customer":"...","precedents":["P1"]}]} 형식의 JSON만.""";
    }

    /**
     * <b>CoverageJudge v2</b> (Inquiry Decision v2.1). The first real-model run found the v1 judge promoting evidence
     * that was RELATED to FULL — inferring past the text, and answering values that depend on a variant nobody named.
     * v2 separates 「관련 있다」 from 「추가 가정 없이 답이 정해진다」 and makes the judge SAY what it leaned on, as lists
     * code can check ({@link NeedAggregation#enforce}). Still no product vocabulary: what can vary is named by kind
     * (규격·옵션·수량·사용 환경·주문 상태), never by an example from a catalogue.
     */
    static String judgeSystemV2() {
        return """
                당신은 판매자가 가진 근거가 고객 문의의 need 하나하나를 "추가 가정 없이" 답하기에 충분한지 판정하는 심사자입니다. 근거가 need와 관련 있는지가 아니라, 근거만으로 이 고객에게 줄 답이 하나로 정해지는지를 판정합니다.
                입력: 고객 문의, need 목록(N…), 판매자의 현재 근거 후보(E…), 판매자가 예전에 쓴 답변(P…).
                status (need마다 하나):
                - FULL: E만으로 이 need의 답을 추가 가정 없이 확정적으로 말할 수 있습니다. 근거가 답을 직접 말하거나, 답을 정하는 모든 전제가 근거에 명시되어 있어 논리적으로 하나로 정해질 때만 FULL입니다.
                - CONDITIONAL_ON_CUSTOMER: 근거에 답이 들어 있지만, 고객만 아는 값(규격·옵션·수량·사용 환경 등)이 정해져야 답이 하나로 정해집니다. 그 값만 받으면 E로 답할 수 있어야 합니다.
                - PARTIAL: 관련 근거는 있지만, 답을 정하는 데 필요한 정보가 하나 이상 근거에 없습니다.
                - NONE: 이 need를 실질적으로 뒷받침하는 근거가 없습니다.
                지켜야 할 것:
                - 상식, 업계의 일반 지식, 비슷한 상품에 대한 짐작으로 근거의 빈칸을 채우지 않습니다.
                - 목록의 일부 항목에서 전체를, 한 경우에서 다른 경우를 추론하지 않습니다.
                - 답이 규격·옵션·수량·주문 상태에 따라 달라지는데 그 값이 문의에도 근거에도 확정되어 있지 않으면 FULL이 아닙니다.
                - 근거가 다른 상품이나 다른 판매 페이지에 대한 것이면 이 상품의 근거로 쓰지 않습니다.
                - 이 고객의 개별 주문 상태나 판매자의 판단이 있어야 답이 되는 need는, 일반 기준이 있어도 FULL이 아닙니다.
                - 예전 답변(P…)은 근거가 아닙니다. evidence에 P를 넣지 않고, P만 보고 FULL이나 CONDITIONAL_ON_CUSTOMER를 주지 않습니다.
                - 확신이 없으면 FULL이 아니라 PARTIAL이나 NONE을 고릅니다.
                need마다 쓸 것:
                - evidence: 판정을 직접 뒷받침하는 E id 목록. NONE이 아니면 하나 이상.
                - missing: 답을 정하는 데 필요하지만 근거에 없는 정보. 짧은 구절의 목록. PARTIAL·NONE이면 하나 이상, FULL이면 비어 있습니다.
                - customer_input: 답을 정하려면 고객에게서 받아야 하는 값. 짧은 구절의 목록. CONDITIONAL_ON_CUSTOMER이면 하나 이상.
                - assumptions: 이 판정이 근거에 쓰여 있지 않은 내용에 기대고 있다면 그 내용. 짧은 구절의 목록. FULL이면 비어 있어야 합니다.
                - ask_customer: CONDITIONAL_ON_CUSTOMER일 때 고객에게 물을 한 문장.
                - precedents: need가 FULL이나 CONDITIONAL_ON_CUSTOMER가 아닐 때, 예전 답변 중 이 need에 대한 일반적인 답으로 다시 쓸 수 있는 P id. 특정 주문·특정 시점·그 대화에만 해당하는 답은 넣지 않습니다.
                출력은 {"verdicts":[{"need":"N1","status":"...","evidence":["E1"],"missing":[],"customer_input":[],"assumptions":[],"ask_customer":"...","precedents":["P1"]}]} 형식의 JSON만.""";
    }

    /**
     * <b>The answer's shape, enforced by the vendor</b> (Structured Outputs, {@code strict: true}) — Inquiry Decision
     * v2.1 audit. JSON mode only promised valid JSON; this promises THIS JSON: a closed object, every field required,
     * statuses from the closed list, and <b>need ids from an enum of exactly the ids this request sent</b>, one verdict
     * per need ({@code minItems = maxItems = needs}). Evidence and precedent ids are enums of the ids sent too, so the
     * model cannot write an id that does not exist. Code still checks the set ({@code InquiryDecisionGenerator
     * .parseJudge(String, List)}) — the schema is the first fence, not the only one.
     */
    static ObjectNode judgeSchema(String version, List<String> needIds, List<String> evidenceIds,
                                  List<String> precedentIds) {
        boolean v2 = !JUDGE_V1.equals(version);
        boolean keyed = JUDGE_V3.equals(version);
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "object").put("additionalProperties", false);
        ObjectNode props = item.putObject("properties");
        ArrayNode required = item.putArray("required");
        if (!keyed) {
            ObjectNode need = props.putObject("need").put("type", "string");
            needIds.forEach(need.putArray("enum")::add);
        }
        ObjectNode status = props.putObject("status").put("type", "string");
        for (String st : List.of("FULL", "CONDITIONAL_ON_CUSTOMER", "PARTIAL", "NONE")) {
            status.withArray("enum").add(st);
        }
        props.set("evidence", idArray(evidenceIds));
        if (v2) {
            for (String list : List.of("missing", "customer_input", "assumptions")) {
                props.putObject(list).put("type", "array").putObject("items").put("type", "string");
            }
        } else {
            props.putObject("missing").putArray("type").add("string").add("null");
        }
        props.putObject("ask_customer").putArray("type").add("string").add("null");
        props.set("precedents", idArray(precedentIds));
        if (JUDGE_V6.equals(version)) {
            props.putObject("settled_by").putArray("type").add("string").add("null");
        }
        props.fieldNames().forEachRemaining(required::add);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object").put("additionalProperties", false);
        root.putArray("required").add("verdicts");
        ObjectNode verdicts = root.putObject("properties").putObject("verdicts");
        if (keyed) {
            // One required property per need id and nothing else: the count is the shape, which both vendors enforce.
            verdicts.put("type", "object").put("additionalProperties", false);
            ObjectNode byNeed = verdicts.putObject("properties");
            ArrayNode requiredNeeds = verdicts.putArray("required");
            for (String id : needIds) {
                byNeed.set(id, item.deepCopy());
                requiredNeeds.add(id);
            }
            return format("coverage_judge_v3", root);
        }
        verdicts.put("type", "array").put("minItems", needIds.size()).put("maxItems", needIds.size());
        verdicts.set("items", item);
        return format(JUDGE_V6.equals(version) ? "coverage_judge_v6" : JUDGE_V4.equals(version) ? "coverage_judge_v4"
                        : v2 ? "coverage_judge_v2" : "coverage_judge_v1",
                root);
    }

    /** The planner's shape: ids are renumbered by code, the type is one of the eight. */
    static ObjectNode planSchema() {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "object").put("additionalProperties", false);
        ObjectNode props = item.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("ask").put("type", "string");
        ObjectNode type = props.putObject("type").put("type", "string");
        for (NeedType t : NeedType.values()) {
            type.withArray("enum").add(t.name());
        }
        props.putObject("search").put("type", "string");
        ArrayNode required = item.putArray("required");
        props.fieldNames().forEachRemaining(required::add);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object").put("additionalProperties", false);
        root.putArray("required").add("needs");
        ObjectNode needs = root.putObject("properties").putObject("needs");
        needs.put("type", "array");
        needs.set("items", item);
        return format("inquiry_need_plan", root);
    }

    private static ObjectNode idArray(List<String> ids) {
        ObjectNode a = MAPPER.createObjectNode();
        a.put("type", "array");
        ObjectNode items = a.putObject("items").put("type", "string");
        if (ids.isEmpty()) {
            a.put("maxItems", 0);
        } else {
            ids.forEach(items.putArray("enum")::add);
        }
        return a;
    }

    private static ObjectNode format(String name, ObjectNode schema) {
        ObjectNode f = MAPPER.createObjectNode();
        f.put("type", "json_schema");
        ObjectNode js = f.putObject("json_schema");
        js.put("name", name).put("strict", true);
        js.set("schema", schema);
        return f;
    }

    /** Needs, evidence and past answers by POSITION — no database id, no organisation, no customer identifier. */
    static String judgeUser(String question, List<InquiryNeed> needs, List<EvidenceCandidate> evidence,
                            List<PrecedentCandidate> precedents) {
        return judgeUser(question, needs, evidence, precedents, null);
    }

    /**
     * The same, with when the inquiry arrived — a date, a day of the week and whether it is a weekend, written by code
     * ({@link InquiryMoment}); omitted when null so a request without it is byte-identical to before.
     */
    static String judgeUser(String question, List<InquiryNeed> needs, List<EvidenceCandidate> evidence,
                            List<PrecedentCandidate> precedents, String received) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("customer", question);
        if (received != null && !received.isBlank()) {
            root.put("received", received);
        }
        ArrayNode n = root.putArray("needs");
        for (InquiryNeed need : needs) {
            n.addObject().put("id", need.id()).put("ask", need.ask()).put("type", need.type().name());
        }
        ArrayNode e = root.putArray("evidence");
        for (EvidenceCandidate c : evidence) {
            e.addObject().put("id", c.id()).put("source", c.kind().scopeLabelKo()).put("title", c.label())
                    .put("text", c.text());
        }
        ArrayNode p = root.putArray("precedents");
        for (PrecedentCandidate c : precedents) {
            p.addObject().put("id", c.id()).put("text", c.text());
        }
        return root.toString();
    }
}
