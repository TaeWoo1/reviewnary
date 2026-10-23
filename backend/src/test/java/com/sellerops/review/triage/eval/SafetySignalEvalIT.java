package com.sellerops.review.triage.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sellerops.common.ReviewIdFingerprint;
import com.sellerops.review.triage.eval.SafetySample.FrameRow;
import com.sellerops.review.triage.eval.SafetySample.Label;
import com.sellerops.review.triage.eval.SafetySample.Labelled;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>The safety evaluation set, end to end</b> — {@code contracts/review-eval/safety/v1/RUBRIC.md} §6. Three gated,
 * local runs, never in CI:
 *
 * <ol>
 *   <li>{@code RUN_SAFETY_SHEET=true} writes the labelling sheet — fingerprint, rating, the review text and an empty
 *       label column — to {@code SAFETY_SHEET_PATH}, which must lie <b>outside this repository</b>. The text is for
 *       the person labelling; nothing here prints it.</li>
 *   <li>{@code RUN_SAFETY_IMPORT=true} reads the filled sheet and writes {@code labels.json} with the fingerprint and
 *       the label only.</li>
 *   <li>{@code RUN_SAFETY_EVAL=true} prints counts and rates, and a verdict only above the adequacy floor.</li>
 * </ol>
 *
 * <p>The database runs read {@code REVIEW_EVAL_JDBC_URL} · {@code REVIEW_EVAL_DB_USER} · {@code
 * REVIEW_EVAL_DB_PASSWORD} on a read-only connection, as {@code ReviewTriageEvalIT} does.
 */
class SafetySignalEvalIT {

    private static final Path DIR = Path.of("..", "contracts", "review-eval", "safety", "v1");
    private static final Path LABELS = DIR.resolve("labels.json");
    /**
     * The sheet: who labelled (OWNER · DELEGATE) and whether the owner checked it are columns, because the owner sets
     * the criteria on a first batch and then checks the unsure ones and a sample of the delegate's (RUBRIC §3). There
     * is deliberately no suggested-label column — a model's guess is never the answer.
     */
    private static final String HEADER = "reviewIdFingerprint,rating,set,label,labeller,ownerChecked,review";

    /** The labeller roles RUBRIC §3 names. */
    private static final java.util.Set<String> LABELLERS = java.util.Set.of("OWNER", "DELEGATE");

    private record Row(FrameRow frame, String body) {}

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SAFETY_SHEET", matches = "true")
    void writeTheLabellingSheetOutsideTheRepository() throws Exception {
        Path sheet = sheetPath();
        Map<String, String> bodies = new HashMap<>();
        List<FrameRow> frame = new ArrayList<>();
        for (Row row : readFrame()) {
            frame.add(row.frame());
            bodies.put(row.frame().fingerprint(), row.body());
        }
        // One row per review, in either set or both; the order within the sheet is the sample order, so the two sets
        // interleave and the labeller does not read all the harm words in a row.
        Map<String, String> sets = new LinkedHashMap<>();
        SafetySample.randomSet(frame).forEach(r -> sets.put(r.fingerprint(), "RANDOM"));
        SafetySample.challengeSet(frame).forEach(r -> sets.merge(r.fingerprint(), "CHALLENGE", (a, b) -> "BOTH"));
        Map<String, FrameRow> byId = new HashMap<>();
        frame.forEach(r -> byId.put(r.fingerprint(), r));
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        sets.keySet().stream().sorted(java.util.Comparator.comparing(CalibrationSample::sampleOrderKey)).forEach(id -> {
            FrameRow r = byId.get(id);
            csv.append(String.join(",", id, r.rating() == null ? "" : r.rating().toString(), sets.get(id), "", "", "",
                    quote(bodies.get(id)))).append('\n');
        });
        Files.writeString(sheet, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("safety sheet: " + sets.size() + " rows written");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SAFETY_IMPORT", matches = "true")
    void importTheFilledSheetAsFingerprintsAndLabelsOnly() throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(LABELS));
        ArrayNode labels = root.putArray("labels");
        int kept = 0;
        for (List<String> cells : parse(Files.readString(sheetPath(), StandardCharsets.UTF_8))) {
            if (cells.isEmpty() || cells.get(0).equals("reviewIdFingerprint") || cells.size() < 6
                    || cells.get(3).isBlank()) {
                continue;
            }
            Label label = Label.valueOf(cells.get(3).strip());
            String labeller = cells.get(4).strip();
            assertThat(cells.get(0)).as("a fingerprint, never a raw id").matches("[0-9a-f]{64}");
            assertThat(LABELLERS).as("who labelled it, by role").contains(labeller);
            labels.addObject().put("reviewIdFingerprint", cells.get(0)).put("label", label.name())
                    .put("labeller", labeller).put("ownerChecked", "Y".equalsIgnoreCase(cells.get(5).strip()));
            kept++;
        }
        Files.writeString(LABELS, json.writeValueAsString(root) + "\n");
        System.out.println("safety labels: " + kept + " imported");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SAFETY_EVAL", matches = "true")
    void measureTheSafetySignalAgainstTheLabels() throws Exception {
        Map<String, Label> labels = new HashMap<>();
        for (JsonNode entry : new ObjectMapper().readTree(Files.readString(LABELS)).path("labels")) {
            labels.put(entry.path("reviewIdFingerprint").asText(), Label.valueOf(entry.path("label").asText()));
        }
        List<FrameRow> frame = new ArrayList<>();
        List<Labelled> labelled = new ArrayList<>();
        int byDelegate = 0;
        int ownerChecked = 0;
        for (JsonNode entry : new ObjectMapper().readTree(Files.readString(LABELS)).path("labels")) {
            byDelegate += "DELEGATE".equals(entry.path("labeller").asText()) ? 1 : 0;
            ownerChecked += entry.path("ownerChecked").asBoolean() ? 1 : 0;
        }
        System.out.println("  labelled by delegate " + byDelegate + " · checked by owner " + ownerChecked);
        for (Row row : readFrame()) {
            frame.add(row.frame());
            Label label = labels.get(row.frame().fingerprint());
            if (label != null) {
                labelled.add(new Labelled(row.frame(), label));
            }
        }
        System.out.print(SafetySample.render(SafetySample.report(frame, labelled)));
    }

    private static Path sheetPath() {
        Path sheet = Path.of(requireEnv("SAFETY_SHEET_PATH")).toAbsolutePath().normalize();
        Path repo = Path.of("..").toAbsolutePath().normalize();
        if (sheet.startsWith(repo)) {
            throw new IllegalStateException("SAFETY_SHEET_PATH must be outside the repository: it holds review text");
        }
        return sheet;
    }

    private static List<Row> readFrame() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Connection db = DriverManager.getConnection(requireEnv("REVIEW_EVAL_JDBC_URL"),
                requireEnv("REVIEW_EVAL_DB_USER"), System.getenv("REVIEW_EVAL_DB_PASSWORD"));
             PreparedStatement ps = db.prepareStatement("select r.external_id, r.body, r.rating from reviews r "
                     + "join channels c on c.id = r.channel_id where c.code = 'NAVER' and r.external_id is not null")) {
            db.setReadOnly(true);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fingerprint = ReviewIdFingerprint.of(rs.getString("external_id"));
                    String body = rs.getString("body");
                    if (fingerprint == null || body == null) {
                        continue;
                    }
                    Integer rating = rs.getObject("rating") == null ? null : rs.getInt("rating");
                    rows.add(new Row(SafetySample.row(fingerprint, rating, body), body));
                }
            }
        }
        return rows;
    }

    static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    /** RFC 4180: quoted fields may hold commas, quotes (doubled) and line breaks. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return value;
    }

}
