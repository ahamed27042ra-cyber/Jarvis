package com.jarvis.assistant.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import com.jarvis.assistant.R
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.ai.AudioEngine
import com.jarvis.assistant.ai.GeminiLiveClient
import com.jarvis.assistant.ui.main.MainActivity
import com.jarvis.assistant.util.AppLauncher
import com.jarvis.assistant.util.TermuxWatchdogBridge
import com.jarvis.assistant.util.ContactCaller
import android.content.Context
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.jarvis.assistant.youtube.YouTubeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import com.jarvis.assistant.audio.JarvisSoundEffects
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.wake.WakeWordDetector
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener as VoskRecognitionListener

/**
 * Owns the mic capture + Gemini Live WebSocket for the whole app lifetime,
 * independent of whatever Activity is currently on screen.
 *
 * Why this exists: Android blocks microphone access for apps that are not
 * in the foreground and have no active foreground service. Previously
 * AudioEngine/GeminiLiveClient lived inside MainActivity, so the moment
 * another app (e.g. YouTube, opened via open_app) came to the front, the
 * OS cut mic access and JARVIS effectively went silent/crashed. Running this
 * as a foreground service with type "microphone" keeps the conversation
 * alive in the background.
 *
 * MainActivity binds to this service for UI updates (transcripts,
 * amplitude, speaking state) via [JarvisVoiceListener], but the service
 * itself does not depend on the Activity being bound or visible.
 *
 * --- Wake word ("Jarvis") gating ---
 * The mic is ALWAYS on and ALWAYS streaming to Gemini (no separate local
 * speech recognizer, so no extra mic-open/close cycles and no earcon
 * beeping). Gemini transcribes speech in real time via input transcription;
 * this class buffers each spoken turn's transcript and only lets JARVIS
 * actually speak a reply or run a tool (open an app, call a contact, etc.)
 * if that turn's transcript contains "Jarvis". If it doesn't, the reply is
 * silently dropped and no tool is executed — so "open YouTube" alone does
 * nothing, but "Jarvis, open YouTube" works.
 */
class JarvisVoiceService : Service() {

    companion object {
        private const val CHANNEL_ID = "jarvis_voice_channel"
        private const val NOTIFICATION_ID = 101

        // Strictly allowed wake phrases:
        // hey jarvis, hello jarvis, hi jarvis, wake jarvis, wake up jarvis
        // Standalone "jarvis" or standalone "hey" are NEVER allowed.
        private val WAKE_PHRASES = listOf(
            "hey jarvis", "hello jarvis", "hi jarvis", "wake jarvis", "wake up jarvis",
            "हे जार्विस", "हेलो जार्विस", "हाय जार्विस", "जागो जार्विस", "वेक अप जार्विस"
        )

        private const val IDLE_TO_SLEEP_MS = 120_000L  // 2 minutes of silence -> auto-sleep
        private const val FOLLOW_UP_WINDOW_MS = 60_000L // 60s continuous follow-up window after speech/turn
        private const val BACKGROUND_AUTO_STANDBY_MS = 60_000L // 60s auto-standby window in background
        @Volatile var instance: JarvisVoiceService? = null
    }

    interface JarvisVoiceListener {
        fun onConnected() {}
        fun onSetupComplete() {}
        fun onDisconnected() {}
        fun onError(msg: String) {}
        fun onInputTranscript(text: String) {}
        fun onOutputTranscript(text: String) {}
        fun onTurnComplete() {}
        fun onAmplitudeChanged(rms: Float) {}
        fun onSpeakingStarted() {}
        fun onSpeakingStopped() {}
        fun onToolCall(name: String, args: JSONObject, callId: String) {}
        /** A turn was heard but ignored because it didn't start with "Jarvis". */
        fun onCommandIgnored() {}
        fun onScreenShareStateChanged(isSharing: Boolean) {}
        fun onCameraVisionStateChanged(isActive: Boolean, isFront: Boolean) {}
        fun onResearchStateChanged(isSearching: Boolean, query: String) {}
        fun onStandbyStateChanged(isStandby: Boolean) {}
        fun onShutdownRequested() {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): JarvisVoiceService = this@JarvisVoiceService
    }

    private val binder = LocalBinder()
    private val toolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** UI listeners (MainActivity, FloatingOrbService) receive callbacks simultaneously. */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<JarvisVoiceListener>()

    fun addListener(listener: JarvisVoiceListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: JarvisVoiceListener) {
        listeners.remove(listener)
    }

    var uiListener: JarvisVoiceListener?
        get() = listeners.firstOrNull()
        set(value) {
            if (value != null) addListener(value)
        }

    private inline fun dispatchToListeners(crossinline action: (JarvisVoiceListener) -> Unit) {
        for (l in listeners) {
            try {
                action(l)
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Error notifying listener", e)
            }
        }
    }

    private var currentVoiceName: String = "Aoede"
    private var isVoiceFemale: Boolean = true

    fun setVoiceConfig(voiceName: String) {
        currentVoiceName = voiceName
        val maleVoiceNames = setOf("puck", "charon", "fenrir", "orus", "arvind", "amartya", "dev")
        isVoiceFemale = !maleVoiceNames.contains(voiceName.lowercase().trim())
    }

    fun getCurrentVoice(): String = currentVoiceName

    fun updateVoice(newVoice: String) {
        if (newVoice.isBlank()) return
        val changed = currentVoiceName.lowercase().trim() != newVoice.lowercase().trim()
        setVoiceConfig(newVoice)
        cachedVoiceName = newVoice
        try {
            getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit()
                .putString("gemini_voice", newVoice)
                .putString("cached_voice", newVoice)
                .apply()
        } catch (_: Exception) {}
        if (changed) {
            Log.i("JarvisVoiceService", "Voice dynamically updated to '$newVoice' — clearing session handle and rebuilding prompt")
            geminiLive?.clearSessionHandle()
            val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "Boss") ?: "Boss"
            val personality = prefs.getString("personality_mode", "best_friend") ?: "best_friend"
            val maleVoices = setOf("puck", "charon", "fenrir", "orus", "arvind", "amartya", "dev")
            val isFemale = !maleVoices.contains(newVoice.lowercase().trim())
            val newPrompt = com.jarvis.assistant.util.PromptBuilder.buildSystemPrompt(userName, personality, isFemale, newVoice)
            cachedSystemPrompt = newPrompt
            if (isSessionStarted && !isInBackgroundStandby()) {
                restartSession(cachedApiKey, cachedModelString, newPrompt, newVoice)
            }
        }
    }

    fun updateSessionConfig(newApiKey: String, newModel: String, newVoice: String, newPrompt: String) {
        Log.i("JarvisVoiceService", "Updating session config dynamically: model=$newModel, voice=$newVoice, keyLen=${newApiKey.length}")
        var configChanged = false
        if (newApiKey.isNotBlank() && newApiKey != cachedApiKey) {
            cachedApiKey = newApiKey
            configChanged = true
        }
        if (newModel.isNotBlank() && newModel != cachedModelString) {
            cachedModelString = newModel
            configChanged = true
        }
        if (newVoice.isNotBlank() && newVoice.lowercase().trim() != cachedVoiceName.lowercase().trim()) {
            setVoiceConfig(newVoice)
            cachedVoiceName = newVoice
            configChanged = true
        }
        if (newPrompt.isNotBlank() && newPrompt != cachedSystemPrompt) {
            cachedSystemPrompt = newPrompt
            configChanged = true
        }

        if (configChanged) {
            geminiLive?.clearSessionHandle()
            if (isSessionStarted && !isInBackgroundStandby()) {
                restartSession(cachedApiKey, cachedModelString, cachedSystemPrompt, cachedVoiceName)
            }
        }
    }

    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    private var screenCaptureEngine: com.jarvis.assistant.vision.ScreenCaptureEngine? = null
    private var cameraVisionEngine: com.jarvis.assistant.vision.CameraVisionEngine? = null
    private var isSessionStarted = false
    private var isUserMuted = false

    // ---------------------------------------------------------------
    // Conversation State Machine
    // ---------------------------------------------------------------
    enum class ConversationState {
        /** Full conversation mode. Mic streams to Gemini. All responses play aloud. */
        ACTIVE,
        /** User has gone silent. 2-minute countdown to SLEEPING. Any speech resets to ACTIVE. */
        IDLE_COUNTDOWN,
        /** Background silent mode. Mic does NOT stream to Gemini. Only WakeWordDetector listens. */
        SLEEPING
    }

    @Volatile private var conversationState = ConversationState.ACTIVE
    private var isAppInForeground = true
    @Volatile private var lastUserSpeechTimeMs = System.currentTimeMillis()
    private var idleCountdownJob: Job? = null
    private var autoSleepJob: Job? = null
    private var connectionHealthJob: Job? = null
    /** Active follow-up window timer (30s after launching an app, conversation can continue without wake word) */
    private var activeFollowUpJob: Job? = null
    @Volatile private var isInFollowUpWindow = false

    /**
     * True from wake-word detection until the full wake conversation cycle completes.
     * Prevents background auto-standby timers from killing the conversation prematurely.
     * Cleared only when: (1) enterStandby is called, (2) the user explicitly backgrounds JARVIS,
     * or (3) the wake session naturally times out after extended silence.
     */
    @Volatile private var isWakeWordActiveSession = false

    // --- Standby / Offline Wake Word components (Continuous in-memory Vosk Recognizer) ---
    @Volatile private var voskRecognizer: Recognizer? = null
    private var standbyActivatedTime = 0L
    // 1.0s cooldown prevents the standby sound tail or immediate echo from falsely re-triggering wake word.
    private val STANDBY_COOLDOWN_MS = 1000L
    private val _isStandby = MutableStateFlow(false)
    val isStandby: StateFlow<Boolean> = _isStandby.asStateFlow()

    // Rolling audio buffer to capture user speech during/immediately after wake word (~2.0 seconds)
    private val wakePreRollBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val WAKE_PRE_ROLL_MAX_CHUNKS = 50 // 50 * 40ms = 2.0s
    private var smartGreetingJob: Job? = null
    @Volatile private var userContinuedSpeakingAfterWake = false

    fun enterSleepingState(sayGoodbye: Boolean = false) {
        enterStandby(sayGoodbye = sayGoodbye, playSound = false)
    }

    /**
     * Transition to background standby mode.
     * JARVIS plays descending harmonic sci-fi chime, pauses Gemini Live audio transport (hot standby),
     * keeps hardware AudioRecord active to prevent Android microphone privacy indicator blinking,
     * and streams chunks to the local offline Vosk Recognizer in memory.
     * ZERO tokens and ZERO network consumed while in background.
     */
    fun enterStandby(sayGoodbye: Boolean = false, playSound: Boolean = true) {
        if (conversationState == ConversationState.SLEEPING && _isStandby.value) return
        if (screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) {
            Log.d("JarvisVoiceService", "enterStandby ignored — screen sharing or camera vision is active.")
            return
        }
        Log.i("JarvisVoiceService", "Entering SLEEPING / STANDBY state (playSound=$playSound).")

        // Cancel health check job and auto-standby job so they don't interfere
        connectionHealthJob?.cancel()
        cancelBackgroundAutoStandby()

        // Gate all Live callbacks first
        conversationState = ConversationState.SLEEPING
        _isStandby.value = true
        currentTurnHasWakeWord = false
        isInFollowUpWindow = false
        userContinuedSpeakingAfterWake = false
        isWakeWordActiveSession = false
        smartGreetingJob?.cancel()
        currentTurnInputText.clear()
        currentTurnOutputText.clear()

        // 1. Sleek descending futuristic standby sound effect if requested
        if (playSound) {
            JarvisSoundEffects.playStandbySound()
        }

        // 2. Stop playback only — KEEP RECORDING STEADY (ZERO MIC BLINKING)
        audioEngine?.stopPlayback()
        audioEngine?.clearPlaybackQueue()
        audioEngine?.startRecording() // Guarantees hardware mic remains continuously active without cycling
        standbyAudioBuffer.clear()
        preConnectionAudioBuffer.clear()
        synchronized(wakePreRollBuffer) { wakePreRollBuffer.clear() }
        resetTurnState()
        try {
            voskRecognizer?.reset()
        } catch (_: Exception) {}

        // 3. Clean Standby: Disconnect Gemini Live cleanly to eliminate zombie sockets,
        // stop background reconnect loops, and preserve 100% battery while offline.
        // Session resumption token is preserved in memory for instantaneous reconnect on wake word.
        try {
            geminiLive?.setAudioTransportPaused(true)
            geminiLive?.disconnect(manual = false)
        } catch (e: Exception) {
            Log.w("JarvisVoiceService", "Error disconnecting geminiLive on standby: ${e.message}")
        }

        // 4. Finish standby bookkeeping
        standbyActivatedTime = System.currentTimeMillis()
        idleCountdownJob?.cancel()
        autoSleepJob?.cancel()
        activeFollowUpJob?.cancel()

        // 5. Update notification to show standby & notify UI
        updateNotificationState(ServiceNotificationState.STANDBY)
        dispatchToListeners { it.onStandbyStateChanged(true) }

        // 6. Pre-warm in-memory recognizer
        prepareVoskRecognizer()

        Log.d("JarvisVoiceService", "SLEEPING: Standby mode active. Continuous hardware mic active. Zero Gemini tokens used.")
    }

    private fun prepareVoskRecognizer(): Recognizer? {
        val existing = voskRecognizer
        if (existing != null) return existing
        val model = VoskModelManager.model ?: return null
        return synchronized(this) {
            if (voskRecognizer != null) return@synchronized voskRecognizer
            try {
                // Kaldi dynamic constrained grammar for strict wake phrases + phonetics + TV distractors.
                // Including common Indian-English phonetics (jervis, javis) ensures natural human wake spotting,
                // while TV distractors (service, notice, news, movie) prevent false alignment from television dialogue.
                val grammar = """[
                    "hey jarvis", "hello jarvis", "hi jarvis", "wake jarvis", "wake up jarvis",
                    "hey javis", "hello javis", "hi javis", "wake javis", "wake up javis",
                    "hey jervis", "hello jervis", "hi jervis", "wake jervis", "wake up jervis",
                    "hey jarves", "hello jarves", "hi jarves", "wake jarves", "wake up jarves",
                    "hey jarviz", "hello jarviz", "hi jarviz", "wake jarviz", "wake up jarviz",
                    "service", "notice", "device", "devices", "news", "movie", "video", "channel",
                    "music", "people", "today", "tonight", "great", "guys", "everyone",
                    "bhai", "karo", "kya", "nahi", "aur", "yeh", "woh", "chup", "band", "suno",
                    "hello", "hey", "hi", "wake", "up", "yes", "no", "stop", "wait", "please", "okay",
                    "[unk]"
                ]""".trimIndent()
                val rec = Recognizer(model, 16000.0f, grammar)
                voskRecognizer = rec
                Log.i("JarvisVoiceService", "Direct Vosk continuous Recognizer initialized with strict wake grammar and distractors")
                rec
            } catch (e: Exception) {
                Log.e("JarvisVoiceService", "Failed to create Vosk Recognizer: ${e.message}", e)
                null
            }
        }
    }

    private var consecutivePartialWakeHits = 0
    private val REQUIRED_PARTIAL_HITS = 2 // 2 consecutive chunks (~80ms) debounces single-frame TV noise flutters

    private fun feedStandbyAudioChunk(chunk: ByteArray) {
        if (!_isStandby.value) return
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) return

        // Maintain rolling pre-roll buffer so words spoken during/immediately after wake word are kept
        while (wakePreRollBuffer.size >= WAKE_PRE_ROLL_MAX_CHUNKS) {
            wakePreRollBuffer.poll()
        }
        wakePreRollBuffer.offer(chunk)

        val rec = prepareVoskRecognizer() ?: return
        try {
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                consecutivePartialWakeHits = 0
                checkVoskHypothesis(rec.result)
            } else {
                checkVoskPartialHypothesis(rec.partialResult)
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Error in feedStandbyAudioChunk: ${e.message}")
        }
    }

    private fun checkVoskHypothesis(hypothesisJson: String?) {
        if (hypothesisJson.isNullOrBlank() || !_isStandby.value) return
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) return

        try {
            val json = JSONObject(hypothesisJson)
            val text = json.optString("text", "")
            val clean = text.trim().lowercase(Locale.ROOT)
            if (clean.isEmpty() || clean == "[unk]") return

            Log.i("JarvisVoiceService", "Vosk standby final hypothesis: '$clean'")
            if (containsStandbyWakeWord(clean)) {
                Log.i("JarvisVoiceService", "Vosk confirmed strict wake word (final): '$clean'")
                try { voskRecognizer?.reset() } catch (_: Exception) {}
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Error in checkVoskHypothesis: ${e.message}")
        }
    }

    private fun checkVoskPartialHypothesis(partialJson: String?) {
        if (partialJson.isNullOrBlank() || !_isStandby.value) return
        if (System.currentTimeMillis() - standbyActivatedTime < STANDBY_COOLDOWN_MS) return

        try {
            val json = JSONObject(partialJson)
            val partial = json.optString("partial", "")
            if (partial.isEmpty() || partial == "[unk]") {
                consecutivePartialWakeHits = 0
                return
            }

            if (containsStandbyWakeWord(partial)) {
                consecutivePartialWakeHits++
                Log.d("JarvisVoiceService", "Vosk partial wake hit ($consecutivePartialWakeHits/$REQUIRED_PARTIAL_HITS): '$partial'")
                if (consecutivePartialWakeHits >= REQUIRED_PARTIAL_HITS) {
                    consecutivePartialWakeHits = 0
                    Log.i("JarvisVoiceService", "Vosk confirmed strict wake word (debounced partial): '$partial'")
                    try { voskRecognizer?.reset() } catch (_: Exception) {}
                    onWakeWordDetected()
                }
            } else {
                consecutivePartialWakeHits = 0
            }
        } catch (e: Exception) {
            Log.e("JarvisVoiceService", "Error in checkVoskPartialHypothesis: ${e.message}")
        }
    }

    // STRICT: Only "hey jarvis", "hello jarvis", "hi jarvis", "wake jarvis", "wake up jarvis".
    // Standalone single words ("jarvis", "hey", "hi", "hello", "wake") NEVER match.
    private val STANDBY_WAKE_REGEX = Regex(
        """\b(hey|hello|hi|wake\s+up|wake)\s+(jarvis|javis|jervis|jarves|jarviz)\b""",
        RegexOption.IGNORE_CASE
    )
    private val HINDI_STANDBY_WAKE_REGEX = Regex(
        """\b(हे|हेलो|हाय|जागो|वेक\s+अप)\s+जार्विस\b""",
        RegexOption.IGNORE_CASE
    )

    private fun containsStandbyWakeWord(phrase: String): Boolean {
        val clean = phrase.lowercase(Locale.ROOT)
            .replace("[unk]", " ")
            .replace(Regex("[^a-zA-Z\\s\\u0900-\\u097F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return false
        return STANDBY_WAKE_REGEX.containsMatchIn(clean) ||
                HINDI_STANDBY_WAKE_REGEX.containsMatchIn(clean)
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        consecutivePartialWakeHits = 0
        try {
            voskRecognizer?.reset()
        } catch (_: Exception) {}
    }

    private fun onWakeWordDetected() {
        if (!_isStandby.value && conversationState == ConversationState.ACTIVE) return
        Log.i("JarvisVoiceService", "WAKE WORD DETECTED! Playing wake sound and activating Live session...")

        // 1. Mark wake-active session — protects conversation from premature standby
        isWakeWordActiveSession = true

        // 2. Brief haptic feedback
        vibrateBriefly()

        // 3. FIRST: Play futuristic ascending wake sound
        JarvisSoundEffects.playWakeSound()

        // 4. Stop offline standby listener
        exitStandbyListening()

        // 5. Cancel any pending background auto-standby immediately
        cancelBackgroundAutoStandby()

        // 6. Reset turn state
        resetTurnState()
        currentTurnHasWakeWord = true
        userContinuedSpeakingAfterWake = false
        startFollowUpWindow()

        // 7. Reconnect/Unpause Gemini Live & enter ACTIVE conversation
        enterActiveState(fromWakeWord = true)
        updateNotificationState(ServiceNotificationState.LISTENING)

        // 8. Immediately flush pre-roll audio so Gemini hears commands spoken right with the wake word
        val preRollChunks = mutableListOf<ByteArray>()
        while (!wakePreRollBuffer.isEmpty()) {
            val chunk = wakePreRollBuffer.poll() ?: break
            preRollChunks.add(chunk)
        }
        if (preRollChunks.isNotEmpty()) {
            toolScope.launch {
                for (chunk in preRollChunks) {
                    if (geminiLive?.isConnected() == true) {
                        geminiLive?.sendAudioChunk(chunk)
                    } else {
                        while (preConnectionAudioBuffer.size >= 60) {
                            preConnectionAudioBuffer.poll()
                        }
                        preConnectionAudioBuffer.offer(chunk)
                    }
                }
            }
        }

        // 9. If woke up in background, schedule generous auto-standby (30s) and ensure overlay is displayed
        // The isWakeWordActiveSession flag will extend this further if JARVIS is speaking/listening
        if (!isAppInForeground) {
            scheduleBackgroundAutoStandby(BACKGROUND_AUTO_STANDBY_MS)
            val enableOverlay = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).getBoolean("enable_floating_overlay", true)
            if (enableOverlay && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
                FloatingOrbService.startService(this)
            }
        }

        // 10. Smart wake greeting: checks if user is already speaking a command.
        // Suppresses canned greeting if the user spoke their command immediately (e.g. "Hey Jarvis open Chrome")
        scheduleSmartWakeGreeting()
    }

    private fun scheduleSmartWakeGreeting() {
        smartGreetingJob?.cancel()
        smartGreetingJob = toolScope.launch {
            // Wait 650ms so the futuristic ascending wake sound finishes playing,
            // while giving Gemini time to reconnect WebSocket in parallel.
            delay(650L)
            if (userContinuedSpeakingAfterWake || currentTurnInputText.isNotEmpty() || audioEngine?.isCurrentlySpeaking() == true) {
                Log.i("JarvisVoiceService", "User spoke a direct command with/after wake word or audio is playing — suppressing canned greeting.")
                return@launch
            }
            if (conversationState != ConversationState.ACTIVE) {
                return@launch
            }
            // If Gemini isn't ready/healthy yet, wait in fast 100ms intervals (up to 4s)
            if (geminiLive?.isSocketHealthy() != true) {
                Log.d("JarvisVoiceService", "Gemini socket not healthy yet during wake greeting — waiting up to 4s...")
                var waited = 0L
                while (waited < 4000L && geminiLive?.isSocketHealthy() != true && conversationState == ConversationState.ACTIVE) {
                    delay(100L)
                    waited += 100L
                }
                if (geminiLive?.isSocketHealthy() != true || conversationState != ConversationState.ACTIVE) {
                    Log.w("JarvisVoiceService", "Gemini still not healthy after waiting — marking pendingWakeGreeting")
                    pendingWakeGreeting = true
                    return@launch
                }
            }
            // User paused after wake word ("Hey Jarvis... [silence]"). Greet them!
            triggerWakeGreeting()
        }
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    /**
     * Transition to ACTIVE state.
     */
    fun enterActiveState(fromWakeWord: Boolean = false) {
        val wasState = conversationState
        val wasStandby = _isStandby.value
        conversationState = ConversationState.ACTIVE
        _isStandby.value = false
        lastUserSpeechTimeMs = System.currentTimeMillis()
        isInFollowUpWindow = false
        idleCountdownJob?.cancel()
        autoSleepJob?.cancel()
        cancelBackgroundAutoStandby()

        exitStandbyListening()
        geminiLive?.setAudioTransportPaused(false)
        audioEngine?.setStreamingPaused(false)
        audioEngine?.setExternalSpeaking(false)

        // If waking from standby or after long background idle, refresh hardware AudioRecord
        // to purge any drift or routing glitches caused by background media (YouTube/Reels).
        if (wasStandby || wasState == ConversationState.SLEEPING) {
            audioEngine?.refreshAudioRecordOnWake()
        }

        // Reconnect Gemini if waking from standby or if socket is not healthy
        val needsReconnect = wasStandby || wasState == ConversationState.SLEEPING || geminiLive?.isSocketHealthy() != true
        if (needsReconnect && isSessionStarted) {
            Log.i("JarvisVoiceService", "ACTIVE: Waking from standby or socket not healthy. Connecting fresh Gemini Live WebSocket...")
            geminiLive?.disconnect(manual = false)
            audioEngine?.startRecording()
            audioEngine?.startPlayback()
            geminiLive?.connect()
        } else {
            audioEngine?.startRecording()
            audioEngine?.startPlayback()
        }

        // Start the idle timer & connection health check
        startIdleTimer()
        startConnectionHealthCheck()
        updateNotificationState(ServiceNotificationState.LISTENING)
        dispatchToListeners { it.onStandbyStateChanged(false) }

        Log.d("JarvisVoiceService", "ACTIVE: Full conversation mode enabled (was $wasState, standby=$wasStandby, wakeWord=$fromWakeWord)")
    }

    /**
     * Start the 2-minute idle timer. If no user speech, transitions to SLEEPING.
     */
    private fun startIdleTimer() {
        autoSleepJob?.cancel()
        autoSleepJob = toolScope.launch {
            while (true) {
                delay(15_000L) // Check every 15s
                if (isAppInForeground) continue // Do not auto-sleep while user is actively viewing the app!

                val silenceDuration = System.currentTimeMillis() - lastUserSpeechTimeMs
                if ((conversationState == ConversationState.ACTIVE || !_isStandby.value) && silenceDuration >= IDLE_TO_SLEEP_MS) {
                    if (screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) {
                        // Keep awake during active screen sharing or camera vision
                        continue
                    }
                    Log.d("JarvisVoiceService", "Idle timer: ${silenceDuration / 1000}s of silence in background → transitioning to SLEEPING")
                    Handler(Looper.getMainLooper()).post { enterSleepingState(sayGoodbye = false) }
                    return@launch
                }
            }
        }
    }

    /**
     * Touch: reset idle timer because the user interacted.
     */
    private fun touchUserActivity() {
        lastUserSpeechTimeMs = System.currentTimeMillis()
        if (conversationState != ConversationState.ACTIVE) {
            conversationState = ConversationState.ACTIVE
        }
    }

    /**
     * Start a 45-second follow-up window (e.g. after Jarvis finishes speaking or executes a tool).
     * During this window, Jarvis continues listening and responds naturally without requiring wake word.
     */
    private fun startFollowUpWindow() {
        if (_isStandby.value || conversationState != ConversationState.ACTIVE) {
            isInFollowUpWindow = false
            return
        }
        isInFollowUpWindow = true
        activeFollowUpJob?.cancel()
        activeFollowUpJob = toolScope.launch {
            delay(FOLLOW_UP_WINDOW_MS)
            isInFollowUpWindow = false
        }
    }

    private var backgroundAutoStandbyJob: Job? = null

    private fun scheduleBackgroundAutoStandby(delayMs: Long = BACKGROUND_AUTO_STANDBY_MS) {
        if (isAppInForeground || isScreenSharing() || screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) return
        backgroundAutoStandbyJob?.cancel()
        backgroundAutoStandbyJob = toolScope.launch {
            delay(delayMs)
            if (isScreenSharing() || screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) {
                Log.d("JarvisVoiceService", "Background auto-standby aborted: screen sharing or camera vision is active")
                return@launch
            }
            if (!isAppInForeground && conversationState == ConversationState.ACTIVE && !_isStandby.value) {
                val isSpeaking = audioEngine?.isCurrentlySpeaking() == true
                if (isSpeaking) {
                    // JARVIS is still talking — defer standby until after speech finishes + follow-up
                    Log.d("JarvisVoiceService", "Background auto-standby deferred: JARVIS is speaking")
                    scheduleBackgroundAutoStandby(5_000L)
                    return@launch
                }
                if (isInFollowUpWindow) {
                    // Active follow-up window — user might still respond
                    Log.d("JarvisVoiceService", "Background auto-standby deferred: follow-up window active")
                    scheduleBackgroundAutoStandby(5_000L)
                    return@launch
                }
                if (isWakeWordActiveSession) {
                    // Wake word session still active — JARVIS is processing or waiting for Gemini
                    val silenceSinceLastSpeech = System.currentTimeMillis() - lastUserSpeechTimeMs
                    if (silenceSinceLastSpeech < 20_000L) {
                        Log.d("JarvisVoiceService", "Background auto-standby deferred: wake session active, silence=${silenceSinceLastSpeech}ms")
                        scheduleBackgroundAutoStandby(8_000L)
                        return@launch
                    }
                    // Extended silence even during wake session — safe to standby now
                    Log.d("JarvisVoiceService", "Wake session timed out after ${silenceSinceLastSpeech}ms silence")
                }
                Log.d("JarvisVoiceService", "Background timeout (${delayMs}ms) elapsed -> returning to standby")
                enterStandby(sayGoodbye = false, playSound = false)
            }
        }
    }

    private fun cancelBackgroundAutoStandby() {
        backgroundAutoStandbyJob?.cancel()
        backgroundAutoStandbyJob = null
    }

    private var lastAppOpenGreetingTimeMs = 0L
    private val GREETING_COOLDOWN_MS = 45_000L
    @Volatile private var pendingAppOpenGreeting = false
    @Volatile private var pendingWakeGreeting = false
    @Volatile private var pendingScreenShareGreeting = false

    fun triggerScreenShareGreeting() {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val userName = prefs.getString("user_name", "Sir")?.ifBlank { "Sir" } ?: "Sir"
        val maleVoices = setOf("puck", "charon", "fenrir", "orus", "arvind", "amartya", "dev")
        val isFemale = !maleVoices.contains(currentVoiceName.lowercase().trim())
        val dekteWord = if (isFemale) "dekh rahi hoon" else "dekh raha hoon"

        currentTurnHasWakeWord = true
        isTurnInterrupted = false

        val startPrompt = "Please speak out loud right now in your natural voice. Say: 'Screen share on ho gaya hai $userName, main aapki screen $dekteWord.' Do not call any tools."
        geminiLive?.sendText(startPrompt, turnComplete = true)
        Log.i("JarvisVoiceService", "triggerScreenShareGreeting: dispatched spoken prompt to Gemini Live: $startPrompt")
    }

    fun triggerWakeGreeting() {
        pendingWakeGreeting = false
        toolScope.launch {
            val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "Sir")?.ifBlank { "Sir" } ?: "Sir"

            val greetingPrompt = "Please respond out loud right now. Say: 'Hi $userName, how can I help you?' Keep it to one short sentence. Do not call any tools."
            Log.i("JarvisVoiceService", "Triggering wake greeting for $userName: $greetingPrompt")

            if (geminiLive?.isSocketHealthy() == true) {
                geminiLive?.sendText(greetingPrompt)
            } else {
                Log.d("JarvisVoiceService", "triggerWakeGreeting: socket not healthy yet, queuing as pendingWakeGreeting")
                pendingWakeGreeting = true
                if (geminiLive?.isConnected() != true) {
                    geminiLive?.connect()
                }
            }
        }
    }

    fun triggerAppOpenGreeting() {
        if (!isAppInForeground || isInBackgroundStandby()) return
        lastAppOpenGreetingTimeMs = System.currentTimeMillis()
        pendingAppOpenGreeting = false

        toolScope.launch {
            delay(400L) // Brief delay to let WebSocket session finish initialization
            if (!isAppInForeground || isInBackgroundStandby()) return@launch

            val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "Sir")?.ifBlank { "Sir" } ?: "Sir"

            val greetingPrompt = "Please greet the user out loud right now. Say: 'Hi $userName, how can I help you?' Keep it to one short sentence. Do not call any tools."
            Log.i("JarvisVoiceService", "Triggering app-open greeting for $userName: $greetingPrompt")
            geminiLive?.sendText(greetingPrompt)
        }
    }

    fun setAppForeground(isForeground: Boolean) {
        val wasForeground = isAppInForeground
        if (wasForeground == isForeground) return
        isAppInForeground = isForeground
        Log.d("JarvisVoiceService", "setAppForeground: $wasForeground -> $isForeground (isStandby=${_isStandby.value})")
        if (isForeground) {
            cancelBackgroundAutoStandby()
            touchUserActivity()
            if (_isStandby.value || conversationState != ConversationState.ACTIVE) {
                enterActiveState(fromWakeWord = false)
            }
            if (screenCaptureEngine == null && cameraVisionEngine?.isCameraStreaming() != true) {
                // Trigger spoken greeting ONLY if waking from standby or fresh start, never during active ongoing conversation
                val now = System.currentTimeMillis()
                if (now - lastAppOpenGreetingTimeMs > GREETING_COOLDOWN_MS && (_isStandby.value || !isSessionStarted)) {
                    if (geminiLive?.isConnected() == true) {
                        triggerAppOpenGreeting()
                    } else {
                        pendingAppOpenGreeting = true
                    }
                }
            }
        } else {
            // App went to background (e.g. user went to Home screen or opened another app)
            // If active conversation is running, KEEP IT ACTIVE smoothly in the background!
            if (conversationState == ConversationState.ACTIVE && !_isStandby.value) {
                Log.d("JarvisVoiceService", "App backgrounded during ACTIVE conversation — keeping live session open and streaming!")
                cancelBackgroundAutoStandby()
                updateNotificationState(ServiceNotificationState.LISTENING)
            } else {
                updateNotificationState(ServiceNotificationState.STANDBY)
            }
        }
    }

    private fun isVoicePlaybackAllowed(): Boolean {
        // Unconditionally allow playback during live screen sharing or camera vision
        if (isScreenSharing() || screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) return true
        // Allow playback if: (1) not in standby, OR (2) wake-word session is active (JARVIS is responding to user)
        if (isWakeWordActiveSession && conversationState == ConversationState.ACTIVE) return true
        if (isInBackgroundStandby()) return false
        return true
    }

    /** True once background standby is active. Standby takes strict priority. */
    private fun isInBackgroundStandby(): Boolean {
        // Never consider background standby active if screen sharing or camera vision is active
        if (isScreenSharing() || screenCaptureEngine != null || cameraVisionEngine?.isCameraStreaming() == true) return false
        return _isStandby.value || conversationState == ConversationState.SLEEPING
    }

    private val currentTurnInputText = StringBuilder()
    private val currentTurnOutputText = StringBuilder()
    private var currentTurnHasWakeWord = false
    private var interruptSentThisTurn = false
    @Volatile private var wakeSoundPlayedThisTurn = false

    private var cachedApiKey = ""
    private var cachedModelString = "models/gemini-3.1-flash-live-preview"
    private var cachedSystemPrompt = ""
    private var cachedVoiceName = "Aoede"

    private val SHUTDOWN_PHRASES = listOf(
        "turn off yourself", "shut down", "shutdown", "power off",
        "close yourself", "exit jarvis", "stop jarvis", "turn off",
        "go offline", "go off", "offline ho jao", "offline jao", "offline ho ja",
        "jarvis shutdown", "jarvis shut down", "jarvis turn off", "jarvis power off",
        "jarvis bandh ho jao", "khud ko band karo", "band ho jao",
        "jarvis off ho jao", "jarvis band ho ja", "band hoja", "band ho ja",
        "khud ko band kar do", "band kar do", "band karo", "stop listening"
    )

    private val BACKGROUND_PHRASES = listOf(
        "jarvis go to the background", "go to the background", "go to background",
        "jarvis go to background", "go into the background", "jarvis go into the background",
        "background mode", "minimize yourself", "minimize jarvis", "run in background",
        "send to background", "background me jao", "peeche chala ja", "background mode me jao",
        "peeche jao", "background me chalay jao", "background jao", "go background",
        "background mein jao", "background me ja", "chup ho jao", "chup raho",
        "go to sleep", "sleep jarvis",
        "enter standby", "standby mode", "go to standby", "take a break", "so jao", "chale jao",
        "bye", "goodbye", "bye jarvis", "goodbye jarvis", "see you later", "see ya", "talk to you later",
        "बैकग्राउंड में जाओ", "बैकग्राउंड जाओ", "पीछे जाओ", "चुप हो जाओ", "चुप रहो"
    )

    private val SHOW_YOURSELF_PHRASES = listOf(
        "show yourself", "show jarvis", "open jarvis", "bring jarvis",
        "come to front", "come back", "jarvis saamne aao", "saamne aao",
        "show me yourself", "appear", "maximize yourself", "open app jarvis",
        "open the app", "open jarvis app", "bring jarvis to front"
    )

    private val CLOUD_WAKE_REGEX = Regex(
        """\b(hey|hello|hi|wake\s+up|wake)\s+jarvis\b""",
        RegexOption.IGNORE_CASE
    )

    private fun textHasWakeWord(text: String): Boolean {
        val cleanText = text.lowercase().trim()
        if (cleanText.isEmpty()) return false
        // STRICT: ONLY multi-word wake phrases (hey jarvis, hello jarvis, hi jarvis, wake jarvis, wake up jarvis)
        return CLOUD_WAKE_REGEX.containsMatchIn(cleanText) ||
                WAKE_PHRASES.any { cleanText.contains(it) }
    }

    private fun isShutdownCommand(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F\\s]"), " ").trim()
        if (normalized.isEmpty()) return false
        return SHUTDOWN_PHRASES.any { normalized.contains(it) }
    }

    private fun isBackgroundCommand(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F\\s]"), " ").trim()
        if (normalized.isEmpty()) return false
        if (normalized == "go" || normalized == "jarvis go" || normalized == "just go" || normalized == "go away" || normalized == "sleep" || normalized == "bye" || normalized == "goodbye" || normalized == "back" || normalized == "background") return true
        if (BACKGROUND_PHRASES.any { normalized.contains(it) }) return true
        val bgRegex = Regex("""\b(go|good|send|run|move|switch)\s+(to\s+)?(the\s+)?back(ground|one)?\b""")
        return bgRegex.containsMatchIn(normalized)
    }

    private fun isShowYourselfCommand(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
        if (normalized.isEmpty()) return false
        return SHOW_YOURSELF_PHRASES.any { normalized.contains(it) }
    }

    private val standbyAudioBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val preConnectionAudioBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var isTurnInterrupted = false

    private fun flushStandbyAudio() {
        while (!standbyAudioBuffer.isEmpty()) {
            val b = standbyAudioBuffer.poll() ?: break
            audioEngine?.queueAudio(b)
        }
    }

    /** Resets per-turn bookkeeping, ready for the next utterance. */
    private fun resetTurnState() {
        currentTurnInputText.clear()
        currentTurnOutputText.clear()
        standbyAudioBuffer.clear()
        preConnectionAudioBuffer.clear()
        interruptSentThisTurn = false
        currentTurnHasWakeWord = false
        isTurnInterrupted = false
        wakeSoundPlayedThisTurn = false
        audioEngine?.setExternalSpeaking(false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Extract/load the local wake model while the app is starting, not after the user has
        // already asked JARVIS to go to the background.
        VoskModelManager.init(applicationContext, toolScope)
        // Fixed, non-arbitrary Termux bridge: starts the MCP watchdog only.
        TermuxWatchdogBridge.startWatchdog(applicationContext)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("JarvisVoiceService", "App removed from recents — preserving JarvisVoiceService in background!")
        isAppInForeground = false
        acquireWakeLock()
        ensureMicrophoneForegroundService()
        ensureSessionActive()

        // If conversation is ACTIVE, KEEP IT RUNNING smoothly in the background!
        // Do NOT abruptly kill it with a 2-second auto-standby or AlarmManager restart.
        if (conversationState == ConversationState.ACTIVE && !_isStandby.value) {
            Log.d("JarvisVoiceService", "onTaskRemoved: active conversation ongoing — keeping session fully awake in background!")
            cancelBackgroundAutoStandby()
            updateNotificationState(ServiceNotificationState.LISTENING)
        } else {
            updateNotificationState(ServiceNotificationState.STANDBY)
        }
    }

    fun ensureSessionActive() {
        if (isSessionStarted && geminiLive?.isConnected() == true) return
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val apiKey = cachedApiKey.ifBlank {
