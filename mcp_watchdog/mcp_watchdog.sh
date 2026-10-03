#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="/data/data/com.termux/files/home/jarvis_work/mcp_watchdog"
LOG="$BASE/watchdog.log"
CONFIG="$BASE/watchdog.env"
[ -f "$CONFIG" ] && . "$CONFIG"
INTERVAL="${WATCHDOG_INTERVAL:-30}"
MCP_URL="${MCP_HEALTH_URL:-}"
TELEGRAM_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-}"
log(){ printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }
notify(){
  [ -n "$TELEGRAM_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ] || return 0
  curl -fsS --max-time 10 -X POST "https://api.telegram.org/bot$TELEGRAM_TOKEN/sendMessage"     --data-urlencode "chat_id=$TELEGRAM_CHAT_ID"     --data-urlencode "text=$1" >/dev/null 2>&1 || true
}
health(){
  [ -n "$MCP_URL" ] || return 2
  curl -fsS --max-time 10 "$MCP_URL" >/dev/null 2>&1
}
last="UNKNOWN"
while true; do
  if health; then state="CONNECTED"; else state="DISCONNECTED"; fi
  if [ "$state" != "$last" ]; then
    log "MCP state: $state"
    if [ "$state" = "DISCONNECTED" ]; then
      notify "🔴 MCP DISCONNECTED\nRecovery watchdog started."
      if [ -x "$BASE/recover.sh" ]; then
        "$BASE/recover.sh" >> "$LOG" 2>&1 || log "Recovery hook failed"
      else
        bash "$BASE/recover.sh" >> "$LOG" 2>&1 || log "Recovery hook failed"
      fi
    else
      notify "🟢 MCP CONNECTION RESTORED"
    fi
    last="$state"
  fi
  sleep "$INTERVAL"
done
