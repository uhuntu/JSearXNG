#!/usr/bin/env python3
"""FIND engine — a real metasearcher.

The 2002 ancestor (JSearch) fanned one query out to several engines, scraped
the HTML, and deduplicated by URL. This does the same, against engines that
actually still answer in 2026: one scraped (Bing) and two with official
public APIs (Wikipedia, Hacker News). DuckDuckGo, Google and Mojeek all
return bot-challenge pages to a plain fetch from a server IP, so they are
not wired in — see README.md.

Single file, standard library only. Run:

    python3 find.py [--port 8091]

Then open http://127.0.0.1:8091/
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


def run_search(query):
    t0 = time.time()

    def one(entry):
        name, fn, _kind = entry
        start = time.time()
        try:
            hits = fn(query)
        except Exception as exc:  # per-engine failure is data, not a crash
            return (name, [], "%s: %s" % (type(exc).__name__, exc), time.time() - start)
        if not sanity_ok(query, hits):
            return (name, [],
                    "response failed the relevance check (query terms absent) — likely a bot-block page",
                    time.time() - start)
        return (name, hits, None, time.time() - start)

    with ThreadPoolExecutor(max_workers=len(ENGINES)) as pool:
        outcomes = list(pool.map(one, ENGINES))

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
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>FIND · %(query)s</title>
<style>
  :root { --bg:#0a0f1d; --card:#11192d; --line:#1e2a44; --text:#dbe4f5;
          --muted:#7c8db0; --accent:#4f7cff; --ok:#34d399; --bad:#f87171; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text);
         font:16px/1.5 system-ui, sans-serif; }
  main { max-width:760px; margin:0 auto; padding:2rem 1rem 4rem; }
  h1 a { color:var(--text); text-decoration:none; font-size:1.6rem; }
  h1 a b { color:var(--accent); }
  .tag { color:var(--muted); font-size:.85rem; margin:.2rem 0 1.5rem; }
  form { display:flex; gap:.5rem; }
  input[type=search] { flex:1; padding:.7rem 1rem; font-size:1rem; color:var(--text);
    background:var(--card); border:1px solid var(--line); border-radius:10px; }
  button { padding:.7rem 1.2rem; font-size:1rem; color:#fff; background:var(--accent);
    border:0; border-radius:10px; cursor:pointer; }
  .chips { margin:1rem 0; display:flex; flex-wrap:wrap; gap:.4rem; font-size:.78rem; }
  .chip { border:1px solid var(--line); border-radius:99px; padding:.15rem .6rem;
          color:var(--muted); }
  .chip b { color:var(--ok); } .chip i { color:var(--bad); font-style:normal; }
  a.chip { text-decoration:none; cursor:pointer; }
  a.chip:hover { border-color:var(--accent); color:var(--accent); }
  .try { color:var(--muted); font-size:.78rem; align-self:center; }
  h4 { margin:1.8rem 0 .3rem; font-size:.95rem; }
  .engines { display:grid; grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); gap:.5rem; }
  .eng { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:.6rem .8rem; }
  .eng b { color:var(--text); font-size:.9rem; }
  .eng span { display:block; color:var(--muted); font-size:.75rem; margin-top:.15rem; }
  .meta { color:var(--muted); font-size:.8rem; margin-bottom:1rem; }
  article { background:var(--card); border:1px solid var(--line); border-radius:10px;
            padding:.9rem 1rem; margin-bottom:.6rem; }
  article a { color:var(--text); font-weight:600; text-decoration:none; font-size:1.02rem; }
  article a:hover { color:var(--accent); }
  .u { color:var(--muted); font-size:.75rem; word-break:break-all; margin:.15rem 0; }
  .s { font-size:.85rem; color:#b9c5dd; margin:0; }
  .badge { float:right; font-size:.7rem; color:var(--accent); border:1px solid var(--accent);
           border-radius:99px; padding:.05rem .5rem; }
  footer { color:var(--muted); font-size:.75rem; margin-top:2.5rem; line-height:1.6; }
</style></head><body><main>
<h1><a href="/">F<b>·</b>IND</a></h1>
<p class="tag">turns search engines into FIND engines — for real, this time.</p>
<form action="/" method="get">
  <input type="search" name="q" value="%(query)s" placeholder="search the live web…" autofocus>
  <button>find</button>
</form>
%(body)s
<footer>
  Live fan-out to %(engine_list)s. One scraped engine, the rest official
  public APIs; deduplicated by URL. No result cache, no tracking, no ads.
  Engines that refuse the fetch — or answer with poisoned filler — report
  here instead of vanishing.
</footer>
</main></body></html>"""


ENGINE_BLURBS = {
    "bing": "live HTML scrape — the one engine we beg for forgiveness",
    "searxng": "the local SearXNG instance — dozens of engines behind one API",
    "marginalia": "independent non-profit web index",
    "wikipedia": "official API, no key",
    "stackexchange": "Stack Overflow, official API, no key",
    "hn": "Hacker News via the Algolia API",
}


def render(data):
    query = data["query"]
    if not query:
        tries = "".join(
            "<a class='chip' href='/?q=%s'>%s</a>" % (urllib.parse.quote(t), t)
            for t in ["searxng", "java applet 2002", "marginalia",
                      "shanghai", "tiny house"])
        engcards = "".join(
            "<div class='eng'><b>%s</b><span>%s · %s</span></div>" % (
                html.escape(name), html.escape(kind),
                html.escape(ENGINE_BLURBS.get(name, "")))
            for name, _fn, kind in ENGINES)
        body = (
            "<div class='chips' style='margin-top:1.2rem'>"
            "<span class='try'>try</span>%s</div>"
            "<h4>%d engines fanned out on every query</h4>"
            "<div class='engines'>%s</div>" % (tries, len(ENGINES), engcards))
    else:
        chips = "".join(
            "<span class='chip'>%s · %s · %dms</span>" % (
                e["engine"],
                ("<b>%d result%s</b>" % (e["count"], "s" if e["count"] != 1 else ""))
                if not e["error"] else "<i>%s</i>" % e["error"],
                e["ms"])
            for e in data["engines"])
        if data["results"]:
            items = "".join(
                "<article><span class='badge'>%s</span>"
                "<a href='%s' rel='noopener'>%s</a>"
                "<div class='u'>%s</div><p class='s'>%s</p></article>" % (
                    html.escape(r["engine"]), html.escape(r["url"], quote=True),
                    html.escape(r["title"]), html.escape(r["url"]),
                    html.escape(r["snippet"]))
                for r in data["results"])
            body = (
                "<div class='chips'>%s</div>"
                "<p class='meta'>%d results · %d duplicates dropped · %d ms</p>%s"
                % (chips, len(data["results"]), data["duplicatesDropped"],
                   data["durationMs"], items))
        else:
            body = ("<div class='chips'>%s</div>"
                    "<p class='meta'>No results — every engine failed or came back empty.</p>%s"
                    % (chips, "".join("<span class='chip'><i>%s</i></span>" % e["error"]
                                      for e in data["engines"] if e["error"])))
    return PAGE % {
        "query": html.escape(query),
        "body": body,
        "engine_list": ", ".join(name for name, _fn, _k in ENGINES),
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        query = (params.get("q", [""])[0] or "").strip()

        if parsed.path == "/api/search":
            data = run_search(query) if query else {"error": "missing q"}
            payload = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(payload)
            return

        data = run_search(query) if query else {"query": "", "engines": [], "results": [],
                                                "duplicatesDropped": 0, "durationMs": 0}
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
    print("FIND engine on http://127.0.0.1:%d/  (%s, live)"
          % (args.port, " + ".join(name for name, _fn, _k in ENGINES)))
    server.serve_forever()


if __name__ == "__main__":
    main()
