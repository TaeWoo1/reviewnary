package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.inquiry.draft.InquiryClaimGuard.Kind;
import com.sellerops.inquiry.draft.InquiryClaimGuard.Violation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Inquiry Claim Guard v1 — a reply may promise, request or state a figure only when the evidence does.
 *
 * <p>The first two cases are the real ones. On 2026-09-19 the Past Answer Prefill proofs drafted the same question twice
 * over the same seller knowledge: {@code apr-7c3e91d4} wrote a clean reply, and {@code apr-3d8b27e5} ended with a
 * request for a photo and a follow-up promise nobody made. The drafts are verbatim model output; the evidence is the
 * product knowledge the seller saved in those proofs. The other passage the drafter saw — the seller's past channel
 * answer — is seller data and is not copied here; it mentions no photo and no follow-up, so it changes neither verdict.
 */
public class InquiryClaimGuardTest {

    public static final String QUESTION = "기존 원터치 디스펜서 제품의 투명한 부분과 아래쪽 보라색 부분은 어떻게 분리하나요?";
    public static final String KNOWLEDGE = "투명한 부분과 아래쪽 보라색 부분은 파이프 부분에서 뚜껑 부분을 분리하듯 똑같이 분리하시면 됩니다.\n"
            + "크기가 완전 딱 맞도록 제작이 되어 끼우는데 어려움이 있을 수 있으니 이 점 주의해주세요.";

    /** apr-3d8b27e5, draft v1 — GROUNDED, and two invented clauses at the end. */
    public static final String INVENTED = "안녕하세요 고객님. 문의 주신 기존 원터치 디스펜서의 투명한 부분과 아래쪽 보라색 부분은 파이프 "
            + "부분에서 뚜껑을 분리하듯 동일하게 분리하시면 됩니다. 크기가 딱 맞도록 제작되어 끼우거나 분리 시 다소 어려울 수 "
            + "있으니 이 점 유의해 주세요. 추가로 필요하시면 사용 중 사진을 보내주시면 확인 후 안내드리겠습니다.";

    /** apr-7c3e91d4, draft v1 — the same question and knowledge, and nothing invented. */
    public static final String CLEAN = "안녕하세요. 문의주신 기존 원터치 디스펜서의 투명한 부분과 아래쪽 보라색 부분은 파이프 부분에서 "
            + "뚜껑을 분리하듯 동일한 방식으로 분리하시면 됩니다. 제품이 딱 맞게 제작되어 끼우거나 분리할 때 다소 뻑뻑할 수 "
            + "있으니 힘 조절에 유의해 천천히 진행해 주세요. 추가로 궁금한 점이 있으시면 언제든지 말씀해 주세요. 감사합니다.";

    private static List<Violation> check(String body, List<String> evidence, List<String> voice) {
        return InquiryClaimGuard.unsupportedClaims(body, evidence, voice, QUESTION);
    }

    @Nested
    @DisplayName("the reproduced case")
    class Reproduced {

        @Test
        @DisplayName("a photo request and a follow-up promise the knowledge never made are refused")
        void theInventedClausesAreFound() {
            assertThat(check(INVENTED, List.of(KNOWLEDGE), List.of()))
                    .extracting(Violation::kind)
                    .containsExactlyInAnyOrder(Kind.SELLER_PROMISE, Kind.CUSTOMER_ACTION);
        }

        @Test
        @DisplayName("the clean draft of the same question, over the same knowledge, passes")
        void theCleanDraftPasses() {
            assertThat(check(CLEAN, List.of(KNOWLEDGE), List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("what may authorize a claim")
    class Authorization {

        @Test
        @DisplayName("evidence that itself asks for a photo and promises a follow-up authorizes both")
        void evidenceAuthorizes() {
            String policy = "파손 문의는 사진을 보내주시면 확인 후 안내드립니다.";
            assertThat(check(INVENTED, List.of(KNOWLEDGE, policy), List.of())).isEmpty();
        }

        @Test
        @DisplayName("a seller who writes that they collect a photo has authorized asking for one — that material only")
        void receivingAuthorizesTheSameMaterial() {
            List<String> guidance = List.of("교환 요청은 먼저 사진을 받아 확인한 뒤 안내합니다.");
            assertThat(check("사진을 먼저 보내 주시면 확인 후 안내드리겠습니다.", guidance, List.of())).isEmpty();
            assertThat(check("주문번호를 보내 주세요.", guidance, List.of()))
                    .extracting(Violation::kind).containsExactly(Kind.CUSTOMER_ACTION);
        }

        @Test
        @DisplayName("the seller's own approved deferral authorizes a follow-up, and nothing else")
        void theSellersFallbackAuthorizesItsOwnPromise() {
            String body = "확인 후 안내드리겠습니다. 사진을 보내주시면 됩니다.";
            assertThat(check(body, List.of(), List.of("담당자가 확인 후 안내드리겠습니다.")))
                    .extracting(Violation::kind).containsExactly(Kind.CUSTOMER_ACTION);
        }

        @Test
        @DisplayName("a remedy is authorized by the evidence alone — the review list, reused")
        void remediesNeedEvidence() {
            assertThat(check("교환해 드리겠습니다.", List.of(KNOWLEDGE), List.of("교환 가능합니다")))
                    .extracting(Violation::kind).containsExactly(Kind.REMEDY);
            assertThat(check("수령 후 7일 이내 교환 가능합니다.", List.of("상품 수령 후 7일 이내에 교환 신청을 하실 수 있습니다."),
                    List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("figures — the certain half in code, meaning to the model")
    class Figures {

        private List<String> toVerify(String body, List<String> evidence, String asked) {
            return InquiryClaimGuard.figuresToVerify(body, evidence, asked, null).stream()
                    .map(InquiryClaimGuard.FigureClaim::value).toList();
        }

        @Test
        @DisplayName("a delivery window the evidence does not state is refused without a model")
        void anInventedWindow() {
            assertThat(check("주문 후 1~2일 내 출고됩니다.", List.of(KNOWLEDGE), List.of()))
                    .extracting(Violation::matched).containsExactly("1", "2");
        }

        @Test
        @DisplayName("the same number only in another unit is refused without a model — 폭 10mm, 폭은 10cm")
        void anotherUnit() {
            List<String> spec = List.of("폭 10mm, 길이 2m입니다.");
            assertThat(InquiryClaimGuard.unsupportedClaims("폭은 10cm입니다.", spec, List.of(), "폭이 얼마예요?"))
                    .extracting(Violation::matched).containsExactly("10");
            assertThat(InquiryClaimGuard.unsupportedClaims("폭은 10 센티예요.", List.of("폭 10cm"), List.of(), ""))
                    .as("a unit written two ways is one unit").isEmpty();
        }

        @Test
        @DisplayName("an exact conversion is code's arithmetic — 10mm and 1cm are one amount, handed to the model to read")
        void anExactConversion() {
            assertThat(InquiryClaimGuard.unsupportedClaims("폭은 1cm입니다.", List.of("폭 10mm"), List.of(), "")).isEmpty();
            assertThat(toVerify("폭은 1cm입니다.", List.of("폭 10mm"), "")).containsExactly("1");
            assertThat(InquiryClaimGuard.unsupportedClaims("용량은 0.5L입니다.", List.of("용량 500ml"), List.of(), "")).isEmpty();
            assertThat(InquiryClaimGuard.unsupportedClaims("폭은 2cm입니다.", List.of("폭 10mm"), List.of(), ""))
                    .as("a different amount in a convertible unit").extracting(Violation::matched).containsExactly("2");
            assertThat(InquiryClaimGuard.unsupportedClaims("무게는 10cm입니다.", List.of("무게 10g"), List.of(), ""))
                    .as("no conversion across kinds").extracting(Violation::matched).containsExactly("10");
        }

        @Test
        @DisplayName("whether a written figure is about the same thing is not decided by words — it is handed over")
        void meaningIsHandedToTheModel() {
            // The second review's cases (2026-10-28). Each value is written in the evidence, so the text cannot settle
            // them either way: none is refused here, and every one is listed for the figure check.
            assertThat(check("상품 높이는 10mm입니다.", List.of("상품 폭 10mm"), List.of())).isEmpty();
            assertThat(toVerify("상품 높이는 10mm입니다.", List.of("상품 폭 10mm"), "")).containsExactly("10");
            assertThat(toVerify("평일 배송은 7일 걸립니다.", List.of("평일 배송 2일, 제주 배송 7일"), "")).containsExactly("7");
            assertThat(toVerify("폭은 10cm입니다.", List.of("폭: 10"), "")).as("no unit written: the model reads it")
                    .containsExactly("10");
            assertThat(toVerify("너비는 10mm입니다.", List.of("폭 10mm"), "")).as("a paraphrase is the model's to accept")
                    .containsExactly("10");
        }

        @Test
        @DisplayName("a number only the customer wrote is handed over as theirs — the model must call it said back")
        void theCustomersNumber() {
            List<InquiryClaimGuard.FigureClaim> claims = InquiryClaimGuard.figuresToVerify("문의하신 20가닥까지 가능합니다.",
                    List.of("최대 10가닥까지 넣을 수 있습니다."), "20가닥도 가능한가요?", null);
            assertThat(claims).singleElement().satisfies(c -> {
                assertThat(c.value()).isEqualTo("20");
                assertThat(c.questionOnly()).isTrue();
            });
            assertThat(check("네, 30가닥까지 가능합니다.", List.of("최대 10가닥"), List.of()))
                    .as("a number nobody wrote is still refused in code").extracting(Violation::matched).containsExactly("30");
        }

        @Test
        @DisplayName("the order's own sentence counts as a written figure")
        void theOrderSentence() {
            assertThat(InquiryClaimGuard.unsupportedClaims("주문하신 2개 모두 오늘 출고됩니다.", List.of(), List.of(), "",
                    "이 주문은 2개 상품이 오늘 출고 예정입니다.")).isEmpty();
        }

        @Test
        @DisplayName("a range and a dimension list carry their last unit")
        void rangesAndLists() {
            assertThat(InquiryClaimGuard.unsupportedClaims("크기는 10 x 20mm예요.", List.of("크기 10×20 mm"), List.of(), ""))
                    .isEmpty();
            assertThat(InquiryClaimGuard.unsupportedClaims("출고는 1~2일 걸려요.", List.of("출고는 1~2일 소요됩니다."), List.of(),
                    "")).isEmpty();
            assertThat(InquiryClaimGuard.unsupportedClaims("가격은 1,500원이에요.", List.of("가격 1500원"), List.of(), ""))
                    .isEmpty();
        }

        @Test
        @DisplayName("a list marker is not a figure")
        void aListMarker() {
            assertThat(check("1. 파이프 부분을 잡아 주세요. 2. 뚜껑처럼 돌려 빼 주세요.", List.of(KNOWLEDGE), List.of()))
                    .isEmpty();
            assertThat(toVerify("1. 파이프 부분을 잡아 주세요.", List.of(KNOWLEDGE), "")).isEmpty();
        }
    }

    @Test
    @DisplayName("a clarifying question names no material to send and is not a request")
    void aClarifyingQuestionPasses() {
        assertThat(check("어떤 규격을 쓰실지 알려주시면 그에 맞춰 말씀드리겠습니다.", List.of(KNOWLEDGE), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a remedy answered the other way from the evidence is refused — 「불가」 written up as 「가능」")
    void aReversedRemedy() {
        assertThat(InquiryClaimGuard.unsupportedClaims("단순 변심도 무료 교환이 가능합니다.",
                List.of("단순 변심은 무료 교환이 불가합니다."), List.of(), "교환되나요?"))
                .extracting(Violation::matched).containsExactly("교환");
    }

    /**
     * The expression rules restated in the 2026-11-01 review: a saying only the seller can stand behind is written only
     * when the seller's material or own sentences carry it — whoever its grammatical subject is.
     */
    @Nested
    class SayingsTheSellerStandsBehind {

        @Test
        void aServiceThatNobodyConfirmedIsNotWrittenWhateverItsSubject() {
            for (String draft : List.of("교환 방법은 따로 안내받으실 수 있습니다.", "자세한 사항은 상담받으실 수 있어요.",
                    "필요하시면 처리 방법을 안내해 드릴 수 있습니다.")) {
                assertThat(InquiryClaimGuard.unbackedSayings(draft, List.of())).as(draft).isNotEmpty();
            }
        }

        @Test
        void anOperatingClaimNeedsTheSellersWord() {
            String draft = "고객님의 후기 하나하나 확인하고 있습니다.";
            assertThat(InquiryClaimGuard.unbackedSayings(draft, List.of())).isNotEmpty();
            assertThat(InquiryClaimGuard.unbackedSayings(draft, List.of("저희는 모든 후기를 하나하나 확인하고 있습니다.")))
                    .isEmpty();
        }

        @Test
        void lookingIntoItIsNotADefaultClose() {
            assertThat(InquiryClaimGuard.unbackedSayings("말씀하신 부분 잘 살펴보겠습니다.", List.of())).isNotEmpty();
            assertThat(InquiryClaimGuard.unbackedSayings("소중한 후기 감사합니다. 불편을 드려 죄송합니다.", List.of()))
                    .isEmpty();
        }

        @Test
        void onlyAContactPathTheMaterialNamesIsGiven() {
            assertThat(InquiryClaimGuard.unbackedSayings("고객센터로 연락 주시면 도와드릴게요.", List.of())).isNotEmpty();
            assertThat(InquiryClaimGuard.unbackedSayings("톡톡으로 문의 주세요.", List.of("교환은 톡톡으로 접수합니다.")))
                    .isEmpty();
            assertThat(InquiryClaimGuard.unsupportedClaims("1:1 문의로 남겨 주세요.", List.of(), List.of(), "교환되나요?"))
                    .extracting(Violation::kind).contains(Kind.SELLER_PROMISE);
        }
    }
}
