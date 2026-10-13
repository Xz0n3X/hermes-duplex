package org.hermes.duplex

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Reading a message back, using the phone's own voice.
 *
 * The bridge can synthesise this — it is the same text the agent would have
 * spoken — but for a button that means waiting on a round trip and a Piper
 * pass that takes seconds, over a socket, for something the phone can do
 * instantly and offline. A "say that again" control that takes forty seconds
 * to start is a control nobody presses twice.
 *
 * It will not match the agent's chosen voice. That is the trade: the voice is
 * right when the agent speaks on its own, and this is for reading a reply
 * that was never spoken, or one worth hearing again.
 */
object Speaker {

    data class Profile(val name: String, val label: String)

    /** The two audible agents exposed by the phone UI. */
    enum class SpeechContext { LLM_RESPONSE, SYSTEM_RESPONSE }

    data class VoiceRouting(
        val llmVoice: String?,
        val systemVoice: String?,
    )

    private var engine: TextToSpeech? = null
    private var ready = false
    private var appContext: Context? = null
    private var profilesValue: List<Profile> = emptyList()
    private var selectedProfile: String? = null
    private var llmVoice: String? = null
    private var systemVoice: String? = null
    private var activeContext: SpeechContext? = null

    /** Settings uses this to populate the phone read-back voice picker. */
    @Volatile
    var onProfilesChanged: (List<Profile>) -> Unit = {}
        set(value) {
            field = value
            if (profilesValue.isNotEmpty()) value(profilesValue)
        }

    val profiles: List<Profile>
        get() = profilesValue

    val selectedProfileName: String?
        get() = selectedProfile

    /** Queued while the engine was still starting up. */
    private val pending = ArrayDeque<Triple<String, Boolean, String?>>()

    /** Called when speech starts or stops, so a button can show which. */
    @Volatile
    var onState: (speaking: Boolean, key: String?) -> Unit = { _, _ -> }

    private var sequence = 0L
    private val utteranceKeys = HashMap<String, String?>()
    private val utteranceTexts = HashMap<String, String>()
    private var activeUtteranceId: String? = null
    private var activeText: String = ""
    private var activeOffset: Int = 0
    private var pausedText: String = ""
    private var pausedKeyValue: String? = null

    /**
     * What is being spoken right now, and how far through it the voice is.
     *
     * The synthesiser reports its position as a character range into the text
     * it was handed -- which is `speakable(...)`, not what is on screen.
     * Those two strings differ on purpose (one drops markup for the ear, the
     * other for the eye), so a highlight drawn at these offsets over the
     * displayed text would drift a word further out with every code span.
     *
     * So the text is published alongside the offsets. A reader that wants to
     * follow the voice renders *this* string while it is non-empty, and its
     * own while it is not, and the two can never disagree.
     */
    @Volatile
    var liveText: String = ""
        private set

    @Volatile
    var liveStart: Int = 0
        private set

    @Volatile
    var liveEnd: Int = 0
        private set

    /**
     * When the current word started, on the uptime clock.
     *
     * The orb needs a level, and on a watch there is no PCM to measure: the
     * synthesiser plays straight out and this process never sees the samples.
     * What it does see is a callback per word, and a word onset is the beat
     * of speech -- so the animation is driven from when the voice reached a
     * word rather than from a timer that would run at the same rate over
     * silence.
     */
    @Volatile
    var liveAt: Long = 0L
        private set

    /** Stable id of the message currently being read, if any. */
    @Volatile
    var currentKey: String? = null
        private set

    val pausedKey: String?
        get() = pausedKeyValue

    val speaking: Boolean
        get() = engine?.isSpeaking == true

    /**
     * Start the engine.
     *
     * Initialisation is asynchronous and takes a moment on first use, so a
     * press that arrives before it is ready is held rather than dropped —
     * otherwise the first tap after a cold start does nothing and the second
     * one works, which reads as broken.
     */
    fun start(context: Context) {
        if (engine != null) return
        val app = context.applicationContext
        appContext = app
        selectedProfile = app.getSharedPreferences("hermes-duplex", Context.MODE_PRIVATE)
            .getString("readback_voice", null)
        val preferences = app.getSharedPreferences("hermes-duplex", Context.MODE_PRIVATE)
        llmVoice = preferences.getString("readback_voice_llm", null)
        systemVoice = preferences.getString("readback_voice_system", null)
        bind(app, preferredEngine(app))
    }

    /**
     * Open the synthesiser, naming an engine when the system has not.
     *
     * A watch is routinely shipped with a TTS engine installed and *no
     * default synthesiser selected* -- `settings get secure tts_default_synth`
     * answers `null`. The no-argument constructor then binds to nothing, init
     * reports failure, and because every `say` before that point is held in
     * `pending`, the symptom is not an error: it is a device that buzzes for
     * every reply and never speaks one. That was this bug.
     *
     * So when there is no default, an installed engine is named explicitly
     * rather than hoped for.
     */
    private fun bind(app: Context, enginePackage: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                EventLog.add("local speech unavailable (status $status)")
                // One retry, and only from the path that did not name an
                // engine -- otherwise a device with a broken engine would
                // rebind to it forever.
                if (enginePackage == null) {
                    val installed = installedEngine(app)
                    if (installed != null) {
                        EventLog.add("retrying speech with $installed")
                        engine?.runCatching { shutdown() }
                        engine = null
                        bind(app, installed)
                    }
                }
                return@OnInitListener
            }
            EventLog.add("speech engine ready: ${enginePackage ?: "system default"}")
            useBestLanguage()
            refreshProfiles()
            // Some Android TTS engines publish their voice list shortly after
            // the init callback. Refresh once more so Settings never gets a
            // permanently empty picker on a cold start.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { if (ready) refreshProfiles() }, 1000
            )
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    activeUtteranceId = id
                    activeText = id?.let { utteranceTexts[it] }.orEmpty()
                    activeOffset = 0
                    liveText = activeText
                    liveStart = 0
                    liveEnd = 0
                    currentKey = id?.let { utteranceKeys[it] }
                    onState(true, currentKey)
                }
                override fun onRangeStart(
                    id: String?, start: Int, end: Int, frame: Int
                ) {
                    if (id != activeUtteranceId) return
                    activeOffset = start.coerceAtLeast(0)
                    liveStart = start.coerceIn(0, activeText.length)
                    liveEnd = end.coerceIn(liveStart, activeText.length)
                    liveAt = android.os.SystemClock.uptimeMillis()
                }
                override fun onDone(id: String?) {
                    if (id == activeUtteranceId && pausedKeyValue == null) {
                        activeUtteranceId = null
                        activeText = ""
                        activeOffset = 0
                    }
                    id?.let { utteranceKeys.remove(it) }
                    id?.let { utteranceTexts.remove(it) }
                    if (pausedKeyValue == null) currentKey = null
                    clearLive()
                    onState(false, pausedKeyValue)
                }
                @Deprecated("required by the base class")
                override fun onError(id: String?) {
                    id?.let { utteranceKeys.remove(it) }
                    id?.let { utteranceTexts.remove(it) }
                    currentKey = null
                    clearLive()
                    onState(false, pausedKeyValue)
                }
            })
            val waiting = pending.toList()
            pending.clear()
            waiting.forEach { (text, queue, key) -> say(text, queue, key) }
        }
        engine = if (enginePackage == null) TextToSpeech(app, listener)
        else TextToSpeech(app, listener, enginePackage)
    }

    /** The engine to name at bind time, or null to take the system default. */
    private fun preferredEngine(app: Context): String? {
        val configured = runCatching {
            android.provider.Settings.Secure.getString(
                app.contentResolver, "tts_default_synth")
        }.getOrNull()
        return if (configured.isNullOrBlank()) installedEngine(app) else null
    }

    /** Any engine actually on the device, preferring Google's if present. */
    private fun installedEngine(app: Context): String? {
        val found = runCatching {
            app.packageManager.queryIntentServices(
                android.content.Intent("android.intent.action.TTS_SERVICE"), 0
            ).mapNotNull { it.serviceInfo?.packageName }
        }.getOrDefault(emptyList())
        return found.firstOrNull { it == "com.google.android.tts" }
            ?: found.firstOrNull()
    }

    /**
     * Pick a language the engine can actually speak.
     *
     * Assigning the device locale and not looking at the answer is how a
     * watch ends up bound, ready, and silent: `setLanguage` reports
     * `LANG_MISSING_DATA` for a locale whose voice was never downloaded, and
     * `speak` then does nothing at all.
     */
    private fun useBestLanguage() {
        val live = engine ?: return
        val preferred = selectedProfile?.let { name ->
            live.voices?.firstOrNull { it.name == name }
        }
        if (preferred != null) {
            live.voice = preferred
            EventLog.add("speech voice selected: ${preferred.name}")
            return
        }
        for (candidate in listOf(Locale.getDefault(), Locale.US, Locale.ENGLISH)) {
            val verdict = runCatching { live.setLanguage(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (verdict != TextToSpeech.LANG_MISSING_DATA &&
                verdict != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                EventLog.add("speech voice: $candidate")
                return
            }
        }
        EventLog.add("no speech voice installed for any English locale")
    }

    /** Return usable installed voices, preferring offline English voices. */
    private fun refreshProfiles() {
        val voices = engine?.voices.orEmpty()
        val english = voices.filter { it.locale.language == "en" }
        val offline = english.filterNot { it.isNetworkConnectionRequired }
        // Prefer offline English voices, but never leave the picker empty on
        // devices whose engine reports locale tags differently or only has
        // another installed language.
        val usable = if (offline.isNotEmpty()) offline
        else if (english.isNotEmpty()) english
        else voices.toList()
        profilesValue = usable.sortedWith(compareBy<Voice> { it.locale.toLanguageTag() }
            .thenBy { it.name }).map { voice ->
            val mode = if (voice.isNetworkConnectionRequired) "online" else "offline"
            Profile(voice.name, "${voice.locale.displayName} · $mode")
        }
        onProfilesChanged(profilesValue)
    }

    /** Switch the phone voice used for message and private-content read-back. */
    fun selectProfile(name: String) {
        val voice = engine?.voices?.firstOrNull { it.name == name } ?: return
        selectedProfile = voice.name
        appContext?.getSharedPreferences("hermes-duplex", Context.MODE_PRIVATE)
            ?.edit()?.putString("readback_voice", voice.name)?.apply()
        EventLog.add("default read-back voice queued for next utterance: ${voice.name}")
    }

    /** Configure one agent voice; null means use the phone/system default. */
    fun selectProfile(context: SpeechContext, name: String?) {
        if (name != null && engine?.voices?.none { it.name == name } == true) return
        when (context) {
            SpeechContext.LLM_RESPONSE -> llmVoice = name
            SpeechContext.SYSTEM_RESPONSE -> systemVoice = name
        }
        appContext?.getSharedPreferences("hermes-duplex", Context.MODE_PRIVATE)
            ?.edit()?.putString(prefKey(context), name)?.apply()
        EventLog.add("${context.name.lowercase()} read-back voice: ${name ?: "system default"}")
    }

    fun selectedProfileName(context: SpeechContext): String? = when (context) {
        SpeechContext.LLM_RESPONSE -> llmVoice
        SpeechContext.SYSTEM_RESPONSE -> systemVoice
    }

    val voiceRouting: VoiceRouting
        get() = VoiceRouting(llmVoice, systemVoice)

    private fun prefKey(context: SpeechContext) = when (context) {
        SpeechContext.LLM_RESPONSE -> "readback_voice_llm"
        SpeechContext.SYSTEM_RESPONSE -> "readback_voice_system"
    }

    /** Apply a route only between utterances; never change a voice mid-speech. */
    private fun applyVoice(context: SpeechContext): Boolean {
        val live = engine ?: return false
        val requested = selectedProfileName(context)
        val voice = requested?.let { name -> live.voices?.firstOrNull { it.name == name } }
        if (voice == null && requested != null) {
            EventLog.add("${context.name.lowercase()} voice unavailable; using system default")
        }
        val fallback = selectedProfile?.let { name ->
            live.voices?.firstOrNull { it.name == name }
        }
        val target = voice ?: fallback
        val changed = activeContext != context || live.voice?.name != target?.name
        if (changed) {
            if (live.isSpeaking) live.stop()
            if (target != null) live.voice = target else useBestLanguage()
        }
        activeContext = context
        return changed
    }

    /**
     * How loud to speak, 0..1.
     *
     * The engine's own volume rather than the device's: a watch's media
     * volume is shared with everything else on it, and someone who wants
     * this quieter usually does not want their alarms quieter too.
     */
    @Volatile
    var volume: Float = 1f

    /**
     * Read [text] aloud.
     *
     * [queue] decides what happens to whatever is already being read.
     * Replacing it is right for a whole reply arriving at once -- the old
     * answer is finished with. It is wrong for a reply arriving in pieces,
     * which is how a CLI session being followed is written: each new
     * paragraph would cut off the one before it and all you would hear is
     * the last fragment of a long answer.
     */
    fun say(
        text: String,
        queue: Boolean = false,
        key: String? = null,
        context: SpeechContext = SpeechContext.SYSTEM_RESPONSE,
    ) {
        val spoken = speakable(text)
        if (spoken.isBlank()) return
        val live = engine
        if (live == null || !ready) {
            // Held for the init callback above. Distinguished in the log,
            // because "no engine at all" and "engine still starting" look
            // identical from the outside and need different fixes.
            pending.add(Triple(spoken, queue, key))
            EventLog.add(
                if (live == null) "read aloud: queued, no speech engine bound"
                else "read aloud: queued, engine not ready yet"
            )
            return
        }
        // Traced because the failure mode of this feature is silence, and
        // silence is indistinguishable from "it decided not to". The Debug
        // screen is where you find out which.
        // Android TTS exposes one active voice. If a different content type
        // arrives while another utterance is playing, finish that boundary
        // cleanly rather than allowing two voices to overlap.
        val voiceChanged = applyVoice(context)
        val effectiveQueue = queue && !voiceChanged
        EventLog.add("read aloud [${context.name.lowercase()}]: ${spoken.take(48)}")
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        val utteranceId = "speech-${++sequence}"
        utteranceKeys[utteranceId] = key
        utteranceTexts[utteranceId] = spoken
        // The verdict was being discarded. `speak` returns ERROR for a
        // synthesiser that bound but cannot actually produce audio -- no
        // voice data, a dead engine process -- and swallowing that leaves
        // exactly the symptom this feature fails with anyway: nothing.
        val verdict = live.speak(
            spoken,
            if (effectiveQueue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId,
        )
        if (verdict != TextToSpeech.SUCCESS) {
            EventLog.add("speech engine refused the utterance (code $verdict)")
        }
        // The progress listener owns the visible state. In particular, do
        // not mark a queued item as playing before TTS has reached it.
    }

    /**
     * Start reading [text] again from the sentence covering [offset].
     *
     * Scrubbing, for a reply being read aloud: the live-read screen turns a
     * scroll position into a character offset and hands it here, and reading
     * picks up from the nearest sentence start at or before it.
     *
     * Snapped to a sentence rather than honoured exactly because the offset
     * comes from a line of laid-out text, and a line boundary falls
     * mid-sentence far more often than not -- starting there reads half a
     * clause aloud, which sounds like a fault rather than a seek.
     */
    fun seek(text: String, offset: Int, key: String? = null): Boolean {
        if (text.isBlank()) return false
        val at = offset.coerceIn(0, text.length)
        val start = PlainText.sentences(text)
            .lastOrNull { it.first <= at }?.first ?: 0
        val rest = text.substring(start.coerceIn(0, text.length))
        if (rest.isBlank()) return false
        // A seek supersedes whatever is being said, including a pause that
        // was holding a different place in the same reply.
        pausedText = ""
        pausedKeyValue = null
        say(rest, queue = false, key = key)
        return true
    }

    /** Pause the current utterance and remember the last character boundary. */
    fun pause(): Boolean {
        val id = activeUtteranceId ?: return false
        val key = utteranceKeys[id] ?: return false
        pausedKeyValue = key
        pausedText = activeText.substring(activeOffset.coerceIn(0, activeText.length))
        engine?.stop()
        currentKey = null
        onState(false, key)
        return true
    }

    /** Resume the paused message from the last range reported by TTS. */
    fun resume(): Boolean {
        val text = pausedText
        val key = pausedKeyValue
        if (text.isBlank() || key == null) return false
        pausedText = ""
        pausedKeyValue = null
        say(text, queue = false, key = key)
        return true
    }

    private fun clearLive() {
        liveText = ""
        liveStart = 0
        liveEnd = 0
        liveAt = 0L
    }

    /**
     * How hard the orb should be lit right now, 0..1.
     *
     * A decay from each word onset, so the orb beats once per word and sits
     * dark between utterances. Silence has to actually read as silence --
     * an orb that keeps moving after the voice stops is the thing that made
     * it look like it was still talking when it was not.
     */
    fun speechLevel(): Double {
        if (liveText.isEmpty() || liveAt == 0L) return 0.0
        val since = (android.os.SystemClock.uptimeMillis() - liveAt).toDouble()
        // A long word takes longer to say than a short one, so the pulse is
        // as long as the word is. A fixed decay made every word the same
        // shape, which is a metronome rather than speech.
        val chars = (liveEnd - liveStart).coerceAtLeast(1)
        val span = (chars * MS_PER_CHAR).coerceIn(MIN_WORD_MS, MAX_WORD_MS)
        val through = (since / span).coerceIn(0.0, 1.0)
        // Opens and closes across the word: nothing at the seams, full in
        // the middle, so consecutive words read as separate beats.
        val envelope = kotlin.math.sin(through * Math.PI)
        // And a fast tremor on top, so it moves like a level meter rather
        // than a heartbeat. Too small to see on its own, enough to stop the
        // envelope looking like a slow breath.
        val tremor = 0.78 + 0.22 * kotlin.math.sin(since / 38.0)
        return (envelope * tremor).coerceIn(0.0, 1.0)
    }

    /** Roughly how long one spoken character lasts, and the sane bounds. */
    private const val MS_PER_CHAR = 62.0
    private const val MIN_WORD_MS = 130.0
    private const val MAX_WORD_MS = 700.0

    /** Whether the synthesiser is working through an utterance right now. */
    val reading: Boolean
        get() = liveText.isNotEmpty()

    fun stop() {
        engine?.stop()
        clearLive()
        pending.clear()
        utteranceKeys.clear()
        utteranceTexts.clear()
        activeUtteranceId = null
        activeText = ""
        activeOffset = 0
        pausedText = ""
        pausedKeyValue = null
        currentKey = null
        onState(false, null)
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
        utteranceKeys.clear()
        utteranceTexts.clear()
        activeUtteranceId = null
        pausedText = ""
        pausedKeyValue = null
        currentKey = null
    }

    /**
     * What the markup sounds like, which is nothing.
     *
     * The bridge strips this server-side before its own synthesiser sees it;
     * the phone's engine needs the same courtesy, or it reads "asterisk
     * asterisk" exactly as Piper did. Kept in step with `server/speakable.py`
     * by hand — the rules are short, and a shared implementation would mean
     * shipping a script interpreter to say a sentence.
     */
    private fun speakable(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " code block ")
        .replace(Regex("\\[([^]]+)]\\((https?://[^\\s)]+)\\)"), "$1")
        .replace(Regex("(?:https?://|www\\.)\\S+"), "the link shown on screen")
        .replace(Regex("`([^`\n]+)`")) { sayCode(it.groupValues[1]) }
        // Absolute and home-relative directories are useful on screen but
        // should never be announced into the room. This final shared TTS
        // gate protects automatic, queued and manually replayed messages,
        // including paths that were not formatted as Markdown code.
        .replace(UNIX_PATH, " the path shown on screen ")
        .replace(WINDOWS_PATH, " the path shown on screen ")
        .replace(SHELL_LINE, "a command shown on screen")
        .replace(Regex("(\\*\\*|__)(?=\\S)(.+?\\S)\\1"), "$2")
        .replace(Regex("(?<![*_\\w])([*_])(?=\\S)([^*_]+?\\S)\\1(?![*_\\w])"), "$2")
        .replace(Regex("~~(?=\\S)(.+?\\S)~~"), "$1")
        .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("(?<!\\w)[*_~`]+|[*_~`]+(?!\\w)"), "")
        .replace(Regex("[(\\[]\\s*[)\\]]"), "")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex(" +([,.;:!?)\\]])"), "$1")
        .trim()

    private val UNIX_PATH = Regex(
        "(?<![A-Za-z0-9])(?:~/|/)(?:[^/\\s,;:!?()\\[\\]{}<>]+/)*" +
            "[^/\\s,;:!?()\\[\\]{}<>]+"
    )
    private val WINDOWS_PATH = Regex(
        "(?i)(?<![A-Za-z0-9])[A-Z]:\\\\(?:[^\\s,;:!?()\\[\\]{}<>]+\\\\)*" +
            "[^\\s,;:!?()\\[\\]{}<>]*"
    )
    private val SHELL_LINE = Regex("(?m)^\\s*(?:[$%>])\\s+\\S.*$")

    private val PATH = Regex(
        "[/\\\\]|\\w+\\.(?:py|kt|js|ts|xml|json|md|txt|sh|yml|yaml|toml|" +
            "jsonl|html|css|java|go|rs|c|h)\\b"
    )
    private val FLAG = Regex("^-{1,2}[A-Za-z]")
    private val LETTER = Regex("[A-Za-z]")

    /**
     * What one `code span` sounds like.
     *
     * A reply about code is mostly paths, flags and commands, and read
     * literally they are unbearable: "/tmp/bridge-test.txt" comes out as
     * "slash tmp slash bridge dash test dot text". Dropping them outright
     * leaves a sentence with holes in it, so each shape becomes the noun for
     * its kind — honest, because the thing itself is on screen to read.
     */
    private fun sayCode(span: String): String {
        val inner = span.trim()
        return when {
            inner.isEmpty() || !LETTER.containsMatchIn(inner) -> ""
            FLAG.containsMatchIn(inner) -> "that option"
            PATH.containsMatchIn(inner) -> "that file"
            inner.contains(' ') -> "that command"
            inner.length > 24 || inner.contains('_') -> "that name"
            else -> inner
        }
    }
}
