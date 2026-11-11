#!/usr/bin/env bash
# Starts the built server once, runs a few commands and checks it answers as Amazake, then runs a short
# /amazake profile against the profiler site's backend (web/server) and fetches the report back.
# With the AmazakeStress plugin jar as second argument, the server starts with the plugin and also runs
# "/stress run 40" with tiny intensities (scripts/stress-smoke-config.yml): the stages must advance, no
# exception may come from the plugin, and the plugin's profiler report must reach the local site.
#   scripts/smoke-test.sh path/to/amazake-paperclip.jar [path/to/AmazakeStress.jar]
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
jar=$(realpath "$1")
stress_jar=""
if [ $# -ge 2 ]; then
  stress_jar=$(realpath "$2")
fi
dir=$(mktemp -d)
site_port=18787
cd "$dir"

# The profiler site, as on a VPS but on localhost.
python3 "$repo/web/server/amazake_web.py" --host 127.0.0.1 --port "$site_port" \
  --data-dir "$dir/site-data" --static-dir "$repo/web/static" --min-free-mb 0 > site.log 2>&1 &
site_pid=$!
trap 'kill "$site_pid" 2>/dev/null || true' EXIT

echo "eula=true" > eula.txt
printf 'online-mode=false\nspawn-protection=0\nmax-players=1\n' > server.properties
if [ -n "$stress_jar" ]; then
  mkdir -p plugins/AmazakeStress
  cp "$stress_jar" plugins/
  sed -E "s|^([[:space:]]*upload-url:).*|\1 \"http://127.0.0.1:$site_port\"|" \
    "$repo/scripts/stress-smoke-config.yml" > plugins/AmazakeStress/config.yml
fi
mkfifo input
# Keep the fifo open for writing so the server never sees end-of-input.
exec 3<>input
java -Xmx2G -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$jar" --nogui < input > server.log 2>&1 &
pid=$!
for _ in $(seq 1 600); do
  grep -q 'Done (' server.log && break
  kill -0 "$pid" 2>/dev/null || { cat server.log; echo "The server stopped before it was ready"; exit 1; }
  sleep 1
done
grep -q 'Done (' server.log || { tail -50 server.log; echo "Not ready after 10 minutes"; exit 1; }

send() { echo "$1" >&3; sleep "${2:-3}"; }
send "version"
send "amazake version"
# Point the profiler at the local site, then reload the settings.
sed -i -E "s|^([[:space:]]*upload-url:).*|\1 http://127.0.0.1:$site_port|" amazake.yml
send "amazake reload"
send "spark tps"
send "amazake profile 5" 15
# The /amazake profile link, taken now: the stress run below uploads a second report.
amazake_id=$(grep -oE "127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" server.log | tail -1 | sed 's|.*/r/||' || true)

stress_from=0
if [ -n "$stress_jar" ]; then
  stress_from=$(wc -l < server.log)
  send "stress run 40" 0
  # World creation + 40 s of load + the upload; the plugin prints the link when the report is on the site.
  for _ in $(seq 1 300); do
    tail -n "+$((stress_from + 1))" server.log | grep -qE "AmazakeStress.*127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  send "stress status" 2
fi
send "stop" 0
wait "$pid" || true
cat server.log
echo "---- site log ----"
cat site.log || true

echo "---- checks ----"
fail=0
check() { if grep -qE "$1" server.log; then echo "ok: $2"; else echo "MISSING: $2"; fail=1; fi; }
check 'This server is running Amazake' "/version names Amazake"
check 'Amazake 1\.21\.11' "/amazake version answers"
check 'Loaded amazake\.yml' "amazake.yml is read"
check 'Reloaded amazake\.yml' "/amazake reload works"
check 'TPS from last' "/spark works (bundled spark)"
check 'first time Amazake starts' "Amazake's first-start message"
if grep -qE 'papermc\.io/downloads|outdated version of Minecraft|docs\.papermc\.io/paper/next-steps' server.log; then
  echo "FOUND: a Paper download or onboarding message"; fail=1
else
  echo "ok: no Paper download or onboarding message"
fi
if [ -f amazake.yml ]; then echo "ok: amazake.yml written"; cat amazake.yml; else echo "MISSING: amazake.yml"; fail=1; fi

# The profile was uploaded: the server printed a /r/<id> link, and the site gives the report back.
id=$amazake_id
if [ -z "$id" ]; then
  echo "MISSING: /amazake profile uploaded a report (no /r/<id> link in the log)"; fail=1
else
  echo "ok: /amazake profile uploaded report $id"
  curl -fsS "http://127.0.0.1:$site_port/api/reports/$id" -o report.json
  if python3 - report.json <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
assert r["format"] == "amazake-profile" and r["version"] == 1, "format"
assert r["server"]["brand"] == "Amazake", r["server"]["brand"]
assert len(r["samples"]) >= 3, "samples: %d" % len(r["samples"])
groups = {g["group"] for g in r["groups"]}
assert "main" in groups, "no main thread group: %s" % sorted(groups)
assert any(t["name"] == "Server thread" for t in r["threads"]), "no Server thread"
print("report: %d samples, %d threads, groups %s, %d cores, per-core %s" % (
    len(r["samples"]), len(r["threads"]), sorted(groups), r["system"]["logicalCores"], r["system"]["perCore"]))
EOF
  then echo "ok: the report is a valid Amazake profile"; else echo "BAD: the report is not what the contract says"; fail=1; fi
  if curl -fsS "http://127.0.0.1:$site_port/r/$id" | grep -qi '<html'; then echo "ok: /r/$id serves the viewer"; else echo "MISSING: viewer page"; fail=1; fi
fi

if [ -n "$stress_jar" ]; then
  echo "---- AmazakeStress ----"
  tail -n "+$((stress_from + 1))" server.log > stress.log
  check 'AmazakeStress ready: modules' "the AmazakeStress plugin is enabled"
  stress_check() { if grep -qE "$1" stress.log; then echo "ok: $2"; else echo "MISSING: $2"; fail=1; fi; }
  for stage in "0 baseline" "1 mobs" "2 physics" "3 redstone" "4 chunks" "5 packets" "6 max"; do
    stress_check " $stage \\(" "stage $stage started"
  done
  stress_check '\[max\] t=[0-9]+/40s' "the periodic status line reaches the max stage"
  stress_check 'Run completed \(.*at tick 800/800' "the run went through all 800 ticks and stopped by itself"
  # A module that throws is disabled and logged at SEVERE with its stack trace; any trace through the plugin fails.
  if grep -nE 'at ink\.hoka\.amazake\.stress\.|Module [a-z]+ failed in |\[[a-z]+\] task failed|Cannot create module' server.log; then
    echo "FOUND: an exception from ink.hoka.amazake.stress"; fail=1
  else
    echo "ok: no exception from ink.hoka.amazake.stress"
  fi
  sid=$(grep -oE "AmazakeStress.*127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" stress.log | tail -1 | sed 's|.*/r/||' || true)
  if [ -z "$sid" ] || [ "$sid" = "$amazake_id" ]; then
    echo "MISSING: /stress run uploaded its profiler report (no AmazakeStress /r/<id> link)"; fail=1
  else
    echo "ok: /stress run uploaded report $sid"
    curl -fsS "http://127.0.0.1:$site_port/api/reports/$sid" -o stress-report.json
    if python3 - stress-report.json <<'EOF'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
assert r["format"] == "amazake-profile" and r["version"] == 1, "format"
assert r["server"]["brand"] == "Amazake", r["server"]["brand"]
assert len(r["samples"]) >= 30, "samples: %d" % len(r["samples"])
assert any(t["name"] == "Server thread" for t in r["threads"]), "no Server thread"
assert any(w["name"] == "stress_world" for w in r["server"]["worlds"]), "no stress_world in worlds"
s = r["stress"]
assert s["scenario"]["mode"] == "all" and s["scenario"]["totalTicks"] == 800, s["scenario"]
stages = [e["stage"] for e in s["stageTimeline"]]
assert stages == ["baseline", "mobs", "physics", "redstone", "chunks", "packets", "max"], stages
assert s["stageTimeline"][-1]["endTick"] == 800, s["stageTimeline"][-1]
assert s["finished"] is True and s["runTicks"] == 800, (s["finished"], s["runTicks"])
done = [x["stage"] for x in s["stageResults"]]
assert done == stages, done
modules = {m["id"]: m for m in s["modules"]}
assert sorted(modules) == ["async", "chunks", "mobs", "packets", "physics", "redstone"], sorted(modules)
failed = [m["id"] + ": " + str(m.get("failure")) for m in modules.values() if m["failed"]]
assert not failed, failed
print("stress report: %d samples, stages %s" % (len(r["samples"]), " ".join(
    "%s=%.1fms" % (x["stage"], x["msptAvg"]) for x in s["stageResults"])))
for m in s["modules"]:
    print("  %s: %s" % (m["id"], m["status"]))
EOF
    then echo "ok: the stress report is a valid profile with the full stage timeline"; else echo "BAD: the stress report"; fail=1; fi
  fi
fi
exit $fail
