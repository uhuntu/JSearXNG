/*
 * JSearXNG — a real metasearcher, in Java, as the J always said.
 *
 * The name is the pedigree: JSearch, the 2002 applet archived next door, fanned
 * one query out to several engines, scraped the HTML, and deduplicated by URL.
 * This does the same against a live 2026 web — its own engines plus the local
 * SearXNG instance, whose engine pool sits behind one JSON call. The 2002 applet
 * UI is reborn as the face: 查询内容/结果/>>预览/>>消息, and smsCh/smlCh are
 * real controls again.
 *
 * Single file, JDK standard library only (java.net.http, com.sun.net.httpserver,
 * java.util.concurrent). The Json reader below is copied from the neighbouring
 * teaching sketch (JSearch/modern/src/main/java/jsearch/Json.java).
 *
 * Run:   java JSearXNG.java [--port 8092]
 * Open:  http://127.0.0.1:8092/
 */

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JSearXNG {

    static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(6);
    static final int RESULTS_PER_ENGINE = 8;

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(FETCH_TIMEOUT)
            .build();

    record Result(String title, String url, String snippet, String engine) {}
    record EngineDef(String name, String kind, SearchFn fn) {}
    interface SearchFn { List<Result> run(String query) throws Exception; }
    record Outcome(String name, List<Result> hits, String error, long ms) {}
    record SearchData(List<Result> results, List<Outcome> engines,
                      long durationMs, int duplicatesDropped) {}

    record EngineEntry(String name, String kind, SearchFn fn) {}

    static final List<EngineEntry> ENGINES = List.of(
            new EngineEntry("bing", "web", JSearXNG::searchBing),
            new EngineEntry("searxng", "metasearch", JSearXNG::searchSearxng),
            new EngineEntry("marginalia", "web", JSearXNG::searchMarginalia),
            new EngineEntry("wikipedia", "reference", JSearXNG::searchWikipedia),
            new EngineEntry("stackexchange", "q&a", JSearXNG::searchStackexchange),
            new EngineEntry("hn", "discussions", JSearXNG::searchHn));

    // ------------------------------------------------------------------
    // fetch
    // ------------------------------------------------------------------

    static String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .body();
    }

    static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Engines. One engine failing is data, not a crash — the 2002 rule.
    // ------------------------------------------------------------------

    static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", "");
    }

    static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&#160;", " ").replace("&#183;", "·");
    }

    static String unwrapBing(String href) {
        // bing.com/ck/a?...&u=a1<base64url> → the real destination URL
        Matcher m = Pattern.compile("[?&]u=a1([A-Za-z0-9_-]+)").matcher(href);
        if (m.find()) {
            try {
                String b64 = m.group(1);
                byte[] decoded = Base64.getUrlDecoder().decode(
                        b64 + "=".repeat((4 - b64.length() % 4) % 4));
                String real = new String(decoded, StandardCharsets.UTF_8);
                if (real.startsWith("http")) return real;
            } catch (IllegalArgumentException ignored) {
                // fall through to the tracking URL
            }
        }
        return href;
    }

    static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*?)>");
    static final Pattern CLASS_ATTR = Pattern.compile("class\\s*=\\s*\"([^\"]*)\"");

    /**
     * Port of the Python _BingParser state machine: walk result blocks
     * (li/div with class b_algo); the title anchor is the first http <a> inside
     * the block's <h2> (falling back to the block's first http anchor); the
     * snippet is the first <p>. hrefs are unwrapped from the tracking redirect.
     */
    static List<Result> searchBing(String query) throws Exception {
        String html = fetch("https://www.bing.com/search?q=" + urlEncode(query));
        List<Result> out = new ArrayList<>();
        String blockTag = null, capture = null, href = null, title = null, snip = "";
        int depth = 0, inH2 = 0;
        StringBuilder buf = new StringBuilder();
        Matcher m = TAG.matcher(html);
        int textStart = 0;
        while (m.find()) {
            if (capture != null && m.start() > textStart) {
                buf.append(html, textStart, m.start());
            }
            textStart = m.end();
            boolean closing = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase();
            String attrs = m.group(3);
            Matcher cls = CLASS_ATTR.matcher(attrs);
            String klass = cls.find() ? cls.group(1) : "";

            if (depth == 0) {
                if (!closing && klass.contains("b_algo")) {
                    depth = 1; blockTag = tag; inH2 = 0; capture = null;
                    buf.setLength(0); href = null; title = null; snip = "";
                }
                continue;
            }
            if (tag.equals(blockTag)) {
                if (!closing) {
                    depth++;
                } else {
                    depth--;
                    if (depth == 0 && href != null && title != null) {
                        out.add(new Result(title, unwrapBing(href), snip, "bing"));
                    }
                }
            } else if (!closing && tag.equals("h2")) {
                inH2++;
            } else if (closing && tag.equals("h2") && inH2 > 0) {
                inH2--;
            } else if (!closing && tag.equals("a")) {
                Matcher hrefAttr = Pattern.compile("href\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
                if (hrefAttr.find()) {
                    // attribute values are &-escaped in the raw HTML; the Python
                    // parser unescaped them for us, here we do it by hand
                    String h = unescape(hrefAttr.group(1));
                    // the title lives in the block's <h2>; any earlier http anchor
                    // (Bing puts the site name in one) is only a fallback
                    if (h.startsWith("http") && (inH2 > 0 || href == null)) {
                        href = h;
                        capture = "title";
                        buf.setLength(0);
                    }
                }
            } else if (!closing && tag.equals("p") && !"title".equals(capture) && snip.isEmpty()) {
                capture = "snippet";
                buf.setLength(0);
            } else if (closing && tag.equals("a") && "title".equals(capture)) {
                title = unescape(buf.toString().trim());
                capture = null;
            } else if (closing && tag.equals("p") && "snippet".equals(capture)) {
                snip = unescape(buf.toString());
                capture = null;
            }
        }
        return out.subList(0, Math.min(out.size(), RESULTS_PER_ENGINE));
    }

    static List<Result> searchSearxng(String query) throws Exception {
        // The local SearXNG instance — a metasearcher as an engine. Each hit
        // carries the upstream engine that produced it, shown in the badge.
        Map<?, ?> data = (Map<?, ?>) Json.parse(fetch(
                "http://127.0.0.1:8888/search?q=" + urlEncode(query) + "&format=json"));
        List<Result> out = new ArrayList<>();
        for (Object o : (List<?>) get(data, "results", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) o;
            String upstream = String.valueOf(get(hit, "engine", "?"));
            out.add(new Result(str(hit.get("title"), "(untitled)"),
                    str(hit.get("url"), ""), str(hit.get("content"), ""),
                    "searxng·" + upstream));
        }
        return out.subList(0, Math.min(out.size(), RESULTS_PER_ENGINE));
    }

    static List<Result> searchMarginalia(String query) throws Exception {
        Map<?, ?> data = (Map<?, ?>) Json.parse(fetch(
                "https://api.marginalia.nu/public/search/" + urlEncode(query) + "/"));
        List<Result> out = new ArrayList<>();
        for (Object o : (List<?>) get(data, "results", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) o;
            out.add(new Result(str(hit.get("title"), "(untitled)"),
                    str(hit.get("url"), ""), str(hit.get("description"), ""),
                    "marginalia"));
        }
        return out.subList(0, Math.min(out.size(), RESULTS_PER_ENGINE));
    }

    static List<Result> searchWikipedia(String query) throws Exception {
        Map<?, ?> data = (Map<?, ?>) Json.parse(fetch(
                "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json"
                        + "&srlimit=" + RESULTS_PER_ENGINE + "&srsearch=" + urlEncode(query)));
        Map<?, ?> q = (Map<?, ?>) get(data, "query", Map.of());
        List<Result> out = new ArrayList<>();
        for (Object o : (List<?>) get(q, "search", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) o;
            String title = str(hit.get("title"), "");
            String url = "https://en.wikipedia.org/wiki/"
                    + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
            out.add(new Result(title, url, stripTags(str(hit.get("snippet"), "")), "wikipedia"));
        }
        return out;
    }

    static List<Result> searchStackexchange(String query) throws Exception {
        Map<?, ?> data = (Map<?, ?>) Json.parse(fetch(
                "https://api.stackexchange.com/2.3/search/advanced?site=stackoverflow"
                        + "&pagesize=" + RESULTS_PER_ENGINE + "&q=" + urlEncode(query)));
        List<Result> out = new ArrayList<>();
        for (Object o : (List<?>) get(data, "items", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) o;
            Object tags = hit.get("tags");
            StringBuilder tagList = new StringBuilder();
            if (tags instanceof List<?> list) {
                for (int i = 0; i < Math.min(list.size(), 4); i++) {
                    if (i > 0) tagList.append(", ");
                    tagList.append(list.get(i));
                }
            }
            String snippet = intOf(hit.get("answer_count")) + " answers · "
                    + intOf(hit.get("score")) + " votes · " + tagList;
            out.add(new Result(unescape(str(hit.get("title"), "(untitled)")),
                    str(hit.get("link"), ""), snippet, "stackexchange"));
        }
        return out;
    }

    static List<Result> searchHn(String query) throws Exception {
        Map<?, ?> data = (Map<?, ?>) Json.parse(fetch(
                "https://hn.algolia.com/api/v1/search?hitsPerPage=" + RESULTS_PER_ENGINE
                        + "&query=" + urlEncode(query)));
        List<Result> out = new ArrayList<>();
        for (Object o : (List<?>) get(data, "hits", List.of())) {
            Map<?, ?> hit = (Map<?, ?>) o;
            String title = str(hit.get("title"), str(hit.get("story_title"), "(untitled)"));
            String url = str(hit.get("url"), "");
            if (url.isEmpty()) {
                url = "https://news.ycombinator.com/item?id=" + str(hit.get("objectID"), "");
            }
            String snippet = intOf(hit.get("points")) + " points · "
                    + intOf(hit.get("num_comments")) + " comments · "
                    + str(hit.get("author"), "?");
            out.add(new Result(title, url, snippet, "hn"));
        }
        return out;
    }

    static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    /** getOrDefault is unusable on a wildcard map; this is its stand-in. */
    static Object get(Map<?, ?> map, String key, Object fallback) {
        Object v = map.get(key);
        return v == null ? fallback : v;
    }

    static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    // ------------------------------------------------------------------
    // Fan-out, sanity, dedup, aggregate
    // ------------------------------------------------------------------

    /**
     * Poisoned anti-bot responses parse fine but are about nothing — Bing
     * serves random popular pages instead of an error. If no result mentions
     * any significant query term, treat the response as a block page.
     */
    static boolean sanityOk(String query, List<Result> hits) {
        List<String> tokens = new ArrayList<>();
        for (String t : query.toLowerCase().split("\\W+")) {
            if (t.length() > 2) tokens.add(t);
        }
        if (tokens.isEmpty() || hits.isEmpty()) return true;
        for (Result r : hits) {
            String blob = (r.title() + " " + r.snippet() + " " + r.url()).toLowerCase();
            for (String t : tokens) {
                if (blob.contains(t)) return true;
            }
        }
        return false;
    }

    static String normalize(String url) {
        String u = url.split("#", 2)[0];
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    static SearchData runSearch(String query, Set<String> engineSel, Integer speed, Integer levels) {
        long t0 = System.currentTimeMillis();
        List<EngineEntry> chosen = new ArrayList<>();
        for (EngineEntry e : ENGINES) {
            if (engineSel == null || engineSel.isEmpty() || engineSel.contains(e.name())) {
                chosen.add(e);
            }
        }
        if (chosen.isEmpty()) chosen = ENGINES;
        int depth = Math.max(1, Math.min(levels == null ? RESULTS_PER_ENGINE : levels, 16));
        int workers = Math.max(1, Math.min(speed == null ? chosen.size() : speed, 8));

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (EngineEntry entry : chosen) {
            futures.add(pool.submit(() -> {
                long start = System.currentTimeMillis();
                try {
                    List<Result> hits = entry.fn().run(query);
                    hits = hits.subList(0, Math.min(hits.size(), depth)); // smlCh
                    if (!sanityOk(query, hits)) {
                        return new Outcome(entry.name(), List.of(),
                                "response failed the relevance check (query terms absent) — likely a bot-block page",
                                System.currentTimeMillis() - start);
                    }
                    return new Outcome(entry.name(), hits, null, System.currentTimeMillis() - start);
                } catch (Exception exc) {
                    return new Outcome(entry.name(), List.of(),
                            exc.getClass().getSimpleName() + ": " + exc.getMessage(),
                            System.currentTimeMillis() - start);
                }
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            try {
                outcomes.add(f.get());
            } catch (Exception e) {
                outcomes.add(new Outcome("?", List.of(), e.toString(), 0));
            }
        }
        pool.shutdown();

        // smsCh bounded the pool above; here the 2002 URL-dedup, across layers
        Set<String> seen = new HashSet<>();
        List<Result> results = new ArrayList<>();
        int raw = 0;
        for (Outcome o : outcomes) {
            raw += o.hits().size();
            for (Result r : o.hits()) {
                if (seen.add(normalize(r.url()))) {
                    results.add(r);
                }
            }
        }
        return new SearchData(results, outcomes, System.currentTimeMillis() - t0, raw - results.size());
    }

    // ------------------------------------------------------------------
    // Web UI — the 2002 applet's face, server-rendered
    // ------------------------------------------------------------------

    static final String TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>JSearXNG · ${query}</title>
<style>
  body { margin:0; padding:10px; background:#3a6ea5; color:#000;
         font:13px/1.4 Tahoma, Verdana, sans-serif; }
  .frame { border:2px solid #fff; outline:1px solid #0b2545; background:#7ba7d7; padding:6px; }
  .tabbar { display:flex; gap:2px; margin-bottom:4px; }
  .tab { background:#d6e4f5; border:1px solid #0b2545; border-bottom:none;
         padding:2px 16px; font-weight:bold; color:#0b3d6e; }
  .cols { display:flex; gap:6px; align-items:flex-start; }
  .left { flex:1 1 auto; min-width:0; }
  .right { flex:0 0 210px; }
  .panel { background:#7fffff; border:2px solid #0b3d6e; padding:6px; margin-bottom:6px; }
  .panel h3 { margin:-6px -6px 6px; background:#9fd0ff; border-bottom:1px solid #0b3d6e;
              padding:2px 6px; font-size:13px; color:#0b3d6e; }
  .querybox { width:99%; font:13px Tahoma, sans-serif; padding:2px 4px;
              border:2px inset #7ba7d7; background:#fff; }
  .btn { font:13px Tahoma, sans-serif; padding:2px 14px; margin:6px 4px 0 0;
         background:#d6e4f5; border:2px outset #d6e4f5; cursor:pointer; color:#000;
         text-decoration:none; display:inline-block; }
  .btn[disabled] { color:#666; border-style:inset; cursor:default; }
  .hint { color:#123a6e; font-size:12px; margin:4px 0 0; }
  .listbox { background:#fff; border:2px inset #7ba7d7; height:230px; overflow:auto;
             padding:2px; font:12px/1.8 "Courier New", monospace; white-space:nowrap; }
  .listbox a { color:#000; text-decoration:none; }
  .listbox a:hover { background:#0b3d6e; color:#fff; }
  .smalltabs { display:flex; gap:2px; margin:6px 0 3px; }
  .smalltab { background:#9fd0ff; border:1px solid #0b3d6e; border-bottom:none;
              padding:1px 8px; font-size:12px; font-weight:bold; color:#0b3d6e; }
  .prevbox { background:#7fffff; border:2px solid #0b3d6e; padding:6px;
             font:12px/1.5 Tahoma, sans-serif; max-height:8em; overflow:auto; }
  .msgbox { background:#fff; border:2px inset #7ba7d7; padding:4px 6px;
            font:12px/1.6 "Courier New", monospace; white-space:pre-wrap; }
  .total { font-size:12px; color:#0b3d6e; margin:2px 0; }
  .cat { list-style:none; margin:0; padding:2px 4px; background:#fff;
         border:2px inset #7ba7d7; font-size:12px; }
  .cblist { background:#fff; border:2px inset #7ba7d7; padding:3px 5px; font-size:12px; }
  .cblist label { display:block; padding:1px 0; }
  select { font:12px Tahoma, sans-serif; width:190px; border:2px inset #7ba7d7; }
  .opt { font-size:12px; }
  .about { font-size:11px; color:#123a6e; line-height:1.5; }
</style></head><body>
<div class="frame">
  <div class="tabbar"><span class="tab">搜索</span></div>
  <form action="/" method="get"><div class="cols">
    <div class="left">
      <div class="panel">
        <h3>查询内容</h3>
        <input class="querybox" type="search" name="q" value="${query}" autofocus>
        <div class="hint">搜索条件依所选搜索引擎而定。</div>
        <button class="btn" type="submit">开始搜索</button><a class="btn" disabled title="服务器一次一查，无可停止">停止搜索</a><a class="btn" href="/">新建搜索</a>
      </div>
      <div class="panel">
        <h3>结果</h3>
        <div class="total">总计: ${total}</div>
        <div class="listbox">${rows}</div>
      </div>
      <div class="smalltabs"><span class="smalltab">&gt;&gt;预览</span></div>
      <div class="prevbox">${preview}</div>
      <div class="smalltabs"><span class="smalltab">&gt;&gt;消息</span></div>
      <div class="msgbox">${message}</div>
    </div>
    <div class="right">
      <div class="panel">
        <h3>引擎</h3>
        <div class="smalltabs" style="margin:0 0 3px"><span class="smalltab">类别</span></div>
        ${kinds}
        <div class="smalltabs" style="margin:6px 0 3px"><span class="smalltab">搜索引擎</span></div>
        <div class="cblist">${engine_checks}</div>
      </div>
      <div class="panel">
        <h3>选项</h3>
        <div class="opt">搜索最大速度 smsCh</div>
        <select name="speed">${speed_opts}</select>
        <div class="opt" style="margin-top:4px">搜索最大层级 smlCh</div>
        <select name="levels">${level_opts}</select>
      </div>
      <div class="panel about">
        <h3>关于</h3>
        J·SearXNG — JSearch 的骨架（2002：扇出、抓取、按 URL 去重、每个引擎如实报告），
        SearXNG 的引擎池。无缓存、无追踪、无广告，单文件 Java 零依赖；每次查询每引擎一个请求。
        smsCh 限并发连接数，smlCh 限单引擎深度——和 2002 年同名参数一个意思。
      </div>
    </div>
  </div></form>
</div>
</body></html>""";

    /** The 2002 result-row columns: pad then clip, like formatString(). */
    static String fmt(String s, int n) {
        String t = s == null ? "" : s;
        return esc(t.substring(0, Math.min(t.length(), n)));
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String jsonEsc(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    record Form(Set<String> engines, int speed, int levels) {}

    static String render(SearchData data, Form form, String query) {
        Set<String> chosen = form.engines();
        StringBuilder checks = new StringBuilder();
        for (EngineEntry e : ENGINES) {
            checks.append("<label><input type='checkbox' name='engines' value='")
                  .append(e.name()).append("'")
                  .append(chosen.isEmpty() || chosen.contains(e.name()) ? " checked" : "")
                  .append("> ").append(e.name()).append("</label>");
        }
        Set<String> kinds = new TreeSet<>();
        for (EngineEntry e : ENGINES) kinds.add(e.kind());
        StringBuilder kindList = new StringBuilder("<ul class='cat'>");
        for (String k : kinds) kindList.append("<li>").append(esc(k)).append("</li>");
        kindList.append("</ul>");

        StringBuilder speedOpts = new StringBuilder();
        for (int v = 1; v <= 8; v++) {
            speedOpts.append("<option").append(v == form.speed() ? " selected" : "")
                     .append(">").append(v).append("</option>");
        }
        StringBuilder levelOpts = new StringBuilder();
        for (int v = 1; v <= 16; v++) {
            levelOpts.append("<option").append(v == form.levels() ? " selected" : "")
                     .append(">").append(v).append("</option>");
        }

        String rows = "", total = "0", preview;
        if (!data.results().isEmpty()) {
            StringBuilder r = new StringBuilder();
            for (Result res : data.results()) {
                r.append("<div><a href='").append(esc(res.url()))
                 .append("' rel='noopener'>").append(fmt(res.title(), 42))
                 .append("</a> | ").append(fmt(res.url(), 40))
                 .append(" | from: ").append(esc(res.engine())).append("</div>");
            }
            rows = r.toString();
            total = String.valueOf(data.results().size());
            Result top = data.results().get(0);
            String snip = top.snippet() == null || top.snippet().isEmpty()
                    ? "(无摘要)" : esc(top.snippet());
            preview = "<b>预览一：" + esc(top.title()) + "</b><br>" + snip
                    + "<br><span style='color:#123a6e'>" + esc(top.url()) + "</span>";
        } else {
            preview = query.isEmpty()
                    ? "输入查询，按“开始搜索”。"
                    : "（无结果可供预览——见 >>消息）";
        }

        String message;
        if (!data.engines().isEmpty()) {
            StringBuilder lines = new StringBuilder();
            for (Outcome o : data.engines()) {
                lines.append("[").append(o.name()).append("] ");
                if (o.error() != null) lines.append(o.error());
                else lines.append(o.hits().size()).append(" results, ").append(o.ms()).append(" ms");
                lines.append("\n");
            }
            lines.append("[jsearxng] 共 ").append(data.results().size())
                 .append(" 条结果，去重丢弃 ").append(data.duplicatesDropped())
                 .append(" 条，耗时 ").append(data.durationMs()).append(" ms");
            message = esc(lines.toString());
        } else {
            message = "[jsearxng] 准备就绪。六个引擎整装待发：bing 会投毒，我们体检；"
                    + "其余走官方 API。勾选引擎、调好 smsCh/smlCh，按“开始搜索”。";
        }

        return TEMPLATE
                .replace("${query}", esc(query))
                .replace("${total}", total)
                .replace("${rows}", rows)
                .replace("${preview}", preview)
                .replace("${message}", message)
                .replace("${kinds}", kindList.toString())
                .replace("${engine_checks}", checks.toString())
                .replace("${speed_opts}", speedOpts.toString())
                .replace("${level_opts}", levelOpts.toString());
    }

    // ------------------------------------------------------------------
    // HTTP server
    // ------------------------------------------------------------------

    static Map<String, String> parseParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    static void send(com.sun.net.httpserver.HttpExchange exchange, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public static void main(String[] args) throws IOException {
        int port = 8092;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                Map<String, String> params =
                        parseParams(exchange.getRequestURI().getRawQuery());
                String query = params.getOrDefault("q", "").trim();
                Set<String> engines = new HashSet<>();
                for (String e : params.getOrDefault("engines", "").split(",")) {
                    if (!e.isBlank()) engines.add(e.trim());
                }
                Integer speed = params.get("speed") == null ? null : Integer.parseInt(params.get("speed"));
                Integer levels = params.get("levels") == null ? null : Integer.parseInt(params.get("levels"));

                if (exchange.getRequestURI().getPath().equals("/api/search")) {
                    if (query.isEmpty()) {
                        send(exchange, "application/json; charset=utf-8",
                                "{\"error\":\"missing q\"}".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    SearchData d = runSearch(query, engines, speed, levels);
                    StringBuilder json = new StringBuilder("{\"query\":\"")
                            .append(jsonEsc(query)).append("\",\"results\":[");
                    for (Result r : d.results()) {
                        json.append("{\"title\":\"").append(jsonEsc(r.title()))
                            .append("\",\"url\":\"").append(jsonEsc(r.url()))
                            .append("\",\"snippet\":\"").append(jsonEsc(r.snippet()))
                            .append("\",\"engine\":\"").append(jsonEsc(r.engine())).append("\"},");
                    }
                    if (!d.results().isEmpty()) json.setLength(json.length() - 1);
                    json.append("],\"engines\":[");
                    for (Outcome o : d.engines()) {
                        json.append("{\"engine\":\"").append(jsonEsc(o.name()))
                            .append("\",\"count\":").append(o.hits().size())
                            .append(",\"error\":")
                            .append(o.error() == null ? "null" : "\"" + jsonEsc(o.error()) + "\"")
                            .append(",\"ms\":").append(o.ms()).append("},");
                    }
                    if (!d.engines().isEmpty()) json.setLength(json.length() - 1);
                    json.append("],\"durationMs\":").append(d.durationMs())
                        .append(",\"duplicatesDropped\":").append(d.duplicatesDropped()).append("}");
                    send(exchange, "application/json; charset=utf-8",
                            json.toString().getBytes(StandardCharsets.UTF_8));
                    return;
                }

                SearchData d = query.isEmpty()
                        ? new SearchData(List.of(), List.of(), 0, 0)
                        : runSearch(query, engines, speed, levels);
                Form form = new Form(engines,
                        speed == null ? 4 : Math.max(1, Math.min(speed, 8)),
                        levels == null ? 8 : Math.max(1, Math.min(levels, 16)));
                send(exchange, "text/html; charset=utf-8",
                        render(d, form, query).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                byte[] msg = ("error: " + e).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(500, msg.length);
                    exchange.getResponseBody().write(msg);
                } catch (IOException ignored) {
                    // client gone
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        StringBuilder names = new StringBuilder();
        for (EngineEntry e : ENGINES) {
            if (names.length() > 0) names.append(" + ");
            names.append(e.name());
        }
        System.out.println("JSearXNG on http://127.0.0.1:" + port + "/  (" + names + ", live)");
    }
}

/**
 * A minimal JSON reader, so the project stays dependency-free. Objects become
 * {@code Map<String,Object>}, arrays {@code List<Object>}, numbers
 * {@code Double}, and {@code null} stays {@code null}.
 * Copied from JSearch/modern/src/main/java/jsearch/Json.java — the teaching
 * sketch supplies the parser.
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException if {@code text} is not valid JSON */
    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipSpace();
        Object value = parser.value();
        parser.skipSpace();
        if (parser.pos != text.length()) {
            throw parser.error("trailing content");
        }
        return value;
    }

    private Object value() {
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        switch (text.charAt(pos)) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (!peek('"')) {
                throw error("expected string key");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, value());
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value());
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw error("bad unicode escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException bad) {
                        throw error("bad unicode escape");
                    }
                    pos += 4;
                    break;
                default: out.append(escaped); // quote, backslash, slash
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.valueOf(text.substring(start, pos));
        } catch (NumberFormatException bad) {
            pos = start;
            throw error("unexpected character");
        }
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected character");
        }
        pos += word.length();
        return result;
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + what);
    }
}
