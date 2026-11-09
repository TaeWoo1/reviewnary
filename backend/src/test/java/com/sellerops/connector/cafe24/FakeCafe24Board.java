package com.sellerops.connector.cafe24;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * <b>A fake Cafe24 mall that answers by what it holds, not by a queue</b> (2026-11-08, open-inquiry recheck). One board-6,
 * articles with a creation time, a reply status and comments; the endpoints the connector uses — the token, the windowed
 * article list, the exact-number list, the {@code comment=T} discovery in both shapes, and an article's comments — answer
 * from that state. The seller answering late on the seller centre is one call ({@link #shopComments}), and the next read,
 * routine or recheck, sees it.
 *
 * <p>Every request is counted; {@link #failNext} makes the next matching requests answer with a status (a 500, a 429),
 * answer normally ({@code 0}), or throw as a dropped connection ({@link #TRANSPORT_FAILURE}). {@link #brokenComments}
 * holds articles whose comment read answers 500 every time, for as long as they are in it — one article's own lasting
 * trouble. {@link #beforeEachRequest}, if set, runs as each request arrives, before it is answered — a test can hold a run
 * there. Safe to call from several threads. Synthetic text only; no network.
 */
final class FakeCafe24Board implements Cafe24HttpClient {

    enum Kind { TOKEN, LIST, BY_NUMBER, DISCOVERY, COMMENTS }

    /** In a {@link #failNext} sequence: the request throws instead of answering, as a dropped connection does. */
    static final int TRANSPORT_FAILURE = -1;

    static final class Article {
        final long no;
        final String created;
        String replyStatus = "N";
        Long parent;
        final List<String[]> comments = new ArrayList<>(); // {member_id, created_date}

        Article(long no, String created) {
            this.no = no;
            this.created = created;
        }
    }

    private final String mallId;
    final Map<Long, Article> articles = new TreeMap<>();
    final List<Kind> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    /** Articles whose comment read answers 500, every time, while they are here. */
    final Set<Long> brokenComments = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Runs as each request arrives, before it is answered — on the calling thread. */
    volatile java.util.function.Consumer<Kind> beforeEachRequest;
    private final Map<Kind, Deque<Integer>> failures = new HashMap<>();
    /** Article numbers every list leaves out, as if the source no longer showed them. */
    final Set<Long> hidden = new java.util.HashSet<>();

    FakeCafe24Board(String mallId) {
        this.mallId = mallId;
    }

    Article post(long no, String createdDate) {
        Article a = new Article(no, createdDate);
        articles.put(no, a);
        return a;
    }

    /** The shop answers on the seller centre by comment, at {@code at}. */
    void shopComments(long no, String at) {
        articles.get(no).comments.add(new String[] {mallId, at});
    }

    void customerComments(long no, String at) {
        articles.get(no).comments.add(new String[] {"customer01", at});
    }

    synchronized void failNext(Kind kind, int... statuses) {
        Deque<Integer> q = failures.computeIfAbsent(kind, k -> new ArrayDeque<>());
        Arrays.stream(statuses).forEach(q::add);
    }

    long count(Kind kind) {
        synchronized (requests) {
            return requests.stream().filter(k -> k == kind).count();
        }
    }

    @Override
    public Response postForm(URI uri, Map<String, String> headers, Map<String, String> form) {
        return answer(Kind.TOKEN, () -> FakeCafe24HttpClient.tokenOk("access-" + requests.size(), "old-refresh-token"));
    }

    @Override
    public Response get(URI uri, Map<String, String> headers) {
        String path = uri.getPath();
        Map<String, String> q = query(uri);
        if (path.endsWith("/comments")) {
            long no = Long.parseLong(path.replaceAll(".*/articles/(\\d+)/comments$", "$1"));
            return answer(Kind.COMMENTS, () -> brokenComments.contains(no) ? new Response(500, "{}", Map.of())
                    : comments(no));
        }
        if (q.containsKey("article_no")) {
            List<Long> named = Arrays.stream(q.get("article_no").split(",")).map(Long::parseLong).toList();
            boolean discovery = "T".equals(q.get("comment"));
            return answer(discovery ? Kind.DISCOVERY : Kind.BY_NUMBER, () -> list(named.stream()
                    .map(articles::get).filter(a -> a != null && !hidden.contains(a.no))
                    .filter(a -> !discovery || !a.comments.isEmpty()).toList()));
        }
        LocalDate start = LocalDate.parse(q.get("start_date"));
        LocalDate end = LocalDate.parse(q.get("end_date"));
        int limit = Integer.parseInt(q.getOrDefault("limit", "100"));
        boolean discovery = "T".equals(q.get("comment"));
        int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
        List<Article> inWindow = articles.values().stream().filter(a -> !hidden.contains(a.no)).filter(a -> {
            LocalDate d = OffsetDateTime.parse(a.created).toLocalDate();
            return !d.isBefore(start) && !d.isAfter(end);
        }).filter(a -> !discovery || !a.comments.isEmpty()).toList();
        List<Article> page = inWindow.subList(Math.min(offset, inWindow.size()), Math.min(offset + limit, inWindow.size()));
        return answer(discovery ? Kind.DISCOVERY : Kind.LIST, () -> list(page));
    }

    private Response answer(Kind kind, java.util.function.Supplier<Response> ok) {
        java.util.function.Consumer<Kind> hook = beforeEachRequest;
        if (hook != null) {
            hook.accept(kind);
        }
        requests.add(kind);
        Integer next;
        synchronized (this) {
            Deque<Integer> q = failures.get(kind);
            next = q == null ? null : q.poll();
        }
        if (next != null) {
            int status = next;
            if (status == 0) {
                return ok.get(); // 0 in a failure sequence: this one answers normally
            }
            if (status == TRANSPORT_FAILURE) {
                // No answer at all, as a broken connection: not a status, and not of any known kind.
                throw new java.io.UncheckedIOException(new java.io.IOException("합성 연결 끊김"));
            }
            return new Response(status, "{}", status == 429 ? Map.of("X-Cafe24-Call-Remain", "1") : Map.of());
        }
        return ok.get();
    }

    private Response list(List<Article> rows) {
        String body = rows.stream().map(a -> {
            String json = FakeCafe24HttpClient.article(a.no, "합성 제목", "합성 본문", null, null, a.created, a.replyStatus);
            return a.parent == null ? json
                    : json.substring(0, json.length() - 1) + ",\"parent_article_no\":" + a.parent + ",\"reply_depth\":1}";
        }).collect(Collectors.joining(","));
        return new Response(200, "{\"articles\":[" + body + "]}", Map.of());
    }

    private Response comments(long no) {
        Article a = articles.get(no);
        if (a == null) {
            return new Response(404, "{}", Map.of());
        }
        List<String> rows = new ArrayList<>();
        int i = 0;
        for (String[] c : a.comments) {
            rows.add("{\"comment_no\":" + (++i) + ",\"article_no\":" + no + ",\"created_date\":\"" + c[1]
                    + "\",\"member_id\":\"" + c[0] + "\",\"content\":\"합성 댓글\"}");
        }
        return new Response(200, "{\"comments\":[" + String.join(",", rows) + "]}", Map.of());
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
