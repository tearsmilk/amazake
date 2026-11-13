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
# The core layout (amazake.yml cpu.layout) is only applied with -XX:ActiveProcessorCount, as the README says.
cpus=$(nproc)
java -Xmx2G -XX:ActiveProcessorCount="$cpus" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$jar" --nogui < input > server.log 2>&1 &
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
# The plugin lock on (one tick thread: never contended), so the stress run below goes through the locked path.
sed -i -E '/^plugin-threading:/,/^[a-z]/ s|^([[:space:]]+mode:).*|\1 global-lock|' amazake.yml
send "amazake reload"
send "amazake cores"
send "spark tps"
send "amazake profile 5" 15
# The /amazake profile link, taken now: the stress run below uploads a second report.
amazake_id=$(grep -oE "127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" server.log | tail -1 | sed 's|.*/r/||' || true)

stress_from=0
if [ -n "$stress_jar" ]; then
  stress_from=$(wc -l < server.log)
  send "stress run 40" 0
  # /amazake cores again once the "async" module runs (from the "packets" stage on): its own worker threads
  # ("AmazakeStress Worker #N", created by the plugin) must be found as plugins-own and moved to the plugins role.
  for _ in $(seq 1 120); do
    tail -n "+$((stress_from + 1))" server.log | grep -qE " 5 packets \(" && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  # A short /amazake profile during the run, once the "async" module runs: its "plugins" block must show the
  # plugin's main-thread tasks/events and its async scheduler CPU.
  send "amazake profile 5" 0
  sleep 4
  send "amazake cores" 0
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
check 'Plugin lock: on \(plugin-threading\.mode: global-lock\)' "/amazake reload turned the plugin lock on"
check 'TPS from last' "/spark works (bundled spark)"
check 'first time Amazake starts' "Amazake's first-start message"
if grep -qE 'papermc\.io/downloads|outdated version of Minecraft|docs\.papermc\.io/paper/next-steps' server.log; then
  echo "FOUND: a Paper download or onboarding message"; fail=1
else
  echo "ok: no Paper download or onboarding message"
fi
if [ -f amazake.yml ]; then echo "ok: amazake.yml written"; cat amazake.yml; else echo "MISSING: amazake.yml"; fail=1; fi

# The core layout: one English line at startup (and on reload), and the /amazake cores table.
echo "---- core layout ($cpus CPUs) ----"
grep -E 'Amazake cores:' server.log || true
if [ "$cpus" -ge 2 ]; then
  check 'Amazake cores: tick=\[[0-9,-]+\] chunks=\[' "the core layout is applied at startup"
  if grep -qE 'Amazake cores: disabled' server.log; then
    echo "FOUND: the core layout was disabled on a $cpus-CPU runner"; fail=1
  else
    echo "ok: the core layout was not disabled"
  fi
  check 'Раскладка ядер включена: tick=\[' "/amazake cores says the layout is on"
  check '[[:space:]]0  tick[[:space:]]+[0-9]+%' "/amazake cores prints the core -> role table with the load"
  check 'Потоки по группам' "/amazake cores lists where the thread groups run"
  check 'main \(tick\): [0-9]+ -> \[[0-9]+\]([^,]|$)' "/amazake cores shows the main thread on the tick core"
  # cpu.reserve is on by default: either reserve CPUs (auto: from 6 CPUs) or the reason there are none.
  check 'Резерв: (ядра \[[0-9,-]+\] пустые|нет \()' "/amazake cores prints the reserve line"
  check 'Плагины: ' "/amazake cores prints the plugins line"
  check 'Своих потоков плагинов найдено \(группа plugins-own\): [0-9]+' "/amazake cores counts plugin-created threads"
  if [ "$cpus" -ge 4 ]; then
    # From 4 CPUs the plugins have a core of their own.
    check '[[:space:]][0-9]+  plugins[[:space:]]+[0-9]+%' "/amazake cores shows a core of the plugins role"
    check 'Плагины: (ядро|ядра) \[[0-9,-]+\] целиком' "/amazake cores says the plugins core is theirs alone"
  fi
else
  check 'Amazake cores: disabled \(only [0-9]+ CPU' "the core layout says why it is off on $cpus CPU"
  check 'Раскладка ядер выключена' "/amazake cores says the layout is off"
fi

# The profile was uploaded: the server printed a /r/<id> link, and the site gives the report back.
id=$amazake_id
if [ -z "$id" ]; then
  echo "MISSING: /amazake profile uploaded a report (no /r/<id> link in the log)"; fail=1
else
  echo "ok: /amazake profile uploaded report $id"
  curl -fsS "http://127.0.0.1:$site_port/api/reports/$id" -o report.json
  if python3 - report.json "$cpus" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
cpus = int(sys.argv[2])
assert r["format"] == "amazake-profile" and r["version"] == 1, "format"
assert r["server"]["brand"] == "Amazake", r["server"]["brand"]
assert len(r["samples"]) >= 3, "samples: %d" % len(r["samples"])
groups = {g["group"] for g in r["groups"]}
assert "main" in groups, "no main thread group: %s" % sorted(groups)
assert any(t["name"] == "Server thread" for t in r["threads"]), "no Server thread"
sysinfo = r["system"]
assert sysinfo["logicalCores"] == cpus, "logicalCores %s, nproc %d" % (sysinfo["logicalCores"], cpus)
if sysinfo["perCore"] and cpus >= 2:
    roles = sysinfo.get("coreRoles")
    assert isinstance(roles, list), "no system.coreRoles while the core layout is on"
    assert len(roles) == sysinfo["logicalCores"], "coreRoles %d entries, logicalCores %d" % (len(roles), sysinfo["logicalCores"])
    known = {"tick", "chunks", "network", "gc", "plugins", "background", "reserve"}
    for role in roles:
        assert role == "shared" or set(role.split("+")) <= known, "unknown core role %r" % role
    assert roles.count("tick") == 1, "one tick core expected: %s" % roles
    if cpus >= 4:
        assert "plugins" in roles, "no core of the plugins role alone on %d CPUs: %s" % (cpus, roles)
    assert all(len(s["cores"]) == len(roles) for s in r["samples"] if s.get("cores")), "samples[].cores length"
e = r.get("entities")
assert isinstance(e, dict), "no entities block (entity tick breakdown)"
assert [c["class"] for c in e["classes"]] == ["mob", "other", "player"], e["classes"]
assert e["ticks"] > 0, e  # no player: worlds may skip entities after 15 s idle, so loop can be 0
total = sum(c["msPerTick"] for c in e["classes"]) + e["msPerTick"]["ear"] + e["msPerTick"]["despawn"] + e["msPerTick"]["overhead"]
assert abs(total - e["msPerTick"]["loop"]) <= 0.02 + 0.01 * e["msPerTick"]["loop"], "entities do not add up: %s vs loop %s" % (total, e["msPerTick"]["loop"])
assert any(isinstance(s.get("entityTicks"), dict) for s in r["samples"]), "no samples[].entityTicks"
assert all(isinstance(s.get("entities", 0), int) for s in r["samples"]), "samples[].entities must stay the entity count"
print("entities: loop %.3f ms/tick, %s, top types %s" % (e["msPerTick"]["loop"],
    ", ".join("%s %.3f" % (c["class"], c["msPerTick"]) for c in e["classes"]), [t["type"] for t in e["types"][:5]]))
p = r.get("plugins")
assert isinstance(p, dict), "no plugins block (plugin tick breakdown)"
assert p["ticks"] > 0 and isinstance(p["plugins"], list), p
assert any("pluginTicks" in s for s in r["samples"]), "no samples[].pluginTicks"
print("plugins: %.3f ms/tick, %s" % (p["msPerTick"], [(x["name"], x["msPerTick"]) for x in p["plugins"][:5]]))
print("report: %d samples, %d threads, groups %s, %d cores, per-core %s, core roles %s" % (
    len(r["samples"]), len(r["threads"]), sorted(groups), sysinfo["logicalCores"], sysinfo["perCore"], sysinfo.get("coreRoles")))
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
  if [ "$cpus" -ge 2 ]; then
    # The /amazake cores sent during the "packets" stage: the plugin's own worker threads are plugins-own.
    stress_check 'plugins-own \(plugins\): [0-9]+ -> \[' "/amazake cores during the run puts plugin-created threads in plugins-own"
    stress_check 'Своих потоков плагинов найдено \(группа plugins-own\): [1-9][0-9]* \(.*"AmazakeStress W"' \
      "the AmazakeStress worker threads are found as plugin threads"
  fi
  stress_check 'Run completed \(.*at tick 800/800' "the run went through all 800 ticks and stopped by itself"
  stress_check 'Races: [1-9][0-9]* checks, 0 violations, 0 exceptions' "the races module checked its invariants: no race"
  if grep -nE 'Race check failed' stress.log; then echo "FOUND: a race in the races module"; fail=1; fi
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
assert sorted(modules) == ["async", "chunks", "mobs", "packets", "physics", "races", "redstone"], sorted(modules)
failed = [m["id"] + ": " + str(m.get("failure")) for m in modules.values() if m["failed"]]
assert not failed, failed
# The races module (a deliberately non-thread-safe plugin workload) must see no race: 0 violations, 0 exceptions.
races = s.get("races")
assert isinstance(races, dict), "no stress.races block"
assert races["checks"] >= 3 and races["counted"] > 0 and races["events"] > 0, races
assert races["violations"] == 0 and races["exceptions"] == 0, races
print("stress races: %d checks, %d violations, %d exceptions, %d events (%d off-main), %d transfers" % (
    races["checks"], races["violations"], races["exceptions"], races["events"], races["offMainEvents"], races["transfers"]))
e = r.get("entities")
assert isinstance(e, dict), "the stress report has no entities block on Amazake"
mobs = next(c for c in e["classes"] if c["class"] == "mob")
assert mobs["msPerTick"] > 0 and mobs["entitiesPerTick"] > 0, mobs
staged = {x["stage"]: x.get("entities") for x in s["stageResults"]}
assert isinstance(staged.get("mobs"), dict), "no stageResults[mobs].entities"
assert next(c for c in staged["mobs"]["classes"] if c["class"] == "mob")["msPerTick"] > 0, staged["mobs"]["classes"]
print("stress entities: " + ", ".join("%s=%s" % (k, None if v is None else round(next(c for c in v["classes"] if c["class"] == "mob")["msPerTick"], 3)) for k, v in staged.items()))
print("stress report: %d samples, stages %s" % (len(r["samples"]), " ".join(
    "%s=%.1fms" % (x["stage"], x["msptAvg"]) for x in s["stageResults"])))
for m in s["modules"]:
    print("  %s: %s" % (m["id"], m["status"]))
EOF
    then echo "ok: the stress report is a valid profile with the full stage timeline"; else echo "BAD: the stress report"; fail=1; fi
  fi
  # The /amazake profile taken during the run (its own link line, not the plugin's).
  # (colour codes sit between the prefix and the text, hence the .*)
  pid2=$(grep -E "\[Профайлер\].*Отчёт готов:.*127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" stress.log | grep -v AmazakeStress \
    | grep -oE "127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" | tail -1 | sed 's|.*/r/||' || true)
  if [ -z "$pid2" ] || [ "$pid2" = "$amazake_id" ] || [ "$pid2" = "$sid" ]; then
    echo "MISSING: /amazake profile during the stress run uploaded a report"; fail=1
  else
    curl -fsS "http://127.0.0.1:$site_port/api/reports/$pid2" -o during-stress.json
    if python3 - during-stress.json <<'EOF'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
p = r.get("plugins")
assert isinstance(p, dict) and p["ticks"] > 0, "no plugins block: %s" % p
row = next((x for x in p["plugins"] if x["name"] == "AmazakeStress"), None)
assert row is not None, "AmazakeStress missing from plugins: %s" % [x["name"] for x in p["plugins"]]
assert row["msPerTick"] > 0, row
assert row["asyncCpuMsPerSecond"] > 0, row
# plugin-threading.mode: global-lock: the lock fields are there; AmazakeStress (folia-supported: false) ran under it.
assert isinstance(p.get("lockHeldMsPerTick"), (int, float)) and p["lockHeldMsPerTick"] > 0, "lockHeldMsPerTick: %s" % p.get("lockHeldMsPerTick")
assert all(isinstance(x.get("lockWaitMsPerTick"), (int, float)) for x in p["plugins"]), "plugins[].lockWaitMsPerTick missing"
assert row["lockWaitMsPerTick"] >= 0, row
print("plugins during stress: %.3f ms/tick, lock held %.3f ms/tick; AmazakeStress %s" % (p["msPerTick"], p["lockHeldMsPerTick"], row))
EOF
    then echo "ok: /amazake profile during the run measured AmazakeStress (main thread and async CPU)"; else echo "BAD: the plugins block during the stress run"; fail=1; fi
  fi
fi
exit $fail
