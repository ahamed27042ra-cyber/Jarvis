#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="/data/data/com.termux/files/home/jarvis_work/mcp_watchdog"
mkdir -p "$BASE"
if [ ! -f "$BASE/watchdog.env" ]; then
  cp "$BASE/watchdog.env.example" "$BASE/watchdog.env"
  echo "Created $BASE/watchdog.env"
  echo "Fill in the values, then run this script again."
  exit 2
fi
nohup "$BASE/mcp_watchdog.sh" >> "$BASE/watchdog.log" 2>&1 &
echo $! > "$BASE/watchdog.pid"
echo "MCP watchdog started: PID $(cat "$BASE/watchdog.pid")"
