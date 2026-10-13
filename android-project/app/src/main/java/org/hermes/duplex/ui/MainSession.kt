package org.hermes.duplex.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.source
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.hermes.duplex.Approvals
import org.hermes.duplex.BuildConfig
import org.hermes.duplex.ConversationStore
import org.hermes.duplex.DuplexClient
import org.hermes.duplex.DuplexService
import org.hermes.duplex.EventLog
import org.hermes.duplex.OrbModel
import org.hermes.duplex.R
import org.hermes.duplex.SettingsActivity
import org.hermes.duplex.Sidebar
import org.hermes.duplex.Speaker
import org.json.JSONObject

/** What colour a status line should be, resolved against the palette in the UI. */
enum class Tone {
    OFFLINE, NEUTRAL, RUNNING, HEARING, HELD, SENT, THINKING, SPEAKING, ERROR, WARNING, ACCENT
}

data class Badge(val text: String, val tone: Tone)

/** A file on its way to, or already on, the bridge's machine. */
data class Attachment(
    val id: Long,
    val name: String,
    val bytes: Long,
    val state: UploadState,
    /** Where it is on the bridge's machine, once it has arrived. */
    val path: String = "",
    val error: String = "",
    val thumbnail: android.graphics.Bitmap? = null,
)

enum class UploadState { UPLOADING, DONE, FAILED }

/** A confirmation or a small form that is open over the chat. */
sealed interface Dialog {
    data class RenameChat(val chat: Sidebar.Chat) : Dialog
    data class DeleteChat(val chat: Sidebar.Chat) : Dialog
    data class DeleteCli(val session: Sidebar.CliSession) : Dialog
    data object NewProject : Dialog
    data class DeleteProject(val project: Sidebar.Project) : Dialog
    data class IdeaDryRun(val instruction: String) : Dialog
}

/**
 * Everything the conversation screen knows, as Compose state.
 *
 * This is the old MainActivity with the views taken out. The rule it keeps is
 * the one the activity followed: it owns no state worth keeping. The socket
 * belongs to [DuplexService], the thread to [ConversationStore], the offers
 * to [DuplexClient], so closing the screen or letting it sleep costs nothing,
 * and a screen that attaches late reads what it missed rather than racing for
 * it.
 */
class MainSession(private val activity: ComponentActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences(DuplexClient.PREFS, Context.MODE_PRIVATE)
    val client: DuplexClient? get() = DuplexClient.active

    // -- state the UI draws ---------------------------------------------------

    var messages by mutableStateOf<List<ConversationStore.Message>>(emptyList())
        private set
    /** Bumped when the list should jump to the newest message regardless. */
    var forceScroll by mutableIntStateOf(0)
        private set

    var status by mutableStateOf("")
        private set
    var tone by mutableStateOf(Tone.OFFLINE)
        private set
    var host by mutableStateOf("")
        private set

    var offers by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var offersDismissed by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set
    var duplex by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var recallArmed by mutableStateOf(false)
        private set
    var wakeArmed by mutableStateOf(false)
        private set
    var readAloud by mutableStateOf(false)
        private set
    var rewriteHeld by mutableStateOf(false)
        private set

    var voiceExpanded by mutableStateOf(false)
        private set
    var orbState by mutableStateOf(OrbModel.State.IDLE)
        private set
    var orbLabel by mutableStateOf("")
        private set
    var orbTone by mutableStateOf(Tone.ACCENT)
        private set
    var caption by mutableStateOf("")
        private set
    var voiceHint by mutableStateOf("")
        private set
    var flashAt by mutableLongStateOf(0L)
        private set
    var badge by mutableStateOf<Badge?>(null)
        private set

    /** Bumped when the bridge's project / chat lists change. */
    var sidebarRevision by mutableIntStateOf(0)
        private set
    var approval by mutableStateOf<Approvals.Request?>(null)
        private set
    /** Steps ("turn#index") whose output has been asked for and not yet received. */
    var outputsLoading by mutableStateOf<Set<String>>(emptySet())
        private set
    var usage by mutableStateOf<JSONObject?>(null)
        private set
    var usageNote by mutableStateOf("")
        private set
    var speakingKey by mutableStateOf<String?>(null)
        private set
    var dialog by mutableStateOf<Dialog?>(null)

    /**
     * The composer's text, as a [TextFieldState].
     *
     * Not a plain String, and that is why pasting an image from the keyboard
     * works: only the state-based text field tells the keyboard which content
     * types it accepts. The string-based one does not, and the keyboard
     * answers "this app does not support pasting images here".
     */
    val draftState = androidx.compose.foundation.text.input.TextFieldState()

    /** The same text as a string, for the code that sets or reads it whole. */
    var draft: String
        get() = draftState.text.toString()
        set(value) { draftState.setTextAndPlaceCursorAtEnd(value) }

    /** Files waiting to go with the next message. */
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    /** The room's loudness, 10 times a second. Read by the orb each frame. */
    @Volatile
    var rms: Double = 0.0
        private set

    // -- plumbing ---------------------------------------------------------------

    private var seenRevision = -1
    private var connecting = false
    private var nextAttachmentId = 1L
    private val uploadCalls = java.util.concurrent.ConcurrentHashMap<Long, okhttp3.Call>()
    private val uploader = okhttp3.OkHttpClient.Builder()
        .callTimeout(15, java.util.concurrent.TimeUnit.MINUTES)
        .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .build()
    private var micRefused = false
    private var autoConnectSuspended = false
    private var pendingAction: (() -> Unit)? = null

    /** Set by an idea card the instant it is touched; read by the list. */
    @Volatile
    var ideaTouched = false

    private val askForPermissions = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Notifications only show the foreground service; the microphone is
        // the one that actually blocks.
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            pendingAction?.invoke()
        } else {
            micRefused = true
            setStatus(activity.getString(R.string.status_no_mic), Tone.ERROR)
        }
        pendingAction = null
    }

    private val pickFiles = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) attachUris(uris) }

    init {
        EventLog.appVersion = BuildConfig.VERSION_NAME
        // Warmed here rather than on first press: initialisation is
        // asynchronous, and a first tap that does nothing reads as broken.
        Speaker.start(activity)
        Speaker.onState = { speaking, key ->
            handler.post { speakingKey = if (speaking) key else null }
        }
        readAloud = prefs.getBoolean(DuplexClient.PREF_READ_ALOUD, false)
        loadCachedUsage()
        refreshConversation(force = true)
        refreshOffers()
        voiceHint = activity.getString(R.string.voice_hint)
    }

    // -- lifecycle ---------------------------------------------------------------

    /** Point the live client's callbacks at this screen, and catch up. */
    fun attach() {
        val live = DuplexClient.active
        live?.onEvent = { event -> handler.post { render(event) } }
        live?.onStatus = { text -> handler.post { setStatus(text, null) } }
        host = hostOf(if (live != null) DuplexClient.lastUrl else SettingsActivity.serverUrl(activity))
        // A session that ended elsewhere must not leave the orb up over a
        // chat it is no longer describing.
        if (voiceExpanded && live?.duplex != true) showVoiceStage(false)
        refreshFlags()

        // Attaching is not only subscribing: the events that open a session
        // arrive within milliseconds of the socket opening, well before a
        // screen that just asked for the connection has wired itself to it.
        // They are recorded in the stores either way, so read them.
        if (live == null) {
            setStatus(activity.getString(R.string.status_offline), Tone.OFFLINE)
        } else {
            setStatus(DuplexClient.lastStatus.ifEmpty {
                activity.getString(R.string.status_ready)
            }, null)
        }
        refreshConversation(force = true)
        refreshOffers()
        refreshSidebar()
    }

    fun onResume() {
        attach()
        autoConnect()
        refreshApproval()
    }

    fun onPause() {
        DuplexClient.active?.onEvent = {}
        DuplexClient.active?.onStatus = {}
    }

    fun onDestroy(finishing: Boolean) {
        handler.removeCallbacksAndMessages(null)
        // Speaker belongs to the service-backed client, not this activity.
        if (finishing) Speaker.shutdown()
    }

    // -- derived state -----------------------------------------------------------

    private fun refreshFlags() {
        val live = client
        duplex = live?.duplex == true
        muted = live?.muted == true
        recallArmed = DuplexClient.recallArmed
        // Stop appears only while there is something to stop, read from the
        // client's view of the bridge rather than whichever event went past.
        busy = live != null && DuplexClient.busy
    }

    /**
     * Push the store into the list. A revision that has not moved does no
     * work at all, which matters because `reply_delta` arrives per token.
     */
    fun refreshConversation(force: Boolean = false) {
        val revision = ConversationStore.revision
        if (!force && revision == seenRevision) return
        seenRevision = revision
        messages = ConversationStore.snapshot()
        if (force) forceScroll++
    }

    fun refreshOffers() {
        offers = DuplexClient.suggestions
        offersDismissed = DuplexClient.suggestionsDismissed
    }

    private fun refreshSidebar() {
        sidebarRevision++
    }

    private fun refreshApproval() {
        val request = Approvals.oldest()
        approval = request
        // If the request arrived while the activity was paused, the service
        // already stored it. Make the same real reason audible when the user
        // returns, without repeating announcements already made.
        if (request != null && request.reason.isNotBlank() && Approvals.claimSpeech(request.id)) {
            Speaker.say(request.reason, key = "approval-${request.id}")
        }
    }

    // -- status ---------------------------------------------------------------------

    private var lastStatus = ""
    private var workingActive = false
    private var workingSince = 0L
    private var workingTokens = 0
    private var workingDoing = ""

    fun setStatus(text: String, newTone: Tone?) {
        lastStatus = text
        // A session that has been working for four minutes must not be
        // relabelled "ready" by an unrelated event arriving behind it. The
        // progress line owns this row for as long as the turn lasts.
        if (!workingActive) status = text
        newTone?.let { tone = it }
    }

    private val workingTick = object : Runnable {
        override fun run() {
            if (!workingActive) return
            status = activity.getString(R.string.working_line, workingWhat(), workingCost())
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun workingWhat() = workingDoing.ifEmpty { activity.getString(R.string.working_default) }

    /** "2m 30s · 19.1k tokens", dropping whichever half is not known yet. */
    private fun workingCost(): String {
        val parts = mutableListOf<String>()
        if (workingSince > 0L) {
            val seconds = ((System.currentTimeMillis() - workingSince) / 1000).coerceAtLeast(0L)
            parts += if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
        }
        if (workingTokens > 0) {
            parts += if (workingTokens >= 1000) {
                String.format(java.util.Locale.US, "%.1fk tokens", workingTokens / 1000.0)
            } else {
                activity.getString(R.string.working_tokens, workingTokens)
            }
        }
        return parts.joinToString(" · ")
    }

    private fun onWorking(event: JSONObject) {
        val wasActive = workingActive
        workingActive = event.optBoolean("active")
        workingSince = event.optLong("started_ms")
        workingTokens = event.optInt("tokens")
        workingDoing = event.optString("doing")
        handler.removeCallbacks(workingTick)
        if (workingActive) {
            orbState(OrbModel.State.WORKING, activity.getString(R.string.working_default))
            tone = Tone.RUNNING
            handler.post(workingTick)
            return
        }
        // Finished. Leave what it cost on screen rather than blanking back to
        // "ready" -- the number answers "was it actually doing anything".
        tone = Tone.SENT
        val cost = workingCost()
        status = when {
            wasActive && cost.isNotEmpty() -> activity.getString(R.string.working_done, cost)
            lastStatus.isNotEmpty() -> lastStatus
            else -> activity.getString(R.string.status_ready)
        }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore("/")

    /**
     * The endpointing verdict, in colour.
     *
     * Silence after you stop talking means one of two opposite things: the
     * sentence was accepted and Hermes is thinking, or it was judged
     * unfinished and the mic is still open waiting for the rest. Only while a
     * voice session is up -- a typed turn has no endpoint to judge.
     */
    private fun badge(tone: Tone, text: String) {
        badge = if (client?.duplex != true) null else Badge(text, tone)
    }

    // -- events -------------------------------------------------------------------------

    private fun render(event: JSONObject) {
        // Repainted on every event rather than toggled by a chosen few, so no
        // single missed message can leave it stuck on.
        refreshFlags()
        val s = activity.getString(R.string.status_ready)
        when (event.optString("type")) {
            "level" -> rms = event.optDouble("rms")
            "working" -> onWorking(event)
            "ready" -> {
                setStatus(s, Tone.SENT)
                orbState(OrbModel.State.LISTENING, activity.getString(R.string.voice_listening))
            }
            "state" -> {
                setStatus(event.optString("value"), null)
                when (event.optString("value")) {
                    "listening" -> orbState(OrbModel.State.LISTENING, activity.getString(R.string.voice_listening))
                    "thinking" -> orbState(OrbModel.State.THINKING, activity.getString(R.string.voice_thinking))
                    "speaking" -> orbState(OrbModel.State.SPEAKING, activity.getString(R.string.voice_speaking))
                }
            }
            "speech_start" -> {
                setStatus(activity.getString(R.string.status_hearing), Tone.HEARING)
                badge(Tone.HEARING, activity.getString(R.string.endpoint_hearing))
                orbState(OrbModel.State.LISTENING, activity.getString(R.string.voice_listening))
            }
            "listening_more" -> {
                // It heard you and decided you were not finished. Say so, or
                // the pause looks identical to the thing being broken.
                setStatus(activity.getString(R.string.status_listening_more), Tone.HELD)
                badge(Tone.HELD, activity.getString(R.string.endpoint_held, event.optString("reason")))
                orbState(OrbModel.State.HELD, activity.getString(R.string.voice_held))
                voiceHint = activity.getString(R.string.voice_held_why, event.optString("reason"))
                caption = event.optString("partial")
            }
            "transcript" -> {
                // Which rule accepted it matters as much as the fact that it
                // did -- "gave up waiting" is not a terminal '?'.
                val why = event.optString("reason").ifEmpty { "sent" }
                if (event.optBoolean("forced")) {
                    badge(Tone.HELD, activity.getString(R.string.endpoint_forced, why))
                } else {
                    badge(Tone.SENT, activity.getString(R.string.endpoint_sent, why))
                }
                orbState(OrbModel.State.THINKING, activity.getString(R.string.voice_thinking))
                caption = event.optString("text")
                refreshConversation()
                refreshOffers()
            }
            "waiting_on_model" -> {
                setStatus(activity.getString(R.string.status_thinking), Tone.THINKING)
                orbState(OrbModel.State.THINKING, activity.getString(R.string.voice_thinking))
            }
            "first_token" -> setStatus(activity.getString(R.string.status_replying, event.optInt("ms")), null)
            "reply_delta" -> {
                orbState(OrbModel.State.SPEAKING, activity.getString(R.string.voice_speaking))
                refreshConversation()
                captionLatestReply()
            }
            "reply" -> {
                refreshConversation()
                captionLatestReply()
                // Back to listening on the reply itself, not only on the state
                // event that follows it; a missed one leaves the orb stuck.
                orbState(OrbModel.State.LISTENING, activity.getString(R.string.voice_listening))
                setStatus(activity.getString(R.string.status_done, event.optInt("total_ms")), Tone.SENT)
            }
            "project_idea" -> {
                refreshConversation(force = true)
                setStatus("Private local idea", Tone.ACCENT)
            }
            "rewrite_transcript" -> {
                val text = event.optString("text").trim()
                if (text.isNotEmpty()) {
                    draft = text
                    client?.sendCommand(
                        JSONObject()
                            .put("type", "llm_rewrite")
                            .put("draft", text)
                            .put("last_reply", ConversationStore.lastAssistant()?.text ?: "")
                    )
                    setStatus("Rewriting privately…", Tone.ACCENT)
                }
            }
            "rewrite_result" -> {
                val text = event.optString("text").trim()
                if (text.isNotEmpty()) {
                    draft = text
                    setStatus("Rewrite ready to review", Tone.ACCENT)
                }
            }
            "hello", "config" -> {
                wakeArmed = event.optBoolean("wake_word", wakeArmed)
                refreshUsage()
                // Engine selection changes the history source and its heading
                // even before the following projects event arrives.
                refreshSidebar()
            }
            "wake_skipped" -> setStatus(activity.getString(R.string.status_wake), Tone.NEUTRAL)
            "wake_ready" -> setStatus(activity.getString(R.string.wake_ready), Tone.HEARING)
            "suggestions" -> refreshOffers()
            "projects" -> refreshSidebar()
            "usage" -> renderUsage(event)
            "chat_opened" -> {
                // A different thread: the bubbles, the offers and the
                // endpointing verdict all belonged to the last one.
                DuplexClient.clearSuggestions()
                refreshConversation(force = true)
                refreshOffers()
                refreshSidebar()
                badge = null
            }
            // The open session gained a turn, or its last one grew, because a
            // CLI is writing it. Not forced: the list follows the bottom only
            // when the reader is already there.
            "chat_delta" -> refreshConversation()
            "media_results" -> {
                val images = event.optJSONArray("images") ?: return
                val media = (0 until images.length()).mapNotNull { index ->
                    val row = images.optJSONObject(index) ?: return@mapNotNull null
                    ConversationStore.Media(
                        image = row.optString("image"),
                        thumbnail = row.optString("thumbnail"),
                        video = row.optString("video"),
                        title = row.optString("title"),
                        source = row.optString("source"),
                        platform = row.optString("platform"),
                        duration = row.optString("duration"),
                    )
                }
                ConversationStore.media(media)
                refreshConversation()
            }
            "approval_request" -> refreshApproval()
            "approval_trace" -> setStatus(
                "APPROVAL[${event.optInt("stage")}/6] ${event.optString("text")}", Tone.WARNING
            )
            // Answered on the watch, or timed out. Take it off the screen
            // rather than leaving a dialog whose buttons now do nothing.
            "approval_closed" -> approval = Approvals.oldest()
            "agent_detail" -> {
                ConversationStore.detail(event.optString("id"), stepsOf(event), loading = false)
                refreshConversation()
            }
            "agent_step" -> {
                val id = event.optString("id")
                val index = event.optInt("step", -1)
                outputsLoading = outputsLoading - "$id#$index"
                val tool = event.optJSONObject("tool")
                if (tool != null) {
                    val out = tool.optString("output")
                    ConversationStore.stepOutput(
                        id, index,
                        if (tool.optBoolean("truncated")) "$out\n\u2026 (shortened)" else out,
                    )
                    refreshConversation()
                }
            }
            "recall_armed" -> recallArmed = DuplexClient.recallArmed
            "recalled" -> {
                // Say what it found, including nothing: a button whose only
                // feedback is the reply cannot be told from one that did not
                // fire at all.
                val count = event.optInt("count")
                ConversationStore.note(
                    when {
                        count == 0 -> activity.getString(R.string.recalled_none)
                        count == 1 -> activity.getString(R.string.recalled_some, count)
                        else -> activity.getString(R.string.recalled_many, count)
                    }
                )
                refreshConversation()
                recallArmed = DuplexClient.recallArmed
            }
            "interrupted" -> {
                refreshConversation()
                orbState(OrbModel.State.LISTENING, activity.getString(R.string.voice_listening))
            }
            "trajectory" -> refreshConversation()
            "notice" -> setStatus(event.optString("text"), null)
            "error" -> {
                setStatus(activity.getString(R.string.status_error, event.optString("text")), Tone.ERROR)
                caption = event.optString("text")
            }
        }
        // Whatever the event was, the session may have gained or lost a
        // thing a button depends on.
        refreshFlags()
    }

    /** The steps of one turn, as the bridge described them. */
    private fun stepsOf(event: JSONObject): List<ConversationStore.Step> {
        val tools = event.optJSONArray("tools") ?: return emptyList()
        val steps = (0 until tools.length()).mapNotNull { index ->
            val call = tools.optJSONObject(index) ?: return@mapNotNull null
            ConversationStore.Step(
                tool = call.optString("tool").ifEmpty { "step" },
                subject = call.optString("subject"),
                output = call.optString("output"),
                command = call.optString("command"),
            )
        }
        // Reasoning is not kept in either CLI's transcript. On the rare turn
        // that has no steps, say why rather than opening onto nothing.
        if (steps.isNotEmpty()) return steps
        val thinking = event.optString("thinking")
        return listOf(
            ConversationStore.Step(
                tool = "note", subject = "",
                output = thinking.ifEmpty { activity.getString(R.string.cli_detail_thinking) },
            )
        )
    }

    private fun captionLatestReply() {
        ConversationStore.snapshot().lastOrNull()
            ?.takeIf { it.role == ConversationStore.Role.HERMES }
            ?.let { caption = it.text }
    }

    private fun orbState(state: OrbModel.State, label: String) {
        if (client?.muted == true) return
        orbState = state
        orbLabel = label
        orbTone = when (state) {
            OrbModel.State.LISTENING -> Tone.ACCENT
            OrbModel.State.WORKING -> Tone.RUNNING
            OrbModel.State.SPEAKING -> Tone.SPEAKING
            OrbModel.State.HELD -> Tone.HELD
            else -> Tone.NEUTRAL
        }
        if (state != OrbModel.State.HELD) voiceHint = activity.getString(R.string.voice_hint)
    }

    // -- connection -------------------------------------------------------------------------

    /**
     * Run [action] with a live socket.
     *
     * The service is what holds the socket open across the screen going off,
     * and it is a microphone-typed foreground service, so the permission is
     * needed to start it even for a session that never records.
     */
    private fun withConnection(action: () -> Unit) {
        // Asking for something is asking for the connection it needs.
        autoConnectSuspended = false
        if (client != null) {
            action()
            return
        }
        if (!hasMicPermission()) {
            if (micRefused) {
                setStatus(activity.getString(R.string.status_no_mic), Tone.ERROR)
                return
            }
            pendingAction = { withConnection(action) }
            askForPermissions.launch(requiredPermissions())
            return
        }
        if (connecting) {
            // Already on its way; queue behind it rather than starting a
            // second service and racing for the same socket.
            handler.postDelayed({ withConnection(action) }, 450)
            return
        }
        connecting = true
        DuplexService.start(activity, SettingsActivity.serverUrl(activity))
        setStatus(activity.getString(R.string.status_connecting), Tone.RUNNING)
        // The client is created inside the service, a moment later.
        handler.postDelayed({
            connecting = false
            attach()
            action()
        }, 400)
    }

    /**
     * Connect on the way in. There is no Connect button -- the composer, the
     * headset and the sidebar all establish the socket when they need it -- so
     * an app that sat idle until you did something would just show "offline".
     */
    private fun autoConnect() {
        if (client != null || connecting || autoConnectSuspended) return
        withConnection { }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        activity, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Put the whole session down: microphone, socket and service. Auto-connect
     * would otherwise bring it straight back, so this also stands the
     * reconnect down until something is deliberately asked of it again.
     */
    fun disconnect() {
        DuplexService.stop(activity)
        showVoiceStage(false)
        autoConnectSuspended = true
        handler.postDelayed({
            attach()
            setStatus(activity.getString(R.string.status_offline), Tone.OFFLINE)
        }, 300)
        toast(activity.getString(R.string.disconnected))
    }

    // -- sending ----------------------------------------------------------------------------------

    /** Send what is typed. */
    fun send() {
        val text = draft.trim()
        if (attachments.any { it.state == UploadState.UPLOADING }) {
            toast("Still uploading — one moment")
            return
        }
        val ready = attachments.filter { it.state == UploadState.DONE }
        val failed = attachments.count { it.state == UploadState.FAILED }
        if (text.isEmpty() && ready.isEmpty()) {
            if (failed > 0) toast("The upload failed, so there is nothing to send")
            return
        }
        if (failed > 0) toast("$failed file${if (failed == 1) "" else "s"} failed to upload and ${if (failed == 1) "was" else "were"} left out")
        val message = withAttachments(text, ready)
        draft = ""
        attachments = emptyList()
        submit(message)
    }

    // -- attachments -------------------------------------------------------------------

    /**
     * Files go to the bridge for real, and the message names where they landed.
     *
     * Each is streamed to `POST /upload` as soon as it is picked or pasted, so
     * by the time Send is pressed the file is already on the Mac, in the open
     * project's upload folder, and the message simply quotes its path. That
     * works for anything -- a screenshot, a PDF, a log -- because the agent
     * reads it from disk like any other file, rather than having its bytes
     * squeezed through the text channel.
     *
     * The folder is the bridge's choice. The phone sends a project name and a
     * file name, never a location.
     */
    fun pickAttachments() = pickFiles.launch(arrayOf("*/*"))

    fun removeAttachment(attachment: Attachment) {
        // A file still on its way is called back, not just hidden.
        uploadCalls.remove(attachment.id)?.cancel()
        attachments = attachments.filterNot { it.id == attachment.id }
    }

    /**
     * Paste whatever is on the clipboard: images and files are uploaded, text is
     * added to the message. Screenshots are the reason this exists -- copy one
     * from any app, come here, paste.
     */
    fun pasteFromClipboard() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("The clipboard is empty")
            return
        }
        val uris = mutableListOf<android.net.Uri>()
        val text = StringBuilder()
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            if (item.uri != null) uris += item.uri
            else item.text?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
        }
        if (text.isNotEmpty()) draft = if (draft.isEmpty()) text.toString() else draft + "\n" + text
        if (uris.isNotEmpty()) attachUris(uris)
        if (uris.isEmpty() && text.isEmpty()) toast("Nothing on the clipboard can be pasted")
    }

    /**
     * Take a file handed over by the keyboard (a pasted or dragged-in image).
     *
     * The keyboard's permission to read it is lent for the duration of the
     * callback, so it is copied into the cache right now, before returning,
     * and the upload works from the copy.
     */
    fun attachFromKeyboard(uri: android.net.Uri) {
        val mime = activity.contentResolver.getType(uri).orEmpty()
        val copy = runCatching {
            val file = java.io.File(activity.cacheDir, "pasted-${System.nanoTime()}${extensionFor(mime)}")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> out.write(readUpTo(input, IMAGE_IN_MEMORY.toInt())) }
            }
            file
        }.getOrNull()
        if (copy == null) { toast("Could not read what the keyboard sent"); return }
        attachUris(listOf(android.net.Uri.fromFile(copy)), mimeOverride = mime)
    }

    private fun attachUris(uris: List<android.net.Uri>, mimeOverride: String? = null) {
        for (uri in uris) {
            val id = nextAttachmentId++
            val mime = mimeOverride ?: activity.contentResolver.getType(uri).orEmpty()
            var name = displayName(uri)
            // A pasted screenshot arrives with no useful name at all.
            if (name.isBlank() || name == "file" || name.all { it.isDigit() }) {
                name = "pasted-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()) + extensionFor(mime)
            }
            val size = sizeOf(uri)
            attachments = attachments + Attachment(id, name, size, UploadState.UPLOADING)
            Thread { upload(id, uri, name, mime, size) }.start()
        }
    }

    private fun upload(id: Long, uri: android.net.Uri, name: String, mime: String, size: Long) {
        try {
            // Images are read whole: the thumbnail wants the bytes anyway, and a
            // screenshot is small. Everything else is streamed, so a large file
            // never has to fit in memory.
            // Unknown size (-1) still gets a thumbnail attempt: read is capped below.
            val isImage = mime.startsWith("image/") && size <= IMAGE_IN_MEMORY
            val bytes = if (isImage) activity.contentResolver.openInputStream(uri)?.use { readUpTo(it, IMAGE_IN_MEMORY.toInt()) } else null
            val thumb = bytes?.let { thumbnailOf(it) }
            // On screen straight away, before the upload has finished.
            if (thumb != null) finishUpload(id) { it.copy(thumbnail = thumb) }
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
                override fun contentLength() = bytes?.size?.toLong() ?: size
                override fun writeTo(sink: okio.BufferedSink) {
                    if (bytes != null) sink.write(bytes)
                    else (activity.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("could not read the file")).use { sink.writeAll(it.source()) }
                }
            }
            val url = uploadBase().toHttpUrl().newBuilder()
                .addPathSegment("upload")
                .addQueryParameter("name", name)
                .addQueryParameter("scope", uploadScope())
                .build()
            val request = okhttp3.Request.Builder().url(url).post(body).build()
            val call = uploader.newCall(request)
            uploadCalls[id] = call
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val why = runCatching { JSONObject(text).optString("detail") }.getOrDefault("")
                    throw java.io.IOException(why.ifBlank { "the bridge said ${response.code}" })
                }
                val reply = JSONObject(text)
                finishUpload(id) { it.copy(
                    state = UploadState.DONE, path = reply.getString("path"),
                    name = reply.optString("name", name), bytes = reply.optLong("bytes", size),
                    thumbnail = thumb,
                ) }
            }
        } catch (e: Exception) {
            // Removing a tile cancels its call, which surfaces here as an
            // exception; that is not a failure anyone should be told about.
            if (uploadCalls.remove(id) != null || attachments.any { it.id == id }) {
                EventLog.add("upload of $name failed: ${e.message}")
                finishUpload(id) { it.copy(state = UploadState.FAILED, error = e.message ?: e.javaClass.simpleName) }
            }
        } finally {
            uploadCalls.remove(id)
        }
    }

    private fun finishUpload(id: Long, change: (Attachment) -> Attachment) {
        handler.post { attachments = attachments.map { if (it.id == id) change(it) else it } }
    }

    /** The bridge's HTTP address: the socket's, with the scheme swapped. */
    private fun uploadBase(): String {
        val ws = if (client != null) DuplexClient.lastUrl else SettingsActivity.serverUrl(activity)
        return ws.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://").removeSuffix("/ws")
    }

    /** Which project's folder these belong in, as the bridge knows the project. */
    private fun uploadScope(): String =
        if (Sidebar.cli.enabled) Sidebar.cli.activeProject?.name ?: Sidebar.cli.id.ifBlank { "agent" }
        else Sidebar.activeProject?.name ?: "chat"

    /** At most [limit] bytes (InputStream.readNBytes needs Android 13). */
    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun sizeOf(uri: android.net.Uri): Long = runCatching {
        // A file we copied ourselves has no content provider to ask.
        if (uri.scheme == "file") return@runCatching java.io.File(uri.path ?: "").length()
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
    }.getOrNull() ?: -1L

    private fun extensionFor(mime: String): String = when {
        mime == "image/png" -> ".png"
        mime == "image/jpeg" -> ".jpg"
        mime == "image/webp" -> ".webp"
        mime == "image/gif" -> ".gif"
        mime == "application/pdf" -> ".pdf"
        mime.startsWith("text/") -> ".txt"
        else -> ""
    }

    private fun thumbnailOf(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun displayName(uri: android.net.Uri): String =
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"

    /** The message, followed by where each file is on the machine the agent runs on. */
    private fun withAttachments(text: String, files: List<Attachment>): String {
        if (files.isEmpty()) return text
        val out = StringBuilder(text.ifEmpty { "(see the attached ${if (files.size == 1) "file" else "files"})" })
        out.append("\n\nAttached ${if (files.size == 1) "file" else "files"} (saved on this machine; read them from these paths):")
        files.forEach { out.append("\n- ").append(it.path) }
        return out.toString()
    }

    /** Send [text] as a turn, opening the socket first if need be. */
    fun submit(text: String) = withConnection {
        client?.sendCommand(promptPayload(text))
        DuplexClient.clearSuggestions()
        refreshOffers()
    }

    /** Build the same payload used by Send, so a dry run cannot drift from it. */
    fun promptPayload(text: String): JSONObject {
        val prompt = JSONObject().put("type", "prompt").put("text", text)
        if (Sidebar.cli.enabled && Sidebar.cli.activeSession.isNotBlank()) {
            prompt.put("engine", Sidebar.cli.id)
            prompt.put("key", Sidebar.cli.activeKey)
            prompt.put("id", Sidebar.cli.activeSession)
        }
        return prompt
    }

    /** Keep generated instructions printable and strip accidental credentials. */
    private fun sanitizeIdeaInstruction(text: String): String =
        text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .replace(Regex("(?i)(api[_ -]?key|token|password)\\s*[:=]\\s*\\S+"), "$1: [redacted]")
            .trim()
            .take(4000)

    /**
     * Final safety gate for a private idea-flow step. Nothing leaves the
     * private card until the user confirms this exact payload.
     */
    fun requestIdeaSend(step: String) {
        val instruction = sanitizeIdeaInstruction(step)
        if (instruction.isNotBlank()) dialog = Dialog.IdeaDryRun(instruction)
    }

    fun stop() {
        client?.sendCommand(JSONObject().put("type", "stop"))
    }

    fun stopRewrite() {
        rewriteHeld = false
        client?.stopRewriteCapture()
    }

    fun startRewrite() {
        rewriteHeld = true
        withConnection { if (rewriteHeld) client?.startRewriteCapture() }
    }

    /**
     * Hermes' /new. Both halves are needed: the bridge drops its history and
     * mints a new session id, and the thread goes with it. Clearing only the
     * screen would leave the model answering in the context of a conversation
     * the user believes is over.
     */
    fun newConversation() {
        val live = client
        if (live == null) {
            toast(activity.getString(R.string.not_connected))
            return
        }
        live.sendCommand(JSONObject().put("type", "reset"))
        ConversationStore.clear()
        DuplexClient.clearSuggestions()
        refreshConversation(force = true)
        refreshOffers()
        toast(activity.getString(R.string.conversation_cleared))
    }

    // -- composer toggles -----------------------------------------------------------------------------

    /**
     * Arm recall for the next turn. Not a send: it changes what the next
     * message is allowed to look up. The bridge is what says it was spent.
     */
    fun toggleRecall() = withConnection {
        val want = !DuplexClient.recallArmed
        client?.sendCommand(JSONObject().put("type", "recall_once").put("on", want))
        if (want) toast(activity.getString(R.string.recall_armed))
    }

    /**
     * Listen for the name, without the orb. The microphone has to be open for
     * this, so it turns duplex on -- but not the stage.
     */
    fun toggleWake() = withConnection {
        val on = !wakeArmed
        client?.setTuning("wake_word" to on)
        if (on) {
            client?.setDuplex(true)
            refreshFlags()
        }
        toast(activity.getString(if (on) R.string.wake_on else R.string.wake_off))
    }

    /**
     * Read every reply aloud as it arrives, in the phone's own voice. Kept on
     * this phone rather than in the bridge's tuning: it is about whether
     * *this* device makes a noise.
     */
    fun toggleReadAloud() {
        readAloud = !readAloud
        prefs.edit().putBoolean(DuplexClient.PREF_READ_ALOUD, readAloud).apply()
        client?.readAloud = readAloud
        if (!readAloud) Speaker.stop()
        toast(activity.getString(if (readAloud) R.string.auto_read_on else R.string.auto_read_off))
    }

    // -- voice ------------------------------------------------------------------------------------------

    /**
     * Tap the headset for the animation; hold it to stop listening. Closing
     * the orb puts the chat back without ending the session. Only the long
     * press hangs up.
     */
    fun onHeadset() {
        if (client?.duplex == true) {
            showVoiceStage(true)
            return
        }
        withConnection {
            client?.setDuplex(true)
            showVoiceStage(true, fresh = true)
            refreshFlags()
        }
    }

    /** Holding it hangs up, which tapping deliberately does not. */
    fun onHeadsetLongPress() {
        if (client?.duplex != true) return
        endVoice()
        toast(activity.getString(R.string.voice_ended))
    }

    private fun endVoice() {
        client?.setDuplex(false)
        showVoiceStage(false)
        clearVoiceStage()
        // The endpointing verdict belongs to a voice turn.
        badge = null
        refreshFlags()
        setStatus(activity.getString(R.string.status_ready), Tone.SENT)
    }

    fun showVoiceStage(show: Boolean, fresh: Boolean = false) {
        voiceExpanded = show
        if (show) {
            // Only a new session starts blank. Re-opening one that has been
            // running keeps whatever it is in the middle of saying.
            if (fresh) clearVoiceStage()
            paintMuted()
        }
        // While a session is live but folded away, say so, and say how to end
        // it. The long press was undiscoverable last time this arrangement
        // existed.
        if (!show && client?.duplex == true) {
            setStatus(activity.getString(R.string.voice_collapsed), Tone.ACCENT)
        }
        refreshFlags()
    }

    /**
     * Wipe the stage back to a fresh session. The caption is a view onto the
     * turn happening now, not a transcript -- the transcript is the chat.
     */
    private fun clearVoiceStage() {
        caption = ""
        orbState = OrbModel.State.LISTENING
        orbLabel = activity.getString(R.string.voice_listening)
        orbTone = Tone.ACCENT
        voiceHint = activity.getString(R.string.voice_hint)
    }

    /** Tap: interrupt. What a tap is wanted for is cutting off a reply already talking. */
    fun onStageTap() {
        flashAt = System.currentTimeMillis()
        if (orbState == OrbModel.State.SPEAKING) stop()
    }

    fun toggleMute() {
        val live = client ?: return
        live.setMuted(!live.muted)
        flashAt = System.currentTimeMillis()
        refreshFlags()
        paintMuted()
    }

    private fun paintMuted() {
        val isMuted = client?.muted == true
        muted = isMuted
        voiceHint = activity.getString(if (isMuted) R.string.voice_hint_muted else R.string.voice_hint)
        if (isMuted) {
            orbLabel = activity.getString(R.string.voice_muted)
            orbTone = Tone.ERROR
        }
    }

    // -- suggestions -------------------------------------------------------------------------------------------

    fun dismissOffers() {
        DuplexClient.suggestionsDismissed = true
        refreshOffers()
    }

    fun restoreOffers() {
        DuplexClient.suggestionsDismissed = false
        refreshOffers()
    }

    fun refreshSuggestions() {
        client?.sendCommand(JSONObject().put("type", "refresh_suggestions"))
        toast("Refreshing follow-up suggestions…")
    }

    fun askForIdea() {
        client?.sendCommand(JSONObject().put("type", "project_idea"))
        toast("Asking your local LLM for a private project idea…")
    }

    /** Bring back the offers a particular reply made. */
    fun restoreSuggestions(message: ConversationStore.Message) {
        if (message.suggestions.isEmpty()) return
        DuplexClient.showSuggestions(message.suggestions)
        refreshOffers()
        toast(
            activity.resources.getString(
                if (message.suggestions.size == 1) R.string.restored_suggestions
                else R.string.restored_suggestions_plural,
                message.suggestions.size,
            )
        )
    }

    // -- per-message actions -------------------------------------------------------------------------------------

    /** Copy one message. The clipboard gets what was said, not the markup. */
    fun copyMessage(message: ConversationStore.Message) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("hermes-duplex", message.text))
        toast(activity.getString(R.string.copied))
    }

    /**
     * Put one of your own turns back in the composer to say differently. The
     * original stays in the thread: rewriting is asking again, not pretending
     * the first attempt did not happen.
     */
    fun rewriteMessage(message: ConversationStore.Message) {
        draft = message.text
    }

    /**
     * Read a reply out with the phone's own voice. The bridge could synthesise
     * it, but that is a round trip and a Piper pass for something the phone
     * does instantly and offline. Pressing again pauses it.
     */
    fun speakMessage(message: ConversationStore.Message) {
        if (Speaker.currentKey == message.speechKey) {
            Speaker.pause()
            return
        }
        if (Speaker.pausedKey == message.speechKey) {
            Speaker.resume()
            return
        }
        // A manual read is one message, never the whole pending auto-read queue.
        Speaker.say(
            message.text,
            queue = false,
            key = message.speechKey,
            context = if (message.role == ConversationStore.Role.IDEA) {
                Speaker.SpeechContext.LLM_RESPONSE
            } else {
                Speaker.SpeechContext.SYSTEM_RESPONSE
            },
        )
    }

    /**
     * Open or close the list of what a turn in a CLI session did. Only the
     * first open asks the bridge, because the contents were never sent: the
     * history says a reply took nine steps, and the nine steps are read off
     * disk for the one turn you tapped.
     */
    fun toggleDetail(message: ConversationStore.Message) {
        if (message.turnId.isEmpty()) return
        if (message.steps.isNotEmpty()) {
            if (message.stepsOpen) ConversationStore.collapseSteps(message.turnId)
            else ConversationStore.expandSteps(message.turnId)
            refreshConversation()
            return
        }
        if (message.detailLoading) return
        ConversationStore.detail(message.turnId, emptyList(), loading = true)
        refreshConversation()
        client?.sendCommand(
            JSONObject()
                .put("type", "agent_detail")
                .put("key", Sidebar.cli.activeKey)
                .put("id", Sidebar.cli.activeSession)
                .put("turn", message.turnId)
        )
    }

    /**
     * Open or close one step. Opening it fetches *that step's* output, once.
     *
     * A turn's steps arrive as names and commands only: the transcripts run to
     * megabytes, nearly all of it tool output nobody asked to see. So output is
     * requested per step, on the tap that opens it, and one small message
     * comes back -- opening one step does not pull in its neighbours.
     */
    fun toggleStep(message: ConversationStore.Message, index: Int) {
        val step = message.steps.getOrNull(index)
        ConversationStore.toggleStep(message.turnId, index)
        val ask = "${message.turnId}#$index"
        if (step != null && !step.expanded && step.output.isBlank() &&
            message.turnId.isNotEmpty() && ask !in outputsLoading
        ) {
            outputsLoading = outputsLoading + ask
            client?.sendCommand(
                JSONObject()
                    .put("type", "agent_step")
                    .put("key", Sidebar.cli.activeKey)
                    .put("id", Sidebar.cli.activeSession)
                    .put("turn", message.turnId)
                    .put("step", index)
            )
        }
        refreshConversation()
    }

    fun toggleIdea(message: ConversationStore.Message) {
        ConversationStore.toggleIdea(message.at)
        refreshConversation()
    }

    /** Any tap outside the active private flow collapses its stepper. */
    fun collapseIdeas() {
        if (ConversationStore.collapseIdeas()) refreshConversation()
    }

    // -- approvals ---------------------------------------------------------------------------------------------------

    fun answerApproval(request: Approvals.Request, allow: Boolean, remember: Boolean) {
        Approvals.resolve(request.id)
        if (Approvals.oldest() == null) DuplexService.clearApproval(activity)
        client?.sendCommand(
            JSONObject()
                .put("type", "approval_reply")
                .put("id", request.id)
                .put("allow", allow)
                .put("remember", remember)
        )
        EventLog.add("APPROVAL[6/6] reply sent id=${request.id} allow=$allow")
        // Left in the thread, not just in a toast: what the agent was allowed
        // to do is part of the record of the conversation.
        ConversationStore.note(
            activity.getString(if (allow) R.string.approve_allowed else R.string.approve_denied) +
                ": " + request.tool + " " + request.subject.take(80)
        )
        refreshConversation()
        // Whatever is still waiting behind this one.
        refreshApproval()
    }

    // -- sidebar ----------------------------------------------------------------------------------------------------------

    /** Every sidebar action needs a socket; opening the app should not. */
    fun sendSidebar(command: JSONObject, then: () -> Unit = {}) = withConnection {
        client?.sendCommand(command)
        then()
    }

    fun openChat(chat: Sidebar.Chat) =
        sendSidebar(JSONObject().put("type", "open_chat").put("id", chat.id))

    fun newChat() = sendSidebar(JSONObject().put("type", "new_chat"))

    fun newCliSession() = sendSidebar(
        JSONObject().put("type", "new_agent_session").put("key", Sidebar.cli.activeKey)
    )

    fun openCliSession(session: Sidebar.CliSession) = sendSidebar(
        JSONObject().put("type", "open_agent_session")
            .put("key", Sidebar.cli.activeKey).put("id", session.id)
    )

    fun openProject(project: Sidebar.Project) {
        if (project.path != Sidebar.activePath) {
            sendSidebar(JSONObject().put("type", "open_project").put("path", project.path))
        }
    }

    fun openCliProject(project: Sidebar.CliProject) {
        if (project.key != Sidebar.cli.activeKey) {
            sendSidebar(JSONObject().put("type", "agent_project").put("key", project.key))
        }
    }

    fun renameChat(chat: Sidebar.Chat, title: String) {
        client?.sendCommand(
            JSONObject().put("type", "rename_chat").put("id", chat.id).put("title", title)
        )
    }

    fun deleteChat(chat: Sidebar.Chat) {
        client?.sendCommand(JSONObject().put("type", "delete_chat").put("id", chat.id))
    }

    fun deleteCliSession(session: Sidebar.CliSession) {
        client?.sendCommand(
            JSONObject().put("type", "delete_agent_session")
                .put("key", Sidebar.cli.activeKey).put("id", session.id)
        )
    }

    fun newProject(name: String) =
        sendSidebar(JSONObject().put("type", "new_project").put("name", name))

    fun deleteProject(project: Sidebar.Project) {
        client?.sendCommand(JSONObject().put("type", "delete_project").put("path", project.path))
    }

    /** The engine name above the history currently shown in the drawer. */
    fun engineLabel(): String {
        val id = Sidebar.cli.id.ifBlank {
            DuplexClient.lastConfig?.optString("engine", "hermes").orEmpty()
        }
        return when (id.lowercase()) {
            "codex" -> "Codex"
            "claude" -> "Claude"
            else -> "Hermes"
        }
    }

    /** The open CLI session's name, or null if we are in a bridge chat. */
    fun cliTitle(): String? {
        val cli = Sidebar.cli
        if (!cli.enabled || cli.activeSession.isEmpty()) return null
        return cli.sessions.firstOrNull { it.id == cli.activeSession }?.title
    }

    // -- usage ----------------------------------------------------------------------------------------------------------------

    fun requestUsage() {
        usageNote = "Refreshing…"
        refreshUsage()
    }

    /** Refresh quota without otherwise changing the sidebar. */
    private fun refreshUsage() {
        withConnection { client?.sendCommand(JSONObject().put("type", "usage")) }
    }

    private fun loadCachedUsage() {
        val raw = prefs.getString(PREF_USAGE_CACHE, null) ?: return
        runCatching { renderUsage(JSONObject(raw), cached = true) }
            .onFailure { EventLog.add("cached usage unreadable: ${it.message}") }
    }

    private fun renderUsage(event: JSONObject, cached: Boolean = false) {
        if (!cached) {
            prefs.edit()
                .putString(PREF_USAGE_CACHE, event.toString())
                .putLong(PREF_USAGE_CACHE_AT, System.currentTimeMillis() / 1000)
                .apply()
        }
        usage = event
        val credits = event.optInt("reset_credits", -1)
        usageNote = when {
            cached -> {
                val at = prefs.getLong(PREF_USAGE_CACHE_AT, 0L)
                if (at > 0) "Cached · updated ${formatTime(at)}" else "Cached usage"
            }
            credits >= 0 -> "$credits earned reset credit${if (credits == 1) "" else "s"}"
            else -> event.optString("note")
        }
    }

    // -- small things --------------------------------------------------------------------------------------------------------------

    fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        /** Images up to this size are read whole, for the thumbnail. */
        private const val IMAGE_IN_MEMORY = 12L * 1024 * 1024
        private const val PREF_USAGE_CACHE = "usage_cache"
        private const val PREF_USAGE_CACHE_AT = "usage_cache_at"

        fun formatTime(epochSeconds: Long): String =
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
            ).format(java.util.Date(epochSeconds * 1000))

        /**
         * Pick the 5-hour or weekly window out of a usage event. Never lets a
         * named Weekly item become 5H; the two-slot compatibility applies only
         * when the labels are absent.
         */
        fun findWindow(event: JSONObject?, short: Boolean): JSONObject? {
            val windows = event?.optJSONArray("windows") ?: return null
            for (index in 0 until windows.length()) {
                val candidate = windows.optJSONObject(index) ?: continue
                val name = candidate.optString("label").lowercase()
                val matches = if (short) name.contains("5") && name.contains("hour")
                else name.contains("week")
                if (matches) return candidate
            }
            if (windows.length() == 2 &&
                windows.optJSONObject(0)?.optString("label").isNullOrBlank() &&
                windows.optJSONObject(1)?.optString("label").isNullOrBlank()
            ) {
                return windows.optJSONObject(if (short) 0 else 1)
            }
            return null
        }
    }
}
