/*
 * JSearXNG's test suite. Every check is on a pure function, so this needs no
 * network, no fixtures and no clock: the layers a search result passes through
 * before it reaches a browser (entity unescaping, query parsing, URL dedup
 * keys, the poison check, Bing redirect unwrapping, escaping, the JSON reader,
 * the page render) are all deterministic. The network half is covered by the
 * live service and by each engine's own error reporting.
 *
 * Several sections exist because a bug of exactly that shape already shipped
 * and was found by eye — the repeated-form-key collapse, the &amp; that
 * survived in Bing tracking URLs, the raw &#0183; in a preview, and the
 * template slot that a query containing "${message}" could expand.
 *
 * Run: ./test.sh
 *      (javac -encoding UTF-8 -d out JSearXNG.java JSearXNGTest.java
 *       && java -cp out JSearXNGTest)
 */

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class JSearXNGTest {

    static int passed;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        section("unescape — the entity layer (a &amp; left in a URL broke dedup; a raw &#0183; showed in a preview)");
        testUnescape();
        section("parseParams — repeated keys (six checkboxes submitted as six engines= values once collapsed to one)");
        testParseParams();
        section("normalize — the 2002 URL dedup key");
        testNormalize();
        section("sanityOk — Bing serves popular pages instead of errors when it smells a bot");
        testSanityOk();
        section("unwrapBing — the ck/a tracking redirect hides the destination in base64url");
        testUnwrapBing();
        section("esc / fmt — what reaches the browser");
        testEscAndFmt();
        section("Json — the dependency-free reader");
        testJson();
        section("parseJson — the gate that reports a 502 as a 502");
        testParseJsonGate();
        section("jsonEsc — the API's string escaping, round-tripped through the reader");
        testJsonEsc();
        section("helpers");
        testHelpers();
        section("render — the 2002 face, server-rendered");
        testRender();
        section("template slots — user text must never be expanded as a slot");
        testTemplateSlots();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("PASS — all " + passed + " checks.");
        } else {
            for (String f : failures) System.out.println("FAIL  " + f);
            System.out.println("FAIL — " + failures.size() + " of " + (passed + failures.size()) + " checks failed.");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // sections
    // ------------------------------------------------------------------

    static void testUnescape() {
        eq("named &amp;", "a&b", JSearXNG.unescape("a&amp;b"));
        eq("decimal entity", "a·b", JSearXNG.unescape("a&#0183;b"));
        eq("hex entity", "a·b", JSearXNG.unescape("a&#xB7;b"));
        eq("&nbsp;", "a b", JSearXNG.unescape("a&nbsp;b"));
        eq("&#160;", "a b", JSearXNG.unescape("a&#160;b"));
        eq("&#160; and &nbsp; are one entity, one character",
                JSearXNG.unescape("a&nbsp;b"), JSearXNG.unescape("a&#160;b"));
        eq("&middot;", "·", JSearXNG.unescape("&middot;"));
        eq("quot/lt/gt/#39", "<a href=\"x\">'",
                JSearXNG.unescape("&lt;a href=&quot;x&quot;&gt;&#39;"));
        eq("null is empty", "", JSearXNG.unescape(null));
        eq("plain text untouched", "plain text 123", JSearXNG.unescape("plain text 123"));
        // one pass, like the Python parser it replaced: &amp;amp; is the escaped
        // form of the literal text "&amp;", not of "&"
        eq("one pass only", "&amp;", JSearXNG.unescape("&amp;amp;"));
        eq("&#0; vanishes", "ab", JSearXNG.unescape("a&#0;b"));
        eq("surrogate pair from two entities", "😀", JSearXNG.unescape("&#xD83D;&#xDE00;"));
        // the actual bug: Bing escapes & inside href, so the URL kept "&amp;"
        // and never matched the dedup key of the same URL elsewhere
        eq("a tracking URL unescapes to a real query string",
                "https://example.com/?a=1&b=2",
                JSearXNG.unescape("https://example.com/?a=1&amp;b=2"));
    }

    static void testParseParams() {
        Map<String, List<String>> p =
                JSearXNG.parseParams("q=java&engines=bing&engines=searxng&engines=hn");
        eq("repeated keys keep every value, in order",
                List.of("bing", "searxng", "hn"), p.get("engines"));
        eq("single key", List.of("java"), p.get("q"));
        eq("four pairs, two keys", 2, p.size());

        eq("percent-decoding", "a b", first(p("q=a%20b").get("q")));
        // form encoding: browsers send a space as +, and URLDecoder agrees
        eq("+ decodes as space (form encoding)", "a b", first(p("q=a+b").get("q")));
        eq("bare key gets an empty value", List.of(""), p("flag").get("flag"));
        eq("the first = splits", "a=b", first(p("q=a=b").get("q")));
        eq("comma-joined engines stay one value (main splits them)",
                List.of("bing,hn"), p("engines=bing%2Chn").get("engines"));
        eq("empty query", 0, JSearXNG.parseParams("").size());
        eq("null query", 0, JSearXNG.parseParams(null).size());
    }

    static void testNormalize() {
        eq("trailing slash", "http://x.com/a", JSearXNG.normalize("http://x.com/a/"));
        eq("fragment", "http://x.com/a", JSearXNG.normalize("http://x.com/a#top"));
        eq("fragment before the slash", "http://x.com/a", JSearXNG.normalize("http://x.com/a#/"));
        eq("root", "http://x.com", JSearXNG.normalize("http://x.com/"));
        eq("plain URL untouched", "http://x.com/a", JSearXNG.normalize("http://x.com/a"));
        eq("query string is significant", "http://x.com/a?b=1", JSearXNG.normalize("http://x.com/a?b=1"));
        eq("slash and fragment variants share one key",
                JSearXNG.normalize("http://x.com/a"), JSearXNG.normalize("http://x.com/a/#frag"));
    }

    static void testSanityOk() {
        List<JSearXNG.Result> poison = List.of(new JSearXNG.Result(
                "Facebook — 登录或注册", "https://www.facebook.com/", "Create an account", "bing"));
        ok("a page about nothing is rejected",
                !JSearXNG.sanityOk("searxng self hosted", poison));
        ok("a relevant page passes",
                JSearXNG.sanityOk("searxng self hosted", List.of(new JSearXNG.Result(
                        "self-hosted searxng guide", "https://example.com/searxng",
                        "docker compose up", "bing"))));
        ok("no hits means nothing to reject", JSearXNG.sanityOk("anything at all", List.of()));
        // "go" splits to one token of length 2, which is filtered as noise
        ok("only short tokens means the check is skipped", JSearXNG.sanityOk("go", poison));
        ok("two-char tokens are ignored",
                !JSearXNG.sanityOk("ai java", List.of(new JSearXNG.Result(
                        "AI news today", "https://x.ai/", "", "bing"))));
        ok("matching is case-insensitive",
                JSearXNG.sanityOk("JAVA history", List.of(new JSearXNG.Result(
                        "JAVA SE history", "https://oracle.com/", "", "hn"))));
        ok("a URL mention counts",
                JSearXNG.sanityOk("marginalia", List.of(new JSearXNG.Result(
                        "t", "https://marginalia.nu/", "", "hn"))));
    }

    static void testUnwrapBing() {
        String real = "https://www.example.com/path?a=1&b=2";
        byte[] raw = real.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String padded = Base64.getUrlEncoder().encodeToString(raw);
        String unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        eq("padded base64url decodes to the destination", real,
                JSearXNG.unwrapBing("https://www.bing.com/ck/a?!&&p=1&u=a1" + padded + "&ntb=1"));
        eq("unpadded base64url decodes too", real,
                JSearXNG.unwrapBing("https://www.bing.com/ck/a?u=a1" + unpadded));
        eq("a payload that is not http keeps the tracking URL",
                "https://www.bing.com/ck/a?u=a1" + Base64.getUrlEncoder()
                        .encodeToString("ftp://files.example.com/x".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)),
                JSearXNG.unwrapBing("https://www.bing.com/ck/a?u=a1" + Base64.getUrlEncoder()
                        .encodeToString("ftp://files.example.com/x".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8))));
        eq("a plain href is returned as-is", "https://example.com/",
                JSearXNG.unwrapBing("https://example.com/"));
        eq("an undecodable payload keeps the href",
                "https://www.bing.com/ck/a?u=a1a",
                JSearXNG.unwrapBing("https://www.bing.com/ck/a?u=a1a"));
        eq("u=a1 not at a parameter boundary is not a redirect",
                "https://x.com/?x=a1aHR0cHM6Ly94LmNvbS8",
                JSearXNG.unwrapBing("https://x.com/?x=a1aHR0cHM6Ly94LmNvbS8"));
    }

    static void testEscAndFmt() {
        eq("all five", "&amp;&lt;&gt;&quot;&#39;", JSearXNG.esc("&<>\"'"));
        eq("null", "", JSearXNG.esc(null));
        eq("round trip through unescape", "a<b>&\"'c", JSearXNG.unescape(JSearXNG.esc("a<b>&\"'c")));
        eq("fmt clips", "abcdef", JSearXNG.fmt("abcdefghij", 6));
        eq("fmt escapes while clipping", "&lt;b&gt;", JSearXNG.fmt("<b>", 10));
        eq("fmt at exact length", "abc", JSearXNG.fmt("abc", 3));
        eq("fmt null", "", JSearXNG.fmt(null, 5));
    }

    static void testJson() {
        Object o = Json.parse("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":-2.5}}");
        ok("object is a Map", o instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        eq("number", 1.0, m.get("a"));
        eq("negative decimal", -2.5, ((Map<?, ?>) m.get("c")).get("d"));
        ok("array is a List of three", m.get("b") instanceof List<?> l && l.size() == 3);
        eq("array holds true", Boolean.TRUE, ((List<?>) m.get("b")).get(0));
        eq("array holds null", null, ((List<?>) m.get("b")).get(1));
        eq("array holds string", "x", ((List<?>) m.get("b")).get(2));

        eq("string escapes", "a\nb\t\"c\\d", Json.parse("\"a\\nb\\t\\\"c\\\\d\""));
        eq("unicode escape", "é中", Json.parse("\"\\u00e9\\u4e2d\""));
        eq("surrogate pair", "😀", Json.parse("\"\\ud83d\\ude00\""));
        eq("exponent", 1000.0, Json.parse("1e3"));
        eq("empty object", 0, ((Map<?, ?>) Json.parse("{}")).size());
        eq("empty array", 0, ((List<?>) Json.parse("[]")).size());

        throwsWith("trailing content is rejected", "trailing content", () -> Json.parse("{} x"));
        throwsWith("empty input is rejected", "unexpected end", () -> Json.parse(""));
        throwsWith("garbage is rejected", "invalid JSON", () -> Json.parse("nope"));
        throwsWith("unterminated string is rejected", "unterminated string", () -> Json.parse("\"abc"));
        throwsWith("short unicode escape is rejected", "bad unicode escape", () -> Json.parse("\"\\u12\""));
    }

    static void testParseJsonGate() {
        eq("a JSON body still parses", 1.0,
                ((Map<?, ?>) JSearXNG.parseJson("{\"a\":1}")).get("a"));
        // the failure this replaced: a 502 page used to surface as
        // "invalid JSON at offset 0"
        throwsWith("an HTML error page is reported as not JSON",
                "response was not JSON",
                () -> JSearXNG.parseJson("<html><body>502 Bad Gateway</body></html>"));
        throwsWith("the message quotes the body",
                "502 Bad Gateway",
                () -> JSearXNG.parseJson("<html>502 Bad Gateway</html>"));
        throwsWith("whitespace only is rejected",
                "response was not JSON", () -> JSearXNG.parseJson("   "));
        throwsWith("null body is rejected",
                "response was not JSON", () -> JSearXNG.parseJson(null));
    }

    static void testJsonEsc() {
        String nasty = "quote\" back\\ nl\n tab\t ctrl\u0001 end";
        eq("round trip through the reader", nasty,
                Json.parse("\"" + JSearXNG.jsonEsc(nasty) + "\""));
        eq("control character becomes \\u", "\\u0001", JSearXNG.jsonEsc("\u0001"));
        eq("plain text unchanged", "abc 123", JSearXNG.jsonEsc("abc 123"));
    }

    static void testHelpers() {
        eq("stripTags", "hi", JSearXNG.stripTags("<p>hi</p>"));
        eq("stripTags null", "", JSearXNG.stripTags(null));
        eq("str falls back", "x", JSearXNG.str(null, "x"));
        eq("str passes through", "v", JSearXNG.str("v", "x"));
        eq("intOf truncates", 5, JSearXNG.intOf(5.9));
        eq("intOf on a non-number is 0", 0, JSearXNG.intOf("5"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", null);
        eq("get falls back on a null value", "d", JSearXNG.get(m, "k", "d"));
        eq("get falls back on a missing key", "d", JSearXNG.get(m, "zz", "d"));
        eq("get returns the value", "v", JSearXNG.get(Map.of("k", "v"), "k", "d"));
        eq("first on null", "", JSearXNG.first(null));
        eq("first on empty", "", JSearXNG.first(List.of()));
        eq("first picks the first", "a", JSearXNG.first(List.of("a", "b")));
        eq("six engines registered", 6, JSearXNG.ENGINES.size());
        List<String> names = new ArrayList<>();
        for (JSearXNG.EngineEntry e : JSearXNG.ENGINES) names.add(e.name());
        eq("engine names", List.of("bing", "searxng", "marginalia", "wikipedia",
                "stackexchange", "hn"), names);
    }

    static void testRender() {
        List<JSearXNG.Result> results = List.of(
                new JSearXNG.Result("Java history",
                        "https://en.wikipedia.org/wiki/Java",
                        "Java is a high-level language", "searxng·wikipedia"),
                new JSearXNG.Result("HN thread",
                        "https://news.ycombinator.com/item?id=1",
                        "12 points · 3 comments · user", "hn"));
        List<JSearXNG.Outcome> engines = List.of(
                new JSearXNG.Outcome("bing", List.of(),
                        "response failed the relevance check (query terms absent) — likely a bot-block page",
                        812),
                new JSearXNG.Outcome("searxng", results, null, 340));
        JSearXNG.SearchData data = new JSearXNG.SearchData(results, engines, 352, 1);
        String html = JSearXNG.render(data, new JSearXNG.Form(Set.of("bing"), 3, 5), "java history");

        ok("total counts the merged results", html.contains("总计: 2"));
        ok("rows carry the click-to-preview attributes",
                html.contains("data-n='1'")
                        && html.contains("data-title='Java history'")
                        && html.contains("data-url='https://en.wikipedia.org/wiki/Java'"));
        ok("the row shows which engine answered", html.contains("from: searxng·wikipedia"));
        ok("the preview names the first result", html.contains("<b>结果 1 · Java history</b>"));
        ok("a failing engine reports why, not silence",
                html.contains("[bing] response failed the relevance check"));
        ok("a succeeding engine reports count and time",
                html.contains("[searxng] 2 results, 340 ms"));
        ok("the dedup count is reported", html.contains("去重丢弃 1 条"));
        ok("smsCh selects its option", html.contains("<option selected>3</option>"));
        ok("smlCh selects its option", html.contains("<option selected>5</option>"));
        ok("the chosen engine is checked", html.contains("value='bing' checked"));
        ok("the unchosen engine is not", !html.contains("value='hn' checked"));

        String xss = JSearXNG.render(data, new JSearXNG.Form(Set.of(), 4, 8),
                "<script>alert(1)</script>");
        ok("the query is escaped in the input box",
                xss.contains("value=\"&lt;script&gt;alert(1)&lt;/script&gt;\""));
        ok("the query is escaped in the title",
                xss.contains("<title>JSearXNG · &lt;script&gt;"));

        String blank = JSearXNG.render(
                new JSearXNG.SearchData(List.of(), List.of(), 0, 0),
                new JSearXNG.Form(Set.of(), 4, 8), "");
        ok("an empty run invites a query", blank.contains("输入查询，按“开始搜索”。"));
        ok("an empty run lists every engine",
                blank.contains("value='bing'") && blank.contains("value='searxng'")
                        && blank.contains("value='marginalia'") && blank.contains("value='wikipedia'")
                        && blank.contains("value='stackexchange'") && blank.contains("value='hn'"));
        ok("no engine selected checks them all", blank.contains("value='bing' checked"));
        ok("an empty run says it is ready", blank.contains("准备就绪"));
        ok("an empty run shows zero", blank.contains("总计: 0"));
        ok("an empty run has no rows", !blank.contains("data-n='1'"));

        String noSnippet = JSearXNG.render(new JSearXNG.SearchData(
                        List.of(new JSearXNG.Result("T", "https://u/", null, "e")),
                        List.of(new JSearXNG.Outcome("e", List.of(), null, 1)), 1, 0),
                new JSearXNG.Form(Set.of(), 4, 8), "q");
        ok("a null snippet previews as 无摘要", noSnippet.contains("(无摘要)"));
    }

    static void testTemplateSlots() {
        // A query or a title containing the literal text of a slot used to be
        // expanded by a later replace(), so the >>消息 panel's content could
        // end up inside the query box's value attribute.
        String injected = JSearXNG.render(
                new JSearXNG.SearchData(List.of(), List.of(), 0, 0),
                new JSearXNG.Form(Set.of(), 4, 8), "${message}");
        ok("a ${message} query stays literal in the box",
                injected.contains("value=\"${message}\""));
        eq("the ready message appears exactly once", 1, count(injected, "准备就绪"));

        String rowsInjected = JSearXNG.render(
                new JSearXNG.SearchData(
                        List.of(new JSearXNG.Result("Java history", "https://u/", "s", "e")),
                        List.of(new JSearXNG.Outcome("e", List.of(), null, 1)), 1, 0),
                new JSearXNG.Form(Set.of(), 4, 8), "${rows}");
        ok("a ${rows} query stays literal in the box",
                rowsInjected.contains("value=\"${rows}\""));
        eq("the row is not duplicated into the query box",
                1, count(rowsInjected, "data-n='1'"));

        String titleInjected = JSearXNG.render(
                new JSearXNG.SearchData(
                        List.of(new JSearXNG.Result("${message}", "https://u/", "s", "e")),
                        List.of(new JSearXNG.Outcome("e", List.of(), null, 1)), 1, 0),
                new JSearXNG.Form(Set.of(), 4, 8), "q");
        ok("a title containing ${message} stays literal",
                titleInjected.contains("data-title='${message}'"));

        String totalInjected = JSearXNG.render(
                new JSearXNG.SearchData(List.of(), List.of(), 0, 0),
                new JSearXNG.Form(Set.of(), 4, 8), "${total}");
        ok("a ${total} query stays literal in the box",
                totalInjected.contains("value=\"${total}\""));
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    static Map<String, List<String>> p(String rawQuery) {
        return JSearXNG.parseParams(rawQuery);
    }

    static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    static int count(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
             i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    static void section(String title) {
        System.out.println();
        System.out.println("— " + title);
    }

    static void ok(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL  " + name);
        }
    }

    static void eq(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failures.add(name + " — expected " + show(expected) + ", got " + show(actual));
            System.out.println("  FAIL  " + name + " — expected " + show(expected)
                    + ", got " + show(actual));
        }
    }

    static void throwsWith(String name, String fragment, Runnable body) {
        try {
            body.run();
            failures.add(name + " — nothing thrown");
            System.out.println("  FAIL  " + name + " — nothing thrown");
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains(fragment)) {
                passed++;
                System.out.println("  ok    " + name);
            } else {
                failures.add(name + " — message \"" + e.getMessage() + "\" lacks \"" + fragment + "\"");
                System.out.println("  FAIL  " + name + " — message \"" + e.getMessage()
                        + "\" lacks \"" + fragment + "\"");
            }
        } catch (Exception e) {
            failures.add(name + " — threw " + e.getClass().getSimpleName());
            System.out.println("  FAIL  " + name + " — threw " + e.getClass().getSimpleName());
        }
    }

    static String show(Object o) {
        if (o == null) return "null";
        String s = o.toString();
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
