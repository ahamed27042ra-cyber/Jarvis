#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="/data/data/com.termux/files/home/jarvis_work/mcp_watchdog"
[ -f "$BASE/watchdog.env" ] && . "$BASE/watchdog.env"

log(){ printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$BASE/watchdog.log"; }

# Optional commands are configured by the user in watchdog.env.
# They are intentionally empty by default so recovery cannot execute an unknown command.
if [ -n "${MCP_RESTART_COMMAND:-}" ]; then
  log "Running configured MCP restart command"
  bash -lc "$MCP_RESTART_COMMAND" >> "$BASE/watchdog.log" 2>&1 || log "MCP restart command failed"
fi

if [ -n "${TUNNEL_RESTART_COMMAND:-}" ]; then
  log "Running configured tunnel restart command"
  bash -lc "$TUNNEL_RESTART_COMMAND" >> "$BASE/watchdog.log" 2>&1 || log "Tunnel restart command failed"
fi
