package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxWatchdogBridge {
    private const val TAG = "TermuxWatchdogBridge"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val WORKDIR = "/data/data/com.termux/files/home/jarvis_work"
    private const val START_SCRIPT = "/data/data/com.termux/files/home/jarvis_work/mcp_watchdog/start_watchdog.sh"

    fun startWatchdog(context: Context): Boolean {
        return try {
            val intent = Intent(RUN_COMMAND_ACTION).apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_PATH, BASH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", "bash $START_SCRIPT"))
                putExtra(EXTRA_WORKDIR, WORKDIR)
                putExtra(EXTRA_BACKGROUND, true)
            }
            context.startService(intent)
            Log.i(TAG, "Requested fixed MCP watchdog start in Termux")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start Termux watchdog: " + e.message, e)
            false
        }
    }
}
