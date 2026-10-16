package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>No outbound request leaves the backend without passing {@link OutboundGate}</b> — proposal §18.4 test 1,
 * §18.7 L1b.
 *
 * <p>Reads every main source file recursively (a flat listing would miss sub-packages — FINDINGS §B10) with
 * comments stripped, and holds two things:
 *
 * <ul>
 *   <li>every JDK {@code send}/{@code sendAsync} call in a file is matched by at least as many
 *       {@code OutboundGate.check(} calls in that file;</li>
 *   <li>the set of files that build a JDK {@code HttpClient} is the same whether it is found by the builder call
 *       or by the send call — two ways of counting the same thing, so a client whose send this regex fails to
 *       see shows up as a disagreement instead of a silent pass (CLAUDE.md 「검사 도구의 실패는 초록색」 5).</li>
 * </ul>
 *
 * <p>Both sets are first required to be non-empty, so an empty scan cannot pass (rule 3). Proven both ways when
 * written: removing the check from one client turns this red; restoring it turns it green.
 */
class OutboundGateFenceTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "sellerops");

    private static final Pattern BUILDS_CLIENT = Pattern.compile("HttpClient\\.new(Builder|HttpClient)\\(\\)");
    private static final Pattern JDK_SEND =
            Pattern.compile("\\b\\w+\\.send(Async)?\\(\\s*[^;]*?HttpResponse\\.BodyHandlers");
    private static final Pattern GATE_CHECK = Pattern.compile("OutboundGate\\.check\\(");

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static int count(Pattern pattern, String code) {
        Matcher m = pattern.matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("every JDK HttpClient send is preceded by OutboundGate.check in the same file")
    void everyOutboundSendIsGated() throws IOException {
        Set<String> builders = new TreeSet<>();
        Set<String> senders = new TreeSet<>();
        List<String> ungated = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = MAIN.relativize(source).toString();
                if (name.equals(Path.of("outbound", "OutboundGate.java").toString())) {
                    continue;
                }
                String code = stripComments(Files.readString(source));
                int built = count(BUILDS_CLIENT, code);
                int sends = count(JDK_SEND, code);
                if (built > 0) {
                    builders.add(name);
                }
                if (sends > 0) {
                    senders.add(name);
                    if (count(GATE_CHECK, code) < sends) {
                        ungated.add(name + " (" + sends + " send, " + count(GATE_CHECK, code) + " check)");
                    }
                }
            }
        }
        assertThat(builders).as("the scan found no JDK HttpClient at all — it is reading the wrong place")
                .isNotEmpty();
        assertThat(senders).as("files that send must be exactly the files that build a client")
                .isEqualTo(builders);
        assertThat(ungated).as("an outbound send with no OutboundGate.check before it").isEmpty();
    }
}
