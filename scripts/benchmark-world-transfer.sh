#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cli_bin="$project_root/cli/build/install/cli/bin/cli"
benchmark_root="${1:-$(mktemp -d /tmp/enderlink-world-benchmark.XXXXXX)}"
receiver_pid=""
monitor_pid=""
trap 'if [[ -n "$receiver_pid" ]]; then kill "$receiver_pid" 2>/dev/null || :; fi; if [[ -n "$monitor_pid" ]]; then kill "$monitor_pid" 2>/dev/null || :; fi' EXIT

if [[ ! -x "$cli_bin" ]]; then
    printf 'Сначала выполните ./gradlew :cli:installDist\n' >&2
    exit 1
fi
mkdir -p "$benchmark_root/source/empty" "$benchmark_root/sender" "$benchmark_root/receiver"
python3 - "$benchmark_root/source/region.mca" <<'PY'
import random
import sys

random_source = random.Random(42)
with open(sys.argv[1], "wb") as output:
    output.write(random_source.randbytes(8 * 1024 * 1024))
PY
printf 'level' > "$benchmark_root/source/level.dat"
benchmark_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"

publish() {
    local label="$1"
    /usr/bin/time -f 'publish_elapsed_s=%e publish_max_rss_kib=%M' -o "$benchmark_root/$label.publish.time" \
        "$cli_bin" world-publish --source "$benchmark_root/source" --repository "$benchmark_root/sender" > "$benchmark_root/$label.publish.log"
}

transfer() {
    local label="$1"
    rm -f "$benchmark_root/$label.monitor.stop"
    python3 - "$benchmark_root/receiver" "$benchmark_root/$label.monitor" "$benchmark_root/$label.monitor.stop" <<'PY' &
import os
import sys
import time

root, result, stop = sys.argv[1:]
def apparent_bytes():
    total = 0
    for directory, _, names in os.walk(root):
        for name in names:
            try:
                total += os.stat(os.path.join(directory, name), follow_symlinks=False).st_size
            except FileNotFoundError:
                pass
    return total

baseline = apparent_bytes()
peak = baseline
while not os.path.exists(stop):
    peak = max(peak, apparent_bytes())
    time.sleep(0.005)
peak = max(peak, apparent_bytes())
with open(result, "w", encoding="utf-8") as output:
    output.write(f"receiver_baseline_bytes={baseline}\nreceiver_peak_bytes={peak}\n"
                 f"receiver_additional_peak_bytes={peak - baseline}\n"
                 f"receiver_final_bytes={apparent_bytes()}\n")
PY
    monitor_pid="$!"
    "$cli_bin" world-receive --repository "$benchmark_root/receiver" --host 127.0.0.1 --port "$benchmark_port" \
        > "$benchmark_root/$label.receive.log" 2>&1 &
    receiver_pid="$!"
    sleep 0.5
    /usr/bin/time -f 'send_elapsed_s=%e send_max_rss_kib=%M' -o "$benchmark_root/$label.send.time" \
        "$cli_bin" world-send --repository "$benchmark_root/sender" --host 127.0.0.1 --port "$benchmark_port" \
        > "$benchmark_root/$label.send.log"
    wait "$receiver_pid"
    receiver_pid=""
    touch "$benchmark_root/$label.monitor.stop"
    wait "$monitor_pid"
    monitor_pid=""
    cmp "$benchmark_root/source/region.mca" "$benchmark_root/receiver/world/region.mca"
    if [[ -f "$benchmark_root/source/new.dat" ]]; then cmp "$benchmark_root/source/new.dat" "$benchmark_root/receiver/world/new.dat"; fi
    test -d "$benchmark_root/receiver/world/empty"
    printf '\n%s\n' "$label"
    cat "$benchmark_root/$label.send.time"
    cat "$benchmark_root/$label.monitor"
    sed -n '/Сеть →/p' "$benchmark_root/$label.send.log"
    du -sb "$benchmark_root/receiver/.enderlink/objects" "$benchmark_root/receiver/world" "$benchmark_root/receiver/.enderlink/staging"
    if [[ -d "$benchmark_root/receiver/.enderlink/previous" ]]; then du -sb "$benchmark_root/receiver/.enderlink/previous"; fi
}

publish initial
cat "$benchmark_root/initial.publish.time"
transfer initial
transfer unchanged

python3 - "$benchmark_root/source/region.mca" <<'PY'
import sys

with open(sys.argv[1], "r+b") as region:
    region.seek(3 * 262144 + 100)
    previous = region.read(1)[0]
    region.seek(-1, 1)
    region.write(bytes([previous ^ 1]))
PY
publish changed
cat "$benchmark_root/changed.publish.time"
transfer changed

python3 - "$benchmark_root/source/new.dat" <<'PY'
import random
import sys

with open(sys.argv[1], "wb") as output:
    output.write(random.Random(99).randbytes(4 * 1024 * 1024))
PY
publish new_data
cat "$benchmark_root/new_data.publish.time"
transfer new_data
printf '\nЛоги и данные: %s\n' "$benchmark_root"
