package com.sellerops.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>세 결정이 코드에서 지켜지는지</b> — Order Context Foundation v1의 D1 · D3, source 위에서.
 *
 * <p>여기 있는 것은 모두 <b>없음</b>에 대한 단정이고, 없음에는 런타임이 없다. 주문 수집이 상품을 만들지
 * 않는다는 것, 이름으로 잇지 않는다는 것, 우리가 관측한 시각을 채널이 말한 시각 자리에 적지 않는다는
 * 것 — 셋 다 「그런 코드가 없다」가 증명의 형태다.
 */
class OrderContextFenceTest {

    private static final Path PACKAGE = Paths.get("src/main/java/com/sellerops/order");

    @Test
    @DisplayName("D1 — 주문 패키지가 저장하는 표는 자기 것뿐이다 (상품 카탈로그에 쓰지 않는다)")
    void itWritesOnlyItsOwnTables() throws IOException {
        // 주문 수집이 상품 카탈로그를 쓰기 시작하면 판매자가 등록하지 않은 상품이 주문에서 태어나고,
        // 그 상품은 아무 지식도 없이 반복 문제·개선 제안의 주소가 된다. 각 writer를 자기가 쓸 수 있는
        // 표현과 함께 이름으로 적고, 수가 맞는지 센다 — 이 산술이 설명하지 못하는 save는 네 번째 저장소다.
        Map<String, List<String>> allowed = Map.of(
                "ChannelOrderIngestionService.java", List.of("orders.save(", "statusEvents.save("),
                "ChannelOrderProductBinder.java", List.of("refs.save(", "refs.saveAll("),
                "OrderService.java", List.of("summaries.save(", "summaries.saveAll("));
        for (Path source : javaFiles()) {
            String text = code(source);
            String name = source.getFileName().toString();
            int saves = occurrences(text, ".save(") + occurrences(text, ".saveAll(");
            if (saves == 0) {
                continue;
            }
            assertThat(allowed).as("%s가 저장하는데 이름이 적혀 있지 않다", name).containsKey(name);
            int accounted = allowed.get(name).stream().mapToInt(e -> occurrences(text, e)).sum();
            assertThat(accounted)
                    .as("%s가 %s 아닌 것으로 저장한다 — 상품 카탈로그나 네 번째 표일 수 있다",
                            name, allowed.get(name))
                    .isEqualTo(saves);
        }
    }

    @Test
    @DisplayName("D1 — 상품을 이름으로 찾는 길이 없다")
    void nothingMatchesAProductByName() throws IOException {
        // 동명이인 상품이 canonical Demo Org에 실제로 있고, 이름은 판매자가 언제든 바꾸는 값이다.
        // ChannelProductRef가 문의 귀속에서 선언한 규칙을 이 패키지도 지킨다: 정확 일치가 아니면 무귀속.
        List<String> forbidden = List.of(
                "findByOrgIdAndName", "findByName", "ByProductName", "NameContaining", "NameLike",
                "ProductNameIgnoreCase", "名", "similarity(", "levenshtein");
        for (Path source : javaFiles()) {
            String text = code(source);
            for (String token : forbidden) {
                assertThat(text).as("%s — 이름 매칭은 남의 상품을 주문에 붙이는 길이다",
                        source.getFileName()).doesNotContain(token);
            }
        }
        // placeholder 상품을 만드는 길도 없다.
        for (Path source : javaFiles()) {
            String text = code(source);
            assertThat(text).as("%s — 주문에서 상품이 태어나지 않는다", source.getFileName())
                    .doesNotContain("new Product(").doesNotContain("new ChannelProduct(");
        }
    }

    @Test
    @DisplayName("D3 — 우리가 관측한 시각이 채널이 말한 시각 자리에 들어가지 않는다")
    void noObservationTimeIsPassedAsAChannelTime() throws IOException {
        // observedAt은 「채널이 이때 바꿨다」는 뜻이다. 그 자리에 recordedAt이나 Instant.now()를 적으면
        // 쿠팡 주문 180건이 전부 「채널이 우리 sync 시각에 상태를 바꿨다」고 말하게 되고, 그 시각으로
        // 「배송 완료 N일 뒤」를 계산하는 다음 arc는 자기 sync 간격을 배송 기간으로 읽는다.
        Pattern call = Pattern.compile("setObservedAt\\(([^)]*)\\)");
        List<String> allowedArguments = List.of("observedAt", "row.statusChangedAt()");
        int seen = 0;
        for (Path source : javaFiles()) {
            Matcher m = call.matcher(code(source));
            while (m.find()) {
                seen++;
                assertThat(m.group(1).trim())
                        .as("%s — 채널이 준 시각만 observedAt에 들어간다", source.getFileName())
                        .isIn(allowedArguments);
            }
        }
        assertThat(seen).as("관측 시각을 쓰는 자리가 사라졌다면 이 울타리는 아무것도 지키지 않는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("D3 — 상품 참조에는 채널이 준 시각이 하나도 없고, 이름이 그것을 말한다")
    void theProductReferenceHoldsNoChannelTime() throws IOException {
        // 이 표의 시각은 전부 우리의 것이다(bound_at, first_seen_at, last_seen_at). 그래서 채널의 시각으로
        // 읽힐 수 있는 이름을 아예 쓰지 않는다 — delivered_at 같은 컬럼 하나가 생기면 그 뒤로는 그 열이
        // 채널의 사실인지 우리의 관측인지 열 이름만으로 알 수 없다.
        String entity = code(PACKAGE.resolve("ChannelOrderProduct.java"));
        for (String token : List.of("deliveredAt", "delivered_at", "shippedAt", "shipped_at",
                "dispatchedAt", "dispatched_at", "statusChangedAt", "occurredAt")) {
            assertThat(entity).as("ChannelOrderProduct에 채널 시각으로 읽히는 이름이 생겼다")
                    .doesNotContain(token);
        }
        String migration = Files.readString(Paths.get(
                "src/main/resources/db/migration/V132__channel_order_product_ref.sql"));
        assertThat(migration).contains("bound_at", "first_seen_at", "last_seen_at");
        for (String token : List.of("delivered_at", "shipped_at", "dispatched_at", "status_changed_at")) {
            assertThat(migration).as("V132에 채널 시각 열이 생겼다").doesNotContain(token);
        }
    }

    @Test
    @DisplayName("provenance는 닫힌 어휘이고, 채널 하나에 출처 하나다")
    void theProvenanceVocabularyIsClosedAndChannelKeyed() {
        assertThat(ChannelProductRefSource.values()).isNotEmpty();
        List<String> channels = new ArrayList<>();
        for (ChannelProductRefSource source : ChannelProductRefSource.values()) {
            assertThat(source.channelCode()).as("%s — 채널 없는 출처", source).isNotBlank();
            assertThat(source.endpoint()).as("%s — endpoint 없는 출처", source).isNotBlank();
            assertThat(source.fieldPath())
                    .as("%s — 필드 경로가 없으면 「어디서 왔나」에 답하지 못한다", source)
                    .isNotBlank();
            assertThat(ChannelProductRefSource.forChannel(source.channelCode())).isEqualTo(source);
            channels.add(source.channelCode());
        }
        assertThat(channels)
                .as("한 채널이 두 출처를 가지면 forChannel이 조용히 하나를 고른다 — 그 날 호출자가 "
                        + "출처를 직접 건네는 쪽으로 바뀌어야 한다")
                .doesNotHaveDuplicates();
        assertThat(ChannelProductRefSource.forChannel("CAFE24"))
                .as("Cafe24 주문 수집은 상품 식별자를 투영하지 않는다 — channel_orders에 Cafe24 행은 0이다")
                .isNull();
        assertThat(ChannelProductRefSource.forChannel(null)).isNull();
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(PACKAGE)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** 주석을 지운 source — 자기 설명 때문에 실패하는 울타리는 고쳐지지 않고 지워진다. */
    private static String code(Path source) throws IOException {
        return Files.readString(source)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }
}
