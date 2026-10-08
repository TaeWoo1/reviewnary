package com.sellerops.responsibility.aside;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>운영 스키마가 허용하는 단어와, 코드가 쓸 수 있는 단어를 맞춰 본다.</b>
 *
 * <p>이 테스트가 없어서 2026-10-09 라이브가 깨졌다. 첫 historical catch-up의 읽기는 성공했다 — 42건,
 * 창이 화면 census로 검증됨, 화면이 인쇄한 총계와 일치, month_moves 2. 그리고 저장이 commit 시점에
 * 거부됐다: 판정을 {@code COMPLETE}로 썼고, {@code delivery_completeness}의 check constraint는
 * {@code BOUNDED}와 {@code PARTIAL}만 허용한다.
 *
 * <p><b>어떤 테스트도 그것을 볼 수 없었다.</b> 이 스위트는 H2에서 Flyway를 끄고 Hibernate가 만든 스키마를
 * 쓴다({@code application-test.properties}: {@code spring.flyway.enabled=false},
 * {@code ddl-auto=create-drop}). 운영 제약은 어느 테스트에도 존재하지 않는다. 판정을 세우는 단위 테스트는
 * mock repository를 썼으니 더더욱 볼 수 없었다.
 *
 * <p>그래서 Postgres 없이 할 수 있는 일을 한다: 마이그레이션 SQL을 읽어 그 칼럼이 허용하는 단어 집합을
 * 꺼내고, 코드가 그 칼럼에 쓸 수 있는 단어가 그 안에 있는지 본다. 느리지도 않고, 운영 DB도 필요 없고,
 * 같은 종류의 실수를 CI에서 잡는다.
 */
class SchemaVocabularyTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** 마이그레이션 전체에서 그 제약의 마지막 정의를 찾아, 따옴표 안의 단어들을 꺼낸다. */
    private static Set<String> allowedBy(String constraint) throws IOException {
        Pattern named = Pattern.compile(
                constraint + "[\\s\\S]{0,400}?in\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
        String latest = null;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<Path> sorted = files.filter(f -> f.toString().endsWith(".sql")).sorted().toList();
            for (Path f : sorted) {
                Matcher m = named.matcher(Files.readString(f));
                while (m.find()) {
                    latest = m.group(1);
                }
            }
        }
        assertThat(latest).as("마이그레이션에서 %s 를 찾지 못했다", constraint).isNotNull();
        Matcher words = Pattern.compile("'([A-Z_]+)'").matcher(latest);
        Set<String> out = new java.util.LinkedHashSet<>();
        while (words.find()) {
            out.add(words.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("delivery_completeness: 칼럼이 받는 단어와 코드가 쓰는 단어가 같다")
    void theCoverageVerdictVocabularyAgrees() throws IOException {
        Set<String> allowed = allowedBy("chk_scheduled_aside_job_delivery_completeness");
        assertThat(allowed).containsExactlyInAnyOrder("BOUNDED", "PARTIAL");

        // 코드가 이 칼럼에 쓸 수 있는 길은 하나다. 그 길이 내놓을 수 있는 단어가 전부 허용 집합 안에 있어야
        // 한다 — 2026-10-09에는 COMPLETE를 내놓을 수 있었고, 그것이 허용 집합 밖이었다.
        for (com.sellerops.responsibility.SourceCompleteness value
                : com.sellerops.responsibility.SourceCompleteness.values()) {
            ScheduledAsideJob job = new ScheduledAsideJob();
            boolean accepted;
            try {
                job.recordCoverageVerdict(value, "WHOLE_PERIOD_READ");
                accepted = true;
            } catch (IllegalArgumentException refused) {
                accepted = false;
            }
            assertThat(accepted)
                    .as("%s: 코드가 쓸 수 있는가 == 칼럼이 받는가", value)
                    .isEqualTo(allowed.contains(value.name()));
        }
    }

    @Test
    @DisplayName("그 판정은 반드시 이 칼럼이 받는 단어로만 기록된다 — 「전부 읽었다」는 BOUNDED다")
    void theWholePeriodVerdictIsBounded() {
        ScheduledAsideJob job = new ScheduledAsideJob();
        job.recordCoverageVerdict(com.sellerops.responsibility.SourceCompleteness.BOUNDED, "WHOLE_PERIOD_READ");
        assertThat(job.getDeliveryCompleteness())
                .isEqualTo(com.sellerops.responsibility.SourceCompleteness.BOUNDED);
        assertThat(job.getCompletenessReason()).isEqualTo("WHOLE_PERIOD_READ");
    }
}
