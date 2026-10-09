# JSearXNG

A real metasearcher, **in Java** — the J is earned, not inherited. The name is
the architecture: **JSearch**, the 2002 Java applet archived in the
neighbouring repository, supplies the skeleton — fan-out, scrape, deduplicate,
show every engine's failure honestly — and **SearXNG**, the local instance,
supplies a community-maintained engine pool behind one JSON call. Same idea
the 2002 author had, in a world that fights back, and the 2002 applet UI is
reborn as the face.

Single file (`JSearXNG.java`), JDK standard library only — `java.net.http`,
`com.sun.net.httpserver`, `java.util.concurrent` — no build step: the source
is executed directly. The JSON reader inside is copied from the teaching
sketch (`JSearch/modern/`), which is the lineage helping its own descendant.

```sh
java JSearXNG.java         # http://127.0.0.1:8092/
```

## Service

A systemd user unit keeps it running and starts it at boot:

```sh
systemctl --user status jsearxng      # running on 127.0.0.1:8092
journalctl --user -u jsearxng -f      # logs
loginctl show-user $USER -p Linger     # Linger=yes = starts at boot
```

## Engines

| Engine | How | Notes |
|---|---|---|
| Bing | HTML scrape (`li.b_algo`) | works from a plain server fetch today |
| SearXNG | the local instance's JSON API (`127.0.0.1:8888`) | dozens of engines behind one call; result badges name the upstream |
| Marginalia | official public API (`api.marginalia.nu`) | independent web index, no key |
| Wikipedia | official public API (`/w/api.php`) | no key, designed for this |
| Stack Overflow | official public API (`api.stackexchange.com`) | no key, 300 req/day/IP |
| Hacker News | official Algolia public API | no key, designed for this |

The SearXNG entry makes this a metasearcher over a metasearcher: one of the
fanned-out engines is itself a fan-out. Duplicates between the layers are
dropped by the same URL-dedup, and the `duplicatesDropped` counter shows it
working.

Deliberately **not** wired in, because they return bot-challenge pages (or
redirect walls) to a plain fetch from a server IP as of 2026-10: DuckDuckGo
(html and lite endpoints both 202), Google (302), Mojeek (JS check page).
This was verified with real requests before writing the parsers, and is the
same wall the archive's `README.md` predicted for its 2001-era engines.

**Bing also poisons**: after a few requests it stops erroring and starts
serving random popular pages instead of the asked-for results. `run_search`
runs a relevance sanity-check on every engine's response — if no result
mentions any query term, the engine's slot shows
`response failed the relevance check … likely a bot-block page` instead of
garbage results. Do not trust a scraped engine that returns "success".

## Honesty rules

- One engine failing never sinks the others (the 2002 rule). Each engine's
  outcome — count or error, and its latency — is shown as a chip on the page
  and in the JSON.
- Results are deduplicated by normalized URL; the page reports how many were
  dropped.
- No cache, no tracking, no ads. One request per engine per query — nothing
  auto-retries, nothing hammers.

## API

```sh
curl 'http://127.0.0.1:8091/api/search?q=searxng'
```

Returns `{query, results[{title,url,snippet,engine}], engines[{engine,count,
error,ms}], durationMs, duplicatesDropped}`.

## Adding an engine

Write `def search_x(query) -> list[Result]`, add it to `ENGINES`. If it dies,
its exception becomes that engine's chip text — by construction.
