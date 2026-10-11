# JSearXNG — moved

This project now lives in the JSearch repository, at
[**`jsearxng/`**](https://github.com/uhuntu/JSearch/tree/main/jsearxng).

Nothing was lost in the move. The history came across by subtree merge, so all
14 commits are ancestors of the JSearch repository — including the fixes for the
repeated-form-key collapse, the `&amp;` that survived inside Bing's tracking
URLs, the raw `&#0183;` in a preview, and the HTTP 451 that ended the session
which built it.

This repository is kept read-only so existing links and stars keep working. It
will not be updated.

## Why it moved

JSearch is the archive of the 2002 Java applet this project descends from, and
the descendant now sits beside its ancestors in one place: `Sources/` and
`versions/` hold the artifact, `modern/` is the design contrast, `runner/` proves
the untouched original still runs, and `jsearxng/` is what the design became.
The 2002 applet UI reborn as the descendant's face is a great deal easier to
believe when you can scroll up in the same repository and read the thing it is
the face of.

## Run it

```sh
git clone https://github.com/uhuntu/JSearch
cd JSearch/jsearxng
java JSearXNG.java        # http://127.0.0.1:8092/   (needs JDK 16+)
./test.sh                 # 117 checks, no network
```

See [`jsearxng/README.md`](https://github.com/uhuntu/JSearch/tree/main/jsearxng)
for the engines, the honesty rules and the API.
