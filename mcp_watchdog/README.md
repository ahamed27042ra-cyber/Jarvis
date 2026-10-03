# MCP Recovery Watchdog

Checks the MCP endpoint, detects connection-state changes, and sends Telegram alerts.

It does not bypass ChatGPT OAuth or manipulate ChatGPT's internal UI.
Use a stable MCP hostname; a temporary Quick Tunnel can disappear or change.

One-time Termux setup:
1. Copy watchdog.env.example to watchdog.env.
2. Set MCP_HEALTH_URL, TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID.
3. Make mcp_watchdog.sh executable.
4. Start the script from Termux.
