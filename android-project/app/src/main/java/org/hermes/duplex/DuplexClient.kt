package org.hermes.duplex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The duplex loop, phone side.
 *
 * Two things run at once and never stop for each other: a capture thread
 * pushing 20 ms PCM frames up the socket, and a playback track draining
 * whatever audio comes down. That simultaneity is the whole point -- the
 * microphone stays open while Hermes is speaking, so you can talk over it.
 *
 * The obvious hazard is the phone transcribing its own speaker. Three
 * things stop that: VOICE_COMMUNICATION capture, the hardware
 * AcousticEchoCanceler, and playback tagged as voice communication so the
 * canceller knows what to subtract. The server raises its detection
 * threshold while speaking as a fourth backstop.
 */
class DuplexClient(
    private val context: Context,
    private val url: String,
    // Mutable so the activity can detach when it goes away without killing
    // the session -- the service owns the socket, the UI just watches it.
    @Volatile var onEvent: (JSONObject) -> Unit = {},
    @Volatile private var statusSink: (String) -> Unit = {},
) {


    companion object {
        private const val TAG = "DuplexClient"

        /** Where the phone's own preferences live. Shared with the screen. */
        const val PREFS = "hermes-duplex"
        const val PREF_READ_ALOUD = "read_aloud"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 640      // 20 ms of 16 kHz mono s16le

        /** How long after asking for a preview its audio is still expected. */
        private const val PREVIEW_WINDOW_MS = 15_000L

        /** Shortest gap between two reply buzzes, however many blocks land. */
        private const val ALERT_QUIET_MS = 20_000L


        /** The live client, so the debug screen can drive the same socket. */
        @Volatile
        var active: DuplexClient? = null
            private set

        /** Survives disconnects, so /debug stays reachable when the socket drops. */
        @Volatile
        var lastUrl: String = "ws://100.105.180.73:8770/ws"
            private set

        /**
         * The server's last statement of its own tuning.
         *
         * Kept because `hello` is sent once, at connect, to whichever screen
         * happened to be attached -- which is the conversation, never
         * Settings. Without this the sliders and switches would show their
         * XML defaults until the user moved one.
         */
        @Volatile
        var lastConfig: JSONObject? = null
            private set

        /** The bridge title for the currently opened thread. */
        @Volatile
        private var activeChatTitle: String = ""

        /**
         * The offers standing after the last reply, as (label, detail).
         *
         * Held here rather than on a screen for the same reason the thread is:
         * a reply that lands while the app is backgrounded still has offers
         * attached to it, and they should be there when it comes back.
         */
        @Volatile
        var suggestions: List<Pair<String, String>> = emptyList()
            private set

        /** Set when the user sets the strip aside; cleared by the next reply. */
        @Volatile
        var suggestionsDismissed: Boolean = false

        /**
         * Recall armed for the next turn.
         *
         * Mirrors the bridge, which owns it: the flag is spent by whichever
         * turn comes next, and that may be a spoken one the phone did not
         * initiate. So the server announces the change and this follows,
         * rather than the button tracking its own state and drifting.
         */
        @Volatile
        var recallArmed: Boolean = false
            private set

        /**
         * The last thing the connection said about itself.
         *
         * A screen that attaches after the fact has missed every status it
         * would have been told, and asking "is the socket open" answers a
         * narrower question than "what is it doing". Without this the strip
         * kept saying "connecting…" over a session that had connected,
         * answered and gone back to listening.
         */
        @Volatile
        var lastStatus: String = ""
            private set

        /**
         * The turn state the bridge last reported: listening, thinking or
         * speaking.
         *
         * Held here because the Stop button has to be derived from it rather
         * than from the events that cross it. Driven by edges, Stop appeared
         * on `transcript` and vanished on `reply` — so a `reply` that landed
         * while the app was backgrounded, or a turn that ended any other way,
         * left the button on screen over a session doing nothing, offering to
         * stop something that had already stopped.
         */
        @Volatile
        var lastState: String = "listening"
            private set

        /** Whether there is currently something a Stop could stop. */
        val busy: Boolean
            get() = lastState == "thinking" || lastState == "speaking"

        /**
         * How loud the reply is, right now, as it leaves the speaker. 0..1.
         *
         * The web drives its speaking animation from a synthetic pulse
         * because a browser cannot see the level of its own synthesiser's
         * output. This client can: every sample is passed through its own
         * AudioTrack on the way out, so the orb can move with the voice
         * instead of strobing to a sine wave that has nothing to do with what
         * is being said.
         */
        @Volatile
        var outputLevel: Double = 0.0
            private set

        internal fun reportOutput(level: Double) {
            outputLevel = level
        }

        /** Put an older reply's offers back in the strip. */
        fun showSuggestions(offers: List<Pair<String, String>>) {
            suggestions = offers
            suggestionsDismissed = false
        }

        fun clearSuggestions() {
            suggestions = emptyList()
            suggestionsDismissed = false
        }
    }

    /**
     * Mirror the turn onto the watch.
     *
     * Only on the events that change what a watch would show. `reply_delta`
     * is deliberately not among them: publishing every token would put a few
     * hundred syncs across a Bluetooth link for one answer, and the watch
     * cannot usefully read a sentence being typed anyway.
     */
    /**
     * Read out what a followed turn just gained.
     *
     * Tracked even when the switch is off, because turning it on should
     * start reading from that moment rather than reciting everything the
     * agent said while it was off.
     *
     * Queued rather than flushed: the fragments of one reply arrive seconds
     * apart and each would otherwise cut off the one before it.
     */
    private fun speakNewPart(moved: List<Pair<String, String>>) {
        for ((id, content) in moved) {
            val from = spokenUpTo[id] ?: 0
            if (content.length <= from) continue
            val fresh = content.substring(from)
            spokenUpTo[id] = content.length
            if (!readAloud || duplex || fresh.isBlank()) continue
            // Buzz for a reply landing, not for every block of one. The
            // bridge now sends each paragraph as its own turn so it can be
            // shown and read as it arrives, which means "a turn we have not
            // seen" is no longer the same thing as "the agent just answered"
            // -- keyed on that, a ten-minute run buzzes every few seconds,
            // and a pocket doing that is not an alert, it is a fault.
            val now = System.currentTimeMillis()
            if (from == 0 && now - lastAlertAt > ALERT_QUIET_MS) {
                lastAlertAt = now
                Alerts.replied(context)
            }
            // Queued, never flushed, and one utterance per block: the blocks
            // of one reply arrive seconds apart, and replacing what is being
            // read would leave only the last fragment of a long answer.
            Speaker.say(fresh, queue = true, key = id)
        }
    }

    private fun publishToWatch(kind: String) {
        val turn = when (kind) {
            "transcript", "waiting_on_model" -> WearLink.Turn.THINKING
            "state" -> lastState
            // Suggestions land after the reply, so the turn is over by then.
            "reply", "interrupted", "error", "ready", "suggestions", "projects",
            // A followed session's turn landing is the whole point of the
            // watch being on: without this the delta reached the phone and
            // stopped there.
            "chat_opened", "approval_request" -> WearLink.Turn.IDLE
            "chat_delta" -> WearLink.Turn.SPEAKING
            // Definitive, and the only event that is: a followed session is
            // working or it is not, and the watch animates on that.
            "working" -> if (lastWorking) WearLink.Turn.WORKING
            else WearLink.Turn.IDLE
            else -> return
        }
        val last = ConversationStore.snapshot().lastOrNull()
        val reply = last?.takeIf { it.role == ConversationStore.Role.HERMES }?.text.orEmpty()
        WearRelayService.publishState(
            context = context,
            reply = reply,
            turn = turn,
            chatTitle = watchChatTitle(),
            suggestions = suggestions,
            connected = running,
            // The watch shows which agent is answering and can switch it, so
            // the choice has to reach it -- and it lives in the bridge's own
            // config, not in either app, because both are windows onto the
            // same session and a disagreement between them is not a state
            // worth being able to reach.
            // The projects event carries the active CLI section and arrives
            // after a reconnect even when the cached config predates the
            // engine switch. Prefer it so the watch cannot call a Codex
            // session Hermes while the phone drawer already shows Codex.
            engine = Sidebar.cli.id.ifBlank {
                lastConfig?.optString("engine", "hermes") ?: "hermes"
            },
            claudeAvailable = lastConfig?.optBoolean("claude_available") ?: false,
            approval = Approvals.oldest(),
            agents = availableAgents(),
            history = watchHistory(),
        )
        // Published every time, not only on the two events that obviously
        // change it. A chat is renamed by its first message, which happens
        // inside a turn, so the list is stale from that moment until whatever
        // event was chosen to refresh it -- and choosing correctly is a bet
        // that has to keep being re-made every time an event is added. The
        // list is a handful of short strings; publishing it costs nothing.
        // Whichever engine is answering. The watch has one chat list and no
        // concept of a command-line agent, so what it is handed is simply
        // "the conversations you can talk to right now" -- and the bridge
        // routes its open/new commands by engine to match.
        val cli = Sidebar.cli
        if (cli.enabled) {
            WearRelayService.publishChats(
                context,
                cli.sessions.map { it.id to it.title },
                cli.projects.map { it.key to it.name },
                cli.activeKey,
            )
        } else {
            WearRelayService.publishChats(
                context,
                Sidebar.chats.map { it.id to it.title },
                Sidebar.projects.map { it.path to it.name },
                Sidebar.activePath,
            )
        }
    }

    /**
     * The last turns of the open conversation, for the wrist.
     *
     * Taken from the same store the phone renders, so the two cannot
     * disagree about what was said. Trimmed hard: a watch is for following
     * the thread, not re-reading it.
     */
    private fun watchHistory(): List<WearLink.Said> =
        ConversationStore.snapshot()
            .filter { it.role != ConversationStore.Role.SYSTEM && it.text.isNotBlank() }
            .takeLast(WearLink.HISTORY_TURNS)
            .map {
                WearLink.Said(
                    fromYou = it.role == ConversationStore.Role.YOU,
                    text = it.text.take(WearLink.HISTORY_CHARS),
                    // Carry the same command/result fields the phone draws.
                    // The watch uses the same two-level disclosure: first the
                    // command list, then each command and its output.
                    steps = it.steps.take(WearLink.STEP_LINES).map { step ->
                        WearLink.Step(
                            tool = step.tool,
                            subject = step.subject,
                            command = step.command,
                            output = step.output,
                        )
                    },
                    detail = it.detail,
                )
            }

    /** The engines the bridge actually has, id to label. */
    private fun availableAgents(): List<Pair<String, String>> {
        val list = lastConfig?.optJSONArray("agents") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val agent = list.optJSONObject(index) ?: return@mapNotNull null
            if (!agent.optBoolean("available")) null
            else agent.optString("id") to agent.optString("label")
        }
    }

    /** What the wrist should call the open conversation. */
    private fun watchChatTitle(): String {
        if (activeChatTitle.isNotBlank()) return activeChatTitle
        val cli = Sidebar.cli
        if (cli.enabled) {
            return cli.sessions.firstOrNull { it.id == cli.activeSession }
                ?.title ?: if (cli.activeSession.startsWith("new-"))
                "New CLI session" else cli.activeSession.takeIf { it.isNotBlank() }.orEmpty()
        }
        return Sidebar.activeChat?.title.orEmpty()
    }

    /** The bridge has already parsed and vetted these; just carry them. */
    private fun readSuggestions(event: JSONObject) {
        val items = event.optJSONArray("items") ?: return
        val offers = mutableListOf<Pair<String, String>>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val label = item.optString("label")
            if (label.isEmpty()) continue
            val marker = when (item.optString("kind")) {
                "diagnose" -> "🔎"
                "advance" -> "🛠"
                "validate" -> "✓"
                else -> ""
            }
            offers += listOf(marker, label).filter { it.isNotEmpty() }
                .joinToString(" ") to item.optString("detail").ifEmpty { label }
        }
        suggestions = offers
        suggestionsDismissed = false
        // Also kept with the reply itself, so its lightbulb can bring them
        // back once newer answers have pushed them out of the strip.
        ConversationStore.offer(offers)
    }

    /**
     * Push the saved voice, if the server did not already start on it.
     *
     * This has to live here rather than on a screen: it is a property of the
     * connection, and the connection is made by a service that may well
     * outlive every activity. Handled on `hello` because that is the only
     * message that reports what the server booted with.
     */
    private fun applySavedVoice(hello: JSONObject) {
        val want = context
            .getSharedPreferences("hermes-duplex", Context.MODE_PRIVATE)
            .getString("voice", null)
            ?: return
        if (want != hello.optString("voice")) setTuning("voice" to want)
    }

    /** Report a status, remembering it for whatever attaches next. */
    private fun onStatus(status: String) {
        lastStatus = status
        statusSink(status)
    }

    /** Where status lines go now. Swapped as screens come and go. */
    var onStatus: (String) -> Unit
        get() = statusSink
        set(value) { statusSink = value }

    /** Send a command up the text channel. Server side: handle_command(). */
    fun sendCommand(payload: JSONObject) {
        EventLog.add("-> ${loggable(payload)}")
        // Both open the speaker for audio we asked for, which in a text
        // session is otherwise shut.
        if (payload.optString("type") == "preview_voice") armPreview(PREVIEW_WINDOW_MS)
        val encoded = payload.toString()
        if (socket?.send(encoded) != true) {
            pendingCommands.add(encoded)
            EventLog.add("command queued until socket opens")
        }
    }

    /**
     * Turn full duplex on or off, live.
     *
     * Safe before the socket is up: the flag is remembered and honoured when
     * it opens, which is what makes the toggle work as the thing that starts
     * a voice session as well as the thing that ends one.
     */
    fun setDuplex(on: Boolean) {
        if (duplex == on) return
        duplex = on
        EventLog.add("duplex ${if (on) "on" else "off"}")
        if (on) muted = false
        if (!running) return
        if (on) startAudio() else stopAudio()
    }

    /**
     * The same message with any credential taken out of it.
     *
     * The debug screen shows this log and people paste it into bug reports,
     * which is exactly the shape of accident that put an ssh root password
     * into a session title on the bridge and made `redact.py` necessary
     * there. Saving a remote machine sends its password up this socket once;
     * it must not also end up in a scrollback the app keeps.
     */
    private fun loggable(payload: JSONObject): String {
        val remote = payload.optJSONObject("remote") ?: return payload.toString()
        if (!remote.has("password") || remote.optString("password").isEmpty()) {
            return payload.toString()
        }
        val copy = JSONObject(payload.toString())
        copy.getJSONObject("remote").put("password", "\u2022\u2022\u2022\u2022\u2022\u2022")
        return copy.toString()
    }

    fun setTuning(vararg pairs: Pair<String, Any>) {
        val payload = JSONObject().put("type", "config")
        pairs.forEach { (key, value) -> payload.put(key, value) }
        sendCommand(payload)
    }

    /** Start private push-to-talk dictation for the local rewrite assistant. */
    fun startRewriteCapture() {
        if (audioOn) return
        sendCommand(JSONObject().put("type", "config").put("rewrite_mode", true))
        startCapture()
        onStatus("rewrite listening")
    }

    /** End private dictation; the server transcribes it but never submits it. */
    fun stopRewriteCapture() {
        if (!audioOn) return
        stopCapture()
        sendCommand(JSONObject().put("type", "rewrite_stop"))
        onStatus("rewriting privately")
    }

    val connected: Boolean get() = running

    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile private var running = false

    /**
     * Whether the microphone is open.
     *
     * The socket and the microphone are separate concerns: text chat needs
     * the socket and nothing else, and full duplex adds the mic and the
     * speaker on top of it. Conflating them meant every typed message also
     * held the mic open and read the answer aloud.
     */
    @Volatile private var audioOn = false

    /** Whether the speaker is open. True for duplex, and for a preview. */
    @Volatile private var playbackOn = false

    /**
     * Whether this device reads replies aloud and makes a sound about them.
     *
     * Held here rather than in the activity because this is where replies
     * arrive: the screen is optional, and monitoring an agent means being
     * told without looking. Persisted by whoever flips it; read back on
     * construction so a service started cold still knows.
     */
    var readAloud: Boolean = false
        set(value) {
            field = value
            Alerts.enabled = value
        }

    /**
     * How much of each followed turn has already been read out.
     *
     * A followed reply does not arrive whole. It is written to the CLI's
     * transcript as several records and the turn holding it grows, so the
     * same turn comes past this code again and again, longer each time.
     * Speaking what arrived would re-read the answer from the top on every
     * poll; speaking nothing is what it did before. Keyed by transcript turn
     * id, it speaks the part that is new and nothing else.
     */
    private val spokenUpTo = HashMap<String, Int>()

    /** When this device last buzzed for a reply, to rate-limit the blocks. */
    private var lastAlertAt = 0L

    /** Whether the followed agent was working, as of the last `working`. */
    @Volatile private var lastWorking = false

    init {
        // The engine takes a moment to start, and warming it here means the
        // first reply of a session is spoken rather than swallowed. Started
        // even when reading is off: the switch can be flipped mid-session and
        // the next reply should not be the one that pays for it.
        Speaker.start(context)
        readAloud = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_READ_ALOUD, false)
    }

    /** Full duplex: mic open, replies spoken, barge-in live. */
    @Volatile
    var duplex: Boolean = false
        private set

    /**
     * Set while a voice preview we asked for is on its way back.
     *
     * Preview audio and reply audio are the same bytes on the same socket,
     * so the only way to tell them apart is that the client asked for one of
     * them. Without this, previewing a voice from Settings is silent in text
     * mode -- which is the one mode where you are most likely to be picking
     * a voice rather than listening to one.
     */
    @Volatile private var expectPreview = false

    /**
     * Audio waiting to be played. Bounded at roughly a minute so a runaway
     * reply can never exhaust memory; overflow is logged, not swallowed.
     */
    private val playback = java.util.concurrent.LinkedBlockingQueue<ByteArray>(300)

    /** True while the user wants a session: drives reconnect, unlike [running]. */
    @Volatile private var wanted = false
    private var reconnectAttempts = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Commands tapped while OkHttp is connecting; never silently discard them. */
    private val pendingCommands = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // a live socket never idles out
        .build()

    fun connect() {
        if (running) return
        running = true
        wanted = true
        active = this
        lastUrl = url
        EventLog.add("connecting to $url")
        onStatus("connecting")

        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                EventLog.add("socket open")
                onStatus(if (duplex) "connected" else "ready")
                while (true) {
                    val queued = pendingCommands.poll() ?: break
                    if (!webSocket.send(queued)) {
                        pendingCommands.add(queued)
                        break
                    }
                    EventLog.add("queued command delivered")
                }
                // Text chat needs the socket and nothing else.
                if (duplex) startAudio()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                val kind = event.optString("type")
                // Level events arrive at 10 Hz; logging them buries everything.
                if (kind != "level" && kind != "reply_delta") EventLog.add("<- $text")
                // Timing is recorded here, not in the activity. The activity
                // detaches its callbacks in onPause, so a turn that finished
                // while the Timing or Voices screen was open used to vanish --
                // which is exactly when someone goes looking for it.
                if (kind == "trajectory") TimingStore.add(event)
                if (kind == "hello" || kind == "config") lastConfig = event
                // Kept so the watch can be told. publishToWatch is handed the
                // event *kind* and not the event, and this is the only field
                // that decides whether a followed agent is working.
                if (kind == "working") lastWorking = event.optBoolean("active")
                if (kind == "hello") applySavedVoice(event)
                // A new question makes the previous answer's offers stale, so
                // they go the moment one is accepted rather than lingering
                // under the reply they no longer follow.
                if (kind == "transcript") clearSuggestions()
                if (kind == "suggestions") readSuggestions(event)
                if (kind == "recall_armed") recallArmed = event.optBoolean("on")
                // The bridge is the authority on whether a turn is running.
                when (kind) {
                    "state" -> lastState = event.optString("value")
                    "ready", "reply", "interrupted", "error" -> lastState = "listening"
                    "transcript", "waiting_on_model" -> lastState = "thinking"
                }
                // The sidebar is the bridge's state, not the phone's: a
                // project is a directory there with its own memory, and a
                // client that guessed at it would show a project the agent is
                // not actually reading from.
                if (kind == "projects") Sidebar.accept(event)
                // A question the agent is blocked on. Recorded here rather
                // than handed straight to a screen, because the screen that
                // has to answer it is often not attached when it arrives.
                if (kind == "approval_request") {
                    EventLog.add("APPROVAL[4/6] received from bridge id=${event.optString("id")}")
                    Approvals.accept(event)
                    // Into the shade as well as onto the screen. The whole
                    // point of this is not having to come back and check: a
                    // question that only exists inside an app you are not
                    // looking at is a question that blocks for three minutes
                    // and then fails.
                    Approvals.oldest()?.let {
                        DuplexService.postApproval(context, it)
                        // Two firm buzzes and a higher tone, as on the watch.
                        // This is the one event that stops a process until it
                        // is answered, so it sounds unlike a reply landing.
                        Alerts.asking(context)
                        if (it.reason.isNotBlank() && Approvals.claimSpeech(it.id)) {
                            // Only an explicitly labeled reason is spoken.
                            // Never use subject/detail: those contain the
                            // command, path, or request body.
                            Speaker.say(
                                it.reason,
                                queue = false,
                                key = "approval-${it.id}",
                            )
                        }
                    }
                }
                // Settled somewhere else -- the watch answered it, or nobody
                // did and it timed out. Either way it is no longer a question.
                if (kind == "approval_closed") {
                    Approvals.resolve(event.optString("id"))
                    if (Approvals.oldest() == null) DuplexService.clearApproval(context)
                }
                // A different chat is a different thread; whatever the last
                // one left on screen is not this one's.
                if (kind == "chat_opened") {
                    activeChatTitle = event.optString("title")
                    ConversationStore.load(event)
                    // Opening a session must not read its backlog out. What
                    // is already on screen counts as heard, so only what
                    // happens from here is spoken.
                    spokenUpTo.clear()
                    val stored = event.optJSONArray("messages")
                    for (index in 0 until (stored?.length() ?: 0)) {
                        val record = stored?.optJSONObject(index) ?: continue
                        if (record.optString("role") != "assistant") continue
                        spokenUpTo[record.optString("id")] =
                            record.optString("content").length
                    }
                }
                // A session someone else is driving, arriving as it is
                // written. Merged rather than loaded: load() replaces the
                // thread, which would throw away everything above the few
                // turns that happened to change.
                if (kind == "chat_delta") speakNewPart(ConversationStore.merge(event))
                // The conversation is recorded here for the same reason, and
                // it matters more: the old UI held the last exchange in two
                // TextViews, so anything said while another screen was open
                // was lost outright.
                // Reading a reply out lives here rather than in the screen
                // that shows it. The activity detaches in onPause, so a reply
                // that arrived with the phone in a pocket -- which is most of
                // them while a CLI agent is working -- was never spoken at
                // all. The socket is owned by the service, and so is this.
                when (kind) {
                    "transcript" -> ConversationStore.said(event.optString("text"))
                    "reply_delta" -> ConversationStore.delta(event.optString("text"))
                    "reply" -> ConversationStore.completed(event.optString("text"))
                    "project_idea" -> {
                        val idea = event.optString("text")
                        ConversationStore.idea(idea)
                        if (readAloud && !duplex && idea.isNotBlank()) {
                            Speaker.say(
                                idea,
                                key = "project-idea-${System.currentTimeMillis()}",
                                context = Speaker.SpeechContext.LLM_RESPONSE,
                            )
                        }
                    }
                    "rewrite_result" -> {
                        val rewritten = event.optString("text").trim()
                        if (rewritten.isNotBlank()) {
                            // This is a private drafting result, not a normal
                            // reply. Read it immediately so the user can
                            // approve it before deciding whether to Send.
                            Speaker.say(
                                rewritten,
                                queue = false,
                                key = "private-rewrite-${System.currentTimeMillis()}",
                                context = Speaker.SpeechContext.LLM_RESPONSE,
                            )
                        }
                    }
                    "interrupted" -> ConversationStore.interrupted()
                    "trajectory" -> TimingStore.recent().firstOrNull()
                        ?.let { ConversationStore.timing(TimingStore.caption(it)) }
                }
                if (kind == "reply" && readAloud && !duplex) {
                    val message = ConversationStore.lastAssistant()
                    if (message != null) {
                        Alerts.replied(context)
                        // Read this completed reply as one independent item.
                        // Its stable key lets the matching row become Pause.
                        Speaker.say(message.text, key = message.speechKey)
                    }
                }
                // Anything the phone learns, the watch is told.
                //
                // Published from here rather than from a screen for the same
                // reason the conversation is recorded here: the phone is
                // usually in a pocket with no Activity attached, which is
                // exactly when the watch is the thing being looked at.
                //
                // After the store, never before it. This reads the very
                // messages the lines above just wrote, so publishing first
                // sent the watch the state as of the *previous* event -- it
                // caught up only because something else arrived behind it.
                publishToWatch(kind)
                // A cancelled reply must stop coming out of the speaker
                // immediately -- draining the buffer would keep talking for
                // another second after the user cut in.
                if (kind == "interrupted") flushPlayback()
                onEvent(event)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Never touch AudioTrack here. write() blocks for the real
                // duration of the audio, and this is OkHttp's reader thread --
                // blocking it stops the socket reading anything at all, text
                // events included, until the receive buffer overflows and the
                // pipe breaks. Hand off to the playback thread instead.
                // In text mode the only audio worth playing is a preview the
                // user asked for; a spoken reply to something they typed is
                // not what a chat window does.
                if (!duplex && !expectPreview) return
                if (!playback.offer(bytes.toByteArray())) {
                    EventLog.add("playback queue full, dropping chunk")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "socket failed", t)
                // The close reason is the single most useful diagnostic when a
                // session dies quietly, and it only exists on this side.
                EventLog.add("socket FAILED: ${t.javaClass.simpleName}: ${t.message}" +
                    (response?.let { " http=${it.code}" } ?: ""))
                onStatus("error: ${t.message}")
                teardownAudio()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                EventLog.add("socket CLOSED code=$code reason='$reason'")
                onStatus("disconnected ($code)")
                teardownAudio()
                if (wanted) scheduleReconnect()
            }
        })
    }

    // -- audio ---------------------------------------------------------------

    /**
     * Speaker only.
     *
     * Split from capture because the two are wanted separately. Full duplex
     * needs both; previewing a voice from Settings needs only this one, in a
     * text session that never opens the microphone.
     */
    private fun startPlayback(forCall: Boolean = true) {
        if (playbackOn) return
        playbackOn = true

        val minTrack = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Voice communication while a duplex session is running:
                    // that tag is what gives the echo canceller a reference
                    // signal to subtract, and capture puts the device in
                    // communication mode to match.
                    //
                    // Media otherwise. Without capture nothing sets that mode,
                    // and a voice-communication track in MODE_NORMAL routes to
                    // the earpiece on the call volume stream -- silent outside
                    // a call. That is why a voice preview, and a reply asked
                    // to be read back, made no sound on a real phone while
                    // measuring fine on an emulator, which does not model
                    // routing at all.
                    .setUsage(
                        if (forCall) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minTrack, FRAME_BYTES * 20))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track?.play()

        // Playback lives on its own thread precisely because write() blocks.
        thread(name = "duplex-playback") {
            while (playbackOn) {
                val chunk = playback.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // Nothing to play: let the level fall away rather than
                    // leaving the orb frozen at whatever it last was.
                    reportOutput(outputLevel * 0.6)
                    continue
                }
                // Written in short slices, and measured slice by slice.
                //
                // write() blocks for the real duration of what it is given,
                // so handing it a whole 200 ms chunk would move the animation
                // five times a second -- visibly stepped. A 20 ms slice
                // updates it fifty times a second, which is the rate the
                // syllables actually arrive at.
                var offset = 0
                while (offset < chunk.size && playbackOn) {
                    val slice = minOf(FRAME_BYTES, chunk.size - offset)
                    reportOutput(loudness(chunk, offset, slice))
                    runCatching { track?.write(chunk, offset, slice) }
                    offset += slice
                }
            }
            reportOutput(0.0)
        }
    }

    private fun stopPlayback() {
        playbackOn = false
        playback.clear()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    /**
     * Mute: the microphone closes, the session does not.
     *
     * Distinct from leaving full duplex, which also gives up the speaker and
     * puts the conversation back in the composer. Muted is still a voice
     * session -- the reply still gets spoken, and unmuting is one press.
     */
    fun setMuted(on: Boolean) {
        if (muted == on) return
        muted = on
        EventLog.add("mic ${if (on) "muted" else "live"}")
        if (!running || !duplex) return
        if (on) stopCapture() else startCapture()
    }

    @Volatile
    var muted: Boolean = false
        private set

    /** Microphone, echo cancellation, and the 20 ms frame pump. */
    private fun startCapture() {
        if (audioOn) return
        audioOn = true

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Communication mode is what actually arms the echo canceller; without
        // it the phone happily transcribes its own voice.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val minRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minRecord, FRAME_BYTES * 10),
        )
        recorder = record

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                enabled = true
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                enabled = true
            }
        }
        EventLog.add(
            "audio up: aec=${echoCanceler?.enabled ?: false} " +
                "ns=${noiseSuppressor?.enabled ?: false} session=${record.audioSessionId}"
        )

        record.startRecording()

        thread(name = "duplex-capture") {
            val buffer = ByteArray(FRAME_BYTES)
            while (audioOn) {
                val read = record.read(buffer, 0, FRAME_BYTES)
                if (read <= 0) continue
                // Send exactly one frame; the server's VAD is framed at 20 ms.
                socket?.send(buffer.copyOf(read).toByteString())
            }
        }
    }

    private fun stopCapture() {
        audioOn = false          // the capture thread polls this and exits

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null

        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    /** Everything a full-duplex session needs. */
    private fun startAudio() {
        startPlayback()
        if (!muted) startCapture()
        onStatus(if (muted) "muted" else "listening")
    }

    /** Back to a text session: the socket stays, the microphone does not. */
    private fun stopAudio() {
        stopCapture()
        stopPlayback()
        onStatus("ready")
    }

    /**
     * Open the speaker for a preview we asked for, briefly.
     *
     * Nothing in the stream marks the end of a preview -- the bytes simply
     * stop -- so the window closes on a timer. Generous, because closing it
     * early would clip the sample, and a spare AudioTrack for a few seconds
     * costs nothing.
     */
    private fun armPreview(window: Long) {
        expectPreview = true
        // Out of the loudspeaker at media volume, unless a duplex session is
        // already running and has put the device in communication mode.
        startPlayback(forCall = duplex)
        handler.removeCallbacks(disarmPreview)
        handler.postDelayed(disarmPreview, window)
    }

    private val disarmPreview = Runnable {
        expectPreview = false
        if (!duplex) stopPlayback()
    }

    /**
     * The loudness of one slice of 16-bit mono PCM, as 0..1.
     *
     * Root mean square rather than peak: peak jumps on a single loud sample
     * and reads as a flicker, where RMS is what an ear would call the volume
     * of the syllable being spoken.
     */
    private fun loudness(pcm: ByteArray, offset: Int, length: Int): Double {
        var sum = 0.0
        var index = offset
        val end = offset + length - 1
        while (index < end) {
            // Little-endian, signed.
            val sample = ((pcm[index + 1].toInt() shl 8) or
                (pcm[index].toInt() and 0xFF)).toShort().toInt()
            sum += (sample.toDouble() / Short.MAX_VALUE) * (sample.toDouble() / Short.MAX_VALUE)
            index += 2
        }
        val count = length / 2
        if (count <= 0) return 0.0
        val rms = kotlin.math.sqrt(sum / count)
        // Speech sits well below full scale; scaled so an ordinary sentence
        // uses most of the range without a loud one pegging it.
        return (rms * 4.0).coerceIn(0.0, 1.0)
    }

    private fun flushPlayback() {
        // Both halves matter: the queue holds audio not yet handed to the
        // hardware, the track holds audio already handed over. Dropping only
        // one of them means the reply keeps talking after being cut off.
        playback.clear()
        reportOutput(0.0)
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    /** Retry a dropped session. Anything that isn't a deliberate stop. */
    private fun scheduleReconnect() {
        if (!wanted) return
        reconnectAttempts++
        // Back off, but stay responsive: a phone coming out of doze or off a
        // dead wifi hop should recover in seconds, not minutes.
        val delayMs = minOf(1000L * reconnectAttempts, 15_000L)
        EventLog.add("reconnect #$reconnectAttempts in ${delayMs}ms")
        onStatus("reconnecting in ${delayMs / 1000}s")
        handler.postDelayed({
            if (!wanted) return@postDelayed
            running = false
            socket = null
            connect()
        }, delayMs)
    }

    /** Release audio hardware without giving up on the session. */
    private fun teardownAudio() {
        stopCapture()
        stopPlayback()
    }

    fun stop() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        active = null
        EventLog.add("disconnecting (user)")
        runCatching { socket?.close(1000, "bye") }
        socket = null
        pendingCommands.clear()

        teardownAudio()
    }
}
