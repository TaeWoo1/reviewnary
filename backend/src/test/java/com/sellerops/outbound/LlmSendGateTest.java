package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.outbound.LlmSendGate.Finding;
import com.sellerops.outbound.LlmSendGate.Images;
import com.sellerops.outbound.LlmSendGate.Prepared;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a seller's PC sends to a model is cleaned or held on the PC</b> — proposal §17.9 AI 경계 1–3.
 *
 * <p>Every positive case is paired with a near miss that must pass untouched (a date beside an account number, a
 * representative number beside a mobile, a product number beside an order number, "택배로 3개" beside a road
 * address): a gate that removed everything would pass the positive half alone.
 */
class LlmSendGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void disarm() {
        OutboundGate.arm(false);
    }

    /** A chat-shaped body with the text where real capabilities put it: inside nested message content. */
    private static String chat(String userText) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "gpt-test");
        root.put("max_completion_tokens", 400);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", "판매자의 응대 기준에 맞춰 답하세요.");
        ArrayNode parts = messages.addObject().put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", userText);
        return root.toString();
    }

    private static Prepared check(String userText) {
        return LlmSendGate.inspect(chat(userText), Images.NONE);
    }

    @Test
    void unarmedTheBodyLeavesByteForByteUnchanged() {
        String body = chat("010-1234-5678로 연락 주세요. 서울시 강남구 테헤란로 123");
        Prepared p = LlmSendGate.prepare(body, Images.NONE);
        assertThat(p.sendable()).isTrue();
        assertThat(p.body()).isSameAs(body);
    }

    @Test
    void armedTheSameBodyIsChecked() {
        OutboundGate.arm(true);
        Prepared p = LlmSendGate.prepare(chat("010-1234-5678로 연락 주세요"), Images.NONE);
        assertThat(p.removed()).containsExactly(Finding.PHONE);
        assertThat(p.body()).contains("[전화번호]로 연락 주세요").doesNotContain("1234");
    }

    @Test
    void phonesAreRemovedButARepresentativeNumberStays() {
        for (String phone : List.of("010-1234-5678", "01012345678", "010 1234 5678", "02-123-4567", "031-1234-5678",
                "0505-123-4567", "070-1234-5678")) {
            Prepared p = check("번호는 " + phone + " 입니다");
            assertThat(p.removed()).as(phone).containsExactly(Finding.PHONE);
            assertThat(p.body()).as(phone).contains("번호는 [전화번호] 입니다");
        }
        Prepared cs = check("고객센터 1588-1234로 문의 주세요");
        assertThat(cs.removed()).isEmpty();
        assertThat(cs.body()).contains("1588-1234");
    }

    @Test
    void emailsAreRemoved() {
        Prepared p = check("buyer.kim+1@example.co.kr 로 보내 주세요");
        assertThat(p.removed()).containsExactly(Finding.EMAIL);
        assertThat(p.body()).contains("[이메일] 로 보내 주세요").doesNotContain("example.co.kr");
    }

    @Test
    void longNumbersAreRemovedButDatesSizesAndProductNumbersStay() {
        for (String number : List.of("2026082612345678", "123456789012", "110-123-456789", "1002-123-456789",
                "900101-1234567", "4111-1111-1111-1111", "123-45-67890")) {
            Prepared p = check("번호 " + number + " 확인 부탁");
            assertThat(p.removed()).as(number).containsExactly(Finding.LONG_NUMBER);
            assertThat(p.body()).as(number).contains("번호 [번호] 확인 부탁");
        }
        Prepared kept = check("2026-08-26 주문, 사이즈 250-260, 상품번호 1000000001, 3-4일 소요");
        assertThat(kept.removed()).isEmpty();
        assertThat(kept.body()).contains("2026-08-26", "250-260", "1000000001", "3-4일");
    }

    @Test
    void anAddressIsHeldNotRewritten() {
        for (String address : List.of("서울시 강남구 테헤란로 123", "성남시 분당구 판교역로 235", "중앙로 45번길 12",
                "산 12-3번지", "래미안 101동 1203호")) {
            Prepared p = check("주소는 " + address + " 입니다");
            assertThat(p.sendable()).as(address).isFalse();
            assertThat(p.held()).as(address).containsExactly(Finding.ADDRESS);
            assertThat(p.body()).as("a held request has no body to send").isNull();
        }
        for (String prose : List.of("택배로 3개 보냈어요", "서울로 2박 3일 여행", "길이 10cm 짧아요", "3동 매장")) {
            assertThat(check(prose).sendable()).as(prose).isTrue();
        }
    }

    @Test
    void aBuyerImageIsHeldAndOnlyAProductImageMayPass() {
        String openAi = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":"
                + "{\"url\":\"data:image/jpeg;base64,MDEwMTIzNDU2Nzg5MDEyMzQ1Njc4OQ==\"}}]}]}";
        String anthropic = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"
                + "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"MDEwMTIzNDU2Nzg5MDEy\"}}]}]}";
        for (String body : List.of(openAi, anthropic)) {
            Prepared held = LlmSendGate.inspect(body, Images.NONE);
            assertThat(held.held()).containsExactly(Finding.IMAGE);
            Prepared product = LlmSendGate.inspect(body, Images.PRODUCT);
            assertThat(product.sendable()).isTrue();
            assertThat(product.body()).as("encoded bytes are never rewritten").isEqualTo(body);
        }
        String bareDataUrl = "{\"input\":\"data:image/png;base64,AAAA\"}";
        assertThat(LlmSendGate.inspect(bareDataUrl, Images.NONE).held()).containsExactly(Finding.IMAGE);
    }

    @Test
    void textAnywhereInTheTreeIsChecked() {
        String body = "{\"system\":\"과거 답변: 010-9876-5432로 연락드렸습니다\","
                + "\"input\":[\"지식: 담당 cs@seller.example\",{\"nested\":{\"deeper\":[\"주문 2026082612345678\"]}}]}";
        Prepared p = LlmSendGate.inspect(body, Images.NONE);
        assertThat(p.removed()).containsExactlyInAnyOrder(Finding.PHONE, Finding.EMAIL, Finding.LONG_NUMBER);
        assertThat(p.body()).doesNotContain("9876", "cs@seller", "2026082612345678");
    }

    @Test
    void structureAndNonTextValuesAreUntouched() throws Exception {
        Prepared p = check("01012345678");
        var tree = MAPPER.readTree(p.body());
        assertThat(tree.path("model").asText()).isEqualTo("gpt-test");
        assertThat(tree.path("max_completion_tokens").asInt()).isEqualTo(400);
        assertThat(tree.path("messages").path(1).path("content").path(0).path("text").asText())
                .isEqualTo("[전화번호]");
    }

    @Test
    void anUnreadableBodyIsHeld() {
        for (String body : new String[] {null, "", "not json", "\"a string\""}) {
            assertThat(LlmSendGate.inspect(body, Images.NONE).held()).containsExactly(Finding.UNREADABLE);
        }
    }

    @Test
    void theHeldMarkerNamesLabelsAndNeverText() {
        Prepared p = check("010-1234-5678, 서울시 강남구 테헤란로 123");
        assertThat(p.heldMarker()).isEqualTo("held:ADDRESS");
        assertThat(p.heldMarker()).doesNotContain("테헤란", "1234");
    }
}
