package com.sellerops.openquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>The questions a draft left, one at a time, with a reason and a history</b> (2026-11-01 tenth review: 「질문별로
 * 처리하고, 해결 · 대응 불필요 등의 이유와 이력을 남긴다」).
 */
@DataJpaTest
@ActiveProfiles("test")
class OpenQuestionServiceTest {

    @Autowired OpenQuestionRepository questions;
    @Autowired OpenQuestionEventRepository events;

    OpenQuestionService service;
    final UUID org = UUID.randomUUID();
    final UUID work = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OpenQuestionService(questions, events);
    }

    @Test
    @DisplayName("a judgement opens what it lists; the seller closes one with a reason, and both acts are history")
    void openedThenHandledWithAReason() {
        service.replace(org, "INQUIRY", work, List.of("세탁 방법", "대량 할인"), true, "inquiry-draft:w:1", "SELLER:a");
        assertThat(service.openRequired(org, "INQUIRY", work)).isEqualTo(2);
        UUID first = service.list(org, "INQUIRY", work).get(0).id();

        OpenQuestionService.OpenQuestionView handled = service.handle(org, first, "NOT_NEEDED", " 이미 안내함 ", "SELLER:b");

        assertThat(handled.state()).isEqualTo("HANDLED");
        assertThat(handled.resolution()).isEqualTo("NOT_NEEDED");
        assertThat(handled.note()).isEqualTo("이미 안내함");
        assertThat(service.openRequired(org, "INQUIRY", work)).isEqualTo(1);
        assertThat(service.list(org, "INQUIRY", work)).extracting(OpenQuestionService.OpenQuestionView::state)
                .as("open first").containsExactly("OPEN", "HANDLED");
        assertThat(events.findByQuestionIdOrderByAtAsc(first)).extracting(OpenQuestionEvent::getAction)
                .containsExactly("OPENED", "HANDLED");
        assertThatThrownBy(() -> service.handle(org, first, "ANSWERED", null, "SELLER:b"))
                .isInstanceOf(ApiException.class).hasMessageContaining("이미 처리된");
    }

    @Test
    @DisplayName("a new judgement keeps what it still asks, closes what it no longer asks as re-judged, and does not reopen "
            + "what the seller handled")
    void aNewJudgementReplacesOnlyWhatChanged() {
        service.replace(org, "INQUIRY", work, List.of("세탁 방법", "대량 할인"), true, "inquiry-draft:w:1", "S");
        UUID washing = service.list(org, "INQUIRY", work).get(0).id();
        UUID bulk = service.list(org, "INQUIRY", work).get(1).id();
        service.handle(org, bulk, "ELSEWHERE", null, "SELLER:a");

        service.replace(org, "INQUIRY", work, List.of("대량 할인", "배송 기간"), true, "inquiry-draft:w:2", "S");

        assertThat(service.openTexts(org, "INQUIRY", work)).containsExactly("배송 기간");
        assertThat(questions.findById(washing).orElseThrow().getResolution()).isEqualTo("REJUDGED");
        assertThat(questions.findById(bulk).orElseThrow().getState()).isEqualTo("HANDLED");

        service.replace(org, "INQUIRY", work, List.of(), true, "inquiry-draft:w:3", "S");
        assertThat(service.openRequired(org, "INQUIRY", work)).as("a version that answers everything closes the rest")
                .isZero();
    }

    @Test
    @DisplayName("only the seller's three reasons, only this org's questions, only the two lanes")
    void closedWordsAndOneOrg() {
        service.replace(org, "REVIEW", work, List.of("재입고 일정"), false, "reply-run:r", "S");
        UUID id = service.list(org, "REVIEW", work).get(0).id();
        assertThat(service.openRequired(org, "REVIEW", work)).as("a review reply is not held").isZero();
        assertThatThrownBy(() -> service.handle(org, id, "REJUDGED", null, "SELLER:a")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.handle(UUID.randomUUID(), id, "ANSWERED", null, "SELLER:a"))
                .isInstanceOf(ApiException.class).hasMessageContaining("찾을 수 없어요");
        assertThatThrownBy(() -> service.list(org, "ORDER", work)).isInstanceOf(ApiException.class);
        assertThat(service.list(UUID.randomUUID(), "REVIEW", work)).isEmpty();
    }

    @Test
    @DisplayName("settled by a completed action: the open question is closed as settled on its record, never as answered; one "
            + "never opened is written closed; settling again adds nothing; no seller may choose the reason (2026-11-06)")
    void settledOnARecord() {
        service.replace(org, "INQUIRY", work, List.of("교환할 수 있는지 — 기준", "언제 받는지"), true, "inquiry-draft:w:1", "S");
        UUID rule = service.list(org, "INQUIRY", work).get(0).id();

        List<OpenQuestionService.Settled> settled = List.of(new OpenQuestionService.Settled("교환할 수 있는지",
                "seller-check:abc"), new OpenQuestionService.Settled("교환을 해 주는지", "seller-check:abc"));
        service.settle(org, "INQUIRY", work, settled, "inquiry-draft:w:2", "SELLER:a");
        service.replace(org, "INQUIRY", work, List.of("언제 받는지"), true, "inquiry-draft:w:2", "SELLER:a");

        OpenQuestion closed = questions.findById(rule).orElseThrow();
        assertThat(closed.getState()).isEqualTo("SUPERSEDED");
        assertThat(closed.getResolution()).isEqualTo("SETTLED_BY_ACTION");
        assertThat(closed.getBasisRef()).isEqualTo("seller-check:abc");
        assertThat(events.findByQuestionIdOrderByAtAsc(rule)).extracting(OpenQuestionEvent::getAction)
                .containsExactly("OPENED", "SETTLED");
        assertThat(service.openTexts(org, "INQUIRY", work)).containsExactly("언제 받는지");
        assertThat(service.list(org, "INQUIRY", work)).filteredOn(v -> "교환을 해 주는지".equals(v.question()))
                .singleElement().satisfies(v -> {
                    assertThat(v.state()).as("written closed").isEqualTo("SUPERSEDED");
                    assertThat(v.resolution()).isEqualTo("SETTLED_BY_ACTION");
                    assertThat(v.basisRef()).isEqualTo("seller-check:abc");
                });
        int rows = service.list(org, "INQUIRY", work).size();
        service.settle(org, "INQUIRY", work, settled, "inquiry-draft:w:3", "SELLER:a");
        assertThat(service.list(org, "INQUIRY", work)).as("settling again adds nothing").hasSize(rows);
        UUID open = service.list(org, "INQUIRY", work).get(0).id();
        assertThatThrownBy(() -> service.handle(org, open, "SETTLED_BY_ACTION", null, "SELLER:a"))
                .isInstanceOf(ApiException.class);
    }
}
