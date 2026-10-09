#!/usr/bin/env python3
"""JSearXNG — a real metasearcher.

The name is the pedigree: JSearch, the 2002 applet archived next door, fanned
one query out to several engines, scraped the HTML, and deduplicated by URL.
This does the same against a live 2026 web — its own engines plus the local
SearXNG instance, whose engine pool sits behind one JSON call.

Single file, standard library only. Run:

    python3 find.py [--port 8092]

Then open http://127.0.0.1:8092/
"""

import argparse
import base64
import html
import json
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)
FETCH_TIMEOUT = 6
RESULTS_PER_ENGINE = 8


class Result:
    __slots__ = ("title", "url", "snippet", "engine")

    def __init__(self, title, url, snippet, engine):
        self.title = title.strip()
        self.url = url
        self.snippet = re.sub(r"\s+", " ", snippet or "").strip()
        self.engine = engine


def fetch(url, data=None):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT) as resp:
        return resp.read().decode("utf-8", "replace")


# --------------------------------------------------------------------------
# Engines. Each returns list[Result]; anything raised becomes that engine's
# per-engine error, never a page-level failure — one dead engine does not
# sink the others, same rule as the 2002 code.
# --------------------------------------------------------------------------

def _unwrap_bing(href):
    """bing.com/ck/a?...&u=a1<base64url> → the real destination URL."""
    m = re.search(r"[?&]u=a1([A-Za-z0-9_-]+)", href)
    if m:
        b64 = m.group(1)
        try:
            real = base64.urlsafe_b64decode(
                b64 + "=" * (-len(b64) % 4)).decode("utf-8", "replace")
            if real.startswith("http"):
                return real
        except Exception:
            pass
    return href


class _BingParser(HTMLParser):
    """Collect result blocks (li/div with class b_algo): first h2>a is the
    hit, the first <p> after it is the snippet."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.results = []
        self._depth = 0
        self._algo_tag = None
        self._capture = None   # "title" | "snippet" | None
        self._buf = []
        self._href = None
        self._title = None
        self._snip = ""
        self._in_h2 = 0

    def handle_starttag(self, tag, attrs):
        cls = dict(attrs).get("class", "")
        if not self._depth and "b_algo" in cls:
            self._depth = 1
            self._algo_tag = tag
            self._capture = None
            self._buf = []
            self._href = None
            self._title = None
            self._snip = ""
        elif self._depth:
            if tag == self._algo_tag:
                self._depth += 1
            elif tag == "h2":
                self._in_h2 += 1
            elif tag == "a":
                href = dict(attrs).get("href", "")
                # the title lives in the block's <h2>; any earlier http anchor
                # (Bing puts the site name in one) is only a fallback
                if href.startswith("http") and (self._in_h2 or self._href is None):
                    self._href = href
                    self._capture = "title"
                    self._buf = []
            elif tag == "p" and self._capture != "title":
                self._capture = "snippet"
                self._buf = []

    def handle_endtag(self, tag):
        if not self._depth:
            return
        if self._capture == "title" and tag == "a":
            self._title = "".join(self._buf).strip()
            self._capture = None
        elif self._capture == "snippet" and tag == "p":
            self._snip = "".join(self._buf)
            self._capture = None
        elif tag == "h2" and self._in_h2:
            self._in_h2 -= 1
        if tag == self._algo_tag:
            self._depth -= 1
            if not self._depth and self._href and self._title:
                self.results.append((self._title,
                                     _unwrap_bing(self._href), self._snip))

    def handle_data(self, data):
        if self._capture:
            self._buf.append(data)


def search_bing(query):
    html_text = fetch("https://www.bing.com/search?" + urllib.parse.urlencode({"q": query}))
    parser = _BingParser()
    parser.feed(html_text)
    return [Result(t, u, s, "bing")
            for t, u, s in parser.results[:RESULTS_PER_ENGINE]]


def search_wikipedia(query):
    params = urllib.parse.urlencode({
        "action": "query", "list": "search", "srsearch": query,
        "format": "json", "srlimit": RESULTS_PER_ENGINE,
    })
    data = json.loads(fetch("https://en.wikipedia.org/w/api.php?" + params))
    out = []
    for hit in data.get("query", {}).get("search", []):
        snippet = re.sub(r"<[^>]+>", "", hit.get("snippet", ""))
        url = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(
            hit["title"].replace(" ", "_"))
        out.append(Result(hit["title"], url, snippet, "wikipedia"))
    return out


def search_searxng(query):
    """The local SearXNG instance — a metasearcher as an engine. Each hit
    carries the upstream engine that produced it, shown in the badge."""
    params = urllib.parse.urlencode({"q": query, "format": "json"})
    data = json.loads(fetch("http://127.0.0.1:8888/search?" + params))
    out = []
    for hit in data.get("results", [])[:RESULTS_PER_ENGINE]:
        upstream = hit.get("engine", "?")
        out.append(Result(hit.get("title", "(untitled)"), hit.get("url", ""),
                          hit.get("content", ""), "searxng·" + upstream))
    return out


def search_marginalia(query):
    data = json.loads(fetch(
        "https://api.marginalia.nu/public/search/" + urllib.parse.quote(query) + "/"))
    out = []
    for hit in data.get("results", [])[:RESULTS_PER_ENGINE]:
        out.append(Result(hit.get("title", "(untitled)"), hit.get("url", ""),
                          hit.get("description", ""), "marginalia"))
    return out


def search_stackexchange(query):
    params = urllib.parse.urlencode(
        {"q": query, "site": "stackoverflow", "pagesize": RESULTS_PER_ENGINE})
    data = json.loads(fetch("https://api.stackexchange.com/2.3/search/advanced?" + params))
    out = []
    for hit in data.get("items", []):
        snippet = "%d answers · %d votes · %s" % (
            hit.get("answer_count", 0), hit.get("score", 0),
            ", ".join(hit.get("tags", [])[:4]))
        out.append(Result(html.unescape(hit.get("title", "(untitled)")),
                          hit["link"], snippet, "stackexchange"))
    return out


def search_hn(query):
    params = urllib.parse.urlencode({"query": query, "hitsPerPage": RESULTS_PER_ENGINE})
    data = json.loads(fetch("https://hn.algolia.com/api/v1/search?" + params))
    out = []
    for hit in data.get("hits", []):
        title = hit.get("title") or hit.get("story_title") or "(untitled)"
        url = hit.get("url") or "https://news.ycombinator.com/item?id=" + str(hit["objectID"])
        snippet = "%s points · %s comments · %s" % (
            hit.get("points", 0), hit.get("num_comments", 0), hit.get("author", "?"))
        out.append(Result(title, url, snippet, "hn"))
    return out


ENGINES = [
    ("bing", search_bing, "web"),
    ("searxng", search_searxng, "metasearch"),
    ("marginalia", search_marginalia, "web"),
    ("wikipedia", search_wikipedia, "reference"),
    ("stackexchange", search_stackexchange, "q&a"),
    ("hn", search_hn, "discussions"),
]


# --------------------------------------------------------------------------
# Fan-out, dedup, aggregate
# --------------------------------------------------------------------------

def normalize(url):
    return url.split("#", 1)[0].rstrip("/")


def sanity_ok(query, hits):
    """Poisoned anti-bot responses parse fine but are about nothing — Bing
    serves random popular pages instead of an error. If no result mentions
    any significant query term, treat the response as a block page."""
    tokens = [t for t in re.split(r"\W+", query.lower()) if len(t) > 2]
    if not tokens or not hits:
        return True
    for r in hits:
        blob = (r.title + " " + r.snippet + " " + r.url).lower()
        if any(t in blob for t in tokens):
            return True
    return False


def run_search(query, engines=None, speed=None, levels=None):
    t0 = time.time()
    chosen = [e for e in ENGINES if not engines or e[0] in engines] or ENGINES
    levels = max(1, min(int(levels or RESULTS_PER_ENGINE), 16))

    def one(entry):
        name, fn, _kind = entry
        start = time.time()
        try:
            hits = fn(query)
        except Exception as exc:  # per-engine failure is data, not a crash
            return (name, [], "%s: %s" % (type(exc).__name__, exc), time.time() - start)
        hits = hits[:levels]  # smlCh — how deep one engine may go
        if not sanity_ok(query, hits):
            return (name, [],
                    "response failed the relevance check (query terms absent) — likely a bot-block page",
                    time.time() - start)
        return (name, hits, None, time.time() - start)

    # smsCh — the 2002 "search max speed": cap simultaneous connections
    with ThreadPoolExecutor(max_workers=max(1, min(int(speed or len(chosen)), 8))) as pool:
        outcomes = list(pool.map(one, chosen))

    seen, results = set(), []
    for _name, hits, _err, _ms in outcomes:
        for r in hits:
            key = normalize(r.url)
            if key in seen:
                continue
            seen.add(key)
            results.append(r)

    status = [
        {"engine": name, "count": len(hits), "error": err, "ms": round(ms * 1000)}
        for name, hits, err, ms in outcomes
    ]
    return {
        "query": query,
        "results": [{"title": r.title, "url": r.url,
                     "snippet": r.snippet, "engine": r.engine} for r in results],
        "engines": status,
        "durationMs": round((time.time() - t0) * 1000),
        "duplicatesDropped": sum(len(h) for _, h, _, _ in outcomes) - len(results),
    }


# --------------------------------------------------------------------------
# Web UI (server-rendered, no JS)
# --------------------------------------------------------------------------

PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>JSearXNG · %(query)s</title>
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
  .querybox { width:99%%; font:13px Tahoma, sans-serif; padding:2px 4px;
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
        <input class="querybox" type="search" name="q" value="%(query)s" autofocus>
        <div class="hint">搜索条件依所选搜索引擎而定。</div>
        <button class="btn" type="submit">开始搜索</button><a class="btn" disabled title="服务器一次一查，无可停止">停止搜索</a><a class="btn" href="/">新建搜索</a>
      </div>
      <div class="panel">
        <h3>结果</h3>
        <div class="total">总计: %(total)s</div>
        <div class="listbox">%(rows)s</div>
      </div>
      <div class="smalltabs"><span class="smalltab">&gt;&gt;预览</span></div>
      <div class="prevbox">%(preview)s</div>
      <div class="smalltabs"><span class="smalltab">&gt;&gt;消息</span></div>
      <div class="msgbox">%(message)s</div>
    </div>
    <div class="right">
      <div class="panel">
        <h3>引擎</h3>
        <div class="smalltabs" style="margin:0 0 3px"><span class="smalltab">类别</span></div>
        %(kinds)s
        <div class="smalltabs" style="margin:6px 0 3px"><span class="smalltab">搜索引擎</span></div>
        <div class="cblist">%(engine_checks)s</div>
      </div>
      <div class="panel">
        <h3>选项</h3>
        <div class="opt">搜索最大速度 smsCh</div>
        <select name="speed">%(speed_opts)s</select>
        <div class="opt" style="margin-top:4px">搜索最大层级 smlCh</div>
        <select name="levels">%(level_opts)s</select>
      </div>
      <div class="panel about">
        <h3>关于</h3>
        J·SearXNG — JSearch 的骨架（2002：扇出、抓取、按 URL 去重、每个引擎如实报告），
        SearXNG 的引擎池。无缓存、无追踪、无广告，单文件零依赖；每次查询每引擎一个请求。
        smsCh 限并发连接数，smlCh 限单引擎深度——和 2002 年同名参数一个意思。
      </div>
    </div>
  </div></form>
</div>
</body></html>"""


def _fmt(s, n):
    """The 2002 result-row columns: pad then clip, like formatString()."""
    return html.escape((s or "")[:n].ljust(n))


def render(data):
    form = data.get("form", {})
    query = data["query"]
    chosen = set(form.get("engines") or [])

    checks = "".join(
        "<label><input type='checkbox' name='engines' value='%s'%s> %s</label>" % (
            name, " checked" if not chosen or name in chosen else "", name)
        for name, _fn, _kind in ENGINES)
    kinds = "<ul class='cat'>%s</ul>" % "".join(
        "<li>%s</li>" % html.escape(k) for k in sorted({k for _n, _f, k in ENGINES}))
    speed_now = form.get("speed", 4)
    level_now = form.get("levels", 8)
    speed_opts = "".join("<option%s>%d</option>" % (" selected" if v == speed_now else "", v)
                         for v in range(1, 9))
    level_opts = "".join("<option%s>%d</option>" % (" selected" if v == level_now else "", v)
                         for v in range(1, 17))

    if data["results"]:
        rows = "".join(
            "<div><a href='%s' rel='noopener'>%s</a> | %s | from: %s</div>" % (
                html.escape(r["url"], quote=True),
                _fmt(r["title"], 42), _fmt(r["url"], 40),
                html.escape(r["engine"]))
            for r in data["results"])
        total = str(len(data["results"]))
        top = data["results"][0]
        preview = "<b>预览一：%s</b><br>%s<br><span style='color:#123a6e'>%s</span>" % (
            html.escape(top["title"]),
            html.escape(top["snippet"]) or "(无摘要)",
            html.escape(top["url"]))
    else:
        rows = ""
        total = "0"
        preview = ("输入查询，按“开始搜索”。" if not query
                   else "（无结果可供预览——见 >>消息）")

    if data["engines"]:
        lines = []
        for e in data["engines"]:
            if e["error"]:
                lines.append("[%s] %s" % (e["engine"], e["error"]))
            else:
                lines.append("[%s] %d results, %d ms" % (e["engine"], e["count"], e["ms"]))
        lines.append("[jsearxng] 共 %d 条结果，去重丢弃 %d 条，耗时 %d ms"
                     % (len(data["results"]), data["duplicatesDropped"], data["durationMs"]))
        message = html.escape("\n".join(lines))
    else:
        message = ("[jsearxng] 准备就绪。六个引擎整装待发：bing 会投毒，我们体检；"
                   "其余走官方 API。勾选引擎、调好 smsCh/smlCh，按“开始搜索”。")

    return PAGE % {
        "query": html.escape(query),
        "total": total,
        "rows": rows,
        "preview": preview,
        "message": message,
        "kinds": kinds,
        "engine_checks": checks,
        "speed_opts": speed_opts,
        "level_opts": level_opts,
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        query = (params.get("q", [""])[0] or "").strip()
        engines = [e for e in (params.get("engines", [""])[0] or "").split(",") if e]
        speed = params.get("speed", [None])[0]
        levels = params.get("levels", [None])[0]

        if parsed.path == "/api/search":
            data = (run_search(query, engines=engines, speed=speed, levels=levels)
                    if query else {"error": "missing q"})
            payload = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(payload)
            return

        data = (run_search(query, engines=engines, speed=speed, levels=levels)
                if query else
                {"query": "", "engines": [], "results": [],
                 "duplicatesDropped": 0, "durationMs": 0})
        data["form"] = {
            "engines": engines,
            "speed": int(speed) if speed else 4,
            "levels": int(levels) if levels else 8,
        }
        page = render(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(page)

    def log_message(self, fmt, *args):
        print("[find]", fmt % args)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8092)
    args = ap.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("JSearXNG on http://127.0.0.1:%d/  (%s, live)"
          % (args.port, " + ".join(name for name, _fn, _k in ENGINES)))
    server.serve_forever()


if __name__ == "__main__":
    main()
