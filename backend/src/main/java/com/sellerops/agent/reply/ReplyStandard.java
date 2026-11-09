package com.sellerops.agent.reply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * <b>The reply standard the agent is held to</b> — {@code contracts/reply-standard/v2} ({@link #IN_USE}; v2 adds U 사용 전 ·
 * 중립, 2026-11-01 fifth review — v1 stays for the drafts and grades written under it), read from the backend's byte-equal
 * copy ({@code ReplyStandardContractTest}). Every model step's instructions are rendered from this, never written beside
 * it, so the standard the owner decided is the one the model is shown.
 */
@Component
public class ReplyStandard {

    /** The standard version this backend holds drafts to; earlier versions stay beside it, unchanged. */
    public static final String IN_USE = "v2";


    public record Category(String code, String name, String signals, boolean needsResponse, boolean handoff,
                           List<String> skeleton, List<String> mustNot) {
    }

    public record Rule(String id, String kind, String text) {
    }

    private final String version;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final List<String> priority = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();

    public ReplyStandard() {
        JsonNode root;
        try (InputStream in = ReplyStandard.class.getResourceAsStream("/reply-standard/" + IN_USE + "/standard.json")) {
            if (in == null) {
                throw new IllegalStateException("reply-standard/" + IN_USE + "/standard.json is not on the classpath");
            }
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        version = root.path("version").asText();
        root.path("categories").forEach(c -> categories.put(c.path("code").asText(), new Category(
                c.path("code").asText(), c.path("name").asText(), c.path("signals").asText(),
                c.path("needsResponse").asBoolean(), c.path("handoff").asBoolean(), strings(c.path("skeleton")),
                strings(c.path("mustNot")))));
        root.path("priority").forEach(p -> priority.add(p.asText()));
        root.path("rules").forEach(r -> rules.add(new Rule(r.path("id").asText(), r.path("kind").asText(),
                r.path("text").asText())));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    public String version() {
        return version;
    }

    /** The categories in priority order. */
    public List<Category> categories() {
        return priority.stream().map(categories::get).toList();
    }

    public java.util.Optional<Category> category(String code) {
        return java.util.Optional.ofNullable(code == null ? null : categories.get(code.strip()));
    }

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    public boolean knowsRule(String id) {
        return rules.stream().anyMatch(r -> r.id().equals(id));
    }
}
