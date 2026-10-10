#!/bin/sh
# Compile the app and its test suite together, then run the suite.
#
# Every check is on a pure function — the entity layer, query parsing, URL
# dedup keys, the poison check, Bing redirect unwrapping, escaping, the JSON
# reader, the page render — so this needs no network, no fixtures and no
# clock. The network half is the live service itself, plus each engine's own
# error reporting in the >>消息 pane.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac -encoding UTF-8 -d out JSearXNG.java JSearXNGTest.java
java -cp out JSearXNGTest
