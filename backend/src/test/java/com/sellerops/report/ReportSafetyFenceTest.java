package com.sellerops.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the report package can never do, pinned by name — the twin of {@code OpportunitySafetyFenceTest}.
 * A report reads; it writes one row of its own; it reaches the model through one door in another package.
 */
class ReportSafetyFenceTest {

    private static final Path PKG = Path.of("src", "main", "java", "com", "sellerops", "report");

    private static String strip(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static List<String[]> sources() throws IOException {
        List<String[]> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(PKG)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.add(new String[] {p.getFileName().toString(), strip(Files.readString(p))});
            }
        }
        return out;
    }

    @Test
    @DisplayName("no channel, no HTTP, no approval, no execution, no direct model transport")
    void noSideEffectsBeyondItsOwnRow() throws IOException {
        List<String> forbidden = List.of("Connector", "HttpClient", "WebClient", "RestTemplate", "Approval",
                "ActionExecutor", "Publish", "AgentLlmTransport", "AgentLlmWireFormat", "ChatModel",
                "KnowledgeSource", "OrgKnowledge", "InquiryReplyDraft", "ReviewReplyDraft");
        List<String> offenders = new ArrayList<>();
        for (String[] s : sources()) {
            for (String word : forbidden) {
                if (s[1].contains(word)) {
                    offenders.add(s[0] + " names " + word);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the only write in the package is the report row itself")
    void theOnlyWriterIsTheReportRow() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String[] s : sources()) {
            String code = s[1];
            int saves = code.split("\\.save\\(", -1).length - 1;
            int deletes = code.split("\\.delete", -1).length - 1;
            if (deletes > 0) {
                offenders.add(s[0] + " deletes");
            }
            if (saves > 0 && !(s[0].equals("AgentReportService.java") && code.contains("reports.save("))) {
                offenders.add(s[0] + " saves something else");
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * <b>생성 경로는 모델에 닿지 않는다</b> (2026-10-06, product-owner decision). 리포트 화면이 narrative를
     * 그리지 않게 된 뒤, 그 호출은 아무도 읽지 않는 산출물을 위해 GET 안에서 벤더를 동기로 기다리는 일이
     * 됐다(한 기간의 첫 열기 25.5초). {@code NarrativeStatus}·{@code NarrativeClaimGuard}·저장 컬럼은
     * 옛 행을 읽기 위해 남지만, 이 패키지는 그 문을 이름으로도 부르지 못한다.
     */
    @Test
    @DisplayName("nothing in the report package can ask a model for anything")
    void generationNamesNoModelDoor() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String[] s : sources()) {
            if (s[1].contains("AgentReportNarrativeService") || s[1].contains("AgentReportProperties")
                    || s[1].contains("AgentReportNarrativeGenerator")) {
                offenders.add(s[0] + " names the narrative capability");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void theMigrationExists() {
        assertThat(Files.exists(Path.of("src", "main", "resources", "db", "migration", "V96__agent_report.sql")))
                .isTrue();
    }
}
