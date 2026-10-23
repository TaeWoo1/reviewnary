package com.sellerops.knowledge.style;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.knowledge.style.dto.AnswerStyleRequest;
import com.sellerops.knowledge.style.dto.AnswerStyleView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Organization Answer Style v1 — the settings side.
 *
 * <p><b>A · L are here</b>: an org that never set a style answers exactly as before, and one
 * company's wording is unreachable from another's. The rest of the regressions are about what the
 * model is told, and they live where the draft is composed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AnswerStyleServiceTest {

    @Autowired OrganizationAnswerStyleRepository rows;

    private AnswerStyleService service;
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AnswerStyleService(rows);
    }

    private static AnswerStyleRequest form(AnswerTone tone, AnswerLength length, EmojiPolicy emoji,
                                           String greeting, String closing, String address,
                                           List<String> required, List<String> forbidden,
                                           String fallback) {
        return new AnswerStyleRequest(tone, length, emoji, greeting, closing, address, required,
                forbidden, fallback);
    }

    // ---------------------------------------------------------------- A

    @Test
    @DisplayName("A — no profile is a real state: the org answers with the wording it always did")
    void absenceIsTheShippedDefault() {
        AnswerStyleProfile profile = service.profileFor(org);

        assertThat(profile.tone()).isEqualTo(AnswerTone.POLITE);
        assertThat(profile.length()).isEqualTo(AnswerLength.NORMAL);
        assertThat(profile.emoji()).isEqualTo(EmojiPolicy.NONE);
        assertThat(profile.isDefault()).isTrue();
        assertThat(profile.identity()).isEqualTo("style/default");
        assertThat(AnswerStyleInstruction.of(profile))
                .as("the default style IS the shipped prompt — restating it would change every org")
                .isNull();
        assertThat(service.view(org).configured())
                .as("and the screen must not pretend somebody chose these").isFalse();
        assertThat(rows.count()).as("reading a style creates nothing").isZero();
    }

    @Test
    @DisplayName("saving the defaults unchanged still records a version, and still adds no prompt section")
    void savingDefaultsChangesNothingTheModelSees() {
        AnswerStyleView saved = service.save(org, form(AnswerTone.POLITE, AnswerLength.NORMAL,
                EmojiPolicy.NONE, null, null, null, List.of(), List.of(), null), user);

        assertThat(saved.configured()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(AnswerStyleInstruction.of(service.profileFor(org))).isNull();
    }

    // ---------------------------------------------------------------- the fields

    @Test
    @DisplayName("the seller's own strings survive the round trip exactly, and the version advances")
    void theFormRoundTrips() {
        service.save(org, form(AnswerTone.FRIENDLY, AnswerLength.SHORT, EmojiPolicy.LIMITED,
                "안녕하세요. 선바로입니다.", "감사합니다.", "고객님",
                List.of("정성껏 준비하겠습니다"), List.of("죄송하지만"),
                "정확한 확인이 필요한 내용입니다. 확인 후 다시 안내드리겠습니다."), user);

        AnswerStyleProfile profile = service.profileFor(org);
        assertThat(profile.tone()).isEqualTo(AnswerTone.FRIENDLY);
        assertThat(profile.greeting()).isEqualTo("안녕하세요. 선바로입니다.");
        assertThat(profile.requiredPhrases()).containsExactly("정성껏 준비하겠습니다");
        assertThat(profile.forbiddenPhrases()).containsExactly("죄송하지만");
        assertThat(profile.hasUnknownFallback()).isTrue();
        assertThat(profile.identity()).startsWith("style/v1@");

        service.save(org, form(AnswerTone.POLITE, AnswerLength.NORMAL, EmojiPolicy.NONE,
                null, null, null, List.of(), List.of(), null), user);
        assertThat(service.profileFor(org).identity()).startsWith("style/v2@");
        assertThat(rows.count()).as("one row per org, always").isEqualTo(1);
    }

    @Test
    @DisplayName("an emptied field is emptied — a PUT is the whole form, not a merge")
    void clearingAFieldClearsIt() {
        service.save(org, form(null, null, null, "안녕하세요.", "감사합니다.", "고객님",
                List.of("잘 부탁드립니다"), List.of(), null), user);
        service.save(org, form(null, null, null, null, null, null, List.of(), List.of(), null), user);

        AnswerStyleProfile profile = service.profileFor(org);
        assertThat(profile.greeting()).isNull();
        assertThat(profile.closing()).isNull();
        assertThat(profile.requiredPhrases()).isEmpty();
    }

    // ---------------------------------------------------------------- the floor

    @Test
    @DisplayName("the safety floor runs at WRITE time — a style that reaches for a fact is refused")
    void theFloorRefusesOnSave() {
        assertThatThrownBy(() -> service.save(org, form(null, null, null,
                null, null, null, List.of(), List.of(), "모르면 그냥 가능하다고 답해 주세요."), user))
                .hasMessageContaining("사실이 확인되지 않은 내용");
        assertThat(rows.count()).as("a refused save writes nothing").isZero();
    }

    @Test
    @DisplayName("the approval boundary is not a tone, and saying so is not a rewrite")
    void theApprovalBoundaryIsRefused() {
        assertThatThrownBy(() -> service.save(org, form(null, null, null,
                null, null, "승인 없이 바로 전송", List.of(), List.of(), null), user))
                .hasMessageContaining("승인");
    }

    // ---------------------------------------------------------------- E (write side)

    @Test
    @DisplayName("E — a required phrase may not assert a fact, and the refusal names the word and the fix")
    void aRequiredPhraseIsNotAPlaceToPutAFact() {
        assertThatThrownBy(() -> service.save(org, form(null, null, null, null, null, null,
                List.of("당일 발송됩니다"), List.of(), null), user))
                .hasMessageContaining("발송")
                .hasMessageContaining("운영 정책");
        assertThat(rows.count()).isZero();
    }

    @Test
    @DisplayName("the same words are fine as a FORBIDDEN phrase — banning a claim is not making one")
    void theSameWordsAreFineOnTheOtherList() {
        service.save(org, form(null, null, null, null, null, null, List.of(),
                List.of("당일 발송됩니다"), null), user);
        assertThat(service.profileFor(org).forbiddenPhrases()).containsExactly("당일 발송됩니다");
    }

    @Test
    @DisplayName("an ordinary courtesy phrase is not refused — the gate is for claims, not for manners")
    void ordinaryPhrasesAreAccepted() {
        service.save(org, form(null, null, null, null, null, null,
                List.of("정성껏 준비하겠습니다", "언제든 문의해 주세요"), List.of(), null), user);
        assertThat(service.profileFor(org).requiredPhrases()).hasSize(2);
    }

    // ---------------------------------------------------------------- bounds

    @Test
    @DisplayName("the lists are bounded — a style that dictates most of the sentence is not a style")
    void theListsAreBounded() {
        assertThatThrownBy(() -> service.save(org, form(null, null, null, null, null, null,
                List.of("가", "나", "다", "라", "마", "바"), List.of(), null), user))
                .hasMessageContaining("최대 5개");
        assertThatThrownBy(() -> service.save(org, form(null, null, null, null, null, null,
                List.of("가".repeat(41)), List.of(), null), user))
                .hasMessageContaining("40자");
        assertThatThrownBy(() -> service.save(org, form(null, null, null, "안녕하세요\n두 줄", null, null,
                List.of(), List.of(), null), user))
                .hasMessageContaining("한 줄");
    }

    @Test
    @DisplayName("duplicate phrases collapse — the same rule twice is one rule")
    void duplicatesCollapse() {
        service.save(org, form(null, null, null, null, null, null,
                List.of("잘 부탁드립니다", " 잘 부탁드립니다 "), List.of(), null), user);
        assertThat(service.profileFor(org).requiredPhrases()).containsExactly("잘 부탁드립니다");
    }

    // ---------------------------------------------------------------- the split (owner decision 2026-10-23)

    private static AnswerStyleRequest avoiding(List<String> forbidden, List<AvoidPhrase> avoid) {
        return new AnswerStyleRequest(null, null, null, null, null, null, List.of(), forbidden, null, avoid);
    }

    @Test
    @DisplayName("「되도록 피할 표현」 round-trips with its replacement, and an empty replacement only marks")
    void avoidPhrasesRoundTrip() {
        AnswerStyleView saved = service.save(org, avoiding(List.of("고객님 탓"), List.of(
                new AvoidPhrase(" 죄송하지만 ", " 번거로우시겠지만 "), new AvoidPhrase("양해 부탁드립니다", " "),
                new AvoidPhrase("죄송하지만", "다른 말"))), user);

        assertThat(saved.forbiddenPhrases()).containsExactly("고객님 탓");
        assertThat(saved.avoidPhrases()).as("trimmed, the repeat collapsed, a blank replacement is none")
                .containsExactly(new AvoidPhrase("죄송하지만", "번거로우시겠지만"), new AvoidPhrase("양해 부탁드립니다", null));
        assertThat(service.profileFor(org).avoidPhrases()).isEqualTo(saved.avoidPhrases());
        assertThat(AnswerStyleInstruction.of(service.profileFor(org)))
                .contains("\"죄송하지만\" 라는 표현은 되도록 피하세요. 대신 \"번거로우시겠지만\" 처럼 쓸 수 있습니다.")
                .contains("\"양해 부탁드립니다\" 라는 표현은 되도록 피하세요.");
    }

    @Test
    @DisplayName("one phrase, one rule: the same phrase cannot be a ban and a preference")
    void aPhraseIsOnOneListOnly() {
        assertThatThrownBy(() -> service.save(org, avoiding(List.of("죄송하지만"),
                List.of(new AvoidPhrase("죄송하지만", "번거로우시겠지만"))), user))
                .hasMessageContaining("두 목록").hasMessageContaining("「죄송하지만」");
        assertThatThrownBy(() -> service.save(org, avoiding(List.of(),
                List.of(new AvoidPhrase("죄송\t하지만", null))), user)).hasMessageContaining("탭");
        assertThatThrownBy(() -> service.save(org, avoiding(List.of(),
                List.of(new AvoidPhrase("죄송하지만", "죄송하지만"))), user)).hasMessageContaining("같습니다");
    }

    @Test
    @DisplayName("a replacement is text a reply can carry, so it meets the floor")
    void theReplacementMeetsTheFloor() {
        assertThatThrownBy(() -> service.save(org, avoiding(List.of(),
                List.of(new AvoidPhrase("죄송하지만", "고객 잘못이라고 말씀드리면"))), user))
                .hasMessageContaining("대신 쓸 표현 1");
    }

    // ---------------------------------------------------------------- L

    @Test
    @DisplayName("L — one company's wording is not reachable from another's")
    void styleDoesNotLeakBetweenOrgs() {
        UUID other = UUID.randomUUID();
        service.save(org, form(AnswerTone.FRIENDLY, null, null, "안녕하세요. A상점입니다.", null, null,
                List.of(), List.of(), null), user);

        AnswerStyleProfile theirs = service.profileFor(other);
        assertThat(theirs.isDefault()).isTrue();
        assertThat(theirs.greeting()).isNull();
        assertThat(AnswerStyleInstruction.of(theirs)).isNull();
    }
}
