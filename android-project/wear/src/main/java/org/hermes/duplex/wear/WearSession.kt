package org.hermes.duplex.wear

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.hermes.duplex.Alerts
import org.hermes.duplex.EventLog
import org.hermes.duplex.PlainText
import org.hermes.duplex.Speaker
import org.hermes.duplex.WearLink

/**
 * The watch's connection to the bridge, and everything it does without being
 * watched.
 *
 * Lifted out of [WearActivity] because it was dying with it. An Activity on
 * Wear is destroyed readily -- swipe the app away, let the system reclaim it
 * -- and it took the socket, the alerts and the voice with it. So a reply
 * that landed a minute after you dropped your wrist was received by nobody,
 * which is exactly the case the watch exists for.
 *
 * A process-level object rather than a Service of its own, with
 * [WearBridgeService] doing nothing but keeping the process alive and
 * foregrounded. That keeps the wiring in one readable place and means the
 * screen can come and go without the connection noticing: the Activity binds
 * to [model] and renders it, and owns only the microphone, which is
 * genuinely a foreground concern -- nobody dictates to a watch in a pocket.
 */
object WearSession {

    lateinit var model: WearState
        private set
    lateinit var settings: WearSettings
        private set

    private lateinit var link: PhoneLink
    private lateinit var direct: DirectLink
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var started = false

    /** Set by the screen while it is attached, so a turn can hold it awake. */
    @Volatile
    var onBusy: () -> Unit = {}

    /** Set by the service, so a question can reach the notification shade. */
    @Volatile
    var onAsked: (WearState) -> Unit = {}

    private val viaWifi: Boolean
        get() = settings.route == WearSettings.Route.WIFI

    /** Build it once. Safe to call from anywhere, as often as you like. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext

        model = WearState()
        settings = WearSettings(appContext)
        link = PhoneLink(appContext)
        direct = DirectLink()

        Speaker.start(appContext)
        Speaker.volume = settings.volume

        link.onState = { state ->
            announce(model.apply(state))
        }
        link.onChats = { model.chats = it }
        link.onProjects = { list, active ->
            model.projects = list
            model.activeProject = active
        }
        link.onReachable = { model.phoneReachable = it }

        // The same screen, fed from whichever route is chosen. The UI does
        // not know which it is, and should not: "the phone is not connected"
        // and "the bridge is not reachable" are the same sentence to someone
        // waiting for an answer.
        direct.onState = { state ->
            val spoke = model.apply(
                PhoneLink.State(
                    connected = state.connected,
                    reply = state.reply,
                    turn = state.turn,
                    chatTitle = state.chatTitle,
                    suggestions = state.suggestions,
                    engine = state.engine,
                    claudeAvailable = state.claudeAvailable,
                    agents = state.agents,
                    history = state.history,
                    approvalId = state.approvalId,
                    approvalTool = state.approvalTool,
                    approvalSubject = state.approvalSubject,
                )
            )
            model.phoneReachable = true
            announce(spoke)
        }
        direct.onTrouble = { model.notice = it }
        direct.onChats = { model.chats = it }
        direct.onProjects = { list, active ->
            model.projects = list
            model.activeProject = active
        }
        direct.onActiveChat = { model.activeChatId = it }

        applyRoute()
    }

    /** What the live connection was built for, so it is not rebuilt for that. */
    private var appliedRoute: String? = null

    /**
     * Swap routes when the setting changes, so the choice takes effect now.
     *
     * Idempotent, which it had to become once the screen stopped owning the
     * connection. The Activity calls this on every onStart, and a watch
     * blanks its screen every few seconds -- so unconditionally reconnecting
     * meant tearing the socket down and building it again several times a
     * minute. The bridge logged the churn as "client connected / client
     * disconnected after 0 turns", and whichever of the two landed last
     * decided whether the wrist believed it was connected at all.
     */
    fun applyRoute(force: Boolean = false) {
        if (!started) return
        val signature = "${settings.route}:${settings.bridgeUrl}"
        if (!force && signature == appliedRoute) return
        appliedRoute = signature
        // Whatever arrives first after this is the current state, not news.
        model.resetSpeech()
        if (viaWifi) {
            link.stop()
            direct.connect(settings.bridgeUrl)
        } else {
            direct.disconnect()
            link.start()
            scope.launch { link.refreshReachability() }
        }
    }

    fun stop() {
        if (!started) return
        link.stop()
        direct.disconnect()
        Speaker.stop()
        started = false
    }

    /** The question last alerted on, so one buzz is not three. */
    private var alertedApproval = ""

    /**
     * Say what just happened, and make it felt.
     *
     * Reading a reply out is half of it; the other half is that a watch is
     * usually not being looked at, so an answer that arrives in silence is an
     * answer nobody receives. Something blocked on a yes or no gets its own,
     * firmer alert -- it is not "look at this eventually", it is "a process
     * is stopped until you answer, and in three minutes it fails".
     *
     * Both routes funnel through here rather than each doing their own, so
     * Wi-Fi and the phone relay cannot drift into behaving differently.
     */
    private fun announce(spoke: String) {
        onBusy()
        val waiting = model.approvalId
        if (waiting.isNotEmpty() && waiting != alertedApproval) {
            alertedApproval = waiting
            Alerts.asking(appContext)
            onAsked(model)
            // The buzz always happens; the voice is a setting. Something
            // blocked on an answer still has to reach you in a meeting, and
            // a wrist that buzzes is how -- speaking the question out loud to
            // the room is the part you get to turn off.
            if (!settings.speakReplies) return
            Speaker.volume = settings.volume
            // Enough to make you look, and not one word more. Reading the
            // command itself out loud was the original intent -- decide
            // without picking anything up -- but a shell line is not speech:
            // it is long, it is full of punctuation nobody can hear, and on a
            // watch it is said out loud to whoever is standing there. The
            // command stays on the face, where the decision is made.
            Speaker.say(
                "${model.approvalTool} is asking permission to run something. " +
                    "Check your watch to allow or deny."
            )
            return
        }
        if (waiting.isEmpty()) alertedApproval = ""
        if (spoke.isBlank()) return
        // A followed reply is published in growing suffixes. The phone and
        // watch should queue those suffixes, otherwise each update cuts off
        // the sentence that came immediately before it.
        // A live followed turn is published as SPEAKING suffixes. Alert only
        // when a complete reply lands; otherwise every polling update buzzes.
        if (model.turn == WearLink.Turn.IDLE) Alerts.replied(appContext)
        // Traced, because "the switch is off" and "the synthesiser is broken"
        // are the same experience from the outside -- a buzz and no voice --
        // and they are not the same problem.
        EventLog.add(
            "reply for the wrist: ${spoke.length} chars, " +
                "read aloud ${if (settings.speakReplies) "on" else "OFF"}"
        )
        if (!settings.speakReplies) return
        Speaker.volume = settings.volume
        // Flattened before it is spoken as well as before it is shown. The
        // synthesiser has its own opinion about markup -- see
        // Speaker.speakable -- but it never saw the bullets and headings a
        // CLI reply is full of, and read them as punctuation.
        Speaker.say(PlainText.flatten(spoke), queue = true)
    }

    /**
     * One call site, two routes.
     *
     * The rest of the app asks for things by name and never learns how they
     * travelled, which is what lets the route be a setting rather than a
     * different app.
     */
    fun send(path: String, payload: String = "") {
        if (!started) return
        if (viaWifi) {
            when (path) {
                WearLink.PATH_PROMPT -> direct.prompt(payload)
                WearLink.PATH_STOP -> direct.stop()
                WearLink.PATH_NEW_CHAT -> direct.newChat()
                WearLink.PATH_OPEN_CHAT -> direct.openChat(payload)
                WearLink.PATH_OPEN_PROJECT -> direct.openProject(payload)
                WearLink.PATH_DELETE_PROJECT -> direct.deleteProject(payload)
                WearLink.PATH_ENGINE -> direct.setEngine(payload)
                WearLink.PATH_APPROVE -> payload.split("|", limit = 2).let {
                    if (it.size == 2) direct.approve(it[0], it[1] == "allow")
                }
            }
            return
        }
        scope.launch { link.send(path, payload) }
    }

    /** Ask something, from wherever it was arrived at. */
    fun ask(text: String) {
        if (text.isBlank()) return
        model.asking(text)
        onBusy()
        send(WearLink.PATH_PROMPT, text)
    }
}
