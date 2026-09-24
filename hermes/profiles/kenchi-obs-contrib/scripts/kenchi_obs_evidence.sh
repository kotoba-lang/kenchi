#!/bin/bash
# Shared signal for kenchi-obs-contrib (deterministic, no timestamps in output).
# Unauthenticated read-only probes. Format: OBS\tkey\tvalue  /  PROBE\tkey\tvalue
set -u

REPO=~/github/com-junkawasaki/orgs/kotoba-lang/kenchi
WS=~/.hermes/profiles/kenchi-obs-contrib/workspace

p() { printf 'PROBE\t%s\t%s\n' "$1" "$2"; }
o() { printf 'OBS\t%s\t%s\n' "$1" "$2"; }

# 1. repo pin freshness (read-only)
if [ -d "$REPO/.git" ]; then
  local_head=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null)
  git -C "$REPO" fetch origin main --quiet 2>/dev/null
  remote_tip=$(git -C "$REPO" rev-parse --short origin/main 2>/dev/null)
  p repo_head "${local_head:-UNMEASURED} vs origin/main ${remote_tip:-UNMEASURED}"
else
  p repo_head "CHECKOUT-ABSENT"
fi

# 2. kenchi test suite green? (kbb/sci, offline) - launcher failure vs suite red are distinct
cd "$REPO" 2>/dev/null || { p suite "REPO-ABSENT"; exit 2; }
suite_out=$(NBB_CLJK_ROOTS='["~/github/com-junkawasaki/orgs/kotoba-lang/kenchi","~/github/com-junkawasaki/orgs/kotoba-lang/text","~/github/com-junkawasaki/orgs/kotoba-lang/langgraph","~/github/com-junkawasaki/orgs/kotoba-lang/langchain"]' \
  kbb --backend sci --classpath "src:test:../text/src:../langgraph/src:../langchain/src" \
  -e "(require '[clojure.test :as t] '[kenchi.core-test] '[kenchi.provenance-contract-test] '[kenchi.live-pipeline-test] '[kenchi.commoncrawl-test] '[kenchi.contrib-test]) (t/run-all-tests)" 2>&1)
rc=$?
if [ $rc -ne 0 ]; then
  p suite "UNMEASURED launcher-rc=$rc fingerprint=$(echo "$suite_out" | head -1 | cut -c1-80)"
else
  line=$(echo "$suite_out" | grep -oE 'Ran [0-9]+ tests containing [0-9]+ assertions' | head -1)
  fails=$(echo "$suite_out" | grep -oE '[0-9]+ failures, [0-9]+ errors' | head -1)
  p suite "${line:-no-summary} ${fails:-no-fail-line}"
fi

# 3. kenchi.contrib existence (the membrane path this bot drives)
if [ -f "$REPO/src/kenchi/contrib.cljk" ]; then
  p contrib_ns "EXISTS ($(grep -c 'defn' "$REPO/src/kenchi/contrib.cljk") defn)"
else
  p contrib_ns "ABSENT (gap: member-submitted Observation path not yet implemented)"
fi

# 4. ATProto observation records - poll the aozora PDS public endpoint (unauthenticated)
# kenchi actor did:web:kenchi.etzhayyim.com - resolve the PDS host, then listRecords.
did_doc=$(curl -sS --max-time 20 "https://kenchi.etzhayyim.com/.well-known/atproto-did" 2>/dev/null | head -c 200)
if [ -n "$did_doc" ]; then
  p atproto_did "did-doc-first-200=$(echo "$did_doc" | tr '\n' ' ' | cut -c1-120)"
else
  p atproto_did "UNMEASURED (no /.well-known/atproto-did at kenchi.etzhayyim.com)"
fi

# count existing processed slices so the bot proposes only NEW material
if [ -d "$WS/slices" ]; then
  n=$(find "$WS/slices" -name '*.edn' 2>/dev/null | wc -l | tr -d ' ')
  p slices_processed "$n"
else
  p slices_processed "0"
fi

echo "=== raw smoke ==="
echo "repo: $(git -C "$REPO" log --oneline -1 2>/dev/null)"
